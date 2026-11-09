import { FormEvent, useEffect, useState } from 'react'
import { Archive, Check, ChevronDown, FilePlus2, FileText, LoaderCircle, RefreshCw, Shield, Trash2, UploadCloud, X } from 'lucide-react'
import { api } from '../api'
import type { DocumentSummary, DocumentVersion, Identity, IngestionStatus } from '../types'

const MAX_UPLOAD_MIB = Number(import.meta.env.VITE_MAX_UPLOAD_MIB || 10)
const CHM_UPLOAD_ENABLED = import.meta.env.VITE_CHM_UPLOAD_ENABLED === 'true'
const FILE_TYPES = CHM_UPLOAD_ENABLED
  ? '.md,.markdown,.txt,.pdf,.chm,text/plain,text/markdown,application/pdf'
  : '.md,.markdown,.txt,.pdf,text/plain,text/markdown,application/pdf'
const SUPPORTED_FORMATS = CHM_UPLOAD_ENABLED
  ? 'Markdown, text, extractable PDF, or CHM'
  : 'Markdown, text, or extractable PDF'
const DOCUMENT_ROLES = ['PUBLIC', 'ENGINEERING', 'HR', 'SECURITY_ADMIN'] as const

export function KnowledgeWorkspace({ identity }: { identity: Identity }) {
  const [documents, setDocuments] = useState<DocumentSummary[]>([])
  const [versions, setVersions] = useState<DocumentVersion[]>([])
  const [selected, setSelected] = useState<DocumentSummary | null>(null)
  const [loading, setLoading] = useState(true)
  const [uploading, setUploading] = useState(false)
  const [showUpload, setShowUpload] = useState(false)
  const [retryDocument, setRetryDocument] = useState<DocumentSummary | null>(null)
  const [activeIngestion, setActiveIngestion] = useState<IngestionStatus | null>(null)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')

  async function refresh() {
    setLoading(true)
    try {
      const currentDocuments = await api.documents()
      setDocuments(currentDocuments)
      const activeJob = currentDocuments.find(document => document.ingestionJobStatus === 'INDEXING' && document.ingestionJobId)
      if (activeJob?.ingestionJobId && activeIngestion?.jobId !== activeJob.ingestionJobId) {
        setActiveIngestion(await api.ingestion(activeJob.ingestionJobId))
      }
      if (activeJob?.ingestionJobId && activeIngestion?.jobId === activeJob.ingestionJobId) {
        setActiveIngestion(await api.ingestion(activeJob.ingestionJobId))
      }
      if (!activeJob && activeIngestion?.status === 'INDEXING') setActiveIngestion(null)
    }
    catch (cause) { setError(cause instanceof Error ? cause.message : 'Could not load documents') }
    finally { setLoading(false) }
  }

  useEffect(() => { void refresh() }, [])

  useEffect(() => {
    if (!activeIngestion || activeIngestion.status === 'SUCCEEDED' || activeIngestion.status === 'FAILED') return
    let stopped = false
    const poll = async () => {
      try {
        if (activeIngestion.status === 'INDEXING') {
          const currentDocument = (await api.documents()).find(document => document.id === activeIngestion.documentId)
          if (currentDocument?.ingestionJobId !== activeIngestion.jobId) {
            const latestJob = await api.activeIngestion(activeIngestion.documentId)
            if (latestJob) {
              setActiveIngestion(latestJob)
              return
            }
          }
        }
        const status = await api.ingestion(activeIngestion.jobId)
        if (stopped) return
        setActiveIngestion(status)
      setDocuments(current => current.map(document => document.id === status.documentId
          ? { ...document, ingestionJobStatus: status.status === 'INDEXING' ? 'INDEXING' : null, progressChunks: status.progressChunks, totalChunks: status.totalChunks }
          : document))
        if (status.status === 'SUCCEEDED') {
          setNotice(`Indexed version for ${status.documentTitle} (${status.progressChunks} chunks).`)
          setShowUpload(false)
          setRetryDocument(null)
          await refresh()
        } else if (status.status === 'FAILED') {
          setError(`Indexing failed after ${status.progressChunks} chunks: ${status.errorMessage || 'unknown error'}`)
          await refresh()
        }
      } catch (cause) {
        if (!stopped) setError(cause instanceof Error ? cause.message : 'Unable to refresh ingestion status')
      }
    }
    void poll()
    const timer = window.setInterval(() => void poll(), 2000)
    return () => { stopped = true; window.clearInterval(timer) }
  }, [activeIngestion?.jobId, activeIngestion?.status])

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
      setNotice(document.currentVersion === null
        ? `${document.title} restored for retry. It remains unavailable to retrieval until indexing succeeds.`
        : `${document.title} restored to retrieval.`)
      setSelected(null)
      await refresh()
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'Unable to restore document') }
  }

  function retryFirstUpload(document: DocumentSummary) {
    setRetryDocument(document)
    setShowUpload(true)
    setError('')
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
    const allowedRoles = selectedAllowedRoles(data)
    if (allowedRoles.length === 0) {
      setError('Select at least one role that may access this document.')
      return
    }
    data.delete('allowedRoles')
    data.delete('allowedRole')
    data.append('allowedRoles', allowedRoles.join(','))
    const file = data.get('file')
    if (typeof file === 'string' || !file || typeof file.size !== 'number' || file.size === 0) {
      setError(`Choose a non-empty ${SUPPORTED_FORMATS} file.`)
      return
    }
    setUploading(true)
    setError('')
    try {
      const result = await api.upload(data, logicalDocumentId)
      if (result.queued && result.jobId) {
        setActiveIngestion(await api.ingestion(result.jobId))
        setNotice(`Upload received. Indexing for ${String(data.get('title') || result.documentId)} continues in the background.`)
        await refresh()
        setUploading(false)
        return
      }
      setNotice(result.duplicate
        ? result.metadataUpdated ? 'Content was unchanged; metadata and ACL were updated without re-embedding.' : 'Identical active content already indexed; no metadata or ACL changes were needed.'
        : `Indexed version ${result.version} for ${result.documentId}.`)
      setShowUpload(false)
      setRetryDocument(null)
      form.reset()
      await refresh()
    } catch (cause) { setError(uploadErrorMessage(cause)) }
    finally { setUploading(false) }
  }

  return <div className="knowledge-page">
    <div className="page-heading knowledge-heading"><div><div className="eyebrow dark"><span /> KNOWLEDGE OPERATIONS</div><h1>Documents &amp; <em>versions.</em></h1><p className="muted">A living index of the knowledge available to your workspace.</p></div>
      {identity.documentAdmin && <button className="primary-button" onClick={() => { setRetryDocument(null); setShowUpload(true); setError('') }}><FilePlus2 size={16} /> Add document</button>}
    </div>
    {notice && <div className="success-banner"><Check size={16} /> {notice}<button onClick={() => setNotice('')} aria-label="Dismiss"><X size={15} /></button></div>}
    {error && <div className="error-banner">{error}<button onClick={() => setError('')} aria-label="Dismiss"><X size={15} /></button></div>}
    <div className="knowledge-layout">
      <section className="document-list-card">
        <div className="list-toolbar"><div><strong>Indexed documents</strong><span>{documents.length} visible to your identity</span></div><button className="icon-button" onClick={() => void refresh()} aria-label="Refresh list"><RefreshCw size={16} /></button></div>
        <div className="document-table-head"><span>DOCUMENT</span><span>CLASSIFICATION</span><span>VERSION</span><span>STATUS</span><span /></div>
      {loading ? <div className="loading-state"><LoaderCircle className="spin" /> Loading index...</div> : documents.length === 0 ? <div className="empty-state">No accessible documents are indexed yet.</div> : documents.map(document => <div key={document.id} className={`document-row ${selected?.id === document.id ? 'selected' : ''}`}>
          <button className="document-row-main" onClick={() => void selectDocument(document)}><span className="file-icon"><FileText size={17} /></span><span className="document-name"><strong>{document.title}</strong><small>{document.id}</small>{identity.documentAdmin && <small className="document-acl">ALLOWED ROLES · {document.allowedRoles?.length ? document.allowedRoles.join(', ') : '—'}</small>}</span><span className={`classification ${document.classification.toLowerCase()}`}>{document.classification}</span><span className="version-pill">{document.currentVersion ? `v${document.currentVersion}` : '—'}</span><span className={`status-pill ${document.currentVersion === null && document.status === 'ACTIVE' && document.indexStatus === 'FAILED' ? 'ready' : document.status.toLowerCase()}`}>{document.ingestionJobStatus === 'INDEXING' ? `INDEXING · ${document.progressChunks ?? 0} chunks` : documentStatusLabel(document)}</span><ChevronDown className="row-chevron" size={15} /></button>
          {selected?.id === document.id && <div className="version-expansion"><div className="version-heading">VERSION HISTORY <span>{versions.length} versions</span></div>{versions.map(version => <div key={version.id} className="version-row"><span className="version-tag">v{version.version}</span><span className="version-source">{version.sourceFilename}<small>{version.embeddingModelId} · {version.embeddingDimensions}d</small></span><span className={version.active ? 'active-version' : 'inactive-version'}>{version.active ? 'ACTIVE' : version.indexStatus}</span><small>{new Date(version.createdAt).toLocaleDateString()}</small></div>)}</div>}
        </div>)}
      </section>
      <aside className="knowledge-aside"><div className="aside-card principle-card"><div className="aside-icon"><Shield size={18} /></div><span className="eyebrow dark">INDEX PRINCIPLE</span><h3>Only active evidence travels.</h3><p>Versions are prepared away from retrieval. A complete index switches active atomically; failed work leaves the last healthy version in place.</p><div className="principle-step"><i>01</i> Stage &amp; validate</div><div className="principle-step"><i>02</i> Atomic activation</div><div className="principle-step"><i>03</i> ACL before search</div></div>
          {identity.documentAdmin && <div className="aside-card admin-card"><div className="admin-kicker"><span /> ADMIN ACTIONS</div><strong>Carol’s management scope</strong><p>Management permission is separate from access to classified evidence.</p>{selected && canRetryFirstUpload(selected) && <button className="secondary-button" onClick={() => retryFirstUpload(selected)}>Retry indexing</button>}{selected && selected.status === 'ACTIVE' && <button className="danger-button" onClick={() => void disableDocument(selected)}><Archive size={15} /> Disable selected document</button>}{selected && selected.status !== 'DELETED' && <button className="danger-button" onClick={() => void deleteDocument(selected)}><Trash2 size={15} /> Soft-delete selected document</button>}{selected && selected.status !== 'ACTIVE' && (selected.currentVersion !== null || selected.indexStatus === 'FAILED') && <button className="secondary-button" onClick={() => void restoreDocument(selected)}>{selected.currentVersion === null ? 'Restore failed document to retry indexing' : 'Restore selected document'}</button>}</div>}
        <div className="aside-card scope-card"><span className="eyebrow dark">YOUR RETRIEVAL SCOPE</span><h3>{identity.roles.join(' · ')}</h3><p>These roles filter retrieval. Management permission does not expand your chat evidence scope.</p></div>
      </aside>
    </div>
     {showUpload && <div className="modal-backdrop" role="presentation" onMouseDown={event => { if (event.target === event.currentTarget) { setShowUpload(false); setRetryDocument(null) } }}><section className="upload-modal" role="dialog" aria-modal="true" aria-labelledby="upload-title"><div className="modal-heading"><div><span className="eyebrow dark">INDEX A SOURCE</span><h2 id="upload-title">{retryDocument ? 'Retry document indexing' : 'Add or update knowledge'}</h2></div><button className="icon-button" onClick={() => { setShowUpload(false); setRetryDocument(null) }} aria-label="Close"><X size={18} /></button></div>
        {activeIngestion?.status === 'INDEXING' && <div className="ingestion-progress" role="status"><LoaderCircle size={16} className="spin" /><div><strong>Indexing {activeIngestion.documentTitle}</strong><p>{ingestionProgressLabel(activeIngestion.progressChunks)}</p></div></div>}
        {retryDocument && <p className="field-note">Select the source file again. The existing document ID and allowed roles are preserved. It remains unavailable to retrieval until indexing succeeds.</p>}
        <form onSubmit={upload} className="upload-form"><label>Logical document ID <input name="documentId" defaultValue={retryDocument?.id ?? ''} readOnly={Boolean(retryDocument)} placeholder="doc-engineering-api-guide" pattern="[A-Za-z0-9][A-Za-z0-9._-]{0,159}" required /></label><p className="field-note">Use a new stable ID for a new document; reuse its existing ID to create a new version. Identical content updates metadata/ACL without re-embedding.</p><label>Document title <input name="title" defaultValue={retryDocument?.title ?? ''} required maxLength={180} /></label><div className="form-two"><label>Department <select name="department" defaultValue={retryDocument?.department ?? 'PUBLIC'}><option>PUBLIC</option><option>ENGINEERING</option><option>HR</option><option>SECURITY</option></select></label><label>Classification <select name="classification" defaultValue={retryDocument?.classification ?? 'PUBLIC'}><option>PUBLIC</option><option>INTERNAL</option><option>CONFIDENTIAL</option><option>RESTRICTED</option></select></label></div><fieldset className="role-selector"><legend>Allowed roles</legend><p className="field-note">Select who may retrieve this document. Classification labels do not grant access.</p><div>{DOCUMENT_ROLES.map(role => <label key={role}><input type="checkbox" name="allowedRole" value={role} defaultChecked={retryDocument?.allowedRoles?.includes(role) ?? false} /> {role}</label>)}</div></fieldset><label className="file-drop"><UploadCloud size={22} /><strong>Choose {SUPPORTED_FORMATS}</strong><small>Maximum {MAX_UPLOAD_MIB} MiB · Scanned PDFs are unsupported</small><input name="file" type="file" accept={FILE_TYPES} required /></label><div className="modal-actions"><button type="button" className="secondary-button" onClick={() => { setShowUpload(false); setRetryDocument(null) }}>Cancel</button><button type="submit" className="primary-button" disabled={uploading || activeIngestion?.status === 'INDEXING'}>{uploading ? <LoaderCircle size={15} className="spin" /> : <UploadCloud size={15} />} {uploading ? 'Submitting...' : retryDocument ? 'Retry indexing' : 'Upload & index'}</button></div></form>
    </section></div>}
  </div>
}

