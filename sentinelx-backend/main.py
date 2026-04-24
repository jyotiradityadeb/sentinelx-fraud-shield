from __future__ import annotations

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


def _caller_trust_points(value: str) -> int:
    normalized = (value or "").upper()
    if normalized in {"KNOWN", "KNOWN_CONTACT"}:
        return 0
    if normalized in {"BUSINESS", "BUSINESS_NUMBER"}:
        return 5
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


@app.on_event("startup")
async def startup_event() -> None:
    print(f"SentinelX API ready - http://0.0.0.0:{config.PORT}")
    try:
        seed_demo_threats(200)
    except Exception as exc:
        print(f"Threat seed skipped: {exc}")


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
        return {"status": "ok", "count": len(rows)}
    except Exception as exc:
        print(f"/events insert warning: {exc}")
        return {"status": "ok", "count": len(rows), "warning": "db_insert_failed"}


@app.post("/score-session")
async def score_session(payload: SessionPayload):
    payload_dict = _to_dict(payload)
    result = _score_payload(payload_dict)

    session_row = {
        "user_id": payload_dict.get("user_id", "unknown"),
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
    }
    inserted_row = None
    try:
        inserted = db.table("sessions").insert(session_row).execute()
        inserted_data = inserted.data or []
        inserted_row = inserted_data[0] if inserted_data else session_row
    except Exception as exc:
        print(f"/score-session insert warning: {exc}")
        inserted_row = session_row

    await manager.broadcast({"type": "new_session", "session": inserted_row})
    response = dict(result)
    response["session_id"] = inserted_row.get("id", payload_dict.get("session_id"))
    return response


@app.post("/explain")
async def explain(payload: dict):
    risk_result = payload.get("risk_result", {}) or {}
    session_features = payload.get("session_features", {}) or {}
    explanation = await explain_risk(risk_result, session_features)

    session_id = risk_result.get("session_id") or session_features.get("session_id")
    if session_id:
        update_doc = {
            "llm_summary": explanation.get("dashboard_summary", ""),
            "llm_user_prompt": explanation.get("user_prompt", ""),
            "threat_type": explanation.get("threat_type", "BEHAVIORAL_ANOMALY"),
        }
        try:
            updated = db.table("sessions").update(update_doc).eq("id", session_id).execute()
            updated_rows = updated.data or []
            if updated_rows:
                await manager.broadcast({"type": "session_updated", "session": updated_rows[0]})
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
    try:
        try:
            init = db.table("sessions").select("*").order("created_at", desc=True).limit(10).execute()
            sessions = init.data or []
            await ws.send_json({"type": "initial_sessions", "sessions": sessions})
        except Exception as exc:
            await ws.send_json({"type": "initial_sessions", "sessions": [], "warning": str(exc)})

        while True:
            _ = await ws.receive_text()
            await ws.send_json({"type": "pong"})
    except WebSocketDisconnect:
        manager.disconnect(ws)


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


@app.post("/notify-guardian")
async def notify_guardian(payload: GuardianNotify):
    alert_doc = {
        "user_id": payload.user_id,
        "score": payload.score,
        "threat_type": payload.threat_type,
        "llm_summary": payload.llm_summary,
        "status": "android_native_guardian_flow",
    }
    await manager.broadcast({"type": "guardian_alert_requested", "payload": alert_doc})
    try:
        db.table("sessions").update({"guardian_alerted": True}).eq("user_id", payload.user_id).execute()
    except Exception:
        pass
    return {"status": "queued_for_device", "detail": "Use Android native SMS/WhatsApp guardian flow."}


@app.get("/demo-inject")
async def demo_inject():
    payload = {
        "user_id": "demo_inject_user",
        "seconds_since_call": 14,
        "switch_count_20s": 5,
        "confirm_dwell_ms": 1100,
        "tap_density": 6.2,
        "revisit_count": 2,
        "session_duration_ms": 17000,
        "caller_trust": "UNKNOWN",
        "is_messaging_before": True,
        "is_whatsapp_voip": True,
        "voice_stress_score": 0.79,
        "network_threat_score": 10,
        "geo_hash": "tdr1j",
    }
    result = _score_payload(payload)
    row = {
        "user_id": payload["user_id"],
        "score": result["score"],
        "label": result["label"],
        "threat_type": "UPI_SCAM",
        "caller_trust": payload["caller_trust"],
        "geo_hash": payload["geo_hash"],
        "triggered_signals": result["triggered_signals"],
        "sig_caller_trust": result["sig_caller_trust"],
        "sig_transition": result["sig_transition"],
        "sig_confirm_press": result["sig_confirm_press"],
        "sig_behavioral": result["sig_behavioral"],
        "sig_voice_stress": result["sig_voice_stress"],
        "sig_network": result["sig_network"],
        "llm_summary": "Demo inject event for live dashboard validation.",
        "llm_user_prompt": "Pause and verify this transaction before proceeding.",
    }
    inserted = db.table("sessions").insert(row).execute()
    rows = inserted.data or []
    session = rows[0] if rows else row
    await manager.broadcast({"type": "new_session", "session": session})
    return {"status": "ok", "session": session}


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
