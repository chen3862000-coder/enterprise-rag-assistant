"""Deterministic offline retrieval evaluation with PostgreSQL persistence.

Evaluation identities and roles are resolved from the fixed server identity map. Both the
development and separately frozen held-out datasets use the production retriever's SQL ACL
filters and evidence gate; no answer-generation model is called and no thresholds are tuned.
"""

import json
import os
from collections import Counter
from datetime import UTC, datetime
from pathlib import Path
from typing import Any
from uuid import UUID, uuid4

from sqlalchemy import text

from app.generation import validate_citations
from app.providers import OllamaEmbeddingProvider
from app.retrieval import (
    IDENTIFIER,
    POLICY_GUIDANCE,
    SENSITIVE,
    HybridRetriever,
    evidence_decision,
    query_terms,
)
from app.retrieval import (
    exact_matches as eligible_exact_matches,
)
from app.security import IDENTITIES, known_identity
from app.settings import get_settings
from app.storage import Storage

DEV_DATASET_VERSION = "2026-10-p1-v5"
HELDOUT_DATASET_VERSION = "heldout-paraphrases-v1"
DATASET_VERSION = f"{DEV_DATASET_VERSION}+{HELDOUT_DATASET_VERSION}"
CATEGORIES = frozenset(
    {
        "FACT_LOOKUP",
        "MULTI_DOCUMENT_QA",
        "KEYWORD_EXACT_MATCH",
        "PERMISSION_ISOLATION",
        "UNANSWERABLE",
        "DOCUMENT_FRESHNESS",
        "HARD_NEGATIVE",
    }
)
RELEASE_MAX_FALSE_ABSTENTION_RATE = 0.20
RELEASE_MAX_FALSE_ADMISSION_RATE = 0.05
ABLATION_POLICIES = (
    "CURRENT_COVERAGE",
    "VECTOR_ONLY",
    "LEXICAL_COVERAGE_ONLY",
    "COVERAGE_030",
    "COVERAGE_040",
)
_PYTHON_BACKEND = Path(__file__).resolve().parents[1]
_REPOSITORY = _PYTHON_BACKEND.parent
DEV_DATASET_PATH = _REPOSITORY / "evals" / "dataset.jsonl"
HELDOUT_DATASET_PATH = _PYTHON_BACKEND / "evals" / "heldout-paraphrases.jsonl"
if dev_dataset_override := os.environ.get("DEV_EVALUATION_DATASET"):
    DEV_DATASET_PATH = Path(dev_dataset_override)
if heldout_dataset_override := os.environ.get("HELDOUT_EVALUATION_DATASET"):
    HELDOUT_DATASET_PATH = Path(heldout_dataset_override)


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    """Load and validate Java-compatible scenario records from a UTF-8 JSONL file."""

    scenarios: list[dict[str, Any]] = []
    identifiers: set[str] = set()
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if not line.strip():
            continue
        try:
            scenario = json.loads(line)
        except json.JSONDecodeError as error:
            raise ValueError(f"Invalid JSON in {path} line {line_number}") from error
        _validate_scenario(scenario, identifiers, path, line_number)
        scenarios.append(scenario)
    if not scenarios:
        raise ValueError(f"Evaluation dataset is empty: {path}")
    return scenarios


