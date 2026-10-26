import { chromium } from '@playwright/test'

const browser = await chromium.launch({ headless: true })
const page = await browser.newPage({ viewport: { width: 1440, height: 960 }, deviceScaleFactor: 1 })

try {
  await page.goto('http://127.0.0.1:3000')
  await page.getByRole('button', { name: /Carol/ }).click()
  await page.getByRole('button', { name: /Enter workspace/ }).click()
  await page.getByRole('button', { name: 'Knowledge' }).click()
  await page.getByText('Indexed documents').waitFor()
  await page.locator('.document-row-main').first().waitFor()
  await page.setViewportSize({ width: 1440, height: 620 })
  await page.screenshot({ path: './docs/media/knowledge-workspace.png' })

  await page.setViewportSize({ width: 1440, height: 960 })
  await page.getByRole('button', { name: 'Evaluation' }).click()
  await page.getByText(/Release quality:/).first().waitFor({ timeout: 15_000 })
  await page.screenshot({ path: './docs/media/evaluation-workspace.png' })

  await page.getByRole('button', { name: 'Chat' }).click()
  await page.getByRole('button', { name: /Enterprise API rate limit/ }).click()
  await page.locator('.citation-chip').first().waitFor({ timeout: 20_000 })
  await page.locator('.citation-chip').first().click()
  await page.locator('.source-text').waitFor()
  await page.screenshot({ path: './docs/media/chat-source.png' })
} finally {
  await browser.close()
}
