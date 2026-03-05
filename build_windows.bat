@echo off
setlocal
cd /d "%~dp0"
set "ICON_PATH=%CD%\app\img\fa82e0f8872e03ff459435036237a46d.ico"

echo ========================================================
echo Building KISDashboard (Windows OneDir)
echo ========================================================

python -m pip install --upgrade pip
if errorlevel 1 exit /b 1

python -m pip install -r requirements.txt pyinstaller
if errorlevel 1 exit /b 1

if not exist app\static mkdir app\static

python -m PyInstaller --noconfirm --clean --windowed --onedir ^
  --name KISDashboard ^
  --icon "%ICON_PATH%" ^
  --add-data "app/templates;app/templates" ^
  --add-data "app/static;app/static" ^
  --add-data "app/img;app/img" ^
  --collect-submodules passlib.handlers ^
  --hidden-import passlib.handlers.bcrypt ^
  launcher_windows.py
if errorlevel 1 exit /b 1

python -m PyInstaller --noconfirm --clean --windowed --onefile ^
  --name KISDashboardUpdater ^
  --icon "%ICON_PATH%" ^
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
