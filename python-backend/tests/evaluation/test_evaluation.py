"""Deterministic unit coverage for frozen offline evaluation semantics."""

from app.evaluation import (
    _ablation_metrics,
    _expected_gate,
    _gate_diagnostics,
    _recall_at_five,
    _reciprocal_rank,
    calculate_metrics,
    evaluate_scenario,
    load_datasets,
    run_evaluation,
)
from app.retrieval import HybridRetriever


def test_development_and_heldout_datasets_are_distinct_and_java_shaped() -> None:
    """Both JSONL datasets preserve Java categories while using disjoint scenario IDs."""

    dev, heldout = load_datasets()

    assert len(dev) == 63
    assert len(heldout) == 14
    assert {scenario["category"] for scenario in heldout} == {
        "FACT_LOOKUP",
        "MULTI_DOCUMENT_QA",
        "KEYWORD_EXACT_MATCH",
        "PERMISSION_ISOLATION",
        "UNANSWERABLE",
        "DOCUMENT_FRESHNESS",
        "HARD_NEGATIVE",
    }
    assert not {scenario["id"] for scenario in dev} & {scenario["id"] for scenario in heldout}
    assert all("shouldPassEvidenceGate" in scenario for scenario in dev + heldout)
    dev_only, no_heldout = load_datasets(include_heldout=False)
    assert len(dev_only) == 63
    assert no_heldout == []


def test_empty_metric_cohorts_use_deterministic_zero_and_unavailable_shadow() -> None:
    """Empty evaluation results do not produce NaN or claim a prefix-shadow run."""

    metrics = calculate_metrics([])

    assert metrics["recallAt5"] == 0.0
    assert metrics["meanReciprocalRank"] == 0.0
    assert metrics["freshnessAccuracy"] == 0.0
    assert metrics["evidenceGateFalseAbstentionRate"] == 0.0
    assert metrics["evidenceGateFalseAdmissionRate"] == 0.0
    assert metrics["releaseQualityPassed"] is False
    assert metrics["prefixShadowMetrics"]["available"] is False
    assert metrics["prefixShadowMetrics"]["recallAt5"] is None


def test_expected_document_ranking_metrics_match_java_conventions() -> None:
    """Answerable recall/MRR and abstention labels follow Java evaluator semantics."""

    row = {
        "expectedDocumentIds": ["doc-a", "doc-b"],
        "retrievedDocumentIds": ["doc-x", "doc-a"],
        "recallAt5": 0.5,
        "reciprocalRank": 0.5,
        "expectedVersion": None,
        "freshnessCorrect": True,
        "citationIdValidationCorrect": True,
        "evidenceGateCorrect": True,
        "expectedEvidenceGate": True,
        "expectedRelevantCandidate": True,
        "aclLeakage": False,
        "gateDiagnostics": {
            "ablationDecisions": {
                policy: True
                for policy in (
                    "CURRENT_COVERAGE",
                    "VECTOR_ONLY",
                    "LEXICAL_COVERAGE_ONLY",
                    "COVERAGE_030",
                    "COVERAGE_040",
                )
            }
        },
    }

    metrics = calculate_metrics([row])

    assert metrics["recallAt5"] == 0.5
    assert metrics["meanReciprocalRank"] == 0.5
    assert metrics["evidenceGateAccuracy"] == 1.0
    assert metrics["evidenceGateFalseAbstentionRate"] == 0.0
    assert metrics["evidenceGateFalseAdmissionRate"] == 0.0


def test_scenario_evaluation_uses_trusted_roles_and_records_expected_labels() -> None:
    """The evaluator derives Alice's scope and records gate/ACL/freshness metrics from retrieval."""

    class FakeEmbeddings:
        """Return a fixed vector without contacting Ollama."""

        model = "test-model"

        def embed(self, _texts):
            return [[0.0] * 768]

    class FakeStorage:
        """Expose an authorized expected chunk to deterministic evaluation."""

        def reserve_retrieval(self, _query, roles, _vector, _identifiers, _depth):
            assert roles == frozenset({"PUBLIC", "ENGINEERING"})
            row = {
                "id": "doc-engineering-rate-limit-configuration-v2-c0",
                "version_id": "v2",
                "document_id": "doc-engineering-rate-limit-configuration",
                "title": "Rate limit",
                "version_number": 2,
                "heading_path": None,
                "chunk_text": "Enterprise API rate limit is 1000 requests",
                "score": 0.95,
                "vector": 0.95,
                "keyword": 0.2,
                "exact": False,
                "coverage": 1.0,
            }
            return [row], [row], []

    retriever = HybridRetriever(FakeStorage(), FakeEmbeddings())
    scenario = {
        "id": "fixture",
        "category": "FACT_LOOKUP",
        "user": "alice",
        "query": "What is the Enterprise API rate limit?",
        "expectedDocumentIds": ["doc-engineering-rate-limit-configuration"],
        "mustNotRetrieveDocumentIds": [],
        "expectedVersion": 2,
        "shouldPassEvidenceGate": True,
        "shouldAbstain": False,
    }
    result = evaluate_scenario(scenario, retriever)
    assert result["retrievedDocumentIds"] == ["doc-engineering-rate-limit-configuration"]
    assert result["freshnessCorrect"] is True
    assert result["evidenceGateCorrect"] is True
    assert result["gateDiagnostics"]["candidateCount"] == 1


