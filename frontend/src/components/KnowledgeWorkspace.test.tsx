import { beforeEach, describe, expect, it, vi } from 'vitest'
import { canRetryFirstUpload, documentStatusLabel, ingestionProgressLabel, selectedAllowedRoles, uploadErrorMessage } from './KnowledgeWorkspace'

describe('KnowledgeWorkspace upload errors', () => {
  beforeEach(() => vi.clearAllMocks())

  it('shows distinct inactive, in-progress, validation, and provider messages', async () => {
    const messages = [
      ['INGESTION_INVALID_INPUT', 'Uploaded file content does not match its filename extension', /Upload rejected/],
      ['DOCUMENT_INACTIVE', 'Restore the inactive document', /Restore it from the document actions before retrying/],
      ['INGESTION_CONFLICT', 'duplicate version conflict', /conflicts with an existing indexing attempt/],
      ['INGESTION_IN_PROGRESS', 'active ingestion', /currently indexing/],
      ['INGESTION_PROVIDER_UNAVAILABLE', 'provider unavailable', /embedding service is temporarily unavailable/i],
      ['INGESTION_UNPROCESSABLE', 'PDF contains no extractable text; scanned PDFs are unsupported', /scanned PDFs are not supported/i],
    ] as const

    for (const [code, message, expected] of messages) {
      expect(uploadErrorMessage(Object.assign(new Error(message), { code, status: 422 }))).toMatch(expected)
    }
  })
})

describe('KnowledgeWorkspace document status', () => {
  it('distinguishes a restored first-upload failure from a failed active reindex', () => {
    const restored = {
      id: 'ChoiceManual', title: 'Choice Manual', department: 'PUBLIC', classification: 'PUBLIC',
      status: 'ACTIVE', currentVersion: null, indexStatus: 'FAILED', updatedAt: null,
    } as const
    expect(documentStatusLabel(restored)).toBe('READY TO RETRY')
    expect(documentStatusLabel({ ...restored, currentVersion: 1 })).toBe('FAILED')
    expect(canRetryFirstUpload(restored)).toBe(true)
    expect(canRetryFirstUpload({ ...restored, status: 'DISABLED' })).toBe(false)
    expect(canRetryFirstUpload({ ...restored, currentVersion: 1 })).toBe(false)
  })

  it('renders backend-polled ingestion chunk progress', () => {
    expect(ingestionProgressLabel(64)).toBe('64 chunks completed. You can close this dialog; the backend continues processing.')
  })
})

describe('KnowledgeWorkspace ACL selection', () => {
  it('requires explicit allowed roles and normalizes checkbox values', () => {
    const empty = new FormData()
    expect(selectedAllowedRoles(empty)).toEqual([])

    const selected = new FormData()
    selected.append('allowedRole', 'engineering')
    selected.append('allowedRole', 'HR')
    selected.append('allowedRole', 'ENGINEERING')
    expect(selectedAllowedRoles(selected)).toEqual(['ENGINEERING', 'HR'])
  })

  it('preserves no default role for a new upload and existing roles for a retry', () => {
    expect(selectedAllowedRoles(new FormData())).toEqual([])
    const retry = new FormData()
    for (const role of ['HR', 'SECURITY_ADMIN']) retry.append('allowedRole', role)
    expect(selectedAllowedRoles(retry)).toEqual(['HR', 'SECURITY_ADMIN'])
  })
})
