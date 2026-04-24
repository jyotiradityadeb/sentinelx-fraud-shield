from __future__ import annotations

import datetime
import random
import uuid

from db import supabase_client

random.seed(42)

GEO_HASHES = ["tdr1j", "te7u1", "ttngf", "tepg6", "tf32s"]

HIGH_RISK_SUMMARIES = [
    "Vishing detected: Unknown caller 22s before ₹50,000 UPI attempt. Voice stress 0.82. 3 network reports.",
    "UPI Scam: WhatsApp VOIP call → PhonePe in 15s. Confirm tap in 1.2s. Behavioral anomaly 0.91.",
    "Impersonation suspected: Repeated unknown caller, 6 app switches in 20s. Score: 94.",
    "Voice coercion pattern: Voice stress 0.89 during call. User rushed confirm in 900ms.",
]

HIGH_RISK_SIGNALS = [
    {"name": "Caller Trust Index", "pts": 22},
    {"name": "Transition Velocity", "pts": 18},
    {"name": "Confirmation Pressure", "pts": 16},
    {"name": "Voice Stress Index", "pts": 18},
    {"name": "Network Threat Score", "pts": 10},
]


def _build_row(idx: int, label: str, score: int, created_at: datetime.datetime, summary: str = "") -> dict:
    if label == "SAFE":
        threat_type = "BEHAVIORAL_ANOMALY"
    elif label == "SUSPICIOUS":
        threat_type = random.choice(["VISHING", "UPI_SCAM", "IMPERSONATION"])
    else:
        threat_type = random.choice(["VISHING", "UPI_SCAM", "IMPERSONATION", "VOICE_COERCION"])

    triggered = (
        HIGH_RISK_SIGNALS
        if label == "HIGH_RISK"
        else [
            {"name": "Caller Trust Index", "pts": random.randint(0, 15)},
            {"name": "Transition Velocity", "pts": random.randint(0, 12)},
        ]
    )

    return {
        "id": str(uuid.uuid4()),
        "created_at": created_at.isoformat(),
        "user_id": f"demo_seed_{idx:03d}",
        "score": score,
        "label": label,
        "threat_type": threat_type,
        "caller_trust": random.choice(["KNOWN_CONTACT", "BUSINESS_NUMBER", "UNKNOWN", "REPEATED_UNKNOWN"]),
        "geo_hash": random.choice(GEO_HASHES),
        "llm_summary": summary or f"{label} pattern detected by SentinelX behavior model.",
        "llm_user_prompt": "Please verify beneficiary and payment request before proceeding.",
        "guardian_alerted": label == "HIGH_RISK",
        "triggered_signals": triggered,
        "sig_caller_trust": random.randint(0, 22),
        "sig_transition": random.randint(0, 20),
        "sig_confirm_press": random.randint(0, 20),
        "sig_behavioral": random.randint(0, 18),
        "sig_voice_stress": random.randint(0, 22),
        "sig_network": random.randint(0, 15),
    }


def _has_recent_seed() -> bool:
    try:
        res = (
            supabase_client.table("sessions")
            .select("created_at,user_id")
            .like("user_id", "demo_seed_%")
            .order("created_at", desc=True)
            .limit(1)
            .execute()
        )
        rows = res.data or []
        if not rows:
            return False
        latest = rows[0].get("created_at")
        if not latest:
            return False
        latest_ts = datetime.datetime.fromisoformat(str(latest).replace("Z", "+00:00"))
        now = datetime.datetime.now(datetime.timezone.utc)
        return (now - latest_ts).total_seconds() < 600
    except Exception:
        return False


def seed_dashboard() -> int:
    if _has_recent_seed():
        print("Recent demo_seed_ rows detected within 10 minutes. Skipping.")
        return 0

    now = datetime.datetime.now(datetime.timezone.utc)
    rows: list[dict] = []
    idx = 0

    for _ in range(18):
        idx += 1
        created = now - datetime.timedelta(minutes=random.randint(0, 120), seconds=random.randint(0, 59))
        rows.append(_build_row(idx, "SAFE", random.randint(5, 35), created))

    for _ in range(8):
        idx += 1
        created = now - datetime.timedelta(minutes=random.randint(0, 120), seconds=random.randint(0, 59))
        rows.append(_build_row(idx, "SUSPICIOUS", random.randint(40, 75), created))

    for i in range(4):
        idx += 1
        created = now - datetime.timedelta(minutes=random.randint(0, 120), seconds=random.randint(0, 59))
        rows.append(_build_row(idx, "HIGH_RISK", random.randint(81, 109), created, HIGH_RISK_SUMMARIES[i]))

    print(f"Preparing to seed {len(rows)} rows...")
    supabase_client.table("sessions").insert(rows).execute()
    print(f"Seeded {len(rows)} sessions")
    return len(rows)


if __name__ == "__main__":
    seed_dashboard()
