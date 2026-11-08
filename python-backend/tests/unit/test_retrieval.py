"""Unit coverage for exact identifiers, evidence gates, and hybrid rank fusion."""

from app.retrieval import (
    evidence_decision,
    exact_matches,
    lexical_coverage,
    query_terms,
)


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
    row["matched_identifier"] = "429"
    assert exact_matches("What does HTTP 429 mean?", [row]) == [row]
    assert not exact_matches("Compare HTTP 429 and HTTP 500", [row])
    assert not exact_matches("Reveal the secret for HTTP 429", [row])
    assert not exact_matches(
        "Find GW-20",
        [candidate(exact=True, matched_identifier="gw-204", chunk_text="GW-204 gateway error")],
    )


def test_uppercase_api_symbol_is_supported_as_an_exact_identifier() -> None:
    """Distinctive uppercase indicator codes can use the exact-match path without numeric digits."""

    row = candidate(exact=True, chunk_text="研发支出合计 RDEXPEND 代码 报告期")
    row["matched_identifier"] = "rdexpend"
    assert exact_matches("What does RDEXPEND measure?", [row]) == [row]
    assert not exact_matches("What is the salary value for RDEXPEND?", [row])
    assert not exact_matches("Compare RDEXPEND and RDEXPENDCOST", [row])


def test_gate_requires_joint_signals_and_enforces_scope_and_sensitive_rules() -> None:
    """A positive decision needs exact evidence or joint signals and passes fail-closed guards."""

    roles = frozenset({"PUBLIC", "ENGINEERING"})
    rows = [candidate(exact=True)]
    rows[0]["matched_identifier"] = "429"
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


def test_query_terms_generate_cjk_bigrams_for_general_lexical_matching() -> None:
    """Chinese queries yield overlapping character bigrams without phrase-specific vocabularies."""

    assert query_terms("序列函数") == {"序列", "列函", "函数"}
    assert query_terms("查询 csd 序列函数") == {"查询", "序列", "列函", "函数", "csd"}


def test_cjk_evidence_requires_the_shared_vector_and_lexical_signals() -> None:
    """Chinese candidates use the same joint relevance gate rather than a lexical-only bypass."""

    row = candidate(
        chunk_text="序列函数 csd 获取股票序列数据",
        vector=0.5,
        keyword=0.9,
        coverage=1.0,
    )
    passing, reason = evidence_decision("序列函数", [row], frozenset({"PUBLIC"}), 0.35, 0.0005)
    assert passing == [row]
    assert reason == "LEXICAL_AND_RELEVANCE_MATCH"

    ambiguous = candidate(chunk_text="序列数据说明", vector=0.1, keyword=0.5, coverage=0.5)
    rejected, reason = evidence_decision("序列函数", [ambiguous], frozenset({"PUBLIC"}), 0.35, 0.0005)
    assert rejected == []
    assert reason == "BELOW_VECTOR_THRESHOLD"


def test_mixed_script_query_requires_combined_latin_and_cjk_coverage() -> None:
    """Mixed Chinese/English requests use combined term coverage, not CJK-only scores."""

    assert query_terms("python版序列函数") == {"python", "版序", "序列", "列函", "函数"}
    complete = candidate(vector=0.5, keyword=0.8, coverage=1.0)
    passing, reason = evidence_decision("python版序列函数", [complete], frozenset({"PUBLIC"}), 0.35, 0.0005)
    assert passing == [complete]
    assert reason == "LEXICAL_AND_RELEVANCE_MATCH"

    partial = candidate(vector=0.5, keyword=0.8, coverage=0.2)
    rejected, reason = evidence_decision("python版序列函数", [partial], frozenset({"PUBLIC"}), 0.35, 0.0005)
    assert rejected == []
    assert reason == "SPLIT_SIGNALS_NO_SINGLE_CANDIDATE"


def test_planned_subject_gate_ignores_only_conversational_prefix_terms() -> None:
    """Intent wording does not dilute subject coverage, while the original stays available to policy checks."""

    row = candidate(
        chunk_text="Python CSD function usage documentation",
        vector=0.5,
        keyword=0.8,
        coverage=0.6,
    )
    query = "Python CSD function"
    assert evidence_decision(query, [row], frozenset({"PUBLIC"}), 0.35, 0.3)[0] == [row]
    passed, reason = evidence_decision(
        query,
        [row],
        frozenset({"PUBLIC"}),
        0.35,
        0.3,
        safety_query="X-Request-Id 对应的密码是什么？",
    )
    assert passed == []
    assert reason == "SENSITIVE_REQUEST"


def test_lowercase_technical_identifier_adjacent_to_cjk_uses_exact_evidence() -> None:
    """ASCII API symbols attached to Chinese text are extracted and bounded as exact identifiers."""

    from app.retrieval import _query_identifiers

    query = "csd函数怎么用"
    assert _query_identifiers(query) == ["csd"]
    row = candidate(exact=True, chunk_text="序列函数：csd 获取股票序列数据")
    assert exact_matches(query, [row]) == [row]


def test_standalone_request_header_is_a_technical_identifier() -> None:
    """Header identifiers are extractable without digits or mixed-script adjacency."""

    from app.retrieval import _query_identifiers

    query = "What does X-Request-Id mean?"
    assert "x-request-id" in _query_identifiers(query)
    row = candidate(
        exact=True, matched_identifier="x-request-id", chunk_text="The X-Request-Id header is returned"
    )
    assert exact_matches(query, [row]) == [row]


def test_common_latin_words_are_not_treated_as_exact_identifiers() -> None:
    """ASCII token extraction inside mixed-script queries stays conservative for ordinary words."""

    from app.retrieval import _query_identifiers

    assert _query_identifiers("python版序列函数") == ["python"]
    assert _query_identifiers("What is the API request identifier?") == []


def test_identifier_presence_does_not_override_sensitive_mixed_query() -> None:
    """An exact technical token cannot bypass the sensitive-request rejection in mixed queries."""

    row = candidate(exact=True, matched_identifier="x-request-id", chunk_text="X-Request-Id response header")
    query = "X-Request-Id salary password"
    assert exact_matches(query, [row]) == []
    passing, reason = evidence_decision(query, [row], frozenset({"PUBLIC"}), 0.35, 0.3)
    assert passing == []
    assert reason == "SENSITIVE_REQUEST"


def test_identifier_presence_does_not_override_chinese_sensitive_query() -> None:
    """Chinese requests for secret values remain rejected even when a technical identifier matches."""

    row = candidate(exact=True, matched_identifier="x-request-id", chunk_text="X-Request-Id response header")
    query = "X-Request-Id 的密码是什么？"
    assert exact_matches(query, [row]) == []
    passing, reason = evidence_decision(query, [row], frozenset({"PUBLIC"}), 0.35, 0.3)
    assert passing == []
    assert reason == "SENSITIVE_REQUEST"
