import { expect, test } from '@playwright/test'

// Playwright supplies a fresh page and browser context for every test.
test.beforeEach(async ({ page }) => {
  await page.goto('/')
})

test('Home media loads and Live World shows six truck markers', async ({ page }) => {
  const workerErrors: string[] = []
  page.on('console', (message) => {
    if (message.type() === 'error' && message.text().includes('Worker failed to load')) {
      workerErrors.push(message.text())
    }
  })
  await expect(page.locator('[aria-label="Mysuru highway"]').locator('video, img')).toBeVisible()
  await page.getByRole('button', { name: 'Live World', exact: true }).click()

  const map = page.getByRole('region', { name: 'Live World map', exact: true })
  await expect(map).toBeVisible()
  const trucks = map.getByRole('button', {
    name: /^Truck T-\d+, .+, \d+(?:\.\d+)? kilometers per hour$/,
  })
  await expect(trucks).toHaveCount(6, { timeout: 20_000 })
  expect(workerErrors, 'MapLibre worker must load without console errors').toEqual([])
})

test('Truck Speeds drills down in the same window and returns to the list', async ({ page }) => {
  await page.getByRole('button', { name: 'Analytics', exact: true }).click()
  await page.getByRole('button', { name: 'Truck Speeds', exact: true }).click()

  const window = page.getByRole('dialog')
  await expect(window).toHaveCount(1)
  const list = window.getByTestId('analytics-truck-speeds')
  await expect(list).toBeVisible()
  const firstTruck = list.getByTestId(/^truck-row-.+$/).first()
  await expect(firstTruck).toBeVisible()
  const truckRowId = await firstTruck.getAttribute('data-testid')
  const titleId = await window.getAttribute('aria-labelledby')
  expect(titleId).toBeTruthy()
  const before = await window.boundingBox()
  if (!before) throw new Error('Truck Speeds window has no bounding box')

  await firstTruck.click()
  await expect(window.getByTestId('truck-speed-chart')).toBeVisible()
  await expect(window).toHaveCount(1)
  await expect(window).toHaveAttribute('aria-labelledby', titleId!)
  await expect.poll(async () => {
    const after = await window.boundingBox()
    if (!after) return Number.POSITIVE_INFINITY
    return Math.max(
      Math.abs(after.x - before.x), Math.abs(after.y - before.y),
      Math.abs(after.width - before.width), Math.abs(after.height - before.height),
    )
  }, { message: 'Truck detail must preserve the window position and size within 2 px' }).toBeLessThanOrEqual(2)

  // The title bar and the detail content both currently expose a Back button.
  await window.getByRole('button', { name: 'Back', exact: true }).first().click()
  await expect(list).toBeVisible()
  await expect(list.getByTestId(truckRowId!)).toBeVisible()
  await expect(window.getByTestId('truck-speed-chart')).toHaveCount(0)
})

test('Logs & Recovery shows three workers covering partitions P0–P5', async ({ page }) => {
  await page.getByRole('button', { name: 'Settings', exact: true }).click()
  await page.getByRole('button', { name: 'Logs & Recovery', exact: true }).click()

  const recovery = page.getByTestId('logs-recovery')
  await expect(recovery).toBeVisible()
  const workers = recovery.getByTestId(/^worker-card-.+$/)
  await expect(workers).toHaveCount(3)
  for (let index = 0; index < 3; index++) {
    const worker = workers.nth(index)
    await expect(worker).toBeVisible()
    const partitions = worker.getByTestId(/^partition-chip-[0-5]$/)
    await expect.poll(() => partitions.count(), {
      message: `Worker card ${index + 1} must show at least one partition`,
    }).toBeGreaterThanOrEqual(1)
    await expect(partitions.first()).toHaveText(/^\s*P[0-5]\s*$/)
  }
  for (let partition = 0; partition < 6; partition++) {
    const chip = workers.getByTestId(`partition-chip-${partition}`).first()
    await expect(chip).toBeVisible()
    await expect(chip).toHaveText(new RegExp(`^\\s*P${partition}\\s*$`))
  }
})
