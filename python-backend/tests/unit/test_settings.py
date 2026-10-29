"""Regression checks for retrieval settings that define cross-backend gate parity."""

from app.settings import Settings


def test_default_gate_thresholds_match_java_runtime_configuration(monkeypatch) -> None:
    """Python defaults must not make the evidence gate more permissive than Java."""

    monkeypatch.delenv("MINIMUM_VECTOR_SIMILARITY", raising=False)
    monkeypatch.delenv("MINIMUM_KEYWORD_SCORE", raising=False)
    settings = Settings()
    assert settings.minimum_vector_similarity == 0.35
    assert settings.minimum_keyword_score == 0.3
