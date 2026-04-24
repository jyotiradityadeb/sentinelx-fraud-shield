from __future__ import annotations

import datetime
import random
import uuid

from db import supabase_client

random.seed(42)

HIGH_RISK_SUMMARIES = [
    "Vishing detected: Unknown caller 22s before ₹50,000 UPI attempt. Voice stress 0.82. 3 network reports.",
    "UPI Scam: WhatsApp VOIP call → PhonePe in 15s. Confirm tap in 1.2s. Behavioral anomaly 0.91.",
    "Impersonation suspected: Repeated unknown caller, 6 app switches in 20s. Score: 94.",
    "Voice coercion pattern: Voice stress 0.89 during call. User rushed confirm in 900ms.",
]


def _make_row(label: str, score: int, created_at: datetime.datetime, idx: int, summary: str = "") -> dict:
    threat = "UPI_SCAM" if label == "HIGH_RISK" else ("BEHAVIORAL_ANOMALY" if label == "SUSPICIOUS" else "NONE")
    return {
        "id": str(uuid.uuid4()),
        "created_at": created_at.isoformat(),
        "user_id": f"demo_seed_{idx:03d}",
        "score": score,
        "label": label,
        "threat_type": threat,
        "caller_trust": random.choice(["KNOWN_CONTACT", "BUSINESS_NUMBER", "UNKNOWN", "REPEATED_UNKNOWN"]),
        "geo_hash": "tdr1j",
        "llm_summary": summary or f"{label} session generated for demo data.",
        "llm_user_prompt": "Please verify payment intent before continuing.",
        "guardian_alerted": label == "HIGH_RISK",
        "triggered_signals": [
            {"name": "Caller Trust Index", "pts": random.randint(0, 22)},
            {"name": "Transition Velocity", "pts": random.randint(0, 20)},
        ],
        "sig_caller_trust": random.randint(0, 22),
        "sig_transition": random.randint(0, 20),
        "sig_confirm_press": random.randint(0, 20),
        "sig_behavioral": random.randint(0, 18),
        "sig_voice_stress": random.randint(0, 22),
        "sig_network": random.randint(0, 15),
    }


def seed() -> int:
    try:
        stale = supabase_client.table("sessions").delete().like("user_id", "demo_seed_%").execute()
        _ = stale
    except Exception:
        pass

    now = datetime.datetime.utcnow()
    rows: list[dict] = []
    idx = 0

    for _ in range(18):
        idx += 1
        created = now - datetime.timedelta(minutes=random.randint(0, 120))
        rows.append(_make_row("SAFE", random.randint(5, 35), created, idx))
    for _ in range(8):
        idx += 1
        created = now - datetime.timedelta(minutes=random.randint(0, 120))
        rows.append(_make_row("SUSPICIOUS", random.randint(40, 75), created, idx))
    for i in range(4):
        idx += 1
        created = now - datetime.timedelta(minutes=random.randint(0, 120))
        rows.append(_make_row("HIGH_RISK", random.randint(81, 109), created, idx, HIGH_RISK_SUMMARIES[i]))

    supabase_client.table("sessions").insert(rows).execute()
    return len(rows)


if __name__ == "__main__":
    count = seed()
    print(f"Seeded {count} sessions")
