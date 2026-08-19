# Live E2E monitor for Astro ME feed (Gemini + reactions + recommendations)
# Usage: .\monitor-e2e.ps1
# Optional: -UserHex '29466b...' -IntervalSec 15

param(
    [string]$UserHex = "29466b682aa4b523c055f01830ab026afaac169e054a7423f316e359fe6a02c6",
    [int]$IntervalSec = 20
)

$pg = "docker exec astro_algo_postgres psql -U astro -d astro_algo -t -A"

function Run-Sql($q) {
    Invoke-Expression "$pg -c `"$q`"" 2>&1
}

Write-Host "=== Astro ME E2E monitor (every ${IntervalSec}s) ===" -ForegroundColor Cyan
Write-Host "User: $UserHex"
Write-Host "Post new images/videos from the app (ME relay). Ctrl+C to stop.`n"

while ($true) {
    $ts = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    Write-Host "--- $ts ---"

    Run-Sql "SELECT status, count(*) FROM media_analysis GROUP BY status ORDER BY status;"
    Write-Host "Recent analysis (last 5):"
    Run-Sql @"
SELECT note_id || ' | ' || status || ' | ' || coalesce(left(title,35),'') || ' | ' || array_to_string(topics[1:3],',')
FROM media_analysis ORDER BY updated_at DESC LIMIT 5;
"@

    Write-Host "Your notes on ME (last 3):"
    Run-Sql @"
SELECT left(id,12) || ' kind=' || kind || ' | ' || coalesce(left(content,40),'') || ' | ' || created_at
FROM notes WHERE author_id='$UserHex' ORDER BY created_at DESC LIMIT 3;
"@

    Write-Host "Reactions (14d):"
    Run-Sql "SELECT count(*) FROM reactions WHERE reactor_id='$UserHex' AND created_at > NOW() - INTERVAL '14 days';"

    Write-Host "Interest topics from liked posts (Gemini):"
    Run-Sql @"
WITH engaged AS (
  SELECT DISTINCT note_id FROM reactions WHERE reactor_id='$UserHex'
    AND created_at > NOW() - INTERVAL '14 days'
)
SELECT DISTINCT unnest(topics) FROM media_analysis
WHERE status='done' AND note_id IN (SELECT note_id FROM engaged)
ORDER BY 1 LIMIT 12;
"@

    Write-Host "Failed Gemini (if any):"
    Run-Sql "SELECT left(note_id,12) || ' ' || left(coalesce(error,''),50) FROM media_analysis WHERE status='failed' ORDER BY updated_at DESC LIMIT 3;"

    Write-Host ""
    Start-Sleep -Seconds $IntervalSec
}
