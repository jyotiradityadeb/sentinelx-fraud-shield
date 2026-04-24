# SentinelX — Hackathon Pitch

## Problem

India loses **₹10,000 crore+ annually** to phone scams. 27% of Indians have been targeted by vishing (voice phishing) attacks. The elderly and financially vulnerable are disproportionately victimized — often tricked into authorizing UPI payments while on a call with a fraudster posing as a bank official, courier, or government agent.

**The window for intervention is 30 seconds** — the time between answering a scam call and completing a fraudulent payment. No existing app protects that window.

---

## Solution: SentinelX

SentinelX is the first **real-time AI fraud shield** that protects users *during* a call, not after money is lost.

### What makes it different

1. **Ringing-phase alert** — When a blocked number calls, SentinelX alerts you *before* you answer. No other app does this.
2. **Behavioral AI** — 6-signal ML scoring: caller trust, transition velocity, confirmation pressure, behavioral deviation, voice stress, and network threat.
3. **Community blocklist** — Crowdsourced protection: numbers reported by 3+ users are auto-blocked for everyone.
4. **Guardian system** — Automatic SMS/WhatsApp alert to a trusted contact when high-risk activity is detected.
5. **Offline mode** — Works without internet using on-device risk scoring.

---

## Innovation

SentinelX combines two complementary defenses that have never been paired before:

| Layer | What it catches |
|-------|----------------|
| Personal + community blocklist | Known bad actors, even before behavior is analyzed |
| Behavioral AI (6-signal ML) | Novel scams with unknown numbers that exhibit suspicious patterns |

Together they cover both **known** and **unknown** scammers, closing the gap that rule-based and ML-only systems each leave open.

---

## Technology

- **Android (Kotlin)**: Foreground service, accessibility monitoring, call state detection, overlay alerts
- **Python FastAPI backend**: 6-signal behavioral scoring, LLM-powered explanations (Claude), community threat aggregation
- **On-device fallback**: RiskEngine for offline scoring when backend is unreachable
- **LLM integration**: Anthropic Claude generates plain-language explanations users can understand ("This call pattern matches a UPI scam — do not proceed")

---

## Impact

- **Target population**: 500M+ smartphone users in India, with focus on 60+ age group and first-time digital banking users
- **Cost of scam call**: Average victim loses ₹1.5 lakh. SentinelX stops this with a 3-second overlay.
- **Community flywheel**: Each blocked number reported improves protection for all users, creating a network effect
- **Deployable today**: Works on Android 8+ (API 26), covers 95%+ of active Indian Android devices

---

## The Full Flow

```
PHONE RINGS
    ↓
SentinelX checks blocklist in < 200ms
    ↓ (if blocked)
ALERT fires while phone is still ringing — user sees "SCAMMER CALLING"
    ↓ (if answered)
Behavioral AI monitors call-to-payment transition
    ↓ (if payment attempted)
6-signal score computed → LLM explanation → Overlay alert
    ↓ (if HIGH_RISK)
Guardian SMS sent automatically
```

---

## Demo

Enable Demo Mode (no backend required) and tap "TRIGGER HIGH RISK" to see the full alert flow including:
- Full-screen HIGH RISK alert with LLM explanation
- Guardian SMS simulation
- Risk score breakdown across all 6 signals
- Blocklist hit notification
