param(
    [int]$TimeoutSec = 10800
)
# sentinel: wait for a new request file in mailbox\requests, report path, exit 0.
# exits 2 on timeout, 3 on missing directory. ASCII only (avoid ps1 encoding pitfalls).
$dir = 'D:\DSH\maid-ai\mailbox\requests'
if (-not (Test-Path $dir)) {
    Write-Output "NO_MAILBOX_DIR: $dir"
    exit 3
}
$deadline = (Get-Date).AddSeconds($TimeoutSec)
Write-Output ("WATCHING until " + $deadline.ToString('HH:mm:ss') + " (timeout ${TimeoutSec}s)")
while ((Get-Date) -lt $deadline) {
    $f = Get-ChildItem -Path $dir -Filter '*.json' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime | Select-Object -First 1
    if ($f) {
        Write-Output ("REQUEST_FILE: " + $f.FullName)
        Write-Output ("SIZE: " + $f.Length)
        exit 0
    }
    Start-Sleep -Milliseconds 300
}
Write-Output "TIMEOUT_NO_REQUEST"
exit 2
