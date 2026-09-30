# start the maid-brain shim (idempotent helper). ASCII only.
$job = Get-Process node -ErrorAction SilentlyContinue | Where-Object {
    $_.CommandLine -match 'shim\.js'
} 2>$null
if ($job) {
    Write-Output "shim already running (pid $($job.Id))"
} else {
    Start-Process -WindowStyle Hidden node -ArgumentList 'D:\DSH\maid-ai\shim.js' -WorkingDirectory 'D:\DSH\maid-ai'
    Start-Sleep -Milliseconds 800
    try {
        $r = Invoke-WebRequest -UseBasicParsing 'http://127.0.0.1:4315/' -TimeoutSec 5
        Write-Output ("shim started: " + $r.Content)
    } catch {
        Write-Output ("shim start failed: " + $_.Exception.Message)
        exit 1
    }
}
