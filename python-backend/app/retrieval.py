"""ACL-filtered hybrid retrieval, RRF, and deterministic evidence sufficiency."""

import re
import unicodedata
from dataclasses import dataclass
from hashlib import sha256
from typing import Any

from app.providers import OllamaEmbeddingProvider
from app.query_planner import normalize_query
from app.settings import get_settings
from app.storage import Storage

IDENTIFIER = re.compile(r"(?=[A-Za-z0-9_./:-]*[0-9])[A-Za-z0-9_./:-]{2,}")
API_SYMBOL = re.compile(r"\b[A-Z][A-Z0-9_]{5,}\b")
HEADER_IDENTIFIER = re.compile(r"\bX-[A-Za-z0-9]+(?:-[A-Za-z0-9]+)+\b", re.I)
CJK_RUN = re.compile(r"[\u3040-\u30ff\u3400-\u4dbf\u4e00-\u9fff\uac00-\ud7af]+")
ASCII_IDENTIFIER = re.compile(r"(?<![A-Za-z0-9_])[A-Za-z][A-Za-z0-9_]{2,}(?![A-Za-z0-9_])")
SENSITIVE = re.compile(
    r"\b(?:what|which|show|reveal|provide|tell me|give me|state|list|identify)\b.{0,120}"
    r"\b(?:password|credential|secret|private key|token|salary|acquisition price|home address)\b|"
    r"\b(?:password|credential|secret|private key|token|salary|acquisition price|home address)\b.{0,80}"
    r"\b(?:value|content|it is|was|exposed|used)\b",
    re.IGNORECASE,
)
SENSITIVE_CJK = re.compile(r"(?:密码|口令|凭证|密钥|令牌|工资|薪资|住址|家庭住址)")
POLICY_GUIDANCE = re.compile(
    r"^\s*(?:what|which)\s+(?:is|are)\s+(?:the\s+)?(?:policy|policies|standard|standards|guidance|requirements?)\b"
    r".{0,100}\b(?:storing|handling|rotation|rotating|protecting|managing)\b.{0,40}"
    r"\b(?:secret|credential|password|token)s?\b",
    re.IGNORECASE,
)
SCOPE_PATTERNS = (
    (
        "HR",
        re.compile(
            r"\b(?:annual leave|leave allowance|leave days|remote work|employee benefits|benefits onboarding|employee onboarding)\b",
            re.I,
        ),
        "REQUIRED_HR_SCOPE_UNAVAILABLE",
    ),
    (
        "ENGINEERING",
        re.compile(
            r"\b(?:gateway|workspace service|deployment|rollback|roll back|release readiness|response header|request identifier|enterprise api (?:request )?limit)\b|\bGW-[0-9]{3}\b",
            re.I,
        ),
        "REQUIRED_ENGINEERING_SCOPE_UNAVAILABLE",
    ),
    (
        "SECURITY_ADMIN",
        re.compile(
            r"\b(?:security incident|incident response|security escalation|access permissions|access review|security controls?)\b",
            re.I,
        ),
        "REQUIRED_SECURITY_SCOPE_UNAVAILABLE",
    ),
)
SPECIAL_REJECTIONS = (
    (
        re.compile(
            r"\b(?:will|would|plan to|plans to|expect to)\b.{0,80}\b(?:20[3-9][0-9]|next year|future)\b", re.I
        ),
        "UNSUPPORTED_FUTURE_SPECULATION",
    ),
    (
        re.compile(
            r"\b(?:name of (?:the )?(?:acme'?s? )?(?:ceo|chief executive)|who is (?:the )?(?:acme'?s? )?(?:ceo|chief executive))\b",
            re.I,
        ),
        "UNSUPPORTED_EXECUTIVE_IDENTITY",
    ),
    (
        re.compile(
            r"\b(?:contractual|contract)\b.{0,80}\b(?:penalty|penalties|damages)\b|\b(?:penalty|penalties)\b.{0,80}\b(?:uptime|availability|sla)\b",
            re.I,
        ),
        "UNSUPPORTED_CONTRACTUAL_PENALTY",
    ),
)


