# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: demo.spec.ts >> real-stack demo: deployed UI, engine values, editor, recovery, reference
- Location: demo.spec.ts:13:1

# Error details

```
Error: Footer must show the deployed EXPECT_SHA prefix

expect(locator).toContainText(expected) failed

Locator: locator('footer').filter({ hasText: 'Deployed version:' })
Expected substring: "Deployed version: 194d5c7"
Received string:    "Deployed version: 815c172"
Timeout: 10000ms

Call log:
  - Footer must show the deployed EXPECT_SHA prefix locator('footer').filter({ hasText: 'Deployed version:' }) with timeout 10000ms
  - waiting for locator('footer').filter({ hasText: 'Deployed version:' })
    15 × locator resolved to <footer class="app-footer">…</footer>
       - unexpected value "Deployed version: 815c172"

```

```yaml
- text: "Deployed version: 815c172"
```

```
Error: A real engine speed window must reach at least one truck row

expect(locator).toBeVisible() failed

Locator: getByRole('region', { name: 'Speed windows', exact: true }).getByText(/^\d+(?:\.\d+)? km\/h$/).first()
Expected: visible
Timeout: 60000ms
Error: element(s) not found

Call log:
  - A real engine speed window must reach at least one truck row getByRole('region', { name: 'Speed windows', exact: true }).getByText(/^\d+(?:\.\d+)? km\/h$/).first() with timeout 60000ms
  - waiting for getByRole('region', { name: 'Speed windows', exact: true }).getByText(/^\d+(?:\.\d+)? km\/h$/).first()

```

```yaml
- main:
  - region "Live delivery city":
    - strong: AETHER CITY
    - text: 2 km grid · 8 trucks · sim 10:04
    - status: Running
    - button "Add truck"
    - text: 40.7 events/s Driving Unloading Stopped Rejected Late 3/3 workers · healthy
  - paragraph: STREAM PROCESSING CONTROL
  - heading "AetherStream" [level=1]
  - text: Healthy
  - region "Execution status":
    - text: Running
    - strong: RUNNING
    - text: Reconnecting
  - region "Dashboard panels":
    - region "Speed windows":
      - heading "Speed windows" [level=2]
      - paragraph: Waiting for speed windows
    - region "Deliveries / min":
      - heading "Deliveries / min" [level=2]
      - strong: "1"
      - img "Recent deliveries per minute"
      - paragraph: Latest completed simulation minute
    - region "Alerts":
      - heading "Alerts" [level=2]
      - text: "0"
      - paragraph: No alerts
    - region "Dead letters":
      - heading "Dead letters" [level=2]
      - text: R 50 · L 50
      - tablist "Dead letter kind":
        - tab "REJECTED" [selected]
        - tab "LATE"
      - text: missing field payload.speedKmh
      - time: 09:56
      - text: missing field payload.speedKmh
      - time: 09:56
      - text: missing field payload.speedKmh
      - time: 09:53
      - text: missing field payload.speedKmh
      - time: 09:52
      - text: missing field payload.speedKmh
      - time: 09:50
    - region "Workers":
      - heading "Workers" [level=2]
      - text: 3 alive
      - strong: worker-1
      - text: Healthy Partitions 0, 3 · 17.1 records/s Source queue
      - progressbar "Source queue 0 of 1000"
      - text: 0/1000 Sink queue
      - progressbar "Sink queue 0 of 1000"
      - text: 0/1000
      - strong: worker-2
      - text: Healthy Partitions 1, 4 · 11.4 records/s Source queue
      - progressbar "Source queue 0 of 1000"
      - text: 0/1000 Sink queue
      - progressbar "Sink queue 0 of 1000"
      - text: 0/1000
      - strong: worker-3
      - text: Healthy Partitions 2, 5 · 12.0 records/s Source queue
      - progressbar "Source queue 0 of 1000"
      - text: 0/1000 Sink queue
      - progressbar "Sink queue 0 of 1000"
      - text: 0/1000
  - text: "Deployed version: 815c172"
```

```
Error: Speed panel must identify the truck

expect(locator).toBeVisible() failed

Locator: getByRole('region', { name: 'Speed windows', exact: true }).getByText(/^truck-\d+$/).first()
Expected: visible
Timeout: 10000ms
Error: element(s) not found

Call log:
  - Speed panel must identify the truck getByRole('region', { name: 'Speed windows', exact: true }).getByText(/^truck-\d+$/).first() with timeout 10000ms
  - waiting for getByRole('region', { name: 'Speed windows', exact: true }).getByText(/^truck-\d+$/).first()

```

