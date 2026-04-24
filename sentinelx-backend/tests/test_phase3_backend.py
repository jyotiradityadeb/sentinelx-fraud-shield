from __future__ import annotations

import asyncio
import os
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parent.parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

os.environ.setdefault("SUPABASE_URL", "https://test.supabase.co")
os.environ.setdefault("SUPABASE_KEY", "test-key")
os.environ.setdefault("OPENAI_KEY", "test-openai-key")
os.environ.setdefault("HMAC_SECRET", "test-hmac-secret")

from fastapi.testclient import TestClient

from llm_explainer import _fallback_explanation
from main import app
from ml_models import score_session

client = TestClient(app)


def test_health_ok():
    response = client.get("/health")
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "healthy"


def test_demo_scam_high_risk():
    response = client.get("/demo-scam")
    assert response.status_code == 200
    body = response.json()
    assert body["total_score"] >= 80
    assert body["label"] == "HIGH_RISK"


def test_demo_safe_low_risk():
    response = client.get("/demo-safe")
    assert response.status_code == 200
    body = response.json()
    assert body["total_score"] <= 39
    assert body["label"] == "SAFE"


def test_explainer_fallback_shape():
    risk_result = {"total_score": 95, "label": "HIGH_RISK", "triggered_signals": [{"name": "Voice Stress Index", "pts": 22}]}
    session_features = {"voice_stress_score": 0.9, "is_whatsapp_voip": True, "seconds_since_call": 20}
    result = _fallback_explanation(risk_result, session_features)
    for key in ["risk_explanation", "user_prompt", "guardian_message", "dashboard_summary", "threat_type"]:
        assert key in result
    assert result["threat_type"] in {
        "VISHING",
        "UPI_SCAM",
        "IMPERSONATION",
        "VOICE_COERCION",
        "MULE_TRANSFER",
        "BEHAVIORAL_ANOMALY",
    }


def test_ml_score_shape():
    payload = {
        "seconds_since_call": 18,
        "switch_count_20s": 5,
        "confirm_dwell_ms": 1200,
        "tap_density": 6.5,
        "revisit_count": 3,
        "session_duration_ms": 19000,
        "caller_trust_score": 2,
        "is_messaging_before": 1,
        "is_whatsapp_voip": 1,
        "voice_stress_score": 0.84,
        "network_threat_score": 12,
    }
    result = score_session(payload)
    assert 0.0 <= result["anomaly_score"] <= 1.0
    assert 0.0 <= result["isoforest_score"] <= 1.0
    assert 0.0 <= result["river_score"] <= 1.0
    assert 0 <= result["behavioral_deviation_pts"] <= 18
