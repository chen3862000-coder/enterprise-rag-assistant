import type {
  ChatResponse,
  DocumentSummary,
  DocumentVersion,
  EvaluationRun,
  EvaluationSummary,
  Identity,
  SourcePreview,
} from './types'

type Csrf = { token: string; headerName: string }

let csrf: Csrf | undefined

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(path, { credentials: 'same-origin', ...init })
  if (!response.ok) {
    const message = await response.text()
    throw new Error(message || `Request failed (${response.status})`)
  }
  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}

async function csrfHeader(): Promise<Record<string, string>> {
  csrf ??= await request<Csrf>('/api/auth/csrf')
  return { [csrf.headerName]: csrf.token }
}

export const api = {
  async me() {
    return request<Identity>('/api/auth/me')
  },
  async login(username: string) {
    return request<Identity>('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...(await csrfHeader()) },
      body: JSON.stringify({ username }),
    })
  },
  async logout() {
    await request<void>('/api/auth/logout', { method: 'POST', headers: await csrfHeader() })
    csrf = undefined
  },
  async chat(message: string) {
    return request<ChatResponse>('/api/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...(await csrfHeader()) },
      body: JSON.stringify({ message }),
    })
  },
  async source(chunkId: string) {
    return request<SourcePreview>(`/api/sources/${encodeURIComponent(chunkId)}`)
  },
  async documents() {
    return request<DocumentSummary[]>('/api/documents')
  },
  async versions(documentId: string) {
    return request<DocumentVersion[]>(`/api/documents/${encodeURIComponent(documentId)}/versions`)
  },
  async upload(form: FormData, documentId?: string) {
    const path = documentId
      ? `/api/documents/${encodeURIComponent(documentId)}/reindex`
      : '/api/documents'
    return request(path, { method: 'POST', headers: await csrfHeader(), body: form })
  },
  async disable(documentId: string) {
    return request<void>(`/api/documents/${encodeURIComponent(documentId)}`, {
      method: 'DELETE',
      headers: await csrfHeader(),
    })
  },
  async evaluations() {
    return request<EvaluationSummary[]>('/api/evaluations?limit=20')
  },
  async runEvaluation() {
    return request<EvaluationRun>('/api/evaluations/run', { method: 'POST', headers: await csrfHeader() })
  },
  async evaluation(runId: string) {
    return request<EvaluationRun>(`/api/evaluations/${encodeURIComponent(runId)}`)
  },
  async trace(traceId: string) {
    return request<Record<string, unknown>>(`/api/traces/${encodeURIComponent(traceId)}`)
  },
}
