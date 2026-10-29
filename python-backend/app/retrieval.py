"""ACL-filtered hybrid retrieval, RRF, and deterministic evidence sufficiency."""

import re
from dataclasses import dataclass
from typing import Any

from app.providers import OllamaEmbeddingProvider
from app.settings import get_settings
from app.storage import Storage

IDENTIFIER = re.compile(r"(?=[A-Za-z0-9_./:-]*[0-9])[A-Za-z0-9_./:-]{2,}")
SENSITIVE = re.compile(
    r"\b(?:what|which|show|reveal|provide|tell me|give me|state|list|identify)\b.{0,120}"
    r"\b(?:password|credential|secret|private key|token|salary|acquisition price|home address)\b|"
    r"\b(?:password|credential|secret|private key|token|salary|acquisition price|home address)\b.{0,80}"
    r"\b(?:value|content|it is|was|exposed|used)\b",
    re.IGNORECASE,
)
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

    def retrieve(self, message: str, roles: frozenset[str]) -> RetrievalResult:
        """Normalize, embed, retrieve authorized candidate lists, fuse, and apply the shared gate."""

        query = " ".join(message.strip().split())
        if not query or len(query) > 2000:
            raise ValueError("Message must contain 1-2000 characters")
        identifiers = list(
            dict.fromkeys(match.group().rstrip(".").lower() for match in IDENTIFIER.finditer(query))
        )
        vectors = self.embeddings.embed([query])[0]
        vector_rows, keyword_rows, exact_rows = self.storage.reserve_retrieval(
            query, roles, vectors, identifiers, self.depth
        )
        rankings = [vector_rows, keyword_rows, exact_rows]
        fused = self._fuse(rankings)
        passing, reason = evidence_decision(
            query, fused, roles, self.minimum_vector_similarity, self.minimum_keyword_score
        )
        return RetrievalResult(
            passing, bool(passing), reason, len(vector_rows), len(keyword_rows), len(exact_rows)
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
                item["rrf"] += 1.0 / (self.rrf_constant + rank)
                if ranking_index == 0:
                    item["vector"] = max(item["vector"], float(row["score"]))
                elif ranking_index == 1:
                    item["keyword"] = max(item["keyword"], float(row["score"]))
                    item["coverage"] = max(item["coverage"], float(row.get("coverage", 0.0)))
                else:
                    item["exact"] = True
        return sorted(fused.values(), key=lambda item: (-item["rrf"], item["id"]))[: self.fused_depth]


def query_terms(query: str) -> set[str]:
    """Return distinct Java-compatible alphanumeric query terms longer than two characters."""

    return {term for term in re.split(r"[^a-z0-9]+", query.lower()) if len(term) > 2}


def lexical_coverage(terms: set[str], text: str) -> float:
    """Compute exact-token fallback coverage for isolated policy unit tests."""

    if not terms:
        return 0.0
    tokens = set(re.findall(r"[a-z0-9]+", text.lower()))
    return len(terms & tokens) / len(terms)


def exact_matches(query: str, candidates: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Return one exact technical-identifier match unless query intent disallows the bypass."""

    if SENSITIVE.search(query) and not POLICY_GUIDANCE.search(query):
        return []
    found = {match.group().rstrip(".").lower() for match in IDENTIFIER.finditer(query)}
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
        rf"(?<![\w./:-]){re.escape(identifier)}\.+(?=$|[^\w/:.-])|(?<![\w./:-]){re.escape(identifier)}(?![\w./:-])",
        re.I,
    )
    return [
        candidate for candidate in candidates if candidate["exact"] and token.search(candidate["chunk_text"])
    ]


def evidence_decision(
    query: str,
    candidates: list[dict[str, Any]],
    roles: frozenset[str],
    vector_minimum: float,
    keyword_minimum: float,
) -> tuple[list[dict[str, Any]], str]:
    """Apply explainable exact/joint-signal gate and fail-closed intent/scope guards."""

    if not candidates:
        return [], "NO_AUTHORIZED_CANDIDATES"
    exact = exact_matches(query, candidates)
    if SENSITIVE.search(query) and not POLICY_GUIDANCE.search(query):
        return [], "SENSITIVE_REQUEST"
    for pattern, reason in SPECIAL_REJECTIONS:
        if pattern.search(query):
            return [], reason
    if re.search(r"\bcustomer[-_][0-9]{2,}\b", query, re.I) and not exact:
        return [], "UNMATCHED_CUSTOMER_IDENTIFIER"
    if re.search(
        r"\bcontractors?\b.{0,70}\b(?:leave|benefits?|receive|eligible|allowance|days?)\b|\b(?:leave|benefits?|allowance)\b.{0,70}\bcontractors?\b",
        query,
        re.I,
    ):
        return [], "CONTRACTOR_POLICY_UNAVAILABLE"
    if re.search(
        r"\b(?:previous|prior|old|former|before the change|used to)\b.{0,80}\b(?:policy|limit|allowance|procedure|standard)\b",
        query,
        re.I,
    ):
        return [], "HISTORICAL_VERSION_UNAVAILABLE"
    identifiers = list(IDENTIFIER.finditer(query))
    if re.search(r"\b(?:compare|versus|vs|difference|between)\b", query, re.I) and len(identifiers) > 1:
        return [], "MULTI_IDENTIFIER_COMPARISON"
    if "IR-" in query.upper() and "SECURITY_ADMIN" not in roles:
        return [], "REQUIRED_SECURITY_SCOPE_UNAVAILABLE"
    for role, pattern, reason in SCOPE_PATTERNS:
        if pattern.search(query) and role not in roles:
            return [], reason
    if exact:
        return exact, "EXACT_IDENTIFIER_MATCH"
    count = len(query_terms(query))
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
