import { expect, test } from '@playwright/test'

const configuredUrl = process.env.GLORIOUS_URL ?? 'http://localhost:8089'
if (configuredUrl.includes(':8088')) {
  throw new Error('Refusing to run worker-kill recovery against the live demo port :8088')
}

type RecoveryEvent = {
  id: number; event: string; workerId: string | null; at: number; restoredFromCheckpoint: number | null
}

test('worker-2 recovers from a server-recorded kill', async ({ page }) => {
  test.skip(process.env.ALLOW_KILL !== '1', 'Set ALLOW_KILL=1 to enable this destructive recovery test')

  await page.goto('/')
  await page.getByRole('button', { name: 'Settings', exact: true }).click()
  await page.getByRole('button', { name: 'Logs & Recovery', exact: true }).click()

  const recovery = page.getByTestId('logs-recovery')
  await expect(recovery).toBeVisible()
  const workerId = 'worker-2'
  const card = recovery.getByTestId(`worker-card-${workerId}`)
  const survivors = recovery.getByTestId(/^worker-card-/).filter({
    hasNot: page.getByRole('heading', { name: workerId, exact: true }),
  })
  const historyPanel = recovery.getByRole('region', { name: 'Activity & failure history', exact: true })
  const readHistory = async (): Promise<RecoveryEvent[]> => {
    const response = await page.request.get('/api/recovery/history', { timeout: 5_000 })
    expect(response.ok(), 'GET /api/recovery/history must succeed').toBeTruthy()
    return response.json()
  }

  const workersResponse = await page.request.get('/api/workers', { timeout: 5_000 })
  expect(workersResponse.ok(), 'GET /api/workers must succeed').toBeTruthy()
  const initialWorkers = await workersResponse.json() as Array<{ workerId: string; partitions: number[] }>
  const initialWorker = initialWorkers.find((worker) => worker.workerId === workerId)
  expect(initialWorker, 'worker-2 must be present before the kill').toBeDefined()
  const partitions = initialWorker!.partitions.map((partition) => `P${partition}`).sort()
  expect(partitions, 'worker-2 must own partitions before the kill').not.toEqual([])
  const expectPartitionsRetained = () => expect.poll(
    async () => (await card.getByTestId(/^partition-chip-/).allTextContents()).sort(),
    { timeout: 5_000, message: 'worker-2 must keep its original partitions' },
  ).toEqual(partitions)
  await expect(card.getByText('Running', { exact: true })).toBeVisible()
  await expect(survivors.getByText('Rejoined', { exact: true })).toHaveCount(0)
  await expectPartitionsRetained()
  const previousIds = new Set((await readHistory()).map((entry) => entry.id))

  const killStartedAt = Date.now()
  const killResponse = await page.request.post('/api/chaos/kill-worker', {
    data: { workerId }, timeout: 10_000,
  })
  expect(killResponse.ok(), 'POST /api/chaos/kill-worker must succeed').toBeTruthy()

  // Observe the short execution pause concurrently with the three-second Dead badge.
  // Waiting for the worker to rejoin first can miss RECOVERING entirely.
  const [death] = await Promise.all([
    (async () => {
      await expect(card.getByText('Dead', { exact: true })).toBeVisible({ timeout: 15_000 })
      expect(await survivors.getByText('Rejoined', { exact: true }).count()).toBe(0)
      let deathEvent: RecoveryEvent | undefined
      await expect.poll(async () => {
        deathEvent = (await readHistory()).find((entry) =>
          entry.workerId === workerId && !previousIds.has(entry.id) && entry.event === 'WORKER_DEAD')
        return deathEvent !== undefined
      }, { timeout: 5_000, intervals: [100, 200, 250] }).toBeTruthy()
      expect(Number.isFinite(deathEvent!.at) && deathEvent!.at > 0).toBeTruthy()
      await expectPartitionsRetained()

      // The hold starts at WORKER_DEAD.at, not KILL_REQUESTED or the restart event.
      // Allow rendering/polling jitter; the death reason may be "re-registered".
      await expect(card.getByText('Rejoined', { exact: true })).toBeVisible({ timeout: 10_000 })
      const rejoinedAt = await page.evaluate(() => Date.now())
      expect(rejoinedAt - deathEvent!.at).toBeGreaterThanOrEqual(2_750)
      expect(rejoinedAt - deathEvent!.at).toBeLessThan(10_000)
      expect(await survivors.getByText('Rejoined', { exact: true }).count()).toBe(0)
      await expectPartitionsRetained()
      await expect(card.getByText('Running', { exact: true })).toBeVisible({ timeout: 20_000 })
      await expectPartitionsRetained()
      return deathEvent!
    })(),
    (async () => {
      const readExecution = async () => {
        const response = await page.request.get('/api/executions/current', { timeout: 5_000 })
        expect(response.ok(), 'GET /api/executions/current must succeed').toBeTruthy()
        return response.json() as Promise<{ state: string; restoredFromCheckpoint: number | null }>
      }
      await expect.poll(async () => (await readExecution()).state, {
        timeout: 10_000, intervals: [100, 200, 250],
      }).toBe('RECOVERING')
      await expect.poll(readExecution, {
        timeout: 10_000, intervals: [100, 200, 250],
      }).toMatchObject({ state: 'RUNNING', restoredFromCheckpoint: expect.any(Number) })
      // Normally ~6 seconds from the kill; allow twice that without a minute-long wait.
      expect(Date.now() - killStartedAt).toBeLessThan(12_000)
    })(),
  ])

  const history = await readHistory()
  const forWorker = history.filter((entry) => entry.workerId === workerId && !previousIds.has(entry.id))
  for (const event of ['KILL_REQUESTED', 'WORKER_DEAD', 'WORKER_RESTARTED', 'RECOVERING', 'RECOVERED']) {
    expect(forWorker.some((entry) => entry.event === event && Number.isFinite(entry.at) && entry.at > 0),
      event).toBeTruthy()
  }
  const recovered = forWorker.find((entry) => entry.event === 'RECOVERED')!
  expect(recovered.restoredFromCheckpoint).toEqual(expect.any(Number))

  // The same confirmed failure must remain visible after the worker is Running again.
  const failure = historyPanel.getByRole('listitem').filter({
    has: page.getByText('Worker died', { exact: true }),
  }).filter({ has: page.getByText(workerId, { exact: true }) }).filter({
    has: page.locator(`time[datetime="${new Date(death.at).toISOString()}"]`),
  })
  await expect(failure).toBeVisible()
  await expect(historyPanel.getByText(
    `Restored from checkpoint #${recovered.restoredFromCheckpoint}`, { exact: false },
  ).first()).toBeVisible()
  await expect(recovery.getByText(
    `Current run restarted from checkpoint #${recovered.restoredFromCheckpoint} ·`, { exact: false },
  )).toBeVisible()
  const eventTimes = await historyPanel.locator('ol > li > .recovery-event-heading > time')
    .evaluateAll((times) => times.map((time) => Date.parse(time.getAttribute('datetime')!)))
  expect(eventTimes.every(Number.isFinite)).toBeTruthy()
  expect(eventTimes).toEqual([...eventTimes].sort((a, b) => b - a))
})
