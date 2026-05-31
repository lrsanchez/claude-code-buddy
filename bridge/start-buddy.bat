@echo off
REM Claude Buddy — Windows launcher
setlocal
set SCRIPT_DIR=%~dp0

python -c "import bleak" 2>nul || (
    echo Installing bleak...
    python -m pip install --quiet bleak
)

python "%SCRIPT_DIR%buddy_daemon.py" %*
pause
