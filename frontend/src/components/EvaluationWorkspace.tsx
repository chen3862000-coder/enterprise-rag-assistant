import { useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import { Activity, CheckCircle2, CircleAlert, Database, LoaderCircle, Play, RefreshCw, ShieldCheck } from 'lucide-react'
import { api } from '../api'
import type { EvaluationRun, EvaluationSummary } from '../types'

export function EvaluationWorkspace() {
  const [runs, setRuns] = useState<EvaluationSummary[]>([])
  const [selected, setSelected] = useState<EvaluationRun | null>(null)
  const [loading, setLoading] = useState(true)
  const [running, setRunning] = useState(false)
  const [error, setError] = useState('')

  async function refresh() {
    setLoading(true)
    try { setRuns(await api.evaluations()) }
    catch (cause) { setError(cause instanceof Error ? cause.message : 'Could not load evaluation runs') }
    finally { setLoading(false) }
  }

  useEffect(() => { void refresh() }, [])

  async function run() {
    setRunning(true)
    setError('')
    try {
      const result = await api.runEvaluation()
      setSelected(result)
      await refresh()
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'Evaluation run failed') }
    finally { setRunning(false) }
  }

  async function open(runId: string) {
    setError('')
    try { setSelected(await api.evaluation(runId)) }
    catch (cause) { setError(cause instanceof Error ? cause.message : 'Could not load run details') }
  }

  const metrics = selected?.metrics ?? runs[0]?.metrics
  return <div className="evaluation-page">
    <div className="page-heading evaluation-heading"><div><div className="eyebrow dark"><span /> QUALITY &amp; DIAGNOSTICS</div><h1>Measure authorized retrieval.</h1><p className="muted">Deterministic retrieval and evidence-gate diagnostics; not a generated-answer benchmark.</p></div><button className="primary-button" onClick={() => void run()} disabled={running}><Play size={15} /> {running ? 'Running 56 scenarios...' : 'Run offline evaluation'}</button></div>
    {error && <div className="error-banner">{error}</div>}
    <div className="evaluation-method-note"><Database size={16} /><span><strong>OFFLINE RETRIEVAL EVALUATION</strong> Uses local embedding and PostgreSQL only. No paid LLM call. Gate decisions are measured by the shared runtime sufficiency policy. Citation ID validation is not factual entailment or answer faithfulness.</span></div>
    <div className="metrics-grid">
      <Metric label="Recall @ 5" value={metrics ? percent(metrics.recallAt5) : '—'} detail="Answerable scenarios" icon={<Activity size={16} />} />
      <Metric label="Mean reciprocal rank" value={metrics ? metrics.meanReciprocalRank.toFixed(3) : '—'} detail="Rank of expected evidence" icon={<Activity size={16} />} />
      <Metric label="ACL leakage" value={metrics ? String(metrics.aclLeakageCount) : '—'} detail="Forbidden document candidates" icon={<ShieldCheck size={16} />} alert={Boolean(metrics?.aclLeakageCount)} />
      <Metric label="Freshness accuracy" value={metrics ? percent(metrics.freshnessAccuracy) : '—'} detail="Expected active version" icon={<RefreshCw size={16} />} />
      <Metric label="Citation ID validation" value={metrics ? percent(metrics.citationIdValidationAccuracy) : '—'} detail="Supplied reference IDs only; not entailment" icon={<CheckCircle2 size={16} />} />
      <Metric label="Evidence gate" value={metrics ? percent(metrics.evidenceGateAccuracy) : '—'} detail="Shared runtime gate matches fixture expectations" icon={<CircleAlert size={16} />} />
    </div>
    {metrics && Object.keys(metrics.gateAblationMetrics ?? {}).length > 0 && <div className="evaluation-method-note">
      <Activity size={16} /><span><strong>DIAGNOSTIC ABLATIONS</strong> Not applied to chat. {Object.entries(metrics.gateAblationMetrics).map(([policy, score]) => score.falseAbstention !== null && score.falseAdmission !== null
        ? `${policy}: false abstention ${percent(score.falseAbstentionRate ?? 0)}, false admission ${percent(score.falseAdmissionRate ?? 0)} (${score.falseAbstention}/${(score.truePass ?? 0) + (score.falseAbstention ?? 0)}, ${score.falseAdmission}/${(score.falseAdmission ?? 0) + (score.trueAbstention ?? 0)})`
        : `${policy}: accuracy ${percent(score.accuracy)} (historical run; error breakdown unavailable)`).join(' · ')}</span>
    </div>}
    {metrics?.prefixShadowMetrics?.available && <div className="evaluation-method-note">
      <Activity size={16} /><span><strong>NOMIC PREFIX SHADOW</strong> Not used for chat. Paired `search_query:` / `search_document:` cosine retrieval: Recall@5 {percent(metrics.prefixShadowMetrics.recallAt5)}, MRR {metrics.prefixShadowMetrics.meanReciprocalRank.toFixed(3)}, vector-gate accuracy {percent(metrics.prefixShadowMetrics.vectorGateAccuracy)}, false abstentions {metrics.prefixShadowMetrics.falseAbstentionCount}, false admissions {metrics.prefixShadowMetrics.falseAdmissionCount}.</span>
    </div>}
    {selected && <p className="metric-footnote">The active gate must agree with `shouldPassEvidenceGate`. `shouldAbstain` is retained as the customer-facing contract expectation.</p>}
    {selected && <p className="metric-footnote">Gate/ACL/freshness contract checks are shown in scenario pass status. Recall and MRR remain separate retrieval measurements; a low retrieval score can fail retrieval without implying an ACL bypass.</p>}
    <div className="evaluation-columns">
      <section className="runs-card"><div className="section-card-heading"><div><h2>Evaluation runs</h2><p>Persisted measurements from the configured dataset</p></div><button className="icon-button" onClick={() => void refresh()} aria-label="Refresh runs"><RefreshCw size={16} /></button></div>
        {loading ? <div className="loading-state"><LoaderCircle className="spin" /> Loading runs...</div> : runs.length === 0 ? <div className="empty-state">No measured runs yet. Run the offline suite to create the first report.</div> : runs.map(item => <button key={item.runId} className={`run-row ${selected?.runId === item.runId ? 'selected' : ''}`} onClick={() => void open(item.runId)}><span className="run-status"><CheckCircle2 size={17} /></span><span className="run-copy"><strong>{item.datasetVersion}</strong><small>{new Date(item.startedAt).toLocaleString()} · {item.scenarioCount} scenarios · {item.method}</small></span><span className="run-score">R@5 <b>{percent(item.metrics.recallAt5)}</b></span></button>)}
      </section>
      <section className="failures-card">
        <div className="section-card-heading">
          <div>
            <h2>{selected ? 'Scenario diagnostics' : 'How to read results'}</h2>
            <p>{selected ? `${selected.scenarios.length} scenario-level outcomes` : 'Measured quality, not placeholder scores'}</p>
            {selected && <small>Retrieval configuration: {JSON.stringify(selected.retrievalConfiguration)}</small>}
          </div>
        </div>
        {selected ? <div className="scenario-list">
          {selected.scenarios.filter(scenario => !scenario.passed || scenario.missingDocumentIds.length > 0).length === 0
            ? <div className="all-clear"><CheckCircle2 size={22} /><strong>All reported checks passed</strong><span>No contract failures or missing expected retrieval candidates in this run.</span></div>
            : selected.scenarios.filter(scenario => !scenario.passed || scenario.missingDocumentIds.length > 0).map(scenario => <div className="scenario-failure" key={scenario.scenarioId}>
              <div><span className="failure-id">{scenario.scenarioId}</span><span className="failure-category">{scenario.category}</span></div>
              <p>{scenario.aclLeakage ? `ACL leakage: ${scenario.forbiddenDocumentIds.join(', ')}`
                : `${scenario.passed ? 'Retrieval relevance miss' : `Contract check failed: gate=${scenario.gateReason}`}; missing expected candidates=${scenario.missingDocumentIds.join(', ') || 'none'}`}</p>
              <small>Expected chunk signals: cosine {scenario.gateDiagnostics.bestExpectedVectorSimilarity.toFixed(3)}, keyword {scenario.gateDiagnostics.bestExpectedKeywordScore.toFixed(5)}. Passing candidates: vector {scenario.gateDiagnostics.vectorPassingCandidateCount}, keyword {scenario.gateDiagnostics.keywordPassingCandidateCount}, both {scenario.gateDiagnostics.jointPassingCandidateCount}.</small>
              <small>Prefix shadow: best cosine {scenario.gateDiagnostics.prefixShadow.bestExpectedCosine.toFixed(3)}, Recall@5 {percent(scenario.gateDiagnostics.prefixShadow.recallAt5)}, vector gate {scenario.gateDiagnostics.prefixShadow.vectorGatePassed ? 'pass' : 'abstain'}.</small>
            </div>)}
        </div> : <div className="metric-explainer">
          <div><b>01</b><span><strong>Retrieval relevance</strong><small>Recall@5 and MRR use expected document IDs for answerable scenarios.</small></span></div>
          <div><b>02</b><span><strong>Permission isolation</strong><small>Forbidden documents must not appear in authorized candidate lists.</small></span></div>
          <div><b>03</b><span><strong>Freshness</strong><small>Retrieval must resolve the expected active logical-document version.</small></span></div>
          <div><b>04</b><span><strong>Citation IDs are not entailment</strong><small>Offline checks do not verify factual support or answer faithfulness.</small></span></div>
        </div>}
      </section>
    </div>
  </div>
}

function Metric({ label, value, detail, icon, alert = false }: { label: string; value: string; detail: string; icon: ReactNode; alert?: boolean }) {
  return <article className={`metric-card ${alert ? 'alert' : ''}`}><div className="metric-top"><span>{label}</span><span>{icon}</span></div><strong>{value}</strong><small>{detail}</small></article>
}

function percent(value: number) { return `${(value * 100).toFixed(1)}%` }
