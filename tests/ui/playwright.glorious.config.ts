import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: '.',
  testMatch: 'glorious.spec.ts',
  workers: 1,
  retries: 1,
  timeout: 60_000,
  expect: { timeout: 20_000 },
  outputDir: 'test-results-glorious',
  reporter: 'list',
  use: {
    baseURL: process.env.GLORIOUS_URL || 'http://localhost:8089',
    viewport: { width: 1440, height: 900 },
    reducedMotion: 'reduce',
    actionTimeout: 10_000,
    navigationTimeout: 20_000,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { browserName: 'chromium' } }],
})
