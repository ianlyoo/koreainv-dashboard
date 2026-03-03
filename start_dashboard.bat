@echo off
title Korea Investment Dashboard
echo ========================================================
echo Starting Korea Investment Dashboard...
echo ========================================================
echo.
echo Starting the local server on port 8000...
echo The dashboard will automatically open in your default browser.
echo Press Ctrl+C in this window to stop the server when finished.
echo.

:: Open the browser in the background (using ping as a makeshift 2-second sleep)
start /b cmd /c "ping 127.0.0.1 -n 3 > nul && start http://localhost:8000"

:: Start the FastAPI server
python main.py

pause
