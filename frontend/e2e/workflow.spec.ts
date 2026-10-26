import { expect, test } from '@playwright/test'

test('demo login, ACL-aware chat/source, upload/versioning, trace, and evaluation', async ({ page }) => {
  const documentId = `doc-e2e-${Date.now()}`
  const title = `E2E Policy ${documentId.slice(-8)}`
  await page.goto('/')
  await expect(page.getByRole('heading', { name: 'Choose your workspace' })).toBeVisible()
  await page.getByRole('button', { name: /Carol/ }).click()
  await page.getByRole('button', { name: /Enter workspace/ }).click()

  await expect(page.getByRole('heading', { name: /Answers that know/ })).toHaveCount(0)
  await page.getByRole('button', { name: /Enterprise API rate limit/ }).click()
  await expect(page.getByRole('button', { name: /View retrieval trace/ })).toBeVisible({ timeout: 15_000 })
  const firstCitation = page.locator('.citation-chip').first()
  if (await firstCitation.count()) {
    await firstCitation.click()
    await expect(page.getByText(/SOURCE EVIDENCE/)).toBeVisible()
  }

  await page.getByRole('button', { name: 'Knowledge' }).click()
  await page.getByRole('button', { name: 'Add document' }).click()
  await page.getByLabel('Logical document ID').fill(documentId)
  await page.getByLabel('Document title').fill(title)
  await page.getByLabel('Department').selectOption('PUBLIC')
  await page.getByLabel('Classification').selectOption('PUBLIC')
  await page.getByLabel('Allowed roles').fill('PUBLIC')
  await page.locator('input[name="file"]').setInputFiles({
    name: 'policy.txt',
    mimeType: 'text/plain',
    buffer: Buffer.from('# E2E Policy\nThe maintenance window starts at 02:00 UTC.'),
  })
  await page.getByRole('button', { name: 'Upload & index' }).click()
  await expect(page.getByText(new RegExp(`Indexed version 1 for ${documentId}`))).toBeVisible({ timeout: 30_000 })

  await page.getByRole('button', { name: new RegExp(title) }).click()
  await expect(page.locator('.version-expansion .version-tag').first()).toHaveText('v1')

  await page.getByRole('button', { name: 'Chat' }).click()
  await page.locator('textarea').fill('What is the public API base path?')
  await page.getByRole('button', { name: 'Send question' }).click()
  await expect(page.getByRole('button', { name: /View retrieval trace/ }).last()).toBeVisible({ timeout: 15_000 })
  await page.getByRole('button', { name: /View retrieval trace/ }).last().click()
  await expect(page.getByText('MODEL')).toBeVisible()
  await expect(page.getByText('STATUS')).toBeVisible()

  await page.getByRole('button', { name: 'Evaluation' }).click()
  await page.getByRole('button', { name: 'Run offline evaluation' }).click()
  await expect(page.getByText(/DIAGNOSTIC ABLATIONS/)).toBeVisible({ timeout: 60_000 })

  await page.getByRole('button', { name: 'Knowledge' }).click()
  await page.getByRole('button', { name: new RegExp(title) }).click()
  page.once('dialog', dialog => dialog.accept())
  await page.getByRole('button', { name: /Soft-delete selected document/ }).click()
  await expect(page.getByText(new RegExp(`${title} soft-deleted`))).toBeVisible()
})

test('Alice cannot preview HR-only evidence', async ({ page }) => {
  await page.goto('/')
  await page.locator('.identity-option').filter({ hasText: 'alice' }).click()
  await page.getByRole('button', { name: /Enter workspace/ }).click()
  await page.getByRole('button', { name: /annual leave/ }).click()
  await expect(page.locator('.message.assistant')).toBeVisible({ timeout: 15_000 })
  await expect(page.locator('.message.assistant')).toContainText(/INSUFFICIENT EVIDENCE/)
  await expect(page.getByText('HR Leave Policy')).toHaveCount(0)
})

test('upload validation uses structured client error text', async ({ page }) => {
  await page.goto('/')
  await page.locator('.identity-option').filter({ hasText: 'carol' }).click()
  await page.getByRole('button', { name: /Enter workspace/ }).click()
  await page.getByRole('button', { name: 'Knowledge' }).click()
  await page.getByRole('button', { name: 'Add document' }).click()
  const documentId = `doc-invalid-${Date.now()}`
  await page.getByLabel('Logical document ID').fill(documentId)
  await page.getByLabel('Document title').fill('Spoofed PDF')
  await page.getByLabel('Allowed roles').fill('PUBLIC')
  await page.locator('input[name="file"]').setInputFiles({
    name: 'spoof.pdf',
    mimeType: 'application/pdf',
    buffer: Buffer.from('plain text renamed to PDF'),
  })
  await page.getByRole('button', { name: 'Upload & index' }).click()
  await expect(page.getByText(/Upload rejected: Uploaded file content does not match its filename extension/))
    .toBeVisible({ timeout: 15_000 })
})
