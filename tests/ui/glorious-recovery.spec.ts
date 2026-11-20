import { expect, test } from '@playwright/test'

const configuredUrl = process.env.GLORIOUS_URL ?? 'http://localhost:8089'
if (configuredUrl.includes(':8088')) {
  throw new Error('Refusing to run worker-kill recovery against the live demo port :8088')
}

test('worker-2 recovers from a server-recorded kill', async ({ page }) => {
  test.skip(process.env.ALLOW_KILL !== '1', 'Set ALLOW_KILL=1 to enable this destructive recovery test')

  await page.goto('/')
  await page.getByRole('button', { name: 'Settings', exact: true }).click()
  await page.getByRole('button', { name: 'Logs & Recovery', exact: true }).click()

  const recovery = page.getByTestId('logs-recovery')
  await expect(recovery).toBeVisible()
  const card = recovery.getByTestId('worker-card-worker-2')

  const workersResponse = await page.request.get('/api/workers')
  expect(workersResponse.ok(), 'GET /api/workers must succeed').toBeTruthy()
  const initialWorkers = await workersResponse.json()
  expect(initialWorkers.some((worker: { workerId: string }) => worker.workerId === 'worker-2'),
    'worker-2 must be present in server worker data before the kill').toBeTruthy()

  const killResponse = await page.request.post('/api/chaos/kill-worker', { data: { workerId: 'worker-2' } })
  expect(killResponse.ok(), 'POST /api/chaos/kill-worker must succeed').toBeTruthy()

  await expect.poll(() => card.locator('.recovery-status').textContent(), {
    timeout: 60_000, message: 'worker-2 must reach its rendered dead state',
  }).toBe('Dead')
  await page.screenshot({ path: 'test-results/recovery-dead.png' })

  await expect.poll(() => card.locator('.recovery-status').textContent(), {
    timeout: 60_000, message: 'worker-2 must reach its rendered recovery state',
  }).toBe('Recovering')
  await page.screenshot({ path: 'test-results/recovery-recovering.png' })

  await expect.poll(() => card.locator('.recovery-status').textContent(), {
    timeout: 60_000, message: 'worker-2 must rejoin and return to running',
  }).toMatch(/^(Rejoined|Running)$/)
  await page.screenshot({ path: 'test-results/recovery-rejoined.png' })

  const historyResponse = await page.request.get('/api/recovery/history')
  expect(historyResponse.ok(), 'GET /api/recovery/history must succeed').toBeTruthy()
  const history = await historyResponse.json() as Array<{
    event: string; workerId: string | null; at: number; restoredFromCheckpoint: number | null
  }>
  const forWorker = history.filter((entry) => entry.workerId === 'worker-2')
  const atIsReal = (entry: typeof forWorker[number]) => Number.isFinite(entry.at) && entry.at > 0
  expect(forWorker.some((entry) => entry.event === 'KILL_REQUESTED' && atIsReal(entry))).toBeTruthy()
  expect(forWorker.some((entry) => ['WORKER_DEAD', 'WORKER_RESTARTED'].includes(entry.event) && atIsReal(entry))).toBeTruthy()
  expect(forWorker.some((entry) => entry.event === 'RECOVERED' && atIsReal(entry) &&
    typeof entry.restoredFromCheckpoint === 'number')).toBeTruthy()

  await expect(recovery.locator('li.recovery-event--worker_dead, li.recovery-event--worker_restarted')
    .filter({ has: page.getByText('worker-2', { exact: true }) }).first()).toBeVisible()
})