def test_metric_helpers_cover_negative_cases_and_rank_boundaries() -> None:
    """Evaluation helper policies define abstention, diagnostic, and cutoff behavior explicitly."""

    assert _expected_gate({"shouldAbstain": True, "expectedDocumentIds": ["doc-a"]}) is False
    assert _recall_at_five([], []) == 0.0
    assert _reciprocal_rank([], []) == 1.0
    assert _reciprocal_rank(["doc-a"], ["doc-x", "doc-a"]) == 0.5
    diagnostic = _gate_diagnostics(
        "What is the password value?", [], {"doc-a"}, 0.35, 0.0005, 0.3, "SENSITIVE_REQUEST"
    )
    assert diagnostic["sensitiveRequest"] is True
    assert diagnostic["roleScopeRejected"] is False
    ablation = _ablation_metrics([], "VECTOR_ONLY")
    assert ablation["accuracy"] == 0.0


def test_complete_development_and_heldout_runs_are_separately_reported(monkeypatch) -> None:
    """Both immutable datasets run through trusted identities and report independent metric groups."""

    class FakeEmbeddings:
        """Avoid paid or live embedding dependencies in deterministic evaluation tests."""

        model = "synthetic-test-model"

        def embed(self, _texts):
            return [[0.0] * 768]

    class FakeStorage:
        """Return no candidates to validate deterministic abstention across both datasets."""

        database = None

        def reserve_retrieval(self, _query, _roles, _vector, _identifiers, _depth):
            return [], [], []

    class CaptureStorage(FakeStorage):
        """Capture the persisted result without introducing database state to this unit test."""

        saved = None

        def save_evaluation(self, run):
            self.saved = run

    storage = CaptureStorage()

    class TestRetriever:
        """Keep the normal scenario evaluator API while stubbing real SQL retrieval."""

        def __init__(self, _storage, _embeddings):
            from app.retrieval import HybridRetriever as RealRetriever

            self.inner = RealRetriever(_storage, _embeddings)
            self.embeddings = self.inner.embeddings
            self.storage = self.inner.storage
            self.depth = self.inner.depth
            self.fused_depth = self.inner.fused_depth
            self.rrf_constant = self.inner.rrf_constant
            self.minimum_vector_similarity = self.inner.minimum_vector_similarity
            self.minimum_keyword_score = self.inner.minimum_keyword_score

        def _fuse(self, rankings):
            return self.inner._fuse(rankings)

    from app import evaluation

    monkeypatch.setattr(evaluation, "HybridRetriever", TestRetriever)
    result = run_evaluation("carol", storage=storage, embeddings=FakeEmbeddings())
    assert result["scenarioCount"] == 77
    assert set(result["datasetMetrics"]) == {"development", "heldout"}
    assert result["datasetMetrics"]["development"]["aclLeakageCount"] == 0
    assert storage.saved["runId"] == result["runId"]

    development_only = run_evaluation(
        "carol", storage=storage, embeddings=FakeEmbeddings(), include_heldout=False
    )
    assert development_only["scenarioCount"] == 63
    assert development_only["datasetVersion"] == "2026-10-p1-v5"
    assert development_only["datasetMetrics"]["heldout"] is None
    assert development_only["status"] == "SUCCEEDED"


def test_synthetic_seed_failure_then_provider_recovery_repairs_corpus(tmp_path, monkeypatch, caplog) -> None:
    """A transient first seed failure is marked repairable and a bounded retry activates the same fixture."""

    import logging

    from app.providers import TransientProviderFailure
    from app.seeding import seed_corpus

    seed_root = tmp_path / "synthetic"
    (seed_root / "public").mkdir(parents=True)
    (seed_root / "public" / "support-faq.txt").write_text("Support instructions", encoding="utf-8")
    monkeypatch.setenv("SEED_PATH", str(seed_root))
    from app.settings import get_settings

    get_settings.cache_clear()

    class FakeStorage:
        """Model failure state and activation while counting exact-content retries."""

        document = None
        active = False
        calls = 0

        def is_active_content(self, _document, _digest):
            return self.active

        def ingest(self, data, process, seed=False):
            self.calls += 1
            assert seed
            if self.document is not None and not self.active:
                assert self.document["seedRepairable"]
            try:
                process()
            except TransientProviderFailure:
                self.document = {"seedManaged": True, "seedRepairable": True, "currentVersion": None}
                raise
            self.document["seedRepairable"] = False
            self.document["currentVersion"] = 1
            self.active = True
            return {"documentId": data["document_id"]}

    class RecoveringEmbeddings:
        """Fail once to simulate transient outage, then return a deterministic fixture vector."""

        calls = 0

        def embed(self, _texts):
            self.calls += 1
            if self.calls == 1:
                raise TransientProviderFailure("temporary")
            return [[0.0] * 768]

    import app.seeding as seeding

    monkeypatch.setattr(seeding.time, "sleep", lambda _delay: None)
    storage = FakeStorage()
    embeddings = RecoveringEmbeddings()
    with caplog.at_level(logging.WARNING):
        seed_corpus(storage, embeddings)
    assert storage.calls == 2
    assert storage.active is True
    assert storage.document["currentVersion"] == 1
    assert "attempt 1/2" in caplog.text
    get_settings.cache_clear()