def load_datasets(
    include_heldout: bool = True,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    """Load development cases and optionally the separately frozen held-out paraphrases."""

    dev = load_jsonl(DEV_DATASET_PATH)
    heldout = load_jsonl(HELDOUT_DATASET_PATH) if include_heldout else []
    expected_counts = {
        "FACT_LOOKUP": 12,
        "MULTI_DOCUMENT_QA": 8,
        "KEYWORD_EXACT_MATCH": 8,
        "PERMISSION_ISOLATION": 12,
        "UNANSWERABLE": 8,
        "DOCUMENT_FRESHNESS": 6,
        "HARD_NEGATIVE": 9,
    }
    if Counter(scenario["category"] for scenario in dev) != expected_counts:
        raise ValueError("Development dataset does not match the Java category-count contract")
    all_ids = [scenario["id"] for scenario in dev + heldout]
    if len(all_ids) != len(set(all_ids)):
        raise ValueError("Development and held-out scenario IDs must be distinct")
    return dev, heldout


def evaluate_scenario(scenario: dict[str, Any], retriever: HybridRetriever) -> dict[str, Any]:
    """Measure pre-gate authorized rankings and gate behavior for one scenario."""

    identity = known_identity(scenario["user"])
    query = " ".join(scenario["query"].strip().split())
    if not query or len(query) > 2000:
        raise ValueError(f"Invalid query length for evaluation scenario {scenario['id']}")
    identifiers = list(
        dict.fromkeys(match.group().rstrip(".").lower() for match in IDENTIFIER.finditer(query))
    )
    vector = retriever.embeddings.embed([query])[0]
    vectors, keywords, exact = retriever.storage.reserve_retrieval(
        query, identity.roles, vector, identifiers, retriever.depth
    )
    candidates = retriever._fuse([vectors, keywords, exact])
    expected = scenario["expectedDocumentIds"]
    forbidden = scenario["mustNotRetrieveDocumentIds"]
    expected_set = set(expected)
    ranked_ids = list(dict.fromkeys(row["document_id"] for row in candidates))
    ranked_set = set(ranked_ids)
    retrieved_versions = {row["document_id"]: row["version_number"] for row in reversed(candidates)}
    should_pass = _expected_gate(scenario)
    gate_chunks, gate_reason = evidence_decision(
        query,
        candidates,
        identity.roles,
        retriever.minimum_vector_similarity,
        retriever.minimum_keyword_score,
    )
    gate_passed = bool(gate_chunks)
    relevant = bool(expected_set & ranked_set)
    min_coverage = _minimum_coverage(query)
    diagnostic = _gate_diagnostics(
        query,
        candidates,
        expected_set,
        retriever.minimum_vector_similarity,
        retriever.minimum_keyword_score,
        min_coverage,
        gate_reason,
    )
    supplied = [{"citationId": f"S{index + 1}"} for index in range(min(5, len(gate_chunks)))]
    sanitized, _valid_citations, _invalid_citation = validate_citations(
        " ".join(f"[{entry['citationId']}]" for entry in supplied) + " [S999]", supplied
    )
    citation_valid = "S999" not in sanitized
    missing = [document_id for document_id in expected if document_id not in ranked_set]
    forbidden_found = [document_id for document_id in forbidden if document_id in ranked_set]
    recall = _recall_at_five(expected, [row["document_id"] for row in candidates[:5]])
    reciprocal_rank = _reciprocal_rank(expected, ranked_ids)
    expected_version = scenario.get("expectedVersion")
    freshness_correct = expected_version is None or all(
        retrieved_versions.get(document_id, 0) >= expected_version for document_id in expected
    )
    return {
        "scenarioId": scenario["id"],
        "category": scenario["category"],
        "expectedDocumentIds": expected,
        "retrievedDocumentIds": ranked_ids,
        "missingDocumentIds": missing,
        "forbiddenDocumentIds": forbidden_found,
        "expectedVersion": expected_version,
        "retrievedVersions": retrieved_versions,
        "reciprocalRank": reciprocal_rank,
        "recallAt5": recall,
        "expectedEvidenceGate": should_pass,
        "expectedRelevantCandidate": relevant,
        "aclLeakage": bool(forbidden_found),
        "freshnessCorrect": freshness_correct,
        "citationIdValidationCorrect": citation_valid,
        "evidenceGateCorrect": gate_passed == should_pass,
        "gateReason": gate_reason,
        "gateDiagnostics": diagnostic,
        "passed": not forbidden_found and freshness_correct and citation_valid and gate_passed == should_pass,
    }


def calculate_metrics(results: list[dict[str, Any]]) -> dict[str, Any]:
    """Aggregate Java-compatible retrieval, ACL, freshness, citation, and gate metrics."""

    answerable = [result for result in results if result["expectedDocumentIds"]]
    freshness = [result for result in results if result["expectedVersion"] is not None]
    positives = [
        result for result in results if result["expectedEvidenceGate"] and result["expectedRelevantCandidate"]
    ]
    negatives = [
        result
        for result in results
        if not (result["expectedEvidenceGate"] and result["expectedRelevantCandidate"])
    ]
    false_abstentions = sum(not _gate_predicted_pass(result) for result in positives)
    false_admissions = sum(_gate_predicted_pass(result) for result in negatives)
    false_abstention_rate = _rate(false_abstentions, len(positives))
    false_admission_rate = _rate(false_admissions, len(negatives))
    ablations = {policy: _ablation_metrics(results, policy) for policy in ABLATION_POLICIES}
    return {
        "recallAt5": _average([result["recallAt5"] for result in answerable]),
        "meanReciprocalRank": _average([result["reciprocalRank"] for result in answerable]),
        "aclLeakageCount": sum(result["aclLeakage"] for result in results),
        "freshnessAccuracy": _accuracy([result["freshnessCorrect"] for result in freshness]),
        "citationIdValidationAccuracy": _accuracy(
            [result["citationIdValidationCorrect"] for result in results]
        ),
        "evidenceGateAccuracy": _accuracy([result["evidenceGateCorrect"] for result in results]),
        "evidenceGateFalseAbstentionRate": false_abstention_rate,
        "evidenceGateFalseAdmissionRate": false_admission_rate,
        "releaseQualityPassed": (
            len(positives) >= 10
            and len(negatives) >= 10
            and false_abstention_rate <= RELEASE_MAX_FALSE_ABSTENTION_RATE
            and false_admission_rate <= RELEASE_MAX_FALSE_ADMISSION_RATE
            and not any(result["aclLeakage"] for result in results)
        ),
        "gateAblationMetrics": ablations,
        "prefixShadowMetrics": {
            "available": False,
            "recallAt5": None,
            "meanReciprocalRank": None,
            "vectorGateAccuracy": None,
            "falseAbstentionCount": 0,
            "falseAdmissionCount": 0,
        },
    }


def run_evaluation(
    requested_by: str,
    storage: Storage | None = None,
    embeddings: OllamaEmbeddingProvider | None = None,
    include_heldout: bool = True,
) -> dict[str, Any]:
    """Run development only or both immutable datasets and persist the offline evaluation."""

    identity = known_identity(requested_by)
    if not identity.document_admin:
        raise PermissionError("Only the server-defined evaluation administrator may run evaluations")
    dev, heldout = load_datasets(include_heldout)
    configured_storage = storage or Storage()
    embedding_provider = embeddings or OllamaEmbeddingProvider()
    retriever = HybridRetriever(configured_storage, embedding_provider)
    started = datetime.now(UTC)
    dev_results = [evaluate_scenario(scenario, retriever) for scenario in dev]
    heldout_results = [evaluate_scenario(scenario, retriever) for scenario in heldout]
    results = dev_results + heldout_results
    completed = datetime.now(UTC)
    metrics = calculate_metrics(results)
    dataset_metrics = {
        "development": calculate_metrics(dev_results),
        "heldout": calculate_metrics(heldout_results) if heldout_results else None,
    }
    configuration = _retrieval_configuration(retriever)
    configuration["datasetMetrics"] = dataset_metrics
    run = {
        "runId": uuid4(),
        "requestedBy": identity.username,
        "datasetVersion": DATASET_VERSION if include_heldout else DEV_DATASET_VERSION,
        "method": "OFFLINE_RETRIEVAL",
        "embeddingModelId": embedding_provider.model,
        "retrievalConfiguration": configuration,
        "scenarioCount": len(results),
        "metrics": metrics,
        "datasetMetrics": dataset_metrics,
        "status": "SUCCEEDED",
        "startedAt": started,
        "completedAt": completed,
        "scenarios": results,
    }
    configured_storage.save_evaluation(run)
    return run


def save_run(storage: Storage, run: dict[str, Any]) -> None:
    """Atomically persist one completed run and all diagnostics in the existing Flyway tables."""

    metrics = run["metrics"]
    with storage.database.begin() as connection:
        connection.execute(
            text("""INSERT INTO evaluation_runs (
                id,requested_by,dataset_version,evaluation_method,embedding_model_id,retrieval_configuration,
                scenario_count,recall_at_5,mean_reciprocal_rank,acl_leakage_count,freshness_accuracy,
                citation_id_validation_accuracy,evidence_gate_accuracy,gate_ablation_accuracy,prefix_shadow_metrics,
                evidence_gate_false_abstention_rate,evidence_gate_false_admission_rate,release_quality_passed,
                status,started_at,completed_at)
                VALUES (:id,:requested_by,:dataset_version,:method,:embedding,CAST(:configuration AS jsonb),
                :count,:recall,:mrr,
                :leakage,:freshness,:citation,:gate,CAST(:ablations AS jsonb),CAST(:prefix AS jsonb),
                :false_abstention,:false_admission,
                :release,:status,:started,:completed)"""),
            {
                "id": run["runId"],
                "requested_by": run["requestedBy"],
                "dataset_version": run["datasetVersion"],
                "method": run["method"],
                "embedding": run["embeddingModelId"],
                "configuration": _json(run["retrievalConfiguration"]),
                "count": run["scenarioCount"],
                "recall": metrics["recallAt5"],
                "mrr": metrics["meanReciprocalRank"],
                "leakage": metrics["aclLeakageCount"],
                "freshness": metrics["freshnessAccuracy"],
                "citation": metrics["citationIdValidationAccuracy"],
                "gate": metrics["evidenceGateAccuracy"],
                "ablations": _json(metrics["gateAblationMetrics"]),
                "prefix": _json(metrics["prefixShadowMetrics"]),
                "false_abstention": metrics["evidenceGateFalseAbstentionRate"],
                "false_admission": metrics["evidenceGateFalseAdmissionRate"],
                "release": metrics["releaseQualityPassed"],
                "status": run["status"],
                "started": run["startedAt"],
                "completed": run["completedAt"],
            },
        )
        for result in run["scenarios"]:
            connection.execute(
                text("""INSERT INTO evaluation_scenario_results (
                    run_id,scenario_id,category,expected_document_ids,retrieved_document_ids,missing_document_ids,
                    forbidden_document_ids,expected_version,retrieved_versions,reciprocal_rank,recall_at_5,
                    acl_leakage,freshness_correct,citation_id_validation_correct,evidence_gate_correct,
                    expected_evidence_gate,expected_relevant_candidate,gate_reason,gate_diagnostics_detail,passed)
                    VALUES (:run_id,:scenario_id,:category,CAST(:expected AS jsonb),CAST(:retrieved AS jsonb),
                    CAST(:missing AS jsonb),CAST(:forbidden AS jsonb),:version,CAST(:versions AS jsonb),
                    :mrr,:recall,:leakage,:freshness,:citation,:gate,:expected_gate,:relevant,
                    :reason,CAST(:diagnostics AS jsonb),:passed)"""),
                {
                    "run_id": run["runId"],
                    "scenario_id": result["scenarioId"],
                    "category": result["category"],
                    "expected": _json(result["expectedDocumentIds"]),
                    "retrieved": _json(result["retrievedDocumentIds"]),
                    "missing": _json(result["missingDocumentIds"]),
                    "forbidden": _json(result["forbiddenDocumentIds"]),
                    "version": result["expectedVersion"],
                    "versions": _json(result["retrievedVersions"]),
                    "mrr": result["reciprocalRank"],
                    "recall": result["recallAt5"],
                    "leakage": result["aclLeakage"],
                    "freshness": result["freshnessCorrect"],
                    "citation": result["citationIdValidationCorrect"],
                    "gate": result["evidenceGateCorrect"],
                    "expected_gate": result["expectedEvidenceGate"],
                    "relevant": result["expectedRelevantCandidate"],
                    "reason": result["gateReason"],
                    "diagnostics": _json(result["gateDiagnostics"]),
                    "passed": result["passed"],
                },
            )


def get_run(storage: Storage, run_id: UUID, requested_by: str) -> dict[str, Any] | None:
    """Read a persisted run for the fixed demo administrator and its scenario diagnostics."""

    identity = known_identity(requested_by)
    if not identity.document_admin:
        raise PermissionError("Only the server-defined evaluation administrator may read evaluations")
    with storage.database.connect() as connection:
        run = (
            connection.execute(
                text("SELECT * FROM evaluation_runs WHERE id=:id AND requested_by=:requested_by"),
                {"id": run_id, "requested_by": identity.username},
            )
            .mappings()
            .first()
        )
        if run is None:
            return None
        scenarios = (
            connection.execute(
                text("SELECT * FROM evaluation_scenario_results WHERE run_id=:id ORDER BY scenario_id"),
                {"id": run_id},
            )
            .mappings()
            .all()
        )
    return _run_from_rows(run, scenarios)


def recent_runs(storage: Storage, requested_by: str, limit: int = 20) -> list[dict[str, Any]]:
    """Return bounded recent evaluation summaries from PostgreSQL."""

    identity = known_identity(requested_by)
    if not identity.document_admin:
        raise PermissionError("Only the server-defined evaluation administrator may read evaluations")
    if not 1 <= limit <= 100:
        raise ValueError("Evaluation result limit must be between 1 and 100")
    with storage.database.connect() as connection:
        rows = (
            connection.execute(
                text(
                    "SELECT * FROM evaluation_runs WHERE requested_by=:requested_by "
                    "ORDER BY started_at DESC LIMIT :limit"
                ),
                {"requested_by": identity.username, "limit": limit},
            )
            .mappings()
            .all()
        )
    return [_summary_from_row(row) for row in rows]


def _validate_scenario(scenario: Any, identifiers: set[str], path: Path, line_number: int) -> None:
    """Reject malformed fields before any scenario can influence retrieval or metrics."""

    required = ("id", "category", "user", "query", "expectedDocumentIds", "mustNotRetrieveDocumentIds")
    if not isinstance(scenario, dict) or any(key not in scenario for key in required):
        raise ValueError(f"Missing scenario fields in {path} line {line_number}")
    if (
        not isinstance(scenario["id"], str)
        or not scenario["id"]
        or scenario["id"] in identifiers
        or scenario["category"] not in CATEGORIES
        or not isinstance(scenario["query"], str)
        or not scenario["query"].strip()
        or not isinstance(scenario["expectedDocumentIds"], list)
        or not isinstance(scenario["mustNotRetrieveDocumentIds"], list)
        or any(not isinstance(value, str) or not value for value in scenario["expectedDocumentIds"])
        or any(not isinstance(value, str) or not value for value in scenario["mustNotRetrieveDocumentIds"])
        or not isinstance(scenario.get("shouldPassEvidenceGate"), bool)
        or not isinstance(scenario.get("shouldAbstain", False), bool)
        or scenario["shouldPassEvidenceGate"] == scenario.get("shouldAbstain", False)
    ):
        raise ValueError(f"Invalid scenario fields in {path} line {line_number}")
    if scenario.get("shouldNotAbstain") not in (None, True, False):
        raise ValueError(f"Invalid abstention label in {path} line {line_number}")
    if scenario.get("expectedVersion") is not None and (
        not isinstance(scenario["expectedVersion"], int) or isinstance(scenario["expectedVersion"], bool)
    ):
        raise ValueError(f"Invalid expected version in {path} line {line_number}")
    if scenario["user"] not in IDENTITIES:
        raise ValueError(f"Unknown evaluation identity in {path} line {line_number}")
    identifiers.add(scenario["id"])


def _expected_gate(scenario: dict[str, Any]) -> bool:
    """Apply the Java scenario's evidence-gate label precedence."""

    if "shouldPassEvidenceGate" in scenario:
        return bool(scenario["shouldPassEvidenceGate"])
    if scenario.get("shouldAbstain") is True:
        return False
    return scenario.get("shouldNotAbstain") is True or bool(scenario["expectedDocumentIds"])


def _gate_diagnostics(
    query: str,
    candidates: list[dict[str, Any]],
    expected: set[str],
    vector_minimum: float,
    keyword_minimum: float,
    coverage_minimum: float,
    gate_reason: str,
) -> dict[str, Any]:
    """Capture comparable per-scenario candidate signals and diagnostic-only ablations."""

    expected_candidates = [row for row in candidates if row["document_id"] in expected]
    exact_allowed = bool(eligible_exact_matches(query, candidates))
    policy_rejection = gate_reason not in {
        "EXACT_IDENTIFIER_MATCH",
        "LEXICAL_AND_RELEVANCE_MATCH",
        "NO_AUTHORIZED_CANDIDATES",
        "NO_KEYWORD_MATCH",
        "BELOW_VECTOR_THRESHOLD",
        "SPLIT_SIGNALS_NO_SINGLE_CANDIDATE",
        "NO_STRONG_EVIDENCE",
    }
    ablations = {
        "CURRENT_COVERAGE": not policy_rejection
        and (
            exact_allowed
            or any(_joint(row, vector_minimum, keyword_minimum, coverage_minimum) for row in candidates)
        ),
        "VECTOR_ONLY": exact_allowed or any(row["vector"] >= vector_minimum for row in candidates),
        "LEXICAL_COVERAGE_ONLY": exact_allowed
        or any(
            row["keyword"] >= keyword_minimum and row["coverage"] >= coverage_minimum for row in candidates
        ),
        "COVERAGE_030": not policy_rejection
        and (exact_allowed or any(_joint(row, vector_minimum, keyword_minimum, 0.30) for row in candidates)),
        "COVERAGE_040": not policy_rejection
        and (exact_allowed or any(_joint(row, vector_minimum, keyword_minimum, 0.40) for row in candidates)),
    }
    return {
        "candidateCount": len(candidates),
        "bestVectorSimilarity": _maximum(candidates, "vector"),
        "bestKeywordScore": _maximum(candidates, "keyword"),
        "bestExpectedVectorSimilarity": _maximum(expected_candidates, "vector"),
        "bestExpectedKeywordScore": _maximum(expected_candidates, "keyword"),
        "expectedExactMatch": any(row["exact"] for row in expected_candidates),
        "vectorPassingCandidateCount": sum(row["vector"] >= vector_minimum for row in candidates),
        "keywordPassingCandidateCount": sum(row["keyword"] >= keyword_minimum for row in candidates),
        "jointPassingCandidateCount": sum(
            _joint(row, vector_minimum, keyword_minimum, coverage_minimum) for row in candidates
        ),
        "ablationDecisions": ablations,
        "minimumLexicalCoverage": coverage_minimum,
        "bestLexicalCoverage": _maximum(candidates, "coverage"),
        "bestExpectedLexicalCoverage": _maximum(expected_candidates, "coverage"),
        "sensitiveRequest": bool(SENSITIVE.search(query) and not POLICY_GUIDANCE.search(query)),
        "intentPolicyRejection": policy_rejection and not gate_reason.startswith("REQUIRED_"),
        "roleScopeRejected": gate_reason.startswith("REQUIRED_"),
        "prefixShadow": {
            "retrievedDocumentIds": [],
            "recallAt5": None,
            "reciprocalRank": None,
            "vectorGatePassed": False,
            "bestExpectedCosine": None,
        },
    }


def _ablation_metrics(results: list[dict[str, Any]], policy: str) -> dict[str, Any]:
    """Calculate an ablation confusion matrix against relevant-retrieved gate labels."""

    counts: Counter[str] = Counter()
    correct = 0
    for result in results:
        expected = result["expectedEvidenceGate"] and result["expectedRelevantCandidate"]
        predicted = result["gateDiagnostics"]["ablationDecisions"].get(policy, False)
        correct += expected == predicted
        counts[
            "truePass"
            if expected and predicted
            else "falseAbstention"
            if expected
            else "falseAdmission"
            if predicted
            else "trueAbstention"
        ] += 1
    positive = counts["truePass"] + counts["falseAbstention"]
    negative = counts["falseAdmission"] + counts["trueAbstention"]
    return {
        **{key: counts[key] for key in ("truePass", "falseAbstention", "falseAdmission", "trueAbstention")},
        "accuracy": _rate(correct, len(results)),
        "falseAbstentionRate": _rate(counts["falseAbstention"], positive),
        "falseAdmissionRate": _rate(counts["falseAdmission"], negative),
    }


def _run_from_rows(run: Any, scenario_rows: Any) -> dict[str, Any]:
    """Deserialize PostgreSQL JSONB and timestamps into stable camelCase result fields."""

    config = _json_value(run["retrieval_configuration"])
    metrics = {
        "recallAt5": run["recall_at_5"],
        "meanReciprocalRank": run["mean_reciprocal_rank"],
        "aclLeakageCount": run["acl_leakage_count"],
        "freshnessAccuracy": run["freshness_accuracy"],
        "citationIdValidationAccuracy": run["citation_id_validation_accuracy"],
        "evidenceGateAccuracy": run["evidence_gate_accuracy"],
        "evidenceGateFalseAbstentionRate": run["evidence_gate_false_abstention_rate"],
        "evidenceGateFalseAdmissionRate": run["evidence_gate_false_admission_rate"],
        "releaseQualityPassed": run["release_quality_passed"],
        "gateAblationMetrics": _json_value(run["gate_ablation_accuracy"]),
        "prefixShadowMetrics": _json_value(run["prefix_shadow_metrics"]),
    }
    scenarios = []
    for row in scenario_rows:
        result = {
            key: _json_value(row[column])
            for key, column in (
                ("expectedDocumentIds", "expected_document_ids"),
                ("retrievedDocumentIds", "retrieved_document_ids"),
                ("missingDocumentIds", "missing_document_ids"),
                ("forbiddenDocumentIds", "forbidden_document_ids"),
                ("retrievedVersions", "retrieved_versions"),
                ("gateDiagnostics", "gate_diagnostics_detail"),
            )
        }
        result.update(
            {
                "scenarioId": row["scenario_id"],
                "category": row["category"],
                "expectedVersion": row["expected_version"],
                "reciprocalRank": row["reciprocal_rank"],
                "recallAt5": row["recall_at_5"],
                "aclLeakage": row["acl_leakage"],
                "freshnessCorrect": row["freshness_correct"],
                "citationIdValidationCorrect": row["citation_id_validation_correct"],
                "evidenceGateCorrect": row["evidence_gate_correct"],
                "expectedEvidenceGate": row["expected_evidence_gate"],
                "expectedRelevantCandidate": row["expected_relevant_candidate"],
                "gateReason": row["gate_reason"],
                "passed": row["passed"],
            }
        )
        scenarios.append(result)
    return {
        "runId": run["id"],
        "datasetVersion": run["dataset_version"],
        "method": run["evaluation_method"],
        "embeddingModelId": run["embedding_model_id"],
        "retrievalConfiguration": config,
        "scenarioCount": run["scenario_count"],
        "metrics": metrics,
        "status": run["status"],
        "startedAt": run["started_at"],
        "completedAt": run["completed_at"],
        "scenarios": scenarios,
    }


def _summary_from_row(row: Any) -> dict[str, Any]:
    """Project a persisted database row into the run-list response shape."""

    result = _run_from_rows(row, [])
    result.pop("scenarios", None)
    result["requestedBy"] = row["requested_by"]
    return result


def _retrieval_configuration(retriever: HybridRetriever) -> dict[str, Any]:
    """Record effective retrieval/gate settings without changing them for evaluation."""

    settings = get_settings()
    return {
        "candidateDepth": retriever.depth,
        "fusedDepth": retriever.fused_depth,
        "rrfConstant": retriever.rrf_constant,
        "minimumVectorSimilarity": retriever.minimum_vector_similarity,
        "minimumKeywordScore": retriever.minimum_keyword_score,
        "keywordQueryMode": "OR over PostgreSQL English stemmed terms",
        "gatePolicy": "exact authorized identifier or joint vector, keyword, and adaptive lexical coverage",
        "releaseMaximumFalseAbstentionRate": RELEASE_MAX_FALSE_ABSTENTION_RATE,
        "releaseMaximumFalseAdmissionRate": RELEASE_MAX_FALSE_ADMISSION_RATE,
        "prefixShadow": "unavailable in Python storage/retrieval contract",
        "embeddingDimensions": settings.embedding_dimensions,
    }


def _minimum_coverage(query: str) -> float:
    """Match the deterministic Java/Python adaptive lexical-coverage boundary."""

    count = len(query_terms(query))
    return 0.30 if count <= 3 else 0.35 if count <= 6 else 0.40


def _joint(row: dict[str, Any], vector_minimum: float, keyword_minimum: float, coverage: float) -> bool:
    """Return whether one candidate satisfies all three non-exact gate signals."""

    return bool(
        row["vector"] >= vector_minimum and row["keyword"] >= keyword_minimum and row["coverage"] >= coverage
    )


def _recall_at_five(expected: list[str], ranked: list[str]) -> float:
    """Calculate expected-document recall among the first five ranked candidates."""

    return 0.0 if not expected else sum(document_id in ranked[:5] for document_id in expected) / len(expected)


def _reciprocal_rank(expected: list[str], ranked: list[str]) -> float:
    """Return reciprocal rank of the first expected document, or zero when absent."""

    if not expected:
        return 1.0
    return next((1.0 / index for index, value in enumerate(ranked, 1) if value in expected), 0.0)


def _maximum(rows: list[dict[str, Any]], field: str) -> float:
    """Read a stable numeric maximum, using zero for an empty authorized result."""

    return max((float(row[field]) for row in rows), default=0.0)


def _gate_predicted_pass(result: dict[str, Any]) -> bool:
    """Recover the evaluator's deterministic gate decision from expected/predicted equality."""

    return bool(result["expectedEvidenceGate"] == result["evidenceGateCorrect"])


def _average(values: list[float]) -> float:
    """Average metrics with the Java calculator's zero-for-empty convention."""

    return sum(values) / len(values) if values else 0.0


def _accuracy(values: list[bool]) -> float:
    """Calculate boolean accuracy with a defined zero-for-empty result."""

    return float(_rate(sum(values), len(values)))


def _rate(numerator: int | float, denominator: int) -> float:
    """Divide counts safely, returning zero for an empty denominator."""

    return numerator / denominator if denominator else 0.0


def _json(value: Any) -> str:
    """Serialize compact JSON for parameterized PostgreSQL JSONB casts."""

    return json.dumps(value, separators=(",", ":"), default=_json_default)


def _json_default(value: Any) -> str:
    """Serialize UUID and timestamp values while rejecting unknown object types."""

    if isinstance(value, UUID):
        return str(value)
    if isinstance(value, datetime):
        return value.isoformat()
    raise TypeError(f"Not JSON serializable: {type(value).__name__}")


def _json_value(value: Any) -> Any:
    """Normalize JSONB values from either psycopg-decoded objects or text results."""

    return json.loads(value) if isinstance(value, str) else value
