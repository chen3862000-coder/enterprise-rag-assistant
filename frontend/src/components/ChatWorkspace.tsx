import { FormEvent, useRef, useState } from 'react'
import { ArrowUp, BookMarked, ChevronRight, Clock3, FileText, LoaderCircle, ShieldCheck, Sparkles, X } from 'lucide-react'
import { api } from '../api'
import type { ChatResponse, SourcePreview } from '../types'

type Message = { id: string; role: 'user' | 'assistant'; text: string; response?: ChatResponse }

const prompts = [
  'What is the Enterprise API rate limit, and how should clients handle HTTP 429?',
  'How many days of annual leave do employees receive?',
  'What happened in restricted incident exercise IR-204?',
]

export function ChatWorkspace() {
  const [messages, setMessages] = useState<Message[]>([])
  const [draft, setDraft] = useState('')
  const [sending, setSending] = useState(false)
  const [error, setError] = useState('')
  const [source, setSource] = useState<SourcePreview | null>(null)
  const [trace, setTrace] = useState<Record<string, unknown> | null>(null)
  const [panelBusy, setPanelBusy] = useState(false)
  const endRef = useRef<HTMLDivElement>(null)

  async function submit(event?: FormEvent, prompt = draft) {
    event?.preventDefault()
    const message = prompt.trim()
    if (!message || sending) return
    setDraft('')
    setError('')
    setSending(true)
    setMessages(previous => [...previous, { id: crypto.randomUUID(), role: 'user', text: message }])
    try {
      const response = await api.chat(message)
      setMessages(previous => [...previous, { id: crypto.randomUUID(), role: 'assistant', text: response.answer, response }])
      requestAnimationFrame(() => endRef.current?.scrollIntoView?.({ behavior: 'smooth' }))
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'The request could not be completed')
    } finally {
      setSending(false)
    }
  }

  async function openSource(chunkId: string) {
    setPanelBusy(true)
    setTrace(null)
    try { setSource(await api.source(chunkId)) } catch (cause) { setError(cause instanceof Error ? cause.message : 'Source unavailable') }
    finally { setPanelBusy(false) }
  }

  async function openTrace(traceId: string) {
    setPanelBusy(true)
    setSource(null)
    try { setTrace(await api.trace(traceId)) } catch (cause) { setError(cause instanceof Error ? cause.message : 'Trace unavailable') }
    finally { setPanelBusy(false) }
  }

  const welcome = messages.length === 0
  return (
    <div className="chat-layout">
      <section className="chat-main">
        <div className="page-heading chat-heading">
          <div><div className="eyebrow dark"><span /> ACME KNOWLEDGE ASSISTANT</div><h1>Find the answer.<br /><em>See the evidence.</em></h1></div>
          <div className="privacy-stamp"><ShieldCheck size={16} /><span>ACCESS FILTERED<br /><b>BEFORE GENERATION</b></span></div>
        </div>
        <div className={`conversation ${welcome ? 'welcome' : ''}`}>
          {welcome ? <div className="welcome-content">
            <div className="welcome-mark"><Sparkles size={19} /></div>
            <p className="welcome-label">START WITH A QUESTION</p>
            <h2>What would you like<br />to know today?</h2>
            <p className="welcome-description">Answers are grounded in knowledge your identity can access.</p>
            <div className="prompt-grid">{prompts.map((prompt, index) => <button key={prompt} className="prompt-card" onClick={() => submit(undefined, prompt)}><span>0{index + 1}</span><p>{prompt}</p><ChevronRight size={16} /></button>)}</div>
          </div> : <div className="message-list">{messages.map(message => <article key={message.id} className={`message ${message.role}`}>
            <div className="message-avatar">{message.role === 'assistant' ? <Sparkles size={15} /> : 'YOU'}</div>
            <div className="message-body"><div className="message-meta">{message.role === 'assistant' ? 'ACME KNOWLEDGE' : 'YOUR QUESTION'} <span>{message.role === 'assistant' && message.response?.groundingStatus === 'CITATIONS_PRESENT' ? '· AUTHORIZED CITATIONS · NOT FACT-CHECKED' : message.role === 'assistant' ? '· INSUFFICIENT EVIDENCE' : ''}</span></div><p>{message.text}</p>
              {message.response && <>
                {message.response.citations.length > 0 && <div className="citations">{message.response.citations.map(citation => <button className="citation-chip" key={citation.citationId} onClick={() => openSource(citation.chunkId)}><span>{citation.citationId}</span><FileText size={14} /><span className="citation-title">{citation.documentTitle}</span><small>v{citation.version}</small></button>)}</div>}
                <button className="trace-link" onClick={() => openTrace(message.response!.traceId)}><ActivityIcon /> View retrieval trace <span>{message.response.traceId.slice(0, 8)}</span></button>
              </>}
            </div>
          </article>)}{sending && <div className="thinking"><LoaderCircle size={17} className="spin" /> Searching authorized knowledge...</div>}<div ref={endRef} /></div>}
          {error && <div className="error-banner inline-error">{error}</div>}
        </div>
        <form className="composer" onSubmit={submit}><textarea value={draft} onChange={event => setDraft(event.target.value)} onKeyDown={event => { if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); void submit() } }} placeholder="Ask about policies, systems, or procedures..." rows={2} maxLength={2000} /><div className="composer-footer"><span><ShieldCheck size={13} /> Private retrieval · Enter to send</span><button className="send-button" type="submit" disabled={!draft.trim() || sending} aria-label="Send question"><ArrowUp size={18} /></button></div></form>
        <div className="chat-footnote">Synthetic Acme Enterprise knowledge · Answers may be incomplete; verify critical decisions against source material.</div>
      </section>
      {(source || trace || panelBusy) && <aside className="evidence-panel"><div className="panel-heading"><div><span className="eyebrow dark">{source ? 'SOURCE EVIDENCE' : 'EXECUTION TRACE'}</span><h3>{source ? 'Reference detail' : 'Request path'}</h3></div><button className="icon-button" onClick={() => { setSource(null); setTrace(null) }} aria-label="Close evidence"><X size={18} /></button></div>
        {panelBusy ? <LoaderCircle className="spin" /> : source ? <><div className="source-meta"><span>{source.documentTitle}</span><small>VERSION {source.version}</small></div>{source.section && <p className="source-section"><BookMarked size={14} /> {source.section}</p>}<pre className="source-text">{source.text}</pre><div className="source-id">{source.documentId} <span>·</span> {source.chunkId}</div></> : trace && <TraceDetails trace={trace} />}</aside>}
    </div>
  )
}

