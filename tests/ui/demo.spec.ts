import { appendFile, writeFile } from 'node:fs/promises'
import { expect, test } from '@playwright/test'

type WindowResult = {
  sinkId: string
  key: string
  windowStart: number
  windowEnd: number
  values: Record<string, number>
  eventCount: number
}

test('real-stack demo: deployed UI, engine values, editor, recovery, reference', async ({ page }, testInfo) => {
  const results: { step: string; status: 'PASS' | 'FAIL'; errors: string[] }[] = []
  const speed = page.getByRole('region', { name: 'Speed windows', exact: true })
  const workers = page.getByRole('region', { name: 'Workers', exact: true })
  const banner = page.getByRole('region', { name: 'Execution status', exact: true })

  // Real same-origin fetches only: no routes, mocks, or simulator speed values.
  async function api<T>(path: string, method = 'GET', data?: unknown): Promise<T> {
    return page.evaluate(async ({ path, method, data }) => {
      const response = await fetch(path, {
        method,
        headers: data === undefined ? undefined : { 'Content-Type': 'application/json' },
        body: data === undefined ? undefined : JSON.stringify(data),
        signal: AbortSignal.timeout(15_000),
      })
      if (!response.ok) throw new Error(`${method} ${path}: HTTP ${response.status}: ${await response.text()}`)
      return response.json()
    }, { path, method, data }) as Promise<T>
  }

  async function screenshot(name: string) {
    const path = testInfo.outputPath(`${name}.png`)
    await page.screenshot({ path, fullPage: true })
    await testInfo.attach(name, { path, contentType: 'image/png' })
  }

  async function step(name: string, action: () => Promise<void>) {
    await test.step(name, async () => {
      const errorsBefore = testInfo.errors.length
      try {
        await action()
      } catch (error) {
        expect.soft(false, `${name}: ${error instanceof Error ? error.message : String(error)}`).toBe(true)
      } finally {
        try {
          await screenshot(name)
        } catch (error) {
          expect.soft(error, `${name}: full-page screenshot must be captured`).toBeUndefined()
        }
        const errors = testInfo.errors.slice(errorsBefore).map((error) => error.message ?? String(error))
        const status = errors.length ? 'FAIL' : 'PASS'
        results.push({ step: name, status, errors })
        console.log(`${status}: ${name}`)
      }
    })
  }

  await step('01-deployed-page', async () => {
    // The once-per-session intro splash (web/src/app/tour, key INTRO_STORAGE_KEY) is for live audiences; mark it seen.
    await page.addInitScript(() => {
      try { window.sessionStorage.setItem('aetherstream.intro.seen', '1') } catch { /* storage blocked: splash still dismisses on click */ }
    })
    await page.goto('/')
    await expect.soft(page, 'Browser title must identify AetherStream').toHaveTitle('AetherStream')
    await expect.soft(page.getByRole('heading', { name: 'AetherStream', exact: true }), 'Dashboard heading must be visible').toBeVisible()
    const sha = process.env.EXPECT_SHA?.trim()
    expect.soft(sha, 'EXPECT_SHA must be supplied; never infer the expected version from the server under test').toBeTruthy()
    if (sha) {
      await expect.soft(page.locator('footer').filter({ hasText: 'Deployed version:' }), 'Footer must show the deployed EXPECT_SHA prefix')
        .toContainText(`Deployed version: ${sha.slice(0, 7)}`)
    }
    await expect.soft(page.getByText('MOCK DATA', { exact: true }), 'Production must not display the MOCK DATA badge').toHaveCount(0)
  })

  await step('02-start-demo-and-workers', async () => {
    const start = page.getByRole('button', { name: 'Start demo pipeline', exact: true })
    if (await start.isVisible()) {
      await start.click()
    } else {
      const demo = await api<unknown>('/api/pipelines/demo')
      const saved = await api<{ id: string }>('/api/pipelines', 'POST', demo)
      const idempotencyKey = await page.evaluate(() => crypto.randomUUID())
      await api(`/api/pipelines/${encodeURIComponent(saved.id)}/executions`, 'POST', { idempotencyKey })
    }
    await expect.soft(speed.getByText(/^\d+(?:\.\d+)? km\/h$/).first(), 'A real engine speed window must reach at least one truck row')
      .toBeVisible({ timeout: 60_000 })
    await expect.soft(speed.getByText(/^truck-\d+$/).first(), 'Speed panel must identify the truck').toBeVisible()
    await expect.soft(workers.getByText('3 alive', { exact: true }), 'All three Compose workers must be alive').toBeVisible({ timeout: 30_000 })
    await expect.soft(workers.getByRole('progressbar', { name: /^Source queue / }), 'Workers panel must render exactly three worker rows').toHaveCount(3)
  })

  await step('03-cross-check-engine-speed', async () => {
    // Capture the DOM before fetching persisted rows. A later UI update cannot
    // change this sample; the API must contain its exact truck and window minute.
    const displayed = await speed.locator('.panel-row').first().evaluate((row) => ({
      truck: row.querySelector('strong')?.textContent?.trim(),
      speed: row.querySelector('span')?.textContent?.trim(),
      minute: row.querySelector('time')?.textContent?.trim(),
    }))
    const rows = await api<WindowResult[]>('/api/results/windows?sinkId=speedSink&limit=200')
    const matching = rows.filter((row) => row.sinkId === 'speedSink' && row.key === displayed.truck
      && new Date(row.windowEnd).toISOString().slice(11, 16) === displayed.minute)
    await testInfo.attach('engine-speed-sample', {
      body: JSON.stringify({ displayed, matching }, null, 2), contentType: 'application/json',
    })
    expect.soft(matching, `Exactly one persisted speedSink row must match ${displayed.truck} at ${displayed.minute}`).toHaveLength(1)
    const row = matching[0]
    expect.soft(row?.values.avgSpeedKmh, 'Engine window must contain a finite avgSpeedKmh, not a UI fallback').toEqual(expect.any(Number))
    if (row && Number.isFinite(row.values.avgSpeedKmh)) {
      expect.soft(displayed.speed, `UI speed for ${row.key}, window ${row.windowStart}..${row.windowEnd}, must equal the ENGINE value rounded to one decimal`)
        .toBe(`${row.values.avgSpeedKmh.toFixed(1)} km/h`)
    } else {
      expect.soft(false, 'No finite matching ENGINE speed available for cross-check').toBe(true)
    }
  })

  await step('04-pipeline-editor-validation', async () => {
    const open = page.getByRole('button', { name: /pipeline editor|edit pipeline|open editor/i })
    await open.click()
    const editor = page.getByRole('dialog', { name: 'Pipeline editor', exact: true })
    await expect.soft(editor, 'Pipeline editor must open as an accessible dialog').toBeVisible()
    // The editor offers an Examples picker when /api/pipelines/examples exists, else a "Load demo" button.
    const examples = editor.getByRole('combobox', { name: 'Examples', exact: true })
    await expect.soft(examples.or(editor.getByRole('button', { name: 'Load demo', exact: true })), 'Editor must offer examples or Load demo').toBeVisible()
    const usePicker = await examples.isVisible()
    if (usePicker) await expect.soft(examples, 'Examples picker must finish loading').toBeEnabled()
    const [demoResponse] = await Promise.all([
      page.waitForResponse((response) => usePicker
        ? new URL(response.url()).pathname === '/api/pipelines/examples/logistics-demo'
        : new URL(response.url()).pathname === '/api/pipelines/demo'),
      usePicker
        ? examples.selectOption('logistics-demo')
        : editor.getByRole('button', { name: 'Load demo', exact: true }).click(),
    ])
    expect.soft(demoResponse.ok(), 'Loading the demo pipeline must succeed against the real API').toBe(true)
    await expect.soft(editor.getByLabel('Output src', { exact: true }), 'Demo source must finish rendering before Validate').toBeVisible()
    await editor.getByRole('button', { name: 'Validate', exact: true }).click()
    await expect.soft(editor.getByText(/^Valid - \d+ operators, \d+ partitions$/), 'Real compiler must validate the demo successfully').toBeVisible()
    await screenshot('04-valid-demo')
    const brokenOption = examples.locator('option[value="broken-for-demo"]')
    const broken = editor.getByRole('button', { name: /broken-for-demo/i })
    const pickerOffersBroken = usePicker && await brokenOption.count() > 0
    if (pickerOffersBroken || await broken.isVisible()) {
      if (pickerOffersBroken) await examples.selectOption('broken-for-demo')
      else await broken.click()
      await expect.soft(editor.getByText('This pipeline has 3 mistakes - press Validate'), 'broken-for-demo must load').toBeVisible()
      await editor.getByRole('button', { name: 'Validate', exact: true }).click()
      const errors = editor.getByRole('region', { name: 'Validation errors', exact: true })
      await expect.soft(errors.getByRole('button'), 'Offered broken-for-demo example must yield exactly three compiler errors').toHaveCount(3)
    } else {
      testInfo.annotations.push({ type: 'optional-example', description: 'No broken-for-demo example offered by this editor' })
    }
    // The step screenshot intentionally retains the editor; close only afterward.
  })


  await step('05-kill-worker-and-recover', async () => {
    const closeEditor = page.getByRole('button', { name: 'Close pipeline editor', exact: true })
    if (await closeEditor.isVisible()) await closeEditor.click()
    await page.getByRole('button', { name: 'Kill worker', exact: true }).click()
    const confirm = page.getByRole('button', { name: 'Confirm kill', exact: true })
    await expect.soft(confirm, 'Killing a worker must require explicit UI confirmation').toBeVisible()
    await expect.soft.poll(async () => (await api<{ lastCompletedCheckpoint: number }>('/api/executions/current')).lastCompletedCheckpoint,
      { message: 'Wait for a real completed checkpoint before injecting a worker crash', timeout: 30_000 }).toBeGreaterThan(0)
    // Observe rendered text before confirming: recovery can be briefer than
    // Playwright's polling interval. Never substitute an API/WS event for UI proof.
    const observation = await banner.evaluateHandle((element) => {
      const evidence = { recoveringAt: 0, runningAt: 0, recoveringText: '', runningText: '' }
      const observer = new MutationObserver(() => {
        const text = element.textContent ?? ''
        if (!evidence.recoveringAt && /Recovering from checkpoint #\d+|\bRECOVERING\b/.test(text)) {
          evidence.recoveringAt = Date.now()
          evidence.recoveringText = text
        } else if (evidence.recoveringAt && /\bRUNNING\b|\bRunning\b/.test(text) && !/recovering/i.test(text)) {
          evidence.runningAt = Date.now()
          evidence.runningText = text
        }
      })
      observer.observe(element, { subtree: true, childList: true, characterData: true })
      return { evidence, disconnect: () => observer.disconnect() }
    })
    try {
      const killedAt = Date.now()
      await confirm.click()
      await expect.soft.poll(() => observation.evaluate(({ evidence }) => evidence.recoveringAt),
        { message: 'UI must visibly enter checkpoint recovery within 20 seconds of Confirm kill', timeout: Math.max(1, 20_000 - (Date.now() - killedAt)), intervals: [100] }).toBeGreaterThan(0)
      const recovery = await observation.evaluate(({ evidence }) => evidence)
      if (recovery.recoveringAt) expect.soft(recovery.recoveringAt - killedAt, 'Recovery display must meet the 20-second kill deadline').toBeLessThanOrEqual(20_000)
      await expect.soft.poll(() => observation.evaluate(({ evidence }) => evidence.runningAt),
        { message: 'Execution banner must return to Running after visible recovery', timeout: 60_000, intervals: [100] }).toBeGreaterThan(0)
      await testInfo.attach('rendered-recovery-evidence', {
        body: JSON.stringify(await observation.evaluate(({ evidence }) => evidence), null, 2), contentType: 'application/json',
      })
    } finally {
      await observation.evaluate((state) => state.disconnect())
      await observation.dispose()
    }
  })

  await step('06-compare-with-reference', async () => {
    await page.getByRole('button', { name: 'Compare with reference', exact: true }).click()
    const match = page.getByRole('heading', { name: /^[\d,]+\s*\/\s*[\d,]+ results match$/ })
    await expect.soft(match, 'Reference comparison must display N / N results match within 60 seconds').toBeVisible({ timeout: 60_000 })
    if (await match.isVisible()) {
      const counts = (await match.innerText()).match(/^([\d,]+)\s*\/\s*([\d,]+)/)!
      const matched = Number(counts[1].replaceAll(',', ''))
      const total = Number(counts[2].replaceAll(',', ''))
      expect.soft(total, 'Comparison must cover actual results, not a vacuous 0 / 0').toBeGreaterThan(0)
      expect.soft(matched, 'Every reference result must match the engine').toBe(total)
    }
  })

  const resultPath = testInfo.outputPath('step-results.json')
  await writeFile(resultPath, JSON.stringify(results, null, 2))
  await testInfo.attach('per-step-results', { path: resultPath, contentType: 'application/json' })
  const summary = `## Real-stack UI demo\n\n| Step | Result |\n| --- | --- |\n${results.map(({ step, status }) => `| ${step} | ${status} |`).join('\n')}\n`
  if (process.env.GITHUB_STEP_SUMMARY) await appendFile(process.env.GITHUB_STEP_SUMMARY, summary)
})
