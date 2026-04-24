SentinelX Phase 0 (Real Device) - Executable Steps

1. Android scaffold and build
- cd SentinelX
- ./gradlew assembleDebug

2. Backend environment
- cd ../sentinelx-backend
- python -m venv venv
- .\venv\Scripts\activate
- pip install -r requirements.txt
- copy .env.example .env
- Fill .env with real values for SUPABASE_URL, SUPABASE_KEY, OPENAI_KEY, HMAC_SECRET

3. Supabase schema
- Open Supabase SQL Editor and run:
  - ../supabase/schema.sql

4. Start backend for phone access
- uvicorn main:app --host 0.0.0.0 --port 8000 --reload

5. Seed threat network data
- python threat_network.py

6. Real device checks (ADB)
- adb devices
- adb shell getprop ro.build.version.sdk
- adb install -r ../SentinelX/app/build/outputs/apk/debug/app-debug.apk
- adb shell pm list packages | findstr sentinelx
- adb shell dumpsys package com.sentinelx | findstr SEND_SMS

7. Health check
- curl http://localhost:8000/health
- adb shell curl -s http://<your-laptop-lan-ip>:8000/health