@dataclass(frozen=True)
class RetrievalResult:
    """Fused authorized candidates, evidence decision, gate reason, and raw method counts."""

    candidates: list[dict[str, Any]]
    sufficient: bool
    reason: str
    vector_count: int
    keyword_count: int
    exact_count: int
    query_diagnostics: dict[str, Any] | None = None


class HybridRetriever:
    """Runs all candidate searches with SQL ACL predicates before RRF fusion."""

    def __init__(self, storage: Storage, embeddings: OllamaEmbeddingProvider) -> None:
        self.storage = storage
        self.embeddings = embeddings
        settings = get_settings()
        self.depth = settings.candidate_depth
        self.fused_depth = settings.fused_depth
        self.rrf_constant = settings.rrf_constant
        self.minimum_vector_similarity = settings.minimum_vector_similarity
        self.minimum_keyword_score = settings.minimum_keyword_score

    def retrieve(
        self,
        message: str,
        roles: frozenset[str],
        *,
        plan: Any | None = None,
        gate_query: str | None = None,
        planned_queries: list[tuple[str, frozenset[str]]] | None = None,
        use_vector: bool = True,
    ) -> RetrievalResult:
        """Retrieve from original and bounded planned queries, ACL-filter each, then fuse once per chunk."""

        original = unicodedata.normalize("NFKC", " ".join(message.strip().split()))
        query = unicodedata.normalize("NFKC", " ".join((gate_query or original).strip().split()))
        if not original or len(original) > 2000 or not query or len(query) > 2000:
            raise ValueError("Message must contain 1-2000 characters")
        normalized_original = normalize_query(original)
        query_set = _retrieval_query_set(original, plan)
        normalized_query_keys = {normalize_query(original).casefold()}
        channel_map = {
            unicodedata.normalize("NFKC", " ".join(query.strip().split())).casefold(): set(channels)
            for query, channels in (planned_queries or [])
        }
        unique_query_keys = {query.casefold() for query in query_set}
        for variant in channel_map:
            if variant not in unique_query_keys and len(query_set) < 10:
                query_set.append(variant)
                unique_query_keys.add(variant)
        vector_rows: list[dict[str, Any]] = []
        keyword_rows: list[dict[str, Any]] = []
        exact_rows: list[dict[str, Any]] = []
        baseline_rows: list[list[dict[str, Any]]] = [[], [], []]
        rewritten_rows: list[list[dict[str, Any]]] = [[], [], []]
        query_diagnostics: dict[str, Any] = {}
        for variant in query_set:
            is_original = variant.casefold() == original.casefold()
            channels = channel_map.get(variant.casefold(), set())
            baseline_normalized = variant.casefold() in normalized_query_keys
            include_vector = use_vector and (is_original or baseline_normalized or "vector" in channels)
            include_keyword = is_original or baseline_normalized or "keyword" in channels
            include_exact = is_original or baseline_normalized or "exact" in channels
            query_vector = self.embeddings.embed([variant])[0] if include_vector else None
            identifiers = _query_identifiers(variant) if include_exact else []
            retrieval_arguments = (variant, roles, query_vector, identifiers, self.depth)
            try:
                variant_vectors, variant_keywords, variant_exact = self.storage.reserve_retrieval(
                    *retrieval_arguments,
                    include_vector=include_vector,
                    include_keyword=include_keyword,
                    include_exact=include_exact,
                )
            except TypeError as error:
                if "unexpected keyword argument" not in str(error):
                    raise
                variant_vectors, variant_keywords, variant_exact = self.storage.reserve_retrieval(
                    *retrieval_arguments,
                )
            channels_for_variant = (variant_vectors, variant_keywords, variant_exact)
            destination = baseline_rows if is_original else rewritten_rows
            for index, variant_rows in enumerate(channels_for_variant):
                destination[index].extend(variant_rows)
            vector_rows.extend(variant_vectors)
            keyword_rows.extend(variant_keywords)
            exact_rows.extend(variant_exact)
            query_diagnostics[sha256(variant.encode("utf-8")).hexdigest()[:12]] = {
                "vectorCount": len(variant_vectors),
                "keywordCount": len(variant_keywords),
                "exactCount": len(variant_exact),
            }
        rankings = [
            _best_rank_per_chunk(vector_rows),
            _best_rank_per_chunk(keyword_rows),
            _best_rank_per_chunk(exact_rows),
        ]
        baseline = self._fuse([_best_rank_per_chunk(rows) for rows in baseline_rows])
        rewritten = self._fuse([_best_rank_per_chunk(rows) for rows in rewritten_rows])
        fused = self._fuse(rankings)
        terms = query_terms(gate_query or query)
        terms.update(query_terms(normalized_original))
        terms.update(token for token in _query_identifiers(original) if len(token) > 2)
        passing, reason = evidence_decision(
            query,
            fused,
            roles,
            self.minimum_vector_similarity,
            self.minimum_keyword_score,
            safety_query=original,
            gate_terms=terms,
        )
        return RetrievalResult(
            passing,
            bool(passing),
            reason,
            len(rankings[0]),
            len(rankings[1]),
            len(rankings[2]),
            {
                "queryCount": len(query_set),
                "channels": query_diagnostics,
                "baselineDocumentIds": _document_ids(baseline),
                "rewrittenDocumentIds": _document_ids(rewritten),
                "combinedDocumentIds": _document_ids(fused),
            },
        )

    def _fuse(self, rankings: list[list[dict[str, Any]]]) -> list[dict[str, Any]]:
        """Merge retrieval lists by reciprocal rank, preserving independent retrieval signals."""

        fused: dict[str, dict[str, Any]] = {}
        for ranking_index, ranking in enumerate(rankings):
            for rank, row in enumerate(ranking, start=1):
                item = fused.setdefault(
                    row["id"],
                    {**row, "rrf": 0.0, "vector": 0.0, "keyword": 0.0, "exact": False, "coverage": 0.0},
                )
                item["rrf"] = max(item["rrf"], 1.0 / (self.rrf_constant + rank))
                if ranking_index == 0:
                    item["vector"] = max(item["vector"], float(row["score"]))
                elif ranking_index == 1:
                    item["keyword"] = max(item["keyword"], float(row["score"]))
                    item["coverage"] = max(item["coverage"], float(row.get("coverage", 0.0)))
                else:
                    item["exact"] = True
        return sorted(fused.values(), key=lambda item: (-item["rrf"], item["id"]))[: self.fused_depth]


