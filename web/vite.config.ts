import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

// Node 25+ enables its own Web Storage global by default, which shadows jsdom's
// localStorage in tests. Turn it off in test workers when this Node knows the
// flag (Node 20 does not, and would refuse to start with it).
const webstorageArgv = process.allowedNodeEnvironmentFlags.has('--experimental-webstorage')
  ? ['--no-experimental-webstorage']
  : []

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': 'http://localhost:18080',
      '/ws': { target: 'http://localhost:18080', ws: true },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup-webstorage.ts', './src/test/setup-testing-library.ts'],
    // Heavy jsdom renders (App shell, React Flow editor) take 3-7 s when the
    // whole suite runs at once on every core; 5 s was hit at random.
    testTimeout: 15_000,
    poolOptions: {
      forks: { execArgv: webstorageArgv },
      threads: { execArgv: webstorageArgv },
    },
    coverage: {
      provider: 'v8',
      reporter: ['text-summary', 'html', 'json-summary'],
      include: ['src/**'],
      exclude: ['tests/mock/**'],
      thresholds: {
        statements: 54.61,
        branches: 71.14,
        functions: 61.23,
        lines: 54.61,
      },
    },
  },
})
