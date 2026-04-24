# SentinelX Live Phone -> Dashboard Verification

This project supports real phone-to-backend-to-dashboard live flow.

## 1) Start Backend

```bash
cd sentinelx-backend
uvicorn main:app --host 0.0.0.0 --port 8000 --reload
```

## 2) USB ADB Reverse (Physical Android)

```bash
adb devices
adb reverse tcp:8000 tcp:8000
adb reverse --list
```

Android app backend URL must be:

```text
http://127.0.0.1:8000
```

## 3) Open Dashboard

Open in browser:

- <http://127.0.0.1:8000>

The status should show `WS: LIVE` once connected.

## 4) Manual Backend Broadcast Test

```bash
curl -X POST http://127.0.0.1:8000/demo-phone-session
```

Expected:
- A `new_session` WebSocket message is broadcast.
- Live risk graph adds a new point from real payload score.
- Live table gets one new row.

## 5) Android Runtime Logs

```bash
adb logcat -s SentinelX EventFlusher RiskEngine GuardianMgr AndroidRuntime
```

Look for:
- backend URL log
- event collected / queued
- /events response
- /score-session response
- broadcasted score updates

## Expected End State

- Dashboard uses live WS messages when connected.
- Demo/random stream only starts when backend/WS is unavailable.
- Android sends real data to `/events` and `/score-session`.
- Live graph/table/KPI updates from real phone sessions.