```yaml
- main:
  - region "Live delivery city":
    - strong: AETHER CITY
    - text: 2 km grid · 8 trucks · sim 09:05
    - status: Running
    - button "Add truck"
    - text: 40.1 events/s Driving Unloading Stopped Rejected Late 3/3 workers · healthy
  - paragraph: STREAM PROCESSING CONTROL
  - heading "AetherStream" [level=1]
  - text: Healthy
  - region "Execution status":
    - text: Checkpointing
    - strong: "Checkpoint #3"
    - text: Live
  - region "Dashboard panels":
    - region "Speed windows":
      - heading "Speed windows" [level=2]
      - paragraph: Waiting for speed windows
    - region "Deliveries / min":
      - heading "Deliveries / min" [level=2]
      - strong: "1"
      - img "Recent deliveries per minute"
      - paragraph: Latest completed simulation minute
    - region "Alerts":
      - heading "Alerts" [level=2]
      - text: "0"
      - paragraph: No alerts
    - region "Dead letters":
      - heading "Dead letters" [level=2]
      - text: R 50 · L 50
      - tablist "Dead letter kind":
        - tab "REJECTED" [selected]
        - tab "LATE"
      - text: missing field payload.speedKmh
      - time: 09:56
      - text: missing field payload.speedKmh
      - time: 09:56
      - text: missing field payload.speedKmh
      - time: 09:53
      - text: missing field payload.speedKmh
      - time: 09:52
      - text: missing field payload.speedKmh
      - time: 09:50
    - region "Workers":
      - heading "Workers" [level=2]
      - text: 3 alive
      - strong: worker-1
      - text: Healthy Partitions — · 0.0 records/s Source queue
      - progressbar "Source queue 0 of 1000"
      - text: 0/1000 Sink queue
      - progressbar "Sink queue 0 of 1000"
      - text: 0/1000
      - strong: worker-2
      - text: Healthy Partitions 0, 1, 2, 3, 4, 5 · 41.1 records/s Source queue
      - progressbar "Source queue 0 of 1000"
      - text: 0/1000 Sink queue
      - progressbar "Sink queue 0 of 1000"
      - text: 0/1000
      - strong: worker-3
      - text: Healthy Partitions — · 0.0 records/s Source queue
      - progressbar "Source queue 0 of 1000"
      - text: 0/1000 Sink queue
      - progressbar "Sink queue 0 of 1000"
      - text: 0/1000
  - text: "Deployed version: 815c172"
```

```
Error: 03-cross-check-engine-speed: locator.evaluate: Timeout 10000ms exceeded.
Call log:
  - waiting for getByRole('region', { name: 'Speed windows', exact: true }).locator('.panel-row').first()


expect(received).toBe(expected) // Object.is equality

Expected: true
Received: false
```

```
Error: 04-pipeline-editor-validation: locator.click: Timeout 10000ms exceeded.
Call log:
  - waiting for getByRole('button', { name: /pipeline editor|edit pipeline|open editor/i })


expect(received).toBe(expected) // Object.is equality

Expected: true
Received: false
```

```
Error: 05-kill-worker-and-recover: locator.click: Timeout 10000ms exceeded.
Call log:
  - waiting for getByRole('button', { name: 'Kill worker', exact: true })


expect(received).toBe(expected) // Object.is equality

Expected: true
Received: false
```

```
Error: 06-compare-with-reference: locator.click: Timeout 10000ms exceeded.
Call log:
  - waiting for getByRole('button', { name: 'Compare with reference', exact: true })


expect(received).toBe(expected) // Object.is equality

Expected: true
Received: false
```

# Test source