export function documentStatusLabel(document: DocumentSummary) {
  if (document.currentVersion === null && document.status === 'ACTIVE' && document.indexStatus === 'FAILED') {
    return 'READY TO RETRY'
  }
  return document.indexStatus || document.status
}

export function canRetryFirstUpload(document: DocumentSummary) {
  return document.status === 'ACTIVE' && document.currentVersion === null && document.indexStatus === 'FAILED'
}

export function ingestionProgressLabel(chunks: number) {
  return `${chunks} chunks completed. You can close this dialog; the backend continues processing.`
}

export function selectedAllowedRoles(data: FormData) {
  return [
    ...new Set(
      [...data.getAll('allowedRole'), ...data.getAll('allowedRoles')]
        .flatMap(role => String(role).split(','))
        .map(role => role.trim().toUpperCase())
        .filter(Boolean),
    ),
  ]
}

export function uploadErrorMessage(cause: unknown) {
  if (!(cause instanceof Error)) return 'Upload failed. Check the file and document details, then retry.'
  const code = 'code' in cause ? cause.code : undefined
  if (code === 'INGESTION_INVALID_INPUT') return `Upload rejected: ${cause.message}`
  if (code === 'DOCUMENT_INACTIVE') return 'This document is inactive. Restore it from the document actions before retrying; inactive documents remain excluded from retrieval.'
  if (code === 'INGESTION_CONFLICT') return 'This document version conflicts with an existing indexing attempt. Refresh the document list and retry.'
  if (code === 'INGESTION_IN_PROGRESS') return 'A version of this document is currently indexing. Wait for it to finish, refresh the document list, and retry if needed.'
  if (code === 'INGESTION_UNPROCESSABLE' && cause.message === 'PDF contains no extractable text; scanned PDFs are unsupported') {
    return 'This PDF contains no extractable text. Scanned PDFs are not supported; upload a text-based PDF instead.'
  }
  if (code === 'INGESTION_UNPROCESSABLE') return `Document could not be indexed: ${cause.message}`
  if (code === 'INGESTION_PROVIDER_UNAVAILABLE') return 'The embedding service is temporarily unavailable. Your active document version was not replaced; retry later.'
  return cause.message || 'Upload failed. Check the file and document details, then retry.'
}
