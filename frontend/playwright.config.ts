import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  use: {
    baseURL: 'http://127.0.0.1:3000',
    ...devices['Desktop Chrome'],
  },
  webServer: {
    command: 'docker compose -f ../compose.yaml up --build -d backend frontend',
    url: 'http://127.0.0.1:3000',
    reuseExistingServer: true,
    timeout: 180_000,
  },
})
