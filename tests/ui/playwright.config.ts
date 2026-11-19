import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: '.',
  testMatch: 'demo.spec.ts',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 480_000,
  expect: { timeout: 10_000 },
  outputDir: 'test-results',
  reporter: [['list'], ['html', { open: 'never' }], ['json', { outputFile: 'results.json' }]],
  use: {
    // Specs use page.goto('/'), which drops a baseURL query. Selecting ?ui=classic
    // requires an init script in a shared fixture; config has no init-script option.
    baseURL: 'http://localhost:8088',
    actionTimeout: 10_000,
    navigationTimeout: 30_000,
    screenshot: 'on',
    video: 'retain-on-failure',
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 1000 } } }],
})
