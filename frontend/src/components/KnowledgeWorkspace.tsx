import { FormEvent, useEffect, useState } from 'react'
import { Archive, Check, ChevronDown, FilePlus2, FileText, LoaderCircle, RefreshCw, Shield, Trash2, UploadCloud, X } from 'lucide-react'
import { api } from '../api'
import type { DocumentSummary, DocumentVersion, Identity } from '../types'

export function KnowledgeWorkspace({ identity }: { identity: Identity }) {
  const [documents, setDocuments] = useState<DocumentSummary[]>([])
  const [versions, setVersions] = useState<DocumentVersion[]>([])
  const [selected, setSelected] = useState<DocumentSummary | null>(null)
  const [loading, setLoading] = useState(true)
  const [uploading, setUploading] = useState(false)
  const [showUpload, setShowUpload] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')

  async function refresh() {
    setLoading(true)
    try { setDocuments(await api.documents()) }
    catch (cause) { setError(cause instanceof Error ? cause.message : 'Could not load documents') }
    finally { setLoading(false) }
  }

  useEffect(() => { void refresh() }, [])

  async function selectDocument(document: DocumentSummary) {
    setSelected(document)
    setVersions([])
    try { setVersions(await api.versions(document.id)) }
    catch (cause) { setError(cause instanceof Error ? cause.message : 'Could not load versions') }
  }

  async function disableDocument(document: DocumentSummary) {
    if (!window.confirm(`Disable “${document.title}”? It will immediately leave retrieval.`)) return
    try {
      await api.disable(document.id)
      setNotice(`${document.title} disabled and removed from retrieval.`)
      setSelected(null)
      await refresh()
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'Unable to disable document') }
  }

  async function deleteDocument(document: DocumentSummary) {
    if (!window.confirm(`Soft-delete “${document.title}”? It will be retained but excluded from retrieval.`)) return
    try {
      await api.deleteDocument(document.id)
      setNotice(`${document.title} soft-deleted and removed from retrieval.`)
      setSelected(null)
      await refresh()
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'Unable to soft-delete document') }
  }

  async function restoreDocument(document: DocumentSummary) {
    try {
      await api.restoreDocument(document.id)
      setNotice(`${document.title} restored to retrieval.`)
      setSelected(null)
      await refresh()
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'Unable to restore document') }
  }

  async function upload(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = event.currentTarget
    const data = new FormData(form)
    const logicalDocumentId = String(data.get('documentId') || '').trim()
    if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,159}$/.test(logicalDocumentId)) {
      setError('Logical document ID must start with a letter or number and use only letters, numbers, dot, underscore, or hyphen.')
      return
    }
    const file = data.get('file')
    if (typeof file === 'string' || !file || typeof file.size !== 'number' || file.size === 0) {
      setError('Choose a non-empty Markdown, text, or extractable PDF file.')
      return
    }
    setUploading(true)
    setError('')
    try {
      const result = await api.upload(data, logicalDocumentId)
      setNotice(result.duplicate
        ? result.metadataUpdated ? 'Content was unchanged; metadata and ACL were updated without re-embedding.' : 'Identical active content already indexed; no metadata or ACL changes were needed.'
        : `Indexed version ${result.version} for ${result.documentId}.`)
      setShowUpload(false)
      form.reset()
      await refresh()
    } catch (cause) { setError(uploadErrorMessage(cause)) }
    finally { setUploading(false) }
  }

  return <div className="knowledge-page">
    <div className="page-heading knowledge-heading"><div><div className="eyebrow dark"><span /> KNOWLEDGE OPERATIONS</div><h1>Documents &amp; <em>versions.</em></h1><p className="muted">A living index of the knowledge available to your workspace.</p></div>
      {identity.documentAdmin && <button className="primary-button" onClick={() => { setShowUpload(true); setError('') }}><FilePlus2 size={16} /> Add document</button>}
    </div>
    {notice && <div className="success-banner"><Check size={16} /> {notice}<button onClick={() => setNotice('')} aria-label="Dismiss"><X size={15} /></button></div>}
    {error && <div className="error-banner">{error}<button onClick={() => setError('')} aria-label="Dismiss"><X size={15} /></button></div>}
    <div className="knowledge-layout">
      <section className="document-list-card">
        <div className="list-toolbar"><div><strong>Indexed documents</strong><span>{documents.length} visible to your identity</span></div><button className="icon-button" onClick={() => void refresh()} aria-label="Refresh list"><RefreshCw size={16} /></button></div>
        <div className="document-table-head"><span>DOCUMENT</span><span>CLASSIFICATION</span><span>VERSION</span><span>STATUS</span><span /></div>
        {loading ? <div className="loading-state"><LoaderCircle className="spin" /> Loading index...</div> : documents.length === 0 ? <div className="empty-state">No accessible documents are indexed yet.</div> : documents.map(document => <div key={document.id} className={`document-row ${selected?.id === document.id ? 'selected' : ''}`}>
          <button className="document-row-main" onClick={() => void selectDocument(document)}><span className="file-icon"><FileText size={17} /></span><span className="document-name"><strong>{document.title}</strong><small>{document.id}</small></span><span className={`classification ${document.classification.toLowerCase()}`}>{document.classification}</span><span className="version-pill">{document.currentVersion ? `v${document.currentVersion}` : '—'}</span><span className={`status-pill ${document.status.toLowerCase()}`}>{document.indexStatus || document.status}</span><ChevronDown className="row-chevron" size={15} /></button>
          {selected?.id === document.id && <div className="version-expansion"><div className="version-heading">VERSION HISTORY <span>{versions.length} versions</span></div>{versions.map(version => <div key={version.id} className="version-row"><span className="version-tag">v{version.version}</span><span className="version-source">{version.sourceFilename}<small>{version.embeddingModelId} · {version.embeddingDimensions}d</small></span><span className={version.active ? 'active-version' : 'inactive-version'}>{version.active ? 'ACTIVE' : version.indexStatus}</span><small>{new Date(version.createdAt).toLocaleDateString()}</small></div>)}</div>}
        </div>)}
      </section>
      <aside className="knowledge-aside"><div className="aside-card principle-card"><div className="aside-icon"><Shield size={18} /></div><span className="eyebrow dark">INDEX PRINCIPLE</span><h3>Only active evidence travels.</h3><p>Versions are prepared away from retrieval. A complete index switches active atomically; failed work leaves the last healthy version in place.</p><div className="principle-step"><i>01</i> Stage &amp; validate</div><div className="principle-step"><i>02</i> Atomic activation</div><div className="principle-step"><i>03</i> ACL before search</div></div>
        {identity.documentAdmin && <div className="aside-card admin-card"><div className="admin-kicker"><span /> ADMIN ACTIONS</div><strong>Carol’s management scope</strong><p>Management permission is separate from access to classified evidence.</p>{selected && selected.status === 'ACTIVE' && <button className="danger-button" onClick={() => void disableDocument(selected)}><Archive size={15} /> Disable selected document</button>}{selected && selected.status !== 'DELETED' && <button className="danger-button" onClick={() => void deleteDocument(selected)}><Trash2 size={15} /> Soft-delete selected document</button>}{selected && selected.status !== 'ACTIVE' && selected.currentVersion !== null && <button className="secondary-button" onClick={() => void restoreDocument(selected)}>Restore selected document</button>}</div>}
        <div className="aside-card scope-card"><span className="eyebrow dark">YOUR RETRIEVAL SCOPE</span><h3>{identity.roles.join(' · ')}</h3><p>These roles filter retrieval. Management permission does not expand your chat evidence scope.</p></div>
      </aside>
    </div>
    {showUpload && <div className="modal-backdrop" role="presentation" onMouseDown={event => { if (event.target === event.currentTarget) setShowUpload(false) }}><section className="upload-modal" role="dialog" aria-modal="true" aria-labelledby="upload-title"><div className="modal-heading"><div><span className="eyebrow dark">INDEX A SOURCE</span><h2 id="upload-title">Add or update knowledge</h2></div><button className="icon-button" onClick={() => setShowUpload(false)} aria-label="Close"><X size={18} /></button></div>
      <form onSubmit={upload} className="upload-form"><label>Logical document ID <input name="documentId" placeholder="doc-engineering-api-guide" pattern="[A-Za-z0-9][A-Za-z0-9._-]{0,159}" required /></label><p className="field-note">Use a new stable ID for a new document; reuse its existing ID to create a new version. Identical content updates metadata/ACL without re-embedding.</p><label>Document title <input name="title" required maxLength={180} /></label><div className="form-two"><label>Department <select name="department"><option>PUBLIC</option><option>ENGINEERING</option><option>HR</option><option>SECURITY</option></select></label><label>Classification <select name="classification"><option>PUBLIC</option><option>INTERNAL</option><option>CONFIDENTIAL</option><option>RESTRICTED</option></select></label></div><label>Allowed roles <input name="allowedRoles" defaultValue="ENGINEERING" placeholder="ENGINEERING,HR" required /></label><label className="file-drop"><UploadCloud size={22} /><strong>Choose Markdown, plain text, or extractable PDF</strong><small>Maximum 10 MB · Scanned PDFs are unsupported</small><input name="file" type="file" accept=".md,.markdown,.txt,.pdf,text/plain,text/markdown,application/pdf" required /></label><div className="modal-actions"><button type="button" className="secondary-button" onClick={() => setShowUpload(false)}>Cancel</button><button type="submit" className="primary-button" disabled={uploading}>{uploading ? <LoaderCircle size={15} className="spin" /> : <UploadCloud size={15} />} {uploading ? 'Indexing...' : 'Upload & index'}</button></div></form>
    </section></div>}
  </div>
}

export function uploadErrorMessage(cause: unknown) {
  if (!(cause instanceof Error)) return 'Upload failed. Check the file and document details, then retry.'
  const code = 'code' in cause ? cause.code : undefined
  if (code === 'INGESTION_INVALID_INPUT') return `Upload rejected: ${cause.message}`
  if (code === 'INGESTION_CONFLICT') return 'This document is inactive or already being indexed. Restore inactive documents or wait for indexing to finish, then retry.'
  if (code === 'INGESTION_UNPROCESSABLE' && cause.message === 'PDF contains no extractable text; scanned PDFs are unsupported') {
    return 'This PDF contains no extractable text. Scanned PDFs are not supported; upload a text-based PDF instead.'
  }
  if (code === 'INGESTION_UNPROCESSABLE') return `Document could not be indexed: ${cause.message}`
  if (code === 'INGESTION_PROVIDER_UNAVAILABLE') return 'The embedding service is temporarily unavailable. Your active document version was not replaced; retry later.'
  return cause.message || 'Upload failed. Check the file and document details, then retry.'
}
