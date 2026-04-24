from __future__ import annotations

import json
import random
import statistics
from pathlib import Path

random.seed(42)

DATA_PATH = Path(__file__).resolve().parent / "sessions.json"


def _safe_row() -> dict:
    return {
        "seconds_since_call": random.randint(120, 900) if random.random() < 0.75 else 9999,
        "switch_count_20s": random.randint(0, 2),
        "confirm_dwell_ms": random.randint(8000, 20000),
        "tap_density": round(random.uniform(0.4, 2.0), 2),
        "revisit_count": random.randint(0, 1),
        "session_duration_ms": random.randint(30000, 120000),
        "caller_trust_score": random.choices([0, 1, 2], weights=[70, 20, 10], k=1)[0],
        "is_messaging_before": 1 if random.random() < 0.10 else 0,
        "is_whatsapp_voip": 1 if random.random() < 0.03 else 0,
        "voice_stress_score": round(random.uniform(0.03, 0.30), 3),
        "network_threat_score": 0 if random.random() < 0.90 else random.randint(1, 4),
        "behavioral_label": 0,
    }


def _scam_row() -> dict:
    threat_score = random.randint(6, 15) if random.random() < 0.60 else random.randint(0, 5)
    return {
        "seconds_since_call": random.randint(5, 45),
        "switch_count_20s": random.randint(3, 7),
        "confirm_dwell_ms": random.randint(700, 3500),
        "tap_density": round(random.uniform(4.0, 9.0), 2),
        "revisit_count": random.randint(1, 4),
        "session_duration_ms": random.randint(8000, 35000),
        "caller_trust_score": random.choices([2, 3, 1], weights=[80, 15, 5], k=1)[0],
        "is_messaging_before": 1 if random.random() < 0.70 else 0,
        "is_whatsapp_voip": 1 if random.random() < 0.40 else 0,
        "voice_stress_score": round(random.uniform(0.45, 0.95), 3),
        "network_threat_score": threat_score,
        "behavioral_label": 1,
    }


def _mutate_with_noise(row: dict, target: str) -> None:
    if target == "safe":
        row["seconds_since_call"] = random.randint(8, 50)
        row["switch_count_20s"] = random.randint(3, 6)
        row["confirm_dwell_ms"] = random.randint(800, 3000)
        row["tap_density"] = round(random.uniform(3.5, 7.5), 2)
        row["voice_stress_score"] = round(random.uniform(0.45, 0.90), 3)
    else:
        row["seconds_since_call"] = random.randint(180, 9999)
        row["switch_count_20s"] = random.randint(0, 2)
        row["confirm_dwell_ms"] = random.randint(7000, 18000)
        row["tap_density"] = round(random.uniform(0.6, 2.0), 2)
        row["voice_stress_score"] = round(random.uniform(0.04, 0.25), 3)


def generate_sessions() -> list[dict]:
    safe_rows = [_safe_row() for _ in range(500)]
    scam_rows = [_scam_row() for _ in range(300)]

    safe_noise_count = max(1, int(len(safe_rows) * 0.05))
    scam_noise_count = max(1, int(len(scam_rows) * 0.05))

    for idx in random.sample(range(len(safe_rows)), safe_noise_count):
        for _ in range(random.randint(1, 2)):
            _mutate_with_noise(safe_rows[idx], target="safe")

    for idx in random.sample(range(len(scam_rows)), scam_noise_count):
        for _ in range(random.randint(1, 2)):
            _mutate_with_noise(scam_rows[idx], target="scam")

    rows = safe_rows + scam_rows
    random.shuffle(rows)
    return rows


def _summary(rows: list[dict]) -> dict:
    safe = [r for r in rows if r["behavioral_label"] == 0]
    scam = [r for r in rows if r["behavioral_label"] == 1]
    return {
        "rows": len(rows),
        "safe": len(safe),
        "scam": len(scam),
        "avg_tap_density": round(statistics.mean(r["tap_density"] for r in rows), 3),
        "avg_voice_stress": round(statistics.mean(r["voice_stress_score"] for r in rows), 3),
    }


if __name__ == "__main__":
    sessions = generate_sessions()
    DATA_PATH.parent.mkdir(parents=True, exist_ok=True)
    DATA_PATH.write_text(json.dumps(sessions, ensure_ascii=False, indent=2), encoding="utf-8")
    print("Generated sessions.json")
    print(_summary(sessions))
