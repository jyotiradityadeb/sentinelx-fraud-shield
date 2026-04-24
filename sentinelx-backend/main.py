from __future__ import annotations

import datetime
from pathlib import Path
from typing import Any

from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles

import config
from db import ping_db, supabase_client as db
from llm_explainer import explain as explain_risk
from ml_models import score_session as score_behavioral_session
from models import EventBatch, GuardianNotify, SessionPayload, ThreatLookup
from threat_network import get_stats, lookup_threat, report_threat, seed_demo_threats

app = FastAPI(title="SentinelX v2 API", version="2.0.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


class ConnectionManager:
    def __init__(self) -> None:
        self.active: list[WebSocket] = []

    async def connect(self, ws: WebSocket) -> None:
        await ws.accept()
        self.active.append(ws)

    def disconnect(self, ws: WebSocket) -> None:
        if ws in self.active:
            self.active.remove(ws)

    async def broadcast(self, data: dict) -> None:
        dead: list[WebSocket] = []
        for ws in self.active:
            try:
                await ws.send_json(data)
            except Exception:
                dead.append(ws)
        for ws in dead:
            self.disconnect(ws)


manager = ConnectionManager()
DASHBOARD_DIR = Path("dashboard")

if DASHBOARD_DIR.exists():
    app.mount("/dashboard", StaticFiles(directory="dashboard"), name="dashboard")

MESSAGING_PACKAGES = {
    "com.whatsapp",
    "org.telegram.messenger",
    "com.google.android.apps.messaging",
    "com.truecaller",
}


def _safe_update_session(session_id: str, fields: dict[str, Any]) -> list[dict[str, Any]]:
    if not session_id or not fields:
        return []
    mutable = dict(fields)
    for _ in range(len(mutable)):
        try:
            updated = db.table("sessions").update(mutable).eq("id", session_id).execute()
            return updated.data or []
        except Exception as exc:
            msg = str(exc)
            missing_key = None
            for key in list(mutable.keys()):
                if key in msg:
                    missing_key = key
                    break
            if missing_key is None:
                break
            mutable.pop(missing_key, None)
            if not mutable:
                break
    return []


def _caller_trust_points(value: str) -> int:
    normalized = (value or "").upper()
    if normalized in {"KNOWN", "KNOWN_CONTACT"}:
        return 0
    if normalized in {"BUSINESS", "BUSINESS_NUMBER"}:
        return 5
    if normalized in {"SCAMMER_MARKED", "MANUAL_SCAMMER"}:
        return 30
    if normalized == "REPEATED_UNKNOWN":
        return 22
    return 15


def _transition_points(payload: dict[str, Any]) -> int:
    points = 0
    seconds_since_call = int(payload.get("seconds_since_call", 9999) or 9999)
    switch_count_20s = int(payload.get("switch_count_20s", 0) or 0)

    if seconds_since_call < 30:
        points += 16
    elif 30 <= seconds_since_call <= 60:
        points += 8

    if switch_count_20s >= 4:
        points += 12
    elif switch_count_20s >= 2:
        points += 6

    if bool(payload.get("is_messaging_before")):
        points += 8
    if bool(payload.get("is_whatsapp_voip")):
        points += 12

    return min(points, 20)


def _confirmation_pressure_points(payload: dict[str, Any]) -> int:
    points = 0
    confirm_dwell_ms = int(payload.get("confirm_dwell_ms", 14000) or 14000)
    tap_density = float(payload.get("tap_density", 1.0) or 1.0)
    revisit_count = int(payload.get("revisit_count", 0) or 0)

    if confirm_dwell_ms < 2000:
        points += 12
    elif 2000 <= confirm_dwell_ms <= 5000:
        points += 6

    if tap_density > 5:
        points += 8
    elif tap_density > 3:
        points += 4

    if revisit_count > 2:
        points += 6

    return min(points, 20)


def _voice_stress_points(payload: dict[str, Any]) -> int:
    score = float(payload.get("voice_stress_score", 0.0) or 0.0)
    if score < 0.2:
        return 0
    if score < 0.4:
        return 8
    if score < 0.6:
        return 14
    if score < 0.8:
        return 18
    return 22


def _label_from_score(total: int) -> str:
    if total >= 80:
        return "HIGH_RISK"
    if total >= 40:
        return "SUSPICIOUS"
    return "SAFE"


def _to_dict(payload: SessionPayload) -> dict[str, Any]:
    try:
        return payload.model_dump()  # pydantic v2
    except AttributeError:
        return payload.dict()


def _score_payload(payload_dict: dict[str, Any]) -> dict[str, Any]:
    ml_out = score_behavioral_session(payload_dict)
    behavioral_pts = int(ml_out.get("behavioral_deviation_pts", 0))
    behavioral_pts = max(0, min(18, behavioral_pts))

    caller_pts = _caller_trust_points(str(payload_dict.get("caller_trust", "UNKNOWN")))
    transition_pts = _transition_points(payload_dict)
    confirm_pts = _confirmation_pressure_points(payload_dict)
    voice_pts = _voice_stress_points(payload_dict)
    network_pts = max(0, min(15, int(payload_dict.get("network_threat_score", 0) or 0)))

    total = min(120, caller_pts + transition_pts + confirm_pts + behavioral_pts + voice_pts + network_pts)
    label = _label_from_score(total)

    signals = [
        {"name": "Caller Trust Index", "pts": caller_pts},
        {"name": "Transition Velocity", "pts": transition_pts},
        {"name": "Confirmation Pressure", "pts": confirm_pts},
        {"name": "Behavioral Deviation", "pts": behavioral_pts},
        {"name": "Voice Stress Index", "pts": voice_pts},
        {"name": "Network Threat Score", "pts": network_pts},
    ]
    triggered = [s for s in signals if s["pts"] > 0]

    return {
        "total_score": total,
        "score": total,
        "label": label,
        "triggered_signals": triggered,
        "sig_caller_trust": caller_pts,
        "sig_transition": transition_pts,
        "sig_confirm_press": confirm_pts,
        "sig_behavioral": behavioral_pts,
        "sig_voice_stress": voice_pts,
        "sig_network": network_pts,
        "anomaly_score": float(ml_out.get("anomaly_score", 0.0)),
        "isoforest_score": float(ml_out.get("isoforest_score", 0.0)),
        "river_score": float(ml_out.get("river_score", 0.0)),
    }


def _now_iso() -> str:
    return datetime.datetime.utcnow().isoformat()


def _decode_geohash(geohash: str) -> tuple[float, float] | None:
    base32 = "0123456789bcdefghjkmnpqrstuvwxyz"
    if not geohash:
        return None
    geohash = geohash.strip().lower()
    if not geohash:
        return None
    even_bit = True
    lat_min, lat_max = -90.0, 90.0
    lon_min, lon_max = -180.0, 180.0
    for char in geohash:
        idx = base32.find(char)
        if idx < 0:
            return None
        for mask in [16, 8, 4, 2, 1]:
            bit = (idx & mask) != 0
            if even_bit:
                mid = (lon_min + lon_max) / 2
                if bit:
                    lon_min = mid
                else:
                    lon_max = mid
            else:
                mid = (lat_min + lat_max) / 2
                if bit:
                    lat_min = mid
                else:
                    lat_max = mid
            even_bit = not even_bit
    return ((lat_min + lat_max) / 2, (lon_min + lon_max) / 2)


def _resolve_lat_lng(session_row: dict[str, Any]) -> tuple[float, float]:
    lat_raw = session_row.get("latitude")
    lng_raw = session_row.get("longitude")
    try:
        if lat_raw is not None and lng_raw is not None:
            return float(lat_raw), float(lng_raw)
    except (TypeError, ValueError):
        pass

    decoded = _decode_geohash(str(session_row.get("geo_hash", "") or ""))
    if decoded is not None:
        return decoded

    # India center fallback for map visuals.
    return (20.5937, 78.9629)


def _init_blocklist_table() -> None:
    """Ensure blocklist_numbers table exists in local store."""
    try:
        db.table("blocklist_numbers").select("*").limit(1).execute()
    except Exception:
        pass  # local store creates tables on first insert


def _get_community_blocked_numbers() -> set[str]:
    """Return numbers reported by 3+ unique reporters."""
    try:
        rows = db.table("blocklist_numbers").select("*").execute().data or []
        from collections import Counter
        counts: Counter = Counter()
        for row in rows:
            num = str(row.get("number", ""))
            reporter = str(row.get("reporter_id", ""))
            if num:
                counts[(num, reporter)] = 1
        number_reporter_counts: Counter = Counter()
        for (num, _reporter), _ in counts.items():
            number_reporter_counts[num] += 1
        return {num for num, cnt in number_reporter_counts.items() if cnt >= 3}
    except Exception as exc:
        print(f"community blocklist error: {exc}")
        return set()


def _as_live_session(session_row: dict[str, Any], result: dict[str, Any] | None = None) -> dict[str, Any]:
    result = result or {}
    caller = str(session_row.get("caller", session_row.get("caller_trust", "UNKNOWN")))
    score = int(session_row.get("score", result.get("score", 0)) or 0)
    label = str(session_row.get("label", result.get("label", "SAFE")))
    threat = str(session_row.get("threat", session_row.get("threat_type", "BEHAVIORAL_ANOMALY")))
    llm_summary = str(session_row.get("llm_summary", ""))
    timestamp = str(session_row.get("timestamp", session_row.get("created_at", _now_iso())))
    latitude, longitude = _resolve_lat_lng(session_row)
    return {
        "id": session_row.get("id"),
        "user_id": str(session_row.get("user_id", "unknown")),
        "caller": caller,
        "score": score,
        "threat": threat,
        "status": label,
        "llm_summary": llm_summary,
        "timestamp": timestamp,
        "label": label,
        "threat_type": threat,
        "caller_trust": caller,
        "created_at": timestamp,
        "geo_hash": session_row.get("geo_hash", "tdr1j"),
        "latitude": latitude,
        "longitude": longitude,
        "sig_caller_trust": int(session_row.get("sig_caller_trust", result.get("sig_caller_trust", 0)) or 0),
        "sig_transition": int(session_row.get("sig_transition", result.get("sig_transition", 0)) or 0),
        "sig_confirm_press": int(session_row.get("sig_confirm_press", result.get("sig_confirm_press", 0)) or 0),
        "sig_behavioral": int(session_row.get("sig_behavioral", result.get("sig_behavioral", 0)) or 0),
        "sig_voice_stress": int(session_row.get("sig_voice_stress", result.get("sig_voice_stress", 0)) or 0),
        "sig_network": int(session_row.get("sig_network", result.get("sig_network", 0)) or 0),
        "triggered_signals": session_row.get("triggered_signals", result.get("triggered_signals", [])),
        "anomaly_score": float(session_row.get("anomaly_score", result.get("anomaly_score", 0.0)) or 0.0),
    }


async def _broadcast_new_session(session_row: dict[str, Any], result: dict[str, Any] | None = None) -> dict[str, Any]:
    live_session = _as_live_session(session_row, result)
    print(
        f"[broadcast] type=new_session user={live_session['user_id']} score={live_session['score']} "
        f"status={live_session['status']} clients={len(manager.active)}"
    )
    await manager.broadcast({"type": "new_session", "session": live_session})
    return live_session


def _persist_session_row(session_row: dict[str, Any]) -> dict[str, Any]:
    try:
        inserted = db.table("sessions").insert(session_row).execute()
        inserted_data = inserted.data or []
        return inserted_data[0] if inserted_data else session_row
    except Exception as exc:
        print(f"[db] session insert warning: {exc}")
        return session_row


def _derive_session_payload_from_events(payload: EventBatch) -> dict[str, Any] | None:
    events = payload.events or []
    if not events:
        return None

    payment_confirm = [e for e in events if str(e.event_type).upper() == "PAYMENT_CONFIRM"]
    if not payment_confirm:
        return None

    ordered = sorted(events, key=lambda e: e.ts)
    first_ts = ordered[0].ts
    last_ts = ordered[-1].ts
    confirm_ts = payment_confirm[-1].ts
    payment_open = next((e for e in reversed(ordered) if str(e.event_type).upper() == "PAYMENT_OPEN" and e.ts <= confirm_ts), None)
    call_end = next((e for e in reversed(ordered) if str(e.event_type).upper() == "CALL_END" and e.ts <= confirm_ts), None)

    window_start = confirm_ts - 20_000
    switch_count = len({e.package_name for e in ordered if e.ts >= window_start and str(e.event_type).upper() == "WINDOW_CHANGE" and e.package_name})
    open_ts = payment_open.ts if payment_open else first_ts
    confirm_dwell = max(0, confirm_ts - open_ts)
    payment_window = max(1000, confirm_dwell)
    tap_events = [e for e in ordered if str(e.event_type).upper() == "CLICK" and open_ts <= e.ts <= confirm_ts]
    tap_density = round(len(tap_events) / (payment_window / 1000.0), 2)
    revisit_count = len([e for e in ordered if str(e.event_type).upper() == "PAYMENT_OPEN"])
    seconds_since_call = max(0, int((confirm_ts - call_end.ts) / 1000)) if call_end else 9999

    packages_before_payment = {e.package_name for e in ordered if e.ts < open_ts and e.package_name}
    is_messaging_before = any(pkg in MESSAGING_PACKAGES for pkg in packages_before_payment)
    is_whatsapp_voip = any(e.package_name == "com.whatsapp" and str(e.call_state).upper() == "OFFHOOK" for e in ordered)

    max_voice = max((float(e.voice_stress_score or 0.0) for e in ordered), default=0.0)
    max_network = max((int(e.network_threat_score or 0) for e in ordered), default=0)
    last = ordered[-1]

    return {
        "session_id": payload.session_id,
        "user_id": payload.user_id,
        "seconds_since_call": seconds_since_call,
        "switch_count_20s": switch_count,
        "confirm_dwell_ms": confirm_dwell,
        "tap_density": tap_density,
        "revisit_count": revisit_count,
        "session_duration_ms": max(0, last_ts - first_ts),
        "caller_trust": str(last.caller_trust or "UNKNOWN"),
        "is_messaging_before": is_messaging_before,
        "is_whatsapp_voip": is_whatsapp_voip,
        "voice_stress_score": max_voice,
        "network_threat_score": max_network,
        "geo_hash": str(last.geo_hash or "tdr1j"),
    }


@app.on_event("startup")
async def startup_event() -> None:
    print(f"SentinelX API ready - http://0.0.0.0:{config.PORT}")
    try:
        seed_demo_threats(200)
    except Exception as exc:
        print(f"Threat seed skipped: {exc}")
    _init_blocklist_table()


@app.get("/")
async def root():
    index = DASHBOARD_DIR / "index.html"
    if index.exists():
        return FileResponse(index)
    return JSONResponse({"status": "ok", "message": "SentinelX API running. Dashboard not found."})


@app.get("/health")
async def health():
    return {"status": "healthy", "version": "2.0.0", "db_ok": await ping_db()}


@app.post("/events")
async def events(payload: EventBatch):
    print(f"[/events] received session_id={payload.session_id} user_id={payload.user_id} events={len(payload.events)}")
    rows = []
    for event in payload.events:
        rows.append(
            {
                "session_id": payload.session_id,
                "ts": event.ts,
                "event_type": event.event_type,
                "package_name": event.package_name,
                "screen_name": event.screen_name,
                "call_state": event.call_state,
                "caller_trust": event.caller_trust,
                "interaction_type": event.interaction_type,
                "dwell_ms": event.dwell_ms,
                "voice_stress_score": event.voice_stress_score,
                "network_threat_score": event.network_threat_score,
                "geo_hash": event.geo_hash,
            }
        )
    try:
        if rows:
            db.table("events").insert(rows).execute()
            print(f"[/events] inserted rows={len(rows)}")
    except Exception as exc:
        print(f"/events insert warning: {exc}")
        return {"status": "ok", "count": len(rows), "warning": "db_insert_failed"}

    try:
        derived = _derive_session_payload_from_events(payload)
        if derived is not None:
            print(f"[/events] derived scorable session payload for session_id={payload.session_id}")
            result = _score_payload(derived)
            session_row = {
                "user_id": derived.get("user_id", "unknown"),
                "caller": derived.get("caller_trust", "UNKNOWN"),
                "score": result["score"],
                "label": result["label"],
                "threat_type": "PENDING_LLM",
                "caller_trust": derived.get("caller_trust", "UNKNOWN"),
                "geo_hash": derived.get("geo_hash", "tdr1j"),
                "triggered_signals": result["triggered_signals"],
                "sig_caller_trust": result["sig_caller_trust"],
                "sig_transition": result["sig_transition"],
                "sig_confirm_press": result["sig_confirm_press"],
                "sig_behavioral": result["sig_behavioral"],
                "sig_voice_stress": result["sig_voice_stress"],
                "sig_network": result["sig_network"],
                "anomaly_score": result["anomaly_score"],
                "created_at": _now_iso(),
                "timestamp": _now_iso(),
            }
            inserted = _persist_session_row(session_row)
            await _broadcast_new_session(inserted, result)
            return {
                "status": "ok",
                "count": len(rows),
                "auto_scored": True,
                "score": result["score"],
                "label": result["label"],
            }
    except Exception as exc:
        print(f"[/events] auto-score warning: {exc}")
        return {"status": "ok", "count": len(rows), "warning": "auto_score_failed"}

    return {"status": "ok", "count": len(rows), "auto_scored": False}


@app.post("/score-session")
async def score_session(payload: SessionPayload):
    payload_dict = _to_dict(payload)
    print(f"[/score-session] payload session_id={payload_dict.get('session_id')} user_id={payload_dict.get('user_id')}")

    # Force HIGH_RISK if the caller number appears in the community blocklist
    caller_number = str(payload_dict.get("caller_trust", "") or "")
    community_numbers = _get_community_blocked_numbers()
    caller_digits = "".join(c for c in caller_number if c.isdigit())
    if caller_digits and caller_digits in community_numbers:
        print(f"[/score-session] community blocklist hit for {caller_digits}")
        payload_dict["caller_trust"] = "SCAMMER_MARKED"

    result = _score_payload(payload_dict)
    print(
        f"[/score-session] scored user={payload_dict.get('user_id')} score={result['score']} label={result['label']} "
        f"signals={len(result['triggered_signals'])}"
    )

    session_row = {
        "user_id": payload_dict.get("user_id", "unknown"),
        "caller": payload_dict.get("caller_trust", "UNKNOWN"),
        "score": result["score"],
        "label": result["label"],
        "threat_type": "PENDING_LLM",
        "caller_trust": payload_dict.get("caller_trust", "UNKNOWN"),
        "geo_hash": payload_dict.get("geo_hash", "tdr1j"),
        "triggered_signals": result["triggered_signals"],
        "sig_caller_trust": result["sig_caller_trust"],
        "sig_transition": result["sig_transition"],
        "sig_confirm_press": result["sig_confirm_press"],
        "sig_behavioral": result["sig_behavioral"],
        "sig_voice_stress": result["sig_voice_stress"],
        "sig_network": result["sig_network"],
        "anomaly_score": result["anomaly_score"],
        "created_at": _now_iso(),
        "timestamp": _now_iso(),
    }
    inserted_row = _persist_session_row(session_row)
    live_session = await _broadcast_new_session(inserted_row, result)
    response = dict(result)
    response["session_id"] = live_session.get("id", payload_dict.get("session_id"))
    return response


@app.post("/explain")
async def explain(payload: dict):
    risk_result = payload.get("risk_result", {}) or {}
    session_features = payload.get("session_features", {}) or {}
    print(f"[/explain] request session_id={risk_result.get('session_id') or session_features.get('session_id')}")
    explanation = await explain_risk(risk_result, session_features)

    session_id = risk_result.get("session_id") or session_features.get("session_id")
    if session_id:
        update_doc = {
            "llm_summary": explanation.get("dashboard_summary", explanation.get("risk_explanation", "")),
            "llm_user_prompt": explanation.get("user_prompt", ""),
            "threat_type": explanation.get("threat_type", "BEHAVIORAL_ANOMALY"),
            "recommended_action": explanation.get("recommended_action", "WARN"),
            "fraud_likelihood": explanation.get("fraud_likelihood", "MEDIUM"),
        }
        try:
            updated_rows = _safe_update_session(str(session_id), update_doc)
            if updated_rows:
                print(f"[/explain] updated session={session_id} threat={explanation.get('threat_type')}")
                await manager.broadcast(
                    {
                        "type": "session_updated",
                        "session_id": session_id,
                        "threat_type": explanation.get("threat_type", "BEHAVIORAL_ANOMALY"),
                        "llm_summary": explanation.get("dashboard_summary", ""),
                        "llm_user_prompt": explanation.get("user_prompt", ""),
                    }
                )
        except Exception as exc:
            print(f"/explain update warning: {exc}")

    return explanation


@app.get("/sessions")
async def sessions():
    try:
        res = db.table("sessions").select("*").order("created_at", desc=True).limit(50).execute()
        return {"status": "ok", "route": "/sessions", "items": res.data or []}
    except Exception as exc:
        print(f"/sessions warning: {exc}")
        return {"status": "ok", "route": "/sessions", "items": []}


@app.websocket("/live")
async def live(ws: WebSocket):
    await manager.connect(ws)
    print(f"[/live] client connected active_clients={len(manager.active)}")
    try:
        try:
            init = db.table("sessions").select("*").order("created_at", desc=True).limit(10).execute()
            sessions = init.data or []
            normalized = [_as_live_session(s) for s in sessions]
            await ws.send_json({"type": "initial_sessions", "sessions": normalized})
            print(f"[/live] sent initial_sessions={len(normalized)}")
        except Exception as exc:
            await ws.send_json({"type": "initial_sessions", "sessions": [], "warning": str(exc)})

        while True:
            _ = await ws.receive_text()
            await ws.send_json({"type": "pong"})
    except WebSocketDisconnect:
        manager.disconnect(ws)
        print(f"[/live] client disconnected active_clients={len(manager.active)}")


@app.post("/threat-lookup")
async def threat_lookup_endpoint(payload: ThreatLookup):
    result = lookup_threat(payload.number_hash)
    if result is None:
        return {
            "found": False,
            "report_count": 0,
            "threat_types": [],
            "avg_score": 0.0,
            "network_score": 0,
        }
    return {"found": True, **result}


@app.post("/report-threat")
async def report_threat_endpoint(payload: dict):
    try:
        ok = report_threat(
            payload["number_hash"],
            payload["threat_type"],
            float(payload["score"]),
        )
    except Exception:
        return {"status": "error"}
    return {"status": "reported" if ok else "error"}


@app.get("/threat-stats")
async def threat_stats():
    return get_stats()


@app.get("/community-blocklist")
async def community_blocklist():
    numbers = list(_get_community_blocked_numbers())
    return {"numbers": numbers, "count": len(numbers), "updated_at": _now_iso()}


@app.post("/report-number")
async def report_number(payload: dict):
    number = str(payload.get("number", "")).strip()
    reporter_id = str(payload.get("reporter_id", "unknown")).strip()
    reason = str(payload.get("reason", "MANUAL")).strip()
    if not number:
        return {"status": "error", "detail": "number required"}
    try:
        db.table("blocklist_numbers").insert({
            "number": number,
            "reporter_id": reporter_id,
            "reason": reason,
            "reported_at": _now_iso(),
        }).execute()
    except Exception as exc:
        print(f"/report-number insert warning: {exc}")
    try:
        rows = db.table("blocklist_numbers").select("*").execute().data or []
        total = sum(1 for r in rows if str(r.get("number", "")) == number)
        community_blocked = total >= 3
    except Exception:
        total = 1
        community_blocked = False
    return {"status": "ok", "total_reports": total, "community_blocked": community_blocked}


@app.post("/analyze-number")
async def analyze_number(payload: dict):
    number = str(payload.get("number", "")).strip()
    if not number:
        return {"risk_level": "UNKNOWN", "explanation": "No number provided.", "suggest_block": False}

    community_blocked = number in _get_community_blocked_numbers()
    if community_blocked:
        return {
            "risk_level": "HIGH",
            "explanation": f"{number} has been reported by multiple users as a scam number.",
            "suggest_block": True,
        }

    # Heuristic analysis based on number patterns
    digits = "".join(c for c in number if c.isdigit())
    risk_level = "LOW"
    explanation = "No known risk signals for this number."
    suggest_block = False

    if len(digits) < 7:
        risk_level = "MEDIUM"
        explanation = "Unusually short number — may be a spoofed or masked caller ID."
        suggest_block = False
    elif digits.startswith(("140", "141", "142", "160")):
        risk_level = "HIGH"
        explanation = "Number prefix matches known telemarketing/scam ranges in India."
        suggest_block = True
    elif digits == digits[0] * len(digits):
        risk_level = "HIGH"
        explanation = "Repeated-digit number often associated with spoofed caller IDs."
        suggest_block = True

    return {"risk_level": risk_level, "explanation": explanation, "suggest_block": suggest_block}


@app.post("/notify-guardian")
async def notify_guardian(payload: GuardianNotify):
    try:
        db.table("sessions").update({"guardian_sms_sent": True}).eq(
            "user_id", payload.user_id
        ).order("created_at", desc=True).limit(1).execute()
    except Exception as exc:
        print(f"guardian log warning: {exc}")
    await manager.broadcast(
        {
            "type": "guardian_alerted",
            "user_id": payload.user_id,
            "score": payload.score,
            "threat_type": payload.threat_type,
            "summary": payload.llm_summary,
        }
    )
    return {"status": "logged"}


@app.post("/demo-phone-session")
async def demo_phone_session():
    payload = {
        "session_id": "demo_phone_" + _now_iso(),
        "user_id": "phone_demo_user",
        "seconds_since_call": 18,
        "switch_count_20s": 5,
        "confirm_dwell_ms": 1200,
        "tap_density": 6.4,
        "revisit_count": 3,
        "session_duration_ms": 21000,
        "caller_trust": "UNKNOWN",
        "is_messaging_before": True,
        "is_whatsapp_voip": True,
        "voice_stress_score": 0.82,
        "network_threat_score": 11,
        "geo_hash": "tdr1j",
    }
    result = _score_payload(payload)
    session_row = {
        "user_id": payload["user_id"],
        "caller": payload["caller_trust"],
        "score": result["score"],
        "label": result["label"],
        "threat_type": "VISHING",
        "caller_trust": payload["caller_trust"],
        "geo_hash": payload["geo_hash"],
        "triggered_signals": result["triggered_signals"],
        "sig_caller_trust": result["sig_caller_trust"],
        "sig_transition": result["sig_transition"],
        "sig_confirm_press": result["sig_confirm_press"],
        "sig_behavioral": result["sig_behavioral"],
        "sig_voice_stress": result["sig_voice_stress"],
        "sig_network": result["sig_network"],
        "anomaly_score": result["anomaly_score"],
        "llm_summary": "Possible scam pattern detected from real-time call-to-payment behavior.",
        "created_at": _now_iso(),
        "timestamp": _now_iso(),
    }
    inserted = _persist_session_row(session_row)
    live = await _broadcast_new_session(inserted, result)
    return {"status": "ok", "session": live}


@app.get("/demo-inject")
async def demo_inject():
    return await demo_phone_session()


# Demo-only judge backup endpoints.
@app.get("/demo-scam")
async def demo_scam():
    payload = {
        "seconds_since_call": 18,
        "switch_count_20s": 5,
        "confirm_dwell_ms": 1200,
        "tap_density": 6.5,
        "revisit_count": 3,
        "session_duration_ms": 19000,
        "caller_trust": "UNKNOWN",
        "is_messaging_before": True,
        "is_whatsapp_voip": True,
        "voice_stress_score": 0.84,
        "network_threat_score": 12,
        "geo_hash": "tdr1j",
    }
    return _score_payload(payload)


# Demo-only judge backup endpoints.
@app.get("/demo-safe")
async def demo_safe():
    payload = {
        "seconds_since_call": 9999,
        "switch_count_20s": 1,
        "confirm_dwell_ms": 14000,
        "tap_density": 1.0,
        "revisit_count": 0,
        "session_duration_ms": 60000,
        "caller_trust": "KNOWN",
        "is_messaging_before": False,
        "is_whatsapp_voip": False,
        "voice_stress_score": 0.08,
        "network_threat_score": 0,
        "geo_hash": "tdr1j",
    }
    return _score_payload(payload)
