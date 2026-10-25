import { useEffect, useState } from 'react'
import { Activity, BookOpen, ChevronRight, CircleHelp, LogOut, ShieldCheck, Sparkles } from 'lucide-react'
import { api } from './api'
import type { Identity } from './types'
import { ChatWorkspace } from './components/ChatWorkspace'
import { KnowledgeWorkspace } from './components/KnowledgeWorkspace'
import { EvaluationWorkspace } from './components/EvaluationWorkspace'

type View = 'chat' | 'knowledge' | 'evaluation'

export default function App() {
  const [identity, setIdentity] = useState<Identity | null>(null)
  const [view, setView] = useState<View>('chat')
  const [busy, setBusy] = useState(true)
  const [error, setError] = useState('')

  useEffect(() => {
    api.me().then(setIdentity).catch(() => setIdentity(null)).finally(() => setBusy(false))
  }, [])

  async function signIn(username: string) {
    setError('')
    try {
      setIdentity(await api.login(username))
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Unable to sign in')
    }
  }

  async function signOut() {
    try {
      await api.logout()
    } finally {
      setIdentity(null)
      setView('chat')
    }
  }

  if (busy) return <div className="boot-screen">Opening the knowledge workspace...</div>
  if (!identity) return <Login onSignIn={signIn} error={error} />

  return (
    <div className="app-shell">
      <aside className="rail">
        <div className="brand-mark">A<span>.</span></div>
        <button className={view === 'chat' ? 'rail-button active' : 'rail-button'} aria-label="Chat" onClick={() => setView('chat')}><Sparkles size={19} /></button>
        <button className={view === 'knowledge' ? 'rail-button active' : 'rail-button'} aria-label="Knowledge" onClick={() => setView('knowledge')}><BookOpen size={19} /></button>
        {identity.documentAdmin && <button className={view === 'evaluation' ? 'rail-button active' : 'rail-button'} aria-label="Evaluation" onClick={() => setView('evaluation')}><Activity size={19} /></button>}
        <div className="rail-bottom"><CircleHelp size={18} /><div className="avatar">{identity.username.slice(0, 1).toUpperCase()}</div></div>
      </aside>
      <div className="main-column">
        <header className="topbar">
          <div className="wordmark"><span className="wordmark-icon"><ShieldCheck size={17} /></span> ACME <span className="wordmark-divider">/</span> KNOWLEDGE DESK</div>
          <div className="topbar-right">
            <span className="environment"><i /> DEMO ENVIRONMENT</span>
            <div className="identity-chip"><span className="avatar small">{identity.username.slice(0, 1).toUpperCase()}</span><span>{identity.username}</span><span className="role-label">{identity.documentAdmin ? 'ADMIN' : identity.roles.filter(role => role !== 'PUBLIC')[0]}</span></div>
            <button className="icon-button" onClick={signOut} title="Sign out" aria-label="Sign out"><LogOut size={17} /></button>
          </div>
        </header>
        <main className="workspace">
          {view === 'chat' && <ChatWorkspace />}
          {view === 'knowledge' && <KnowledgeWorkspace identity={identity} />}
          {view === 'evaluation' && identity.documentAdmin && <EvaluationWorkspace />}
        </main>
      </div>
    </div>
  )
}

function Login({ onSignIn, error }: { onSignIn: (user: string) => void; error: string }) {
  const [selected, setSelected] = useState('alice')
  const people = [
    { id: 'alice', role: 'ENGINEERING', scope: 'Public + engineering', initials: 'AL' },
    { id: 'bob', role: 'HUMAN RESOURCES', scope: 'Public + HR', initials: 'BO' },
    { id: 'carol', role: 'SECURITY ADMIN', scope: 'Public + security · Admin', initials: 'CA' },
  ]
  return (
    <div className="login-page">
      <section className="login-story">
        <div className="story-top"><div className="brand-mark">A<span>.</span></div><span>ACME ENTERPRISE · KNOWLEDGE SYSTEMS</span></div>
        <div className="story-copy">
          <div className="eyebrow"><span /> SECURE KNOWLEDGE, IN CONTEXT</div>
          <h1>Answers that know<br />where they came from.</h1>
          <p>One considered interface for finding policy, operating knowledge, and the evidence behind every answer.</p>
          <div className="story-foot"><div className="story-line" /><span>PRIVATE KNOWLEDGE · EXPLICIT ACCESS · TRACEABLE EVIDENCE</span></div>
        </div>
        <div className="orbit orbit-one" /><div className="orbit orbit-two" /><div className="orbit-core">A<span>.</span></div>
      </section>
      <section className="login-panel">
        <div className="login-form-wrap">
          <div className="eyebrow dark"><span /> DEMONSTRATION ACCESS</div>
          <h2>Choose your workspace</h2>
          <p className="muted">Your server-issued identity determines which knowledge can be retrieved.</p>
          <div className="identity-options">
            {people.map(person => <button key={person.id} className={`identity-option ${selected === person.id ? 'selected' : ''}`} onClick={() => setSelected(person.id)}>
              <span className="person-avatar">{person.initials}</span><span className="person-copy"><strong>{person.id[0].toUpperCase() + person.id.slice(1)}</strong><small>{person.scope}</small></span><span className="radio-dot" />
            </button>)}
          </div>
          {error && <div className="error-banner">{error}</div>}
          <button className="primary-button login-submit" onClick={() => onSignIn(selected)}>Enter workspace <ChevronRight size={17} /></button>
          <div className="login-note"><ShieldCheck size={15} /> Roles are assigned by the server, never by this selector.</div>
        </div>
        <footer>PORTFOLIO DEMO <span>·</span> SYNTHETIC COMPANY KNOWLEDGE</footer>
      </section>
    </div>
  )
}
