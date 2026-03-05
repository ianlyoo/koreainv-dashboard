#!/bin/zsh
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_DIR"

APP_NAME="KISDashboard"
DIST_DIR="$PROJECT_DIR/dist"
APP_BUNDLE="$DIST_DIR/${APP_NAME}.app"
UPDATER_BIN="$DIST_DIR/KISDashboardUpdater"
ARCH="$(uname -m)"
ZIP_PATH="$DIST_DIR/${APP_NAME}-mac-${ARCH}.zip"

mkdir -p app/static

echo "========================================================"
echo "Building ${APP_NAME} (macOS PyInstaller)"
echo "========================================================"

python3 -m pip install --upgrade pip
python3 -m pip install -r requirements.txt pyinstaller

# Build updater first and embed into app bundle later.
python3 -m PyInstaller --noconfirm --clean --onefile \
  --name KISDashboardUpdater \
  updater_mac.py

if [ ! -f "$UPDATER_BIN" ]; then
  echo "[ERROR] Missing updater binary: $UPDATER_BIN" >&2
  exit 1
fi

python3 -m PyInstaller --noconfirm --clean --windowed \
  --name "$APP_NAME" \
  --add-data "app/templates:app/templates" \
  --add-data "app/static:app/static" \
  --add-data "app/img:app/img" \
  --collect-submodules passlib.handlers \
  --hidden-import passlib.handlers.bcrypt \
  --add-binary "$UPDATER_BIN:." \
  launcher_mac.py

if [ ! -d "$APP_BUNDLE" ]; then
  echo "[ERROR] Build output not found: $APP_BUNDLE" >&2
  exit 1
fi

# Free-tier signing: enforce ad-hoc deep signing for stable local verification.
xattr -cr "$APP_BUNDLE"
codesign --force --deep --sign - "$APP_BUNDLE"
codesign --verify --deep --strict --verbose=2 "$APP_BUNDLE"

rm -f "$ZIP_PATH"
ditto -c -k --sequesterRsrc --keepParent "$APP_BUNDLE" "$ZIP_PATH"

echo
echo "Build complete:"
echo "  $APP_BUNDLE"
echo "  $ZIP_PATH"
echo