def query_terms(query: str) -> set[str]:
    """Return English tokens and overlapping CJK bigrams for deterministic lexical coverage."""

    query = unicodedata.normalize("NFKC", query).lower()
    terms = {term for term in re.split(r"[^a-z0-9]+", query) if len(term) > 2}
    for run in CJK_RUN.findall(query):
        terms.update(run[index : index + 2] for index in range(len(run) - 1))
        if len(run) == 1:
            terms.add(run)
    return terms


def normalized_query_for_gate(query: str) -> str:
    """Return the deterministic subject-normalized query for coverage diagnostics."""

    from app.query_planner import normalize_query

    return normalize_query(query)


def _retrieval_query_set(original: str, plan: Any | None) -> list[str]:
    """Keep the original baseline and cap/deduplicate planner variants."""

    original_value = unicodedata.normalize("NFKC", " ".join(original.strip().split()))
    values = [original_value]
    if plan is not None:
        values.extend(
            [
                plan.subject,
                plan.semantic_query,
                *plan.lexical_queries,
                *plan.alternative_queries,
            ]
        )
    unique: list[str] = []
    seen: set[str] = set()
    for value in values:
        normalized = (
            original_value
            if not unique
            else unicodedata.normalize("NFKC", " ".join(str(value).strip().split()))
        )
        key = normalized.casefold()
        if normalized and key not in seen:
            seen.add(key)
            unique.append(normalized)
        if len(unique) >= 10:
            break
    return unique