```ts
  1   | import { appendFile, writeFile } from 'node:fs/promises'
  2   | import { expect, test } from '@playwright/test'
  3   | 
  4   | type WindowResult = {
  5   |   sinkId: string
  6   |   key: string
  7   |   windowStart: number
  8   |   windowEnd: number
  9   |   values: Record<string, number>
  10  |   eventCount: number
  11  | }
  12  | 
  13  | test('real-stack demo: deployed UI, engine values, editor, recovery, reference', async ({ page }, testInfo) => {
  14  |   const results: { step: string; status: 'PASS' | 'FAIL'; errors: string[] }[] = []
  15  |   const speed = page.getByRole('region', { name: 'Speed windows', exact: true })
  16  |   const workers = page.getByRole('region', { name: 'Workers', exact: true })
  17  |   const banner = page.getByRole('region', { name: 'Execution status', exact: true })
  18  | 
  19  |   // Real same-origin fetches only: no routes, mocks, or simulator speed values.
  20  |   async function api<T>(path: string, method = 'GET', data?: unknown): Promise<T> {
  21  |     return page.evaluate(async ({ path, method, data }) => {
  22  |       const response = await fetch(path, {
  23  |         method,
  24  |         headers: data === undefined ? undefined : { 'Content-Type': 'application/json' },
  25  |         body: data === undefined ? undefined : JSON.stringify(data),
  26  |         signal: AbortSignal.timeout(15_000),
  27  |       })
  28  |       if (!response.ok) throw new Error(`${method} ${path}: HTTP ${response.status}: ${await response.text()}`)
  29  |       return response.json()
  30  |     }, { path, method, data }) as Promise<T>
  31  |   }
  32  | 
  33  |   async function screenshot(name: string) {
  34  |     const path = testInfo.outputPath(`${name}.png`)
  35  |     await page.screenshot({ path, fullPage: true })
  36  |     await testInfo.attach(name, { path, contentType: 'image/png' })
  37  |   }
  38  | 
  39  |   async function step(name: string, action: () => Promise<void>) {
  40  |     await test.step(name, async () => {
  41  |       const errorsBefore = testInfo.errors.length
  42  |       try {
  43  |         await action()
  44  |       } catch (error) {
> 45  |         expect.soft(false, `${name}: ${error instanceof Error ? error.message : String(error)}`).toBe(true)
      |                                                                                                  ^ Error: 06-compare-with-reference: locator.click: Timeout 10000ms exceeded.
  46  |       } finally {
  47  |         try {
  48  |           await screenshot(name)
  49  |         } catch (error) {
  50  |           expect.soft(error, `${name}: full-page screenshot must be captured`).toBeUndefined()
  51  |         }
  52  |         const errors = testInfo.errors.slice(errorsBefore).map((error) => error.message ?? String(error))
  53  |         const status = errors.length ? 'FAIL' : 'PASS'
  54  |         results.push({ step: name, status, errors })
  55  |         console.log(`${status}: ${name}`)
  56  |       }
  57  |     })
  58  |   }
  59  | 
  60  |   await step('01-deployed-page', async () => {
  61  |     await page.goto('/')
  62  |     await expect.soft(page, 'Browser title must identify AetherStream').toHaveTitle('AetherStream')
  63  |     await expect.soft(page.getByRole('heading', { name: 'AetherStream', exact: true }), 'Dashboard heading must be visible').toBeVisible()
  64  |     const sha = process.env.EXPECT_SHA?.trim()
  65  |     expect.soft(sha, 'EXPECT_SHA must be supplied; never infer the expected version from the server under test').toBeTruthy()
  66  |     if (sha) {
  67  |       await expect.soft(page.locator('footer').filter({ hasText: 'Deployed version:' }), 'Footer must show the deployed EXPECT_SHA prefix')
  68  |         .toContainText(`Deployed version: ${sha.slice(0, 7)}`)
  69  |     }
  70  |     await expect.soft(page.getByText('MOCK DATA', { exact: true }), 'Production must not display the MOCK DATA badge').toHaveCount(0)
  71  |   })
  72  | 
  73  |   await step('02-start-demo-and-workers', async () => {
  74  |     const start = page.getByRole('button', { name: 'Start demo pipeline', exact: true })
  75  |     if (await start.isVisible()) {
  76  |       await start.click()
  77  |     } else {
  78  |       const demo = await api<unknown>('/api/pipelines/demo')
  79  |       const saved = await api<{ id: string }>('/api/pipelines', 'POST', demo)
  80  |       const idempotencyKey = await page.evaluate(() => crypto.randomUUID())
  81  |       await api(`/api/pipelines/${encodeURIComponent(saved.id)}/executions`, 'POST', { idempotencyKey })
  82  |     }
  83  |     await expect.soft(speed.getByText(/^\d+(?:\.\d+)? km\/h$/).first(), 'A real engine speed window must reach at least one truck row')
  84  |       .toBeVisible({ timeout: 60_000 })
  85  |     await expect.soft(speed.getByText(/^truck-\d+$/).first(), 'Speed panel must identify the truck').toBeVisible()
  86  |     await expect.soft(workers.getByText('3 alive', { exact: true }), 'All three Compose workers must be alive').toBeVisible({ timeout: 30_000 })
  87  |     await expect.soft(workers.getByRole('progressbar', { name: /^Source queue / }), 'Workers panel must render exactly three worker rows').toHaveCount(3)
  88  |   })
  89  | 
  90  |   await step('03-cross-check-engine-speed', async () => {
  91  |     // Capture the DOM before fetching persisted rows. A later UI update cannot
  92  |     // change this sample; the API must contain its exact truck and window minute.
  93  |     const displayed = await speed.locator('.panel-row').first().evaluate((row) => ({
  94  |       truck: row.querySelector('strong')?.textContent?.trim(),
  95  |       speed: row.querySelector('span')?.textContent?.trim(),
  96  |       minute: row.querySelector('time')?.textContent?.trim(),
  97  |     }))
  98  |     const rows = await api<WindowResult[]>('/api/results/windows?sinkId=speedSink&limit=200')
  99  |     const matching = rows.filter((row) => row.sinkId === 'speedSink' && row.key === displayed.truck
  100 |       && new Date(row.windowEnd).toISOString().slice(11, 16) === displayed.minute)
  101 |     await testInfo.attach('engine-speed-sample', {
  102 |       body: JSON.stringify({ displayed, matching }, null, 2), contentType: 'application/json',
  103 |     })
  104 |     expect.soft(matching, `Exactly one persisted speedSink row must match ${displayed.truck} at ${displayed.minute}`).toHaveLength(1)
  105 |     const row = matching[0]
  106 |     expect.soft(row?.values.avgSpeedKmh, 'Engine window must contain a finite avgSpeedKmh, not a UI fallback').toEqual(expect.any(Number))
  107 |     if (row && Number.isFinite(row.values.avgSpeedKmh)) {
  108 |       expect.soft(displayed.speed, `UI speed for ${row.key}, window ${row.windowStart}..${row.windowEnd}, must equal the ENGINE value rounded to one decimal`)
  109 |         .toBe(`${row.values.avgSpeedKmh.toFixed(1)} km/h`)
  110 |     } else {
  111 |       expect.soft(false, 'No finite matching ENGINE speed available for cross-check').toBe(true)
  112 |     }
  113 |   })
  114 | 
  115 |   await step('04-pipeline-editor-validation', async () => {
  116 |     const open = page.getByRole('button', { name: /pipeline editor|edit pipeline|open editor/i })
  117 |     await open.click()
  118 |     const editor = page.getByRole('dialog', { name: 'Pipeline editor', exact: true })
  119 |     await expect.soft(editor, 'Pipeline editor must open as an accessible dialog').toBeVisible()
  120 |     const [demoResponse] = await Promise.all([
  121 |       page.waitForResponse((response) => new URL(response.url()).pathname === '/api/pipelines/demo'),
  122 |       editor.getByRole('button', { name: 'Load demo', exact: true }).click(),
  123 |     ])
  124 |     expect.soft(demoResponse.ok(), 'Load demo must succeed against the real API').toBe(true)
  125 |     await expect.soft(editor.getByLabel('Output src', { exact: true }), 'Demo source must finish rendering before Validate').toBeVisible()
  126 |     await editor.getByRole('button', { name: 'Validate', exact: true }).click()
  127 |     await expect.soft(editor.getByText(/^Valid - \d+ operators, \d+ partitions$/), 'Real compiler must validate the demo successfully').toBeVisible()
  128 |     await screenshot('04-valid-demo')
  129 |     const broken = editor.getByRole('button', { name: /broken-for-demo/i })
  130 |     if (await broken.isVisible()) {
  131 |       await broken.click()
  132 |       await editor.getByRole('button', { name: 'Validate', exact: true }).click()
  133 |       const errors = editor.getByRole('region', { name: 'Validation errors', exact: true })
  134 |       await expect.soft(errors.getByRole('button'), 'Offered broken-for-demo example must yield exactly three compiler errors').toHaveCount(3)
  135 |     } else {
  136 |       testInfo.annotations.push({ type: 'optional-example', description: 'No broken-for-demo example offered by this editor' })
  137 |     }
  138 |     // The step screenshot intentionally retains the editor; close only afterward.
  139 |   })
  140 | 
  141 | 
  142 |   await step('05-kill-worker-and-recover', async () => {
  143 |     const closeEditor = page.getByRole('button', { name: 'Close pipeline editor', exact: true })
  144 |     if (await closeEditor.isVisible()) await closeEditor.click()
  145 |     await page.getByRole('button', { name: 'Kill worker', exact: true }).click()
```