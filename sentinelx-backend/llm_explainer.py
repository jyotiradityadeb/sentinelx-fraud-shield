from __future__ import annotations

# Example output (HIGH_RISK):
# {"risk_explanation":"Rapid post-call payment behavior with multiple pressure indicators.","user_prompt":"This payment looks risky due to unusual urgency and caller context. Please pause and verify independently before continuing.","guardian_message":"SentinelX detected a high-risk payment pattern and paused action. Please call the user now to verify the request before any transfer.","dashboard_summary":"High-risk scoring triggered by caller trust, fast transition, high tap density, and elevated voice stress.","threat_type":"UPI_SCAM"}
#
# Example output (SUSPICIOUS):
# {"risk_explanation":"Behavior deviates from baseline with moderate stress indicators.","user_prompt":"We detected unusual payment behavior. Please confirm the recipient and reason before you proceed.","guardian_message":"SentinelX flagged suspicious payment behavior. Please check in with the user if possible.","dashboard_summary":"Suspicious pattern with moderate anomaly and caller uncertainty.","threat_type":"BEHAVIORAL_ANOMALY"}
#
# Example output (SAFE):
# {"risk_explanation":"Session appears consistent with normal behavior.","user_prompt":"No major risk signals detected. Continue carefully and verify recipient details.","guardian_message":"No emergency alert. Session currently appears safe.","dashboard_summary":"Low-risk session with normal interaction cadence.","threat_type":"BEHAVIORAL_ANOMALY"}

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


def _word_limit(text: str, max_words: int) -> str:
    words = text.split()
    if len(words) <= max_words:
        return text.strip()
    return " ".join(words[:max_words]).strip()


def _choose_threat_type(risk_result: dict[str, Any], session_features: dict[str, Any]) -> str:
    label = str(risk_result.get("label", "")).upper()
    triggered = risk_result.get("triggered_signals", []) or []
    signal_names = " ".join(str(item.get("name", "")) for item in triggered if isinstance(item, dict)).lower()

    if bool(session_features.get("is_whatsapp_voip")) and session_features.get("seconds_since_call", 9999) < 60:
        return "VISHING"
    if session_features.get("network_threat_score", 0) >= 10:
        return "MULE_TRANSFER"
    if session_features.get("voice_stress_score", 0.0) >= 0.8:
        return "VOICE_COERCION"
    if "caller trust" in signal_names and str(session_features.get("caller_trust", "")).upper() in {"UNKNOWN", "REPEATED_UNKNOWN"}:
        return "IMPERSONATION"
    if "transition velocity" in signal_names or "confirmation pressure" in signal_names:
        return "UPI_SCAM"
    if label == "SAFE":
        return "BEHAVIORAL_ANOMALY"
    return "BEHAVIORAL_ANOMALY"


def _fallback_explanation(risk_result: dict[str, Any], session_features: dict[str, Any]) -> dict[str, str]:
    score = int(risk_result.get("total_score", risk_result.get("score", 0)) or 0)
    label = str(risk_result.get("label", "SAFE"))
    threat_type = _choose_threat_type(risk_result, session_features)

    risk_explanation = (
        f"Score {score}/120 labeled {label}. Signals indicate caller/context pressure "
        f"with behavioral deviation and session urgency."
    )
    user_prompt = _word_limit(
        "This payment may be unsafe due to unusual urgency and behavior. "
        "Pause now, verify the caller independently, and confirm recipient details before continuing.",
        45,
    )
    guardian_message = _word_limit(
        "SentinelX flagged a high-risk payment pattern with scam indicators. "
        "Please call immediately to verify the request before any transfer is completed.",
        60,
    )
    dashboard_summary = _word_limit(
        "Fallback explanation used. Session flagged using risk score, behavioral deviation, "
        "voice stress, and network context signals for operator review.",
        80,
    )

    return {
        "risk_explanation": risk_explanation,
        "user_prompt": user_prompt,
        "guardian_message": guardian_message,
        "dashboard_summary": dashboard_summary,
        "threat_type": threat_type if threat_type in ALLOWED_THREAT_TYPES else "BEHAVIORAL_ANOMALY",
    }


async def explain(risk_result: dict, session_features: dict) -> dict:
    cfg_key = ""
    if config is not None:
        cfg_key = str(getattr(config, "OPENAI_KEY", "") or "")
    api_key = (os.getenv("OPENAI_KEY", "") or cfg_key).strip()
    if not api_key or "placeholder" in api_key.lower():
        return _fallback_explanation(risk_result, session_features)

    try:
        from openai import AsyncOpenAI
    except Exception:
        return _fallback_explanation(risk_result, session_features)

    schema = {
        "name": "sentinelx_explanation",
        "strict": True,
        "schema": {
            "type": "object",
            "additionalProperties": False,
            "properties": {
                "risk_explanation": {"type": "string"},
                "user_prompt": {"type": "string"},
                "guardian_message": {"type": "string"},
                "dashboard_summary": {"type": "string"},
                "threat_type": {
                    "type": "string",
                    "enum": sorted(ALLOWED_THREAT_TYPES),
                },
            },
            "required": [
                "risk_explanation",
                "user_prompt",
                "guardian_message",
                "dashboard_summary",
                "threat_type",
            ],
        },
    }

    system_prompt = (
        "You are SentinelX, a real-time fraud prevention AI embedded in a payment app. "
        "You analyze behavioral signals to detect scam-induced payments such as vishing, UPI fraud, "
        "impersonation, voice coercion, and mule transfers. You never claim fraud definitively. "
        "You ask the user to verify. You speak calmly in simple language because the user may be distressed. "
        "Keep user-facing text under 45 words. Be specific about observed signals."
    )

    user_prompt = (
        f"risk_result={json.dumps(risk_result, ensure_ascii=False)}\n"
        f"session_features={json.dumps(session_features, ensure_ascii=False)}\n"
        "Return valid JSON only."
    )

    client = AsyncOpenAI(api_key=api_key)
    try:
        response = await client.responses.create(
            model="gpt-4o",
            temperature=0.3,
            max_output_tokens=500,
            input=[
                {"role": "system", "content": [{"type": "text", "text": system_prompt}]},
                {"role": "user", "content": [{"type": "text", "text": user_prompt}]},
            ],
            text={"format": {"type": "json_schema", "name": schema["name"], "schema": schema["schema"], "strict": True}},
        )

        text = ""
        if hasattr(response, "output_text") and response.output_text:
            text = response.output_text
        else:
            text = str(response)

        parsed = json.loads(text)
        if not isinstance(parsed, dict):
            raise ValueError("Non-dict JSON output")

        if parsed.get("threat_type") not in ALLOWED_THREAT_TYPES:
            parsed["threat_type"] = _choose_threat_type(risk_result, session_features)

        parsed["user_prompt"] = _word_limit(str(parsed.get("user_prompt", "")), 45)
        parsed["guardian_message"] = _word_limit(str(parsed.get("guardian_message", "")), 60)
        parsed["dashboard_summary"] = _word_limit(str(parsed.get("dashboard_summary", "")), 80)
        parsed["risk_explanation"] = str(parsed.get("risk_explanation", "")).strip()
        return parsed
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
    }
    demo_session = {
        "seconds_since_call": 18,
        "caller_trust": "UNKNOWN",
        "voice_stress_score": 0.84,
        "network_threat_score": 12,
        "confirm_dwell_ms": 1200,
        "switch_count_20s": 5,
        "is_whatsapp_voip": True,
    }
    result = asyncio.run(explain(demo_risk, demo_session))
    print(json.dumps(result, ensure_ascii=False, indent=2))
