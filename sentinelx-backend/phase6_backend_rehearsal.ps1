param(
    [string]$BaseUrl = "http://127.0.0.1:8000"
)

$ErrorActionPreference = "Stop"

Write-Host "Phase 6 backend rehearsal against $BaseUrl"

$health = Invoke-RestMethod -Uri "$BaseUrl/health" -Method Get

$scoreBody = @{
    session_id = "demo-session-001"
    user_id = "demo_user_001"
    seconds_since_call = 18
    switch_count_20s = 5
    confirm_dwell_ms = 1200
    tap_density = 6.5
    revisit_count = 3
    session_duration_ms = 14000
    caller_trust = "REPEATED_UNKNOWN"
    is_messaging_before = $true
    is_whatsapp_voip = $true
    voice_stress_score = 0.86
    network_threat_score = 12
    geo_hash = "tdr1j"
} | ConvertTo-Json -Compress
$score = Invoke-RestMethod -Uri "$BaseUrl/score-session" -Method Post -ContentType "application/json" -Body $scoreBody

$explainBody = @{
    risk_result = @{
        total_score = 96
        label = "HIGH_RISK"
        triggered_signals = @(@{ name = "Caller Trust"; pts = 22 })
    }
    session_features = @{
        session_id = "demo-session-001"
        seconds_since_call = 18
        caller_trust = "REPEATED_UNKNOWN"
        voice_stress_score = 0.86
        network_threat_reports = 4
        confirm_dwell_ms = 1200
        switch_count_20s = 5
        is_whatsapp_voip = $true
    }
} | ConvertTo-Json -Compress -Depth 6
$explain = Invoke-RestMethod -Uri "$BaseUrl/explain" -Method Post -ContentType "application/json" -Body $explainBody

$now = [int][DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$eventsBody = @{
    session_id = "demo-session-001"
    user_id = "demo_user_001"
    events = @(
        @{
            ts = $now
            session_id = "demo-session-001"
            user_id = "demo_user_001"
            event_type = "WINDOW_CHANGE"
            package_name = "com.whatsapp"
            screen_name = "chat"
            call_state = "IDLE"
            caller_trust = "UNKNOWN"
            interaction_type = "tap"
            dwell_ms = 850
            voice_stress_score = 0.81
            network_threat_score = 8
            geo_hash = "tdr1j"
        }
    )
} | ConvertTo-Json -Compress -Depth 6
$events = Invoke-RestMethod -Uri "$BaseUrl/events" -Method Post -ContentType "application/json" -Body $eventsBody

$sessions = Invoke-RestMethod -Uri "$BaseUrl/sessions" -Method Get

$notifyBody = @{
    user_id = "demo_user_001"
    llm_summary = "Unknown caller pushed urgent UPI payment."
    score = 92
    threat_type = "VISHING"
} | ConvertTo-Json -Compress
$notify = Invoke-RestMethod -Uri "$BaseUrl/notify-guardian" -Method Post -ContentType "application/json" -Body $notifyBody

[PSCustomObject]@{
    health_status = $health.status
    health_db_ok = $health.db_ok
    score_label = $score.label
    score_total = $score.total_score
    events_status = $events.status
    events_count = $events.count
    sessions_count = ($sessions.items | Measure-Object).Count
    notify_status = $notify.status
    explain_has_dashboard_summary = [bool]$explain.dashboard_summary
} | Format-List
