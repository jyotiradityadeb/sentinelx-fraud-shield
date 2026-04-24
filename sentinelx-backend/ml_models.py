from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import joblib
import numpy as np
from river import anomaly
from sklearn.ensemble import IsolationForest
from sklearn.preprocessing import StandardScaler

FEATURES = [
    "seconds_since_call",
    "switch_count_20s",
    "confirm_dwell_ms",
    "tap_density",
    "revisit_count",
    "session_duration_ms",
    "caller_trust_score",
    "is_messaging_before",
    "is_whatsapp_voip",
    "voice_stress_score",
    "network_threat_score",
]

MODEL_DIR = Path("models")
DATA_PATH = Path("data/sessions.json")
ISO_MODEL_PATH = MODEL_DIR / "isolation_forest.pkl"
SCALER_PATH = MODEL_DIR / "scaler.pkl"

_iso_model: IsolationForest | None = None
_scaler: StandardScaler | None = None
_river_model = anomaly.HalfSpaceTrees(n_trees=10, height=8, window_size=50, seed=42)


def _clamp01(value: float) -> float:
    return float(max(0.0, min(1.0, value)))


def _feature_vector(row: dict[str, Any]) -> list[float]:
    values: list[float] = []
    for key in FEATURES:
        raw = row.get(key, 0.0)
        if isinstance(raw, bool):
            values.append(1.0 if raw else 0.0)
            continue
        if raw is None:
            values.append(0.0)
            continue
        try:
            values.append(float(raw))
        except (TypeError, ValueError):
            values.append(0.0)
    return values


def train_isolation_forest(data_path: str = "data/sessions.json") -> dict[str, Any]:
    path = Path(data_path)
    rows = json.loads(path.read_text(encoding="utf-8"))
    safe_rows = [row for row in rows if int(row.get("behavioral_label", 0)) == 0]
    if not safe_rows:
        raise ValueError("No safe rows found to train IsolationForest")

    x_safe = np.array([_feature_vector(row) for row in safe_rows], dtype=float)

    scaler = StandardScaler()
    x_scaled = scaler.fit_transform(x_safe)

    model = IsolationForest(contamination=0.05, n_estimators=100, random_state=42)
    model.fit(x_scaled)

    MODEL_DIR.mkdir(parents=True, exist_ok=True)
    joblib.dump(model, ISO_MODEL_PATH)
    joblib.dump(scaler, SCALER_PATH)

    global _iso_model, _scaler
    _iso_model = model
    _scaler = scaler

    return {
        "safe_rows": len(safe_rows),
        "total_rows": len(rows),
        "model_path": str(ISO_MODEL_PATH),
        "scaler_path": str(SCALER_PATH),
    }


def load_models() -> tuple[IsolationForest, StandardScaler]:
    global _iso_model, _scaler
    if _iso_model is not None and _scaler is not None:
        return _iso_model, _scaler

    if not ISO_MODEL_PATH.exists() or not SCALER_PATH.exists():
        train_isolation_forest(str(DATA_PATH))

    _iso_model = joblib.load(ISO_MODEL_PATH)
    _scaler = joblib.load(SCALER_PATH)
    return _iso_model, _scaler


def score_river(session_features: dict[str, Any]) -> float:
    vector = {feature: float(session_features.get(feature, 0.0) or 0.0) for feature in FEATURES}
    raw = float(_river_model.score_one(vector))
    _river_model.learn_one(vector)
    normalized = raw / (abs(raw) + 1.0)
    return _clamp01(normalized)


def _normalize_isoforest(decision_value: float) -> float:
    # decision_function: positive => inlier, negative => anomaly.
    anomaly_distance = -decision_value
    normalized = 1.0 / (1.0 + np.exp(-4.0 * anomaly_distance))
    return _clamp01(float(normalized))


def score_session(session_dict: dict[str, Any]) -> dict[str, Any]:
    model, scaler = load_models()
    vector = np.array([_feature_vector(session_dict)], dtype=float)
    scaled = scaler.transform(vector)

    decision_value = float(model.decision_function(scaled)[0])
    isoforest_score = _normalize_isoforest(decision_value)
    river_score = score_river(session_dict)

    anomaly_score = _clamp01(0.6 * isoforest_score + 0.4 * river_score)
    behavioral_deviation_pts = int(round(anomaly_score * 18.0))
    behavioral_deviation_pts = max(0, min(18, behavioral_deviation_pts))

    return {
        "anomaly_score": anomaly_score,
        "isoforest_score": _clamp01(isoforest_score),
        "river_score": _clamp01(river_score),
        "behavioral_deviation_pts": behavioral_deviation_pts,
    }


if __name__ == "__main__":
    print(train_isolation_forest())
    demo = {
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
    print(score_session(demo))
