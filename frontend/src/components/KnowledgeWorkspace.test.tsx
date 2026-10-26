import { beforeEach, describe, expect, it, vi } from 'vitest'
import { uploadErrorMessage } from './KnowledgeWorkspace'

describe('KnowledgeWorkspace upload errors', () => {
  beforeEach(() => vi.clearAllMocks())

  it('shows structured validation, conflict, and provider messages', async () => {
    const messages = [
      ['INGESTION_INVALID_INPUT', 'Uploaded file content does not match its filename extension', /Upload rejected/],
      ['INGESTION_CONFLICT', 'already being indexed', /Wait for it to finish/],
      ['INGESTION_PROVIDER_UNAVAILABLE', 'provider unavailable', /embedding service is temporarily unavailable/i],
      ['INGESTION_UNPROCESSABLE', 'PDF contains no extractable text; scanned PDFs are unsupported', /could not be indexed/i],
    ] as const

    for (const [code, message, expected] of messages) {
      expect(uploadErrorMessage(Object.assign(new Error(message), { code, status: 422 }))).toMatch(expected)
    }
  })
})
