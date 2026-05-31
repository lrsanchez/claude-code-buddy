# Claude Buddy — Windows PowerShell launcher
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

try { python -c "import bleak" 2>$null }
catch {
    Write-Host "Installing bleak..."
    python -m pip install --quiet bleak
}

& python "$ScriptDir\buddy_daemon.py" @args
