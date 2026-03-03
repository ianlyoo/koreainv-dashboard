@echo off
setlocal
cd /d "%~dp0"

echo ========================================================
echo Building KISDashboard (Windows OneDir)
echo ========================================================

py -3 -m pip install --upgrade pip
if errorlevel 1 exit /b 1

py -3 -m pip install -r requirements.txt pyinstaller
if errorlevel 1 exit /b 1

py -3 -m PyInstaller --noconfirm --clean --windowed --onedir ^
  --name KISDashboard ^
  --add-data "app/templates;app/templates" ^
  --add-data "app/static;app/static" ^
  --add-data "app/img;app/img" ^
  launcher_windows.py
if errorlevel 1 exit /b 1

py -3 -m PyInstaller --noconfirm --clean --windowed --onefile ^
  --name KISDashboardUpdater ^
  --distpath "dist\KISDashboard" ^
  --workpath "build\updater" ^
  --specpath "build" ^
  updater_windows.py
if errorlevel 1 exit /b 1

if not exist dist\KISDashboard (
  echo Build output not found.
  exit /b 1
)

powershell -NoProfile -Command "Compress-Archive -Path 'dist\KISDashboard\*' -DestinationPath 'dist\KISDashboard-win64.zip' -Force"
if errorlevel 1 exit /b 1

echo.
echo Build complete:
echo   dist\KISDashboard\
echo   dist\KISDashboard-win64.zip
echo.

endlocal
