from __future__ import annotations

import json
import os
from typing import Any

try:
    import config  # type: ignore
except Exception:
    config = None

ALLOWED_THREAT_TYPES = {
    "VISHING",
    "UPI_SCAM",
    "IMPERSONATION",
    "VOICE_COERCION",
    "MULE_TRANSFER",
    "BEHAVIORAL_ANOMALY",
}

ALLOWED_FRAUD_LIKELIHOOD = {"LOW", "MEDIUM", "HIGH", "CRITICAL"}
ALLOWED_ACTIONS = {"ALLOW", "WARN", "DELAY_AND_VERIFY", "BLOCK_AND_ALERT"}
LIKELIHOOD_ORDER = {"LOW": 0, "MEDIUM": 1, "HIGH": 2, "CRITICAL": 3}


def _word_limit(text: str, max_words: int) -> str:
    words = (text or "").split()
    if len(words) <= max_words:
        return (text or "").strip()
    return " ".join(words[:max_words]).strip()


def _as_int(value: Any, default: int = 0) -> int:
    try:
        return int(value)
    except Exception:
        return default


def _as_float(value: Any, default: float = 0.0) -> float:
    try:
        return float(value)
    except Exception:
        return default


def _choose_threat_type(risk_result: dict[str, Any], session_features: dict[str, Any]) -> str:
    label = str(risk_result.get("label", "")).upper()
    triggered = risk_result.get("triggered_signals", []) or []
    signal_names = " ".join(str(item.get("name", "")) for item in triggered if isinstance(item, dict)).lower()

    if bool(session_features.get("is_whatsapp_voip")) and _as_int(session_features.get("seconds_since_call", 9999), 9999) < 60:
        return "VISHING"
    if _as_int(session_features.get("network_threat_score", 0), 0) >= 10:
        return "MULE_TRANSFER"
    if _as_float(session_features.get("voice_stress_score", 0.0), 0.0) >= 0.8:
        return "VOICE_COERCION"
    if "caller trust" in signal_names and str(session_features.get("caller_trust", "")).upper() in {"UNKNOWN", "REPEATED_UNKNOWN", "SCAMMER_MARKED"}:
        return "IMPERSONATION"
    if "transition velocity" in signal_names or "confirmation pressure" in signal_names:
        return "UPI_SCAM"
    if label == "SAFE":
        return "BEHAVIORAL_ANOMALY"
    return "BEHAVIORAL_ANOMALY"


def build_evidence(risk_result: dict[str, Any], session_features: dict[str, Any]) -> list[str]:
    evidence: list[str] = []
    seconds_since_call = _as_int(session_features.get("seconds_since_call", 9999), 9999)
    caller_trust = str(session_features.get("caller_trust", "UNKNOWN")).upper()
    switch_count = _as_int(session_features.get("switch_count_20s", 0), 0)
    confirm_ms = _as_int(session_features.get("confirm_dwell_ms", 14000), 14000)
    tap_density = _as_float(session_features.get("tap_density", 1.0), 1.0)
    voice_stress = _as_float(session_features.get("voice_stress_score", 0.0), 0.0)
    network_score = _as_int(session_features.get("network_threat_score", 0), 0)
    anomaly_score = _as_float(risk_result.get("anomaly_score", 0.0), 0.0)

    if seconds_since_call < 9999:
        evidence.append(f"Payment attempted {seconds_since_call} seconds after call ended")
    if caller_trust in {"UNKNOWN", "REPEATED_UNKNOWN", "SCAMMER_MARKED"}:
        if caller_trust == "SCAMMER_MARKED":
            evidence.append("Caller matches user-marked scammer number")
        else:
            evidence.append("Unknown caller context")
    if bool(session_features.get("is_whatsapp_voip")):
        evidence.append("WhatsApp/voice-call-to-payment pattern detected")
    if switch_count >= 2:
        evidence.append(f"High app switch count: {switch_count} in 20 seconds")
    if confirm_ms <= 5000:
        evidence.append(f"Fast confirmation time: {confirm_ms} ms")
    if tap_density >= 3.0:
        evidence.append(f"High tap density near confirmation: {tap_density:.2f}")
    if voice_stress >= 0.4:
        evidence.append(f"Voice stress indicator elevated: {voice_stress:.2f}")
    if network_score > 0:
        evidence.append(f"Network threat score: {network_score}/15")
    if anomaly_score > 0:
        evidence.append(f"Behavioral anomaly score: {anomaly_score:.2f}")

    if not evidence:
        evidence.append("No strong fraud indicators detected in current session signals")
    return evidence


