import { expect, test } from '@playwright/test'

test.use({ screenshot: 'only-on-failure' })

test('tour, pipeline examples, operations savepoint, and city scenario', async ({ page }) => {
  await page.addInitScript(() => {
    window.sessionStorage.setItem('aetherstream.intro.seen', '1')
  })
  await page.goto('/')
  await expect(page.getByRole('dialog', { name: 'AetherStream introduction' })).toHaveCount(0)

  await page.getByRole('button', { name: 'Start tour' }).click()
  const tour = page.getByRole('dialog')
  for (const title of [
    'The city',
    'The stream into the engine',
    'Live panels',
    'Pipeline editor',
    'Chaos',
    'Kill a worker',
    'Compare with reference',
    'DevOps',
  ]) {
    await expect(tour.getByRole('heading', { name: title, exact: true })).toBeVisible()
    await tour.getByRole('button', { name: title === 'DevOps' ? 'Finish' : 'Next', exact: true }).click()
  }
  await expect(tour).toHaveCount(0)

  await page.getByRole('button', { name: 'Open pipeline editor' }).click()
  const editor = page.getByRole('dialog', { name: 'Pipeline editor' })
  const examples = editor.getByRole('combobox', { name: 'Examples', exact: true })
  await expect(examples).toBeEnabled()
  await examples.selectOption('broken-for-demo')
  await editor.getByRole('button', { name: 'Validate', exact: true }).click()
  const errors = editor.locator('.validation-list__item')
  await expect(errors).toHaveCount(3)
  await expect(errors.first()).toBeVisible()

  await examples.selectOption('logistics-demo')
  await editor.getByRole('button', { name: 'Validate', exact: true }).click()
  await expect(editor.getByText(/^Valid - \d+ operators, \d+ partitions$/)).toBeVisible()
  await expect(errors).toHaveCount(0)
  await editor.getByRole('button', { name: 'Close pipeline editor' }).click()

  const operations = page.getByRole('region', { name: 'Operations' })
  await operations.getByRole('button', { name: 'Take savepoint', exact: true }).click()
  const savepointNotice = operations.getByRole('status')
  await expect(savepointNotice).toContainText(/^Savepoint .+ at checkpoint #\d+$/)
  await expect(operations.getByLabel('Recent savepoints')).toContainText(/checkpoint #\d+/)

  const scenarios = page.getByRole('region', { name: 'City scenarios' })
  await scenarios.getByRole('button', { name: 'Rush hour', exact: true }).click()
  await expect(scenarios.getByText('Active: Rush hour', { exact: true })).toBeVisible()
})
