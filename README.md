# SentinelX — Real-Time AI Fraud Shield

> Detects and blocks phone scam calls **before** you answer or send money.

---

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                  Android App (Kotlin)                   │
│                                                         │
│  SentinelForegroundService                              │
│    ├── CallStateMonitor  (RINGING → blocklist check)    │
│    ├── CallerTrustClassifier  (contacts + blocklist)    │
│    ├── SentinelAccessibilityService  (UI events)        │
│    ├── VoiceStressAnalyzer  (audio → stress score)      │
│    └── EventFlusher  (batch → backend → score → alert)  │
│                                                         │
│  BlocklistManager  ←→  SharedPreferences (JSON)         │
│  InterventionManager  (Tier 1 / 2 / 3 alerts)          │
│  GuardianManager  (SMS / WhatsApp alert)                │
└──────────────────────┬──────────────────────────────────┘
                       │ HTTP / WebSocket
┌──────────────────────▼──────────────────────────────────┐
│            Python FastAPI Backend                       │
│                                                         │
│  POST /events          →  store + auto-score            │
│  POST /score-session   →  6-signal ML scoring           │
│  POST /explain         →  LLM (Claude) explanation      │
│  GET  /community-blocklist  →  crowd-sourced numbers    │
│  POST /report-number   →  report a scammer number       │
│  POST /analyze-number  →  AI risk preview               │
│  WS   /live            →  real-time dashboard push      │
│                                                         │
│  DB: Supabase PostgreSQL  (fallback: local JSON)        │
└─────────────────────────────────────────────────────────┘
```

---

## Features

- **Ringing-phase blocklist alert** — fires while the phone is still ringing, before you answer
- **6-signal behavioral AI** — caller trust, transition velocity, confirmation pressure, behavioral deviation, voice stress, network threat
- **Community blocklist** — numbers reported by 3+ users are auto-blocked for everyone
- **LLM explanations** — plain-language alert text (e.g. "This matches a UPI scam pattern")
- **Guardian alert** — automatic SMS/WhatsApp to emergency contact on HIGH_RISK detection
- **Offline mode** — on-device risk engine when backend is unreachable
- **Demo mode** — full alert flow demo without any backend running
- **Stats dashboard** — calls analyzed, scams blocked, days protected

---

## Setup — Backend

```bash
cd sentinelx-backend
python -m venv venv
venv\Scripts\activate        # Windows
# source venv/bin/activate   # Mac/Linux
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8000 --reload
```

Open dashboard: http://127.0.0.1:8000

---

## Setup — Android

1. Open `SentinelX/` in Android Studio
2. Connect device or start emulator (API 26+)
3. Run → select device
4. For physical device with local backend:
   ```bash
   adb reverse tcp:8000 tcp:8000
   ```
5. In the app, set Backend URL to `http://127.0.0.1:8000`
6. Grant all requested permissions
7. Enable Accessibility service for SentinelX

---

## How the Blocklist Works

```
INCOMING CALL
    ↓
CallStateMonitor fires RINGING state
    ↓
SentinelForegroundService polls call log (every 500ms, up to 3s)
    ↓
CallerTrustClassifier.classify(number) → SCAMMER_MARKED?
    ↓ YES
InterventionManager.showBlocklistAlert() fires immediately
Vibration + MAX priority notification + full-screen intent
    ↓
User sees "SCAMMER CALLING" alert while phone rings
```

---

## Screenshots

> _Screenshots placeholder — attach before submission_

---

## Demo Mode

Tap **Demo Mode: ON** in the main screen. All backend calls are intercepted with realistic fake responses. Full alert flow works without any server running.

---

## Hackathon Context

Built for the SIH / hackathon track on financial fraud prevention. SentinelX addresses a gap no existing solution covers: the 30-second window between answering a scam call and completing a fraudulent payment.

See [PITCH.md](PITCH.md) for the full pitch deck.