def _fallback_explanation(risk_result: dict[str, Any], session_features: dict[str, Any]) -> dict[str, Any]:
    score = _as_int(risk_result.get("total_score", risk_result.get("score", 0)), 0)
    label = str(risk_result.get("label", "SAFE")).upper()
    threat_type = _choose_threat_type(risk_result, session_features)
    evidence = build_evidence(risk_result, session_features)

    if score >= 95:
        likelihood = "CRITICAL"
        action = "BLOCK_AND_ALERT"
    elif score >= 80:
        likelihood = "HIGH"
        action = "DELAY_AND_VERIFY"
    elif score >= 40:
        likelihood = "MEDIUM"
        action = "WARN"
    else:
        likelihood = "LOW"
        action = "ALLOW"

    risk_explanation = (
        "Observed a possible scam pattern based on caller context, timing, interaction pressure, "
        "and behavioral indicators. This is not a definitive fraud confirmation."
    )
    user_prompt = _word_limit(
        "This appears to be a high-risk pattern. Pause payment, verify caller identity independently, and confirm recipient details before proceeding.",
        45,
    )
    guardian_message = _word_limit(
        "SentinelX flagged a possible scam pattern during payment activity. Please contact the user now and verify the request through a trusted channel.",
        60,
    )
    dashboard_summary = _word_limit(
        f"{label} pattern flagged with score {score}/120. Review observed evidence and verify before permitting payment.",
        80,
    )

    return _apply_guardrails(
        {
            "fraud_likelihood": likelihood,
            "risk_explanation": risk_explanation,
            "observed_evidence": evidence,
            "missing_evidence": ["No definitive fraud confirmation available."],
            "user_prompt": user_prompt,
            "guardian_message": guardian_message,
            "dashboard_summary": dashboard_summary,
            "recommended_action": action,
            "threat_type": threat_type,
        },
        risk_result,
    )


def _apply_guardrails(explanation: dict[str, Any], risk_result: dict[str, Any]) -> dict[str, Any]:
    score = _as_int(risk_result.get("total_score", risk_result.get("score", 0)), 0)
    label = str(risk_result.get("label", "SAFE")).upper()

    likelihood = str(explanation.get("fraud_likelihood", "MEDIUM")).upper()
    if likelihood not in ALLOWED_FRAUD_LIKELIHOOD:
        likelihood = "MEDIUM"

    action = str(explanation.get("recommended_action", "WARN")).upper()
    if action not in ALLOWED_ACTIONS:
        action = "WARN"

    threat_type = str(explanation.get("threat_type", "BEHAVIORAL_ANOMALY")).upper()
    if threat_type not in ALLOWED_THREAT_TYPES:
        threat_type = "BEHAVIORAL_ANOMALY"

    if score >= 95:
        action = "BLOCK_AND_ALERT"
        likelihood = "CRITICAL"
    elif score >= 80 and LIKELIHOOD_ORDER[likelihood] < LIKELIHOOD_ORDER["HIGH"]:
        likelihood = "HIGH"

    if label == "SAFE" and action == "BLOCK_AND_ALERT":
        action = "WARN"

    explanation["fraud_likelihood"] = likelihood
    explanation["recommended_action"] = action
    explanation["threat_type"] = threat_type

    explanation["risk_explanation"] = _word_limit(str(explanation.get("risk_explanation", "")).strip(), 80)
    explanation["user_prompt"] = _word_limit(str(explanation.get("user_prompt", "")).strip(), 45)
    explanation["guardian_message"] = _word_limit(str(explanation.get("guardian_message", "")).strip(), 60)
    explanation["dashboard_summary"] = _word_limit(str(explanation.get("dashboard_summary", "")).strip(), 80)

    observed = explanation.get("observed_evidence")
    missing = explanation.get("missing_evidence")
    if not isinstance(observed, list):
        observed = []
    if not isinstance(missing, list):
        missing = []
    explanation["observed_evidence"] = [str(x) for x in observed if str(x).strip()]
    explanation["missing_evidence"] = [str(x) for x in missing if str(x).strip()]
    return explanation


