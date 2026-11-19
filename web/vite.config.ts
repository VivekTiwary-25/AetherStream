import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

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
