import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { ChatWorkspace } from './ChatWorkspace'

vi.mock('../api', () => ({
  api: {
    chat: vi.fn(),
    source: vi.fn(),
    trace: vi.fn(),
  },
}))

import { api } from '../api'

describe('ChatWorkspace', () => {
  beforeEach(() => vi.clearAllMocks())

  it('shows an abstention response without fabricating citations', async () => {
    vi.mocked(api.chat).mockResolvedValue({
      answer: 'I could not find sufficient authorized evidence to answer that question.',
      citations: [],
      traceId: 'trace-1',
      groundingStatus: 'INSUFFICIENT_EVIDENCE',
    })
    render(<ChatWorkspace />)
    fireEvent.click(screen.getByText('What happened in restricted incident exercise IR-204?'))

    await waitFor(() => expect(screen.getByText(/sufficient authorized evidence/)).toBeInTheDocument())
    expect(screen.queryByText('S1')).not.toBeInTheDocument()
  })

  it('shows resolvable citations returned by the backend', async () => {
    vi.mocked(api.chat).mockResolvedValue({
      answer: 'The default limit is 1,000 requests per minute [S1].',
      citations: [{ citationId: 'S1', documentId: 'doc-rate-limit', documentTitle: 'Enterprise API Policy', version: 2, chunkId: 'doc-rate-limit-v2-c0', section: 'Rate Limits' }],
      traceId: 'trace-2',
      groundingStatus: 'SUPPORTED',
    })
    render(<ChatWorkspace />)
    fireEvent.click(screen.getByText('What is the Enterprise API rate limit, and how should clients handle HTTP 429?'))

    expect(await screen.findByText('Enterprise API Policy')).toBeInTheDocument()
    expect(screen.getByText('v2')).toBeInTheDocument()
  })
})
