# PRIVACY DESIGN: Raw phone numbers are NEVER stored in this database.
# Numbers are hashed with HMAC-SHA256 + daily rotating key.
# Key = HMAC_SECRET + YYYYMMDD - rotates automatically each midnight.
# Even if the database is breached, phone numbers cannot be recovered.
# DPDP Act (India) and GDPR compliant by design.

from __future__ import annotations

import datetime
import hashlib
import hmac
import random
from collections import Counter

from config import HMAC_SECRET
from db import supabase_client as db

THREAT_TYPES = ["VISHING", "UPI_SCAM", "IMPERSONATION", "MULE_TRANSFER", "VOICE_COERCION"]


def get_daily_key() -> bytes:
    date_str = datetime.datetime.utcnow().strftime("%Y%m%d")
    key_material = f"{HMAC_SECRET}:{date_str}"
    return hashlib.sha256(key_material.encode()).digest()


def hash_number(phone: str) -> str:
    normalized = "".join(ch for ch in phone if ch.isdigit())
    if normalized.startswith("91") and len(normalized) == 12:
        normalized = normalized[2:]
    if normalized.startswith("0") and len(normalized) == 11:
        normalized = normalized[1:]
    return hmac.new(get_daily_key(), normalized.encode(), hashlib.sha256).hexdigest()


def _network_score(report_count: int) -> int:
    if report_count <= 1:
        return 5
    if report_count == 2:
        return 8
    if report_count <= 4:
        return 10
    return 15


def lookup_threat(number_hash: str) -> dict | None:
    result = db.table("threat_network").select("*").eq("number_hash", number_hash).limit(1).execute()
    rows = result.data or []
    if not rows:
        return None

    row = rows[0]
    report_count = int(row.get("report_count", 0))
    return {
        "report_count": report_count,
        "threat_types": row.get("threat_types", []),
        "avg_score": float(row.get("avg_score", 0.0)),
        "last_seen": row.get("last_seen"),
        "network_score": _network_score(report_count),
    }


def report_threat(number_hash: str, threat_type: str, score: float) -> bool:
    existing = lookup_threat(number_hash)
    now = datetime.datetime.utcnow().isoformat()
    if existing:
        current_count = existing["report_count"]
        new_count = current_count + 1
        new_avg = (existing["avg_score"] * current_count + score) / new_count
        new_types = sorted(set(existing["threat_types"] + [threat_type]))
        db.table("threat_network").update(
            {
                "report_count": new_count,
                "avg_score": round(new_avg, 2),
                "threat_types": new_types,
                "last_seen": now,
            }
        ).eq("number_hash", number_hash).execute()
    else:
        db.table("threat_network").insert(
            {
                "number_hash": number_hash,
                "report_count": 1,
                "threat_types": [threat_type],
                "avg_score": score,
                "last_seen": now,
            }
        ).execute()
    return True


def _random_report_count() -> int:
    roll = random.random()
    if roll < 0.4:
        return 1
    if roll < 0.7:
        return random.randint(2, 3)
    if roll < 0.9:
        return random.randint(4, 6)
    return random.randint(7, 12)


def seed_demo_threats(count: int = 200) -> int:
    existing = db.table("threat_network").select("number_hash", count="exact").limit(1).execute()
    existing_count = existing.count or 0
    if existing_count >= 150:
        print("Already seeded, skipping")
        return 0

    rows: list[dict] = []
    now = datetime.datetime.utcnow()
    for _ in range(count):
        report_count = _random_report_count()
        type_count = random.randint(1, 2)
        last_seen = now - datetime.timedelta(days=random.randint(0, 30), hours=random.randint(0, 23))
        rows.append(
            {
                "number_hash": "".join(random.choices("0123456789abcdef", k=64)),
                "report_count": report_count,
                "threat_types": random.sample(THREAT_TYPES, k=type_count),
                "avg_score": round(random.uniform(55.0, 95.0), 1),
                "first_seen": (last_seen - datetime.timedelta(days=random.randint(0, 30))).isoformat(),
                "last_seen": last_seen.isoformat(),
            }
        )

    inserted = 0
    for i in range(0, len(rows), 50):
        chunk = rows[i : i + 50]
        db.table("threat_network").insert(chunk).execute()
        inserted += len(chunk)
    return inserted


def get_stats() -> dict:
    result = db.table("threat_network").select("report_count, threat_types").execute()
    rows = result.data or []
    counter: Counter[str] = Counter()
    total_reports = 0
    for row in rows:
        total_reports += int(row.get("report_count", 0))
        for threat_type in row.get("threat_types", []) or []:
            counter[threat_type] += 1

    return {
        "total_numbers": len(rows),
        "total_reports": total_reports,
        "by_threat_type": dict(counter),
    }


if __name__ == "__main__":
    print("Seeding demo threat data...")
    n = seed_demo_threats(200)
    print(f"Seeded {n} entries")
    print(f"Stats: {get_stats()}")
