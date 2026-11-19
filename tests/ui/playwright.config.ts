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
    baseURL: 'http://localhost:8088',
    // The new UI is the default. These specs test the classic UI: page.goto('/') drops a
    // baseURL query, so select classic via a localStorage flag that web/src/main.tsx honours.
    storageState: {
      cookies: [],
      origins: [{ origin: 'http://localhost:8088', localStorage: [{ name: 'aether.ui', value: 'classic' }] }],
    },
    actionTimeout: 10_000,
    navigationTimeout: 30_000,
    screenshot: 'on',
    video: 'retain-on-failure',
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 1000 } } }],
})