async def explain(risk_result: dict[str, Any], session_features: dict[str, Any]) -> dict[str, Any]:
    cfg_key = ""
    if config is not None:
        cfg_key = str(getattr(config, "OPENAI_KEY", "") or "")
    api_key = (os.getenv("OPENAI_KEY", "") or cfg_key).strip()
    model = (os.getenv("OPENAI_MODEL", "gpt-4o-mini") or "gpt-4o-mini").strip()

    evidence = build_evidence(risk_result, session_features)

    if not api_key or "placeholder" in api_key.lower():
        return _fallback_explanation(risk_result, session_features)

    try:
        from openai import AsyncOpenAI
    except Exception:
        return _fallback_explanation(risk_result, session_features)

    schema = {
        "type": "object",
        "additionalProperties": False,
        "properties": {
            "fraud_likelihood": {"type": "string", "enum": sorted(ALLOWED_FRAUD_LIKELIHOOD)},
            "risk_explanation": {"type": "string"},
            "observed_evidence": {"type": "array", "items": {"type": "string"}},
            "missing_evidence": {"type": "array", "items": {"type": "string"}},
            "user_prompt": {"type": "string"},
            "guardian_message": {"type": "string"},
            "dashboard_summary": {"type": "string"},
            "recommended_action": {"type": "string", "enum": sorted(ALLOWED_ACTIONS)},
            "threat_type": {"type": "string", "enum": sorted(ALLOWED_THREAT_TYPES)},
        },
        "required": [
            "fraud_likelihood",
            "risk_explanation",
            "observed_evidence",
            "missing_evidence",
            "user_prompt",
            "guardian_message",
            "dashboard_summary",
            "recommended_action",
            "threat_type",
        ],
    }

    system_prompt = (
        "You are SentinelX fraud explanation engine. "
        "Return strict JSON only. Never claim fraud is 100% confirmed. "
        "Use phrases like 'high-risk pattern' or 'possible scam pattern'. "
        "Use only provided signals and evidence; do not invent facts. "
        "Signals allowed: caller trust, call-to-payment time, app switching, confirmation dwell time, "
        "tap density, voice stress indicator, network threat score, behavioral anomaly score. "
        "If evidence is weak, reflect uncertainty in missing_evidence."
    )

    user_prompt = (
        f"risk_result={json.dumps(risk_result, ensure_ascii=False)}\n"
        f"session_features={json.dumps(session_features, ensure_ascii=False)}\n"
        f"deterministic_evidence={json.dumps(evidence, ensure_ascii=False)}\n"
        "Generate the required JSON."
    )

    client = AsyncOpenAI(api_key=api_key)
    try:
        response = await client.responses.create(
            model=model,
            temperature=0.1,
            max_output_tokens=900,
            input=[
                {"role": "system", "content": [{"type": "text", "text": system_prompt}]},
                {"role": "user", "content": [{"type": "text", "text": user_prompt}]},
            ],
            text={
                "format": {
                    "type": "json_schema",
                    "name": "sentinelx_explanation",
                    "schema": schema,
                    "strict": True,
                }
            },
        )
        content = response.output_text if getattr(response, "output_text", None) else "{}"
        parsed = json.loads(content)
        if not isinstance(parsed, dict):
            raise ValueError("LLM returned non-object JSON")
        return _apply_guardrails(parsed, risk_result)
    except Exception:
        return _fallback_explanation(risk_result, session_features)


if __name__ == "__main__":
    import asyncio

    demo_risk = {
        "total_score": 96,
        "label": "HIGH_RISK",
        "triggered_signals": [
            {"name": "Caller Trust Index", "pts": 15},
            {"name": "Transition Velocity", "pts": 20},
            {"name": "Voice Stress Index", "pts": 18},
        ],
        "anomaly_score": 0.72,
    }
    demo_session = {
        "seconds_since_call": 18,
        "caller_trust": "UNKNOWN",
        "voice_stress_score": 0.84,
        "network_threat_score": 12,
        "confirm_dwell_ms": 1200,
        "switch_count_20s": 5,
        "tap_density": 6.2,
        "is_whatsapp_voip": True,
    }
    result = asyncio.run(explain(demo_risk, demo_session))
    print(json.dumps(result, ensure_ascii=False, indent=2))