def _best_rank_per_chunk(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Deduplicate results from rewrites by keeping each chunk's strongest channel signal."""

    best: dict[str, dict[str, Any]] = {}
    for row in rows:
        existing = best.get(row["id"])
        if existing is None or float(row["score"]) > float(existing["score"]):
            best[row["id"]] = dict(row)
        elif existing is not None:
            existing["coverage"] = max(float(existing.get("coverage", 0.0)), float(row.get("coverage", 0.0)))
    return sorted(best.values(), key=lambda row: (-float(row["score"]), row["id"]))


def _document_ids(rows: list[dict[str, Any]]) -> list[str]:
    """Return unique document IDs in candidate order for bounded evaluation diagnostics."""

    return list(dict.fromkeys(row["document_id"] for row in rows))[:20]


def _query_identifiers(query: str) -> list[str]:
    """Extract technical IDs and identifier-shaped ASCII terms for indexed exact lookup."""

    query = unicodedata.normalize("NFKC", query)
    matches = [match.group() for match in [*IDENTIFIER.finditer(query), *API_SYMBOL.finditer(query)]]
    technical_identifier = bool(re.search(r"[0-9]|[-_]", query)) or bool(HEADER_IDENTIFIER.search(query))
    if CJK_RUN.search(query) or technical_identifier:
        matches.extend(match.group() for match in ASCII_IDENTIFIER.finditer(query))
    matches.extend(match.group() for match in HEADER_IDENTIFIER.finditer(query))
    status_match = re.search(r"\b(?:http|status|error|code)\s*[-:]?\s*([0-9]{3})\b", query, re.I)
    if status_match:
        matches.append(status_match.group(1).lower())
    return list(dict.fromkeys(match.rstrip(".").lower() for match in matches))


def lexical_coverage(terms: set[str], text: str) -> float:
    """Compute exact-token fallback coverage for isolated policy unit tests."""

    if not terms:
        return 0.0
    tokens = set(re.findall(r"[a-z0-9]+", text.lower()))
    return len(terms & tokens) / len(terms)


def exact_matches(query: str, candidates: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Return one exact technical-identifier match unless query intent disallows the bypass."""

    if _sensitive_query(query):
        return []
    indexed = {
        str(candidate["matched_identifier"]).lower()
        for candidate in candidates
        if candidate.get("exact") and candidate.get("matched_identifier")
    }
    query_identifiers = set(_query_identifiers(query))
    if indexed:
        if _sensitive_query(query):
            return []
        if (
            re.search(r"\b(?:compare|versus|vs|difference|between)\b", query, re.I)
            and len(query_identifiers) > 1
        ):
            return []
        if _sensitive_query(query):
            return []
        matched = indexed & query_identifiers
        if not matched:
            return []
        if len(matched) != 1:
            return []
        if re.search(r"\b(?:compare|versus|vs|difference|between)\b", query, re.I):
            return []
        return [
            candidate
            for candidate in candidates
            if candidate.get("matched_identifier", "").lower() in matched and candidate["vector"] >= 0.35
        ]
    found = set(_query_identifiers(query))
    normalized_query = normalized_query_for_gate(query)
    found.update(_query_identifiers(normalized_query))
    allowed = {
        identifier
        for identifier in found
        if re.search(r"[a-z]", identifier)
        or re.search(r"\b(?:http|status|error|code)\s*[-:]?\s*[0-9]{3}\b", query, re.I)
    }
    status_match = re.search(r"\b(?:http|status|error|code)\s*[-:]?\s*([0-9]{3})\b", query, re.I)
    if status_match:
        allowed.add(status_match.group(1).lower())
    if len(allowed) != 1:
        return []
    identifier = next(iter(allowed))
    token = re.compile(
        rf"(?<![A-Za-z0-9_./:-]){re.escape(identifier)}\.+(?=$|[^A-Za-z0-9_/:.-])|(?<![A-Za-z0-9_./:-]){re.escape(identifier)}(?![A-Za-z0-9_./:-])",
        re.I,
    )
    return [
        candidate for candidate in candidates if candidate["exact"] and token.search(candidate["chunk_text"])
    ]


def _sensitive_query(query: str) -> bool:
    """Reject requests that ask for sensitive values even when they also contain an identifier."""

    return bool(
        (
            SENSITIVE.search(query)
            or SENSITIVE_CJK.search(query)
            or re.search(r"\b(?:salary|password|credential|secret|private key|token)\b", query, re.I)
        )
        and not POLICY_GUIDANCE.search(query)
    )


def evidence_decision(
    query: str,
    candidates: list[dict[str, Any]],
    roles: frozenset[str],
    vector_minimum: float,
    keyword_minimum: float,
    *,
    safety_query: str | None = None,
    gate_terms: set[str] | None = None,
) -> tuple[list[dict[str, Any]], str]:
    """Apply explainable exact/joint-signal gate and fail-closed intent/scope guards."""

    if not candidates:
        return [], "NO_AUTHORIZED_CANDIDATES"
    policy_query = safety_query or query
    exact = exact_matches(policy_query, candidates)
    if _sensitive_query(policy_query):
        return [], "SENSITIVE_REQUEST"
    for pattern, reason in SPECIAL_REJECTIONS:
        if pattern.search(policy_query):
            return [], reason
    if re.search(r"\bcustomer[-_][0-9]{2,}\b", policy_query, re.I) and not exact:
        return [], "UNMATCHED_CUSTOMER_IDENTIFIER"
    if re.search(
        r"\bcontractors?\b.{0,70}\b(?:leave|benefits?|receive|eligible|allowance|days?)\b|\b(?:leave|benefits?|allowance)\b.{0,70}\bcontractors?\b",
        policy_query,
        re.I,
    ):
        return [], "CONTRACTOR_POLICY_UNAVAILABLE"
    if re.search(
        r"\b(?:previous|prior|old|former|before the change|used to)\b.{0,80}\b(?:policy|limit|allowance|procedure|standard)\b",
        policy_query,
        re.I,
    ):
        return [], "HISTORICAL_VERSION_UNAVAILABLE"
    indexed_identifiers = {
        candidate["matched_identifier"].lower()
        for candidate in candidates
        if candidate.get("exact") and candidate.get("matched_identifier")
    }
    if (
        re.search(r"\b(?:compare|versus|vs|difference|between)\b", policy_query, re.I)
        and len(indexed_identifiers) > 1
    ):
        return [], "MULTI_IDENTIFIER_COMPARISON"
    if "IR-" in policy_query.upper() and "SECURITY_ADMIN" not in roles:
        return [], "REQUIRED_SECURITY_SCOPE_UNAVAILABLE"
    for role, pattern, reason in SCOPE_PATTERNS:
        if pattern.search(policy_query) and role not in roles:
            return [], reason
    if exact:
        vector_supported = [candidate for candidate in exact if candidate["vector"] >= vector_minimum]
        if vector_supported:
            return vector_supported, "EXACT_IDENTIFIER_MATCH"
    terms = gate_terms if gate_terms is not None else query_terms(query)
    count = len(terms)
    minimum_coverage = 0.30 if count <= 3 else 0.35 if count <= 6 else 0.40
    joint = [
        candidate
        for candidate in candidates
        if candidate["vector"] >= vector_minimum
        and candidate["keyword"] >= keyword_minimum
        and candidate["coverage"] >= minimum_coverage
    ]
    if joint:
        return joint, "LEXICAL_AND_RELEVANCE_MATCH"
    if not any(candidate["keyword"] >= keyword_minimum for candidate in candidates):
        reason = "NO_KEYWORD_MATCH"
    elif not any(candidate["vector"] >= vector_minimum for candidate in candidates):
        reason = "BELOW_VECTOR_THRESHOLD"
    elif any(
        candidate["vector"] >= vector_minimum or candidate["keyword"] >= keyword_minimum
        for candidate in candidates
    ):
        reason = "SPLIT_SIGNALS_NO_SINGLE_CANDIDATE"
    else:
        reason = "NO_STRONG_EVIDENCE"
    return [], reason
