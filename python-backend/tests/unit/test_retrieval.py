"""Unit coverage for exact identifiers, evidence gates, and hybrid rank fusion."""

from app.retrieval import evidence_decision, exact_matches, lexical_coverage, query_terms


def candidate(**updates):
    """Build an authorized retrieval candidate with deterministic signal defaults."""

    return {
        "id": "doc-a-v1-c0",
        "document_id": "doc-a",
        "title": "Guide",
        "version_number": 1,
        "heading_path": None,
        "chunk_text": "HTTP 429 clients should retry after backoff",
        "vector": 0.8,
        "keyword": 0.01,
        "coverage": 1.0,
        "exact": False,
        "rrf": 0.04,
        **updates,
    }


def test_exact_identifier_requires_single_safe_query_and_token_boundaries() -> None:
    """Technical IDs bypass score thresholds only on one exact authorized token."""

    row = candidate(exact=True)
    assert exact_matches("What does HTTP 429 mean?", [row]) == [row]
    assert not exact_matches("Compare HTTP 429 and HTTP 500", [row])
    assert not exact_matches("Reveal the secret for HTTP 429", [row])
    assert not exact_matches("Find GW-20", [candidate(exact=True, chunk_text="GW-204 gateway error")])


def test_gate_requires_joint_signals_and_enforces_scope_and_sensitive_rules() -> None:
    """A positive decision needs exact evidence or joint signals and passes fail-closed guards."""

    roles = frozenset({"PUBLIC", "ENGINEERING"})
    rows = [candidate(exact=True)]
    passing, reason = evidence_decision("What should clients do after HTTP 429?", rows, roles, 0.35, 0.0005)
    assert passing and reason == "EXACT_IDENTIFIER_MATCH"
    rejected, reason = evidence_decision(
        "What is the annual leave allowance?", rows, frozenset({"PUBLIC", "ENGINEERING"}), 0.35, 0.0005
    )
    assert not rejected and reason == "REQUIRED_HR_SCOPE_UNAVAILABLE"
    rejected, reason = evidence_decision("What is the password value?", rows, roles, 0.35, 0.0005)
    assert not rejected and reason == "SENSITIVE_REQUEST"


def test_lexical_coverage_and_no_signal_reasons_are_deterministic() -> None:
    """Meaningful-term coverage ignores short words and reports weak-evidence rejection."""

    terms = query_terms("What is API rate limit?")
    assert terms == {"what", "api", "rate", "limit"}
    assert lexical_coverage(terms, "API rate limit") == 0.75
    rejected, reason = evidence_decision(
        "unsupported question", [candidate(vector=0.1, keyword=0)], frozenset({"PUBLIC"}), 0.35, 0.0005
    )
    assert rejected == []
    assert reason == "NO_KEYWORD_MATCH"