function TraceDetails({ trace }: { trace: Record<string, unknown> }) {
  const fields: Array<[string, unknown]> = [
    ['USER', trace.userId], ['STATUS', trace.status], ['MODEL', trace.modelId], ['GROUNDING', trace.groundingStatus],
    ['RETRIEVAL', `${trace.retrievalDurationMs} ms`], ['GENERATION', `${trace.generationDurationMs} ms`],
    ['TOTAL', `${trace.totalDurationMs} ms`], ['VECTOR CANDIDATES', trace.vectorCandidateCount],
    ['KEYWORD CANDIDATES', trace.keywordCandidateCount], ['EXACT MATCHES', trace.exactCandidateCount],
    ['CONTEXT CHUNKS', trace.contextChunkCount], ['CITATIONS', trace.citationCount],
  ]
  return <><div className="trace-route"><span>01</span> Identity & ACL scope <b>APPLIED</b></div><div className="trace-route"><span>02</span> Vector + keyword retrieval <b>FILTERED</b></div><div className="trace-route"><span>03</span> Fusion & context <b>BOUNDED</b></div><div className="trace-route"><span>04</span> Generation & citation check <b>VALIDATED</b></div><dl className="trace-grid">{fields.map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{String(value ?? '—')}</dd></div>)}</dl><p className="trace-safe-note"><Clock3 size={14} /> Query content is not retained in this trace.</p></>
}

function ActivityIcon() { return <Clock3 size={13} /> }
