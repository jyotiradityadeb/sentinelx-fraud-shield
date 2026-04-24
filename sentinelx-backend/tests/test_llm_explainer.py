from __future__ import annotations

import asyncio

import llm_explainer


def _sample_session() -> dict:
    return {
        "seconds_since_call": 18,
        "switch_count_20s": 5,
        "confirm_dwell_ms": 1200,
        "tap_density": 6.2,
        "revisit_count": 3,
        "caller_trust": "UNKNOWN",
        "is_messaging_before": True,
        "is_whatsapp_voip": True,
        "voice_stress_score": 0.84,
        "network_threat_score": 12,
    }


def test_fallback_without_openai_key(monkeypatch):
    monkeypatch.setenv("OPENAI_KEY", "")
    if hasattr(llm_explainer, "config") and llm_explainer.config is not None:
        monkeypatch.setattr(llm_explainer.config, "OPENAI_KEY", "", raising=False)

    risk = {"total_score": 82, "score": 82, "label": "HIGH_RISK", "anomaly_score": 0.6}
    result = asyncio.run(llm_explainer.explain(risk, _sample_session()))

    assert result["fraud_likelihood"] in {"HIGH", "CRITICAL"}
    assert result["recommended_action"] in {"DELAY_AND_VERIFY", "BLOCK_AND_ALERT"}
    assert isinstance(result["observed_evidence"], list)


def test_high_risk_action_is_delay_or_block():
    risk = {"total_score": 81, "score": 81, "label": "HIGH_RISK", "anomaly_score": 0.4}
    result = llm_explainer._fallback_explanation(risk, _sample_session())
    assert result["recommended_action"] in {"DELAY_AND_VERIFY", "BLOCK_AND_ALERT"}


def test_score_95_forces_block_and_alert():
    risk = {"total_score": 96, "score": 96, "label": "HIGH_RISK", "anomaly_score": 0.8}
    result = llm_explainer._fallback_explanation(risk, _sample_session())
    assert result["recommended_action"] == "BLOCK_AND_ALERT"


def test_safe_does_not_block_and_alert():
    risk = {"total_score": 20, "score": 20, "label": "SAFE", "anomaly_score": 0.05}
    session = dict(_sample_session())
    session["caller_trust"] = "KNOWN_CONTACT"
    session["seconds_since_call"] = 9999
    result = llm_explainer._fallback_explanation(risk, session)
    assert result["recommended_action"] != "BLOCK_AND_ALERT"

