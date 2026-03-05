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
ICON_SRC="$PROJECT_DIR/app/img/fa82e0f8872e03ff459435036237a46d.ico"
ICONSET_DIR="$PROJECT_DIR/build/.tmp_${APP_NAME}.iconset"
ICON_ICNS="$PROJECT_DIR/build/${APP_NAME}.icns"

mkdir -p app/static
mkdir -p "$PROJECT_DIR/build"

if [ ! -f "$ICON_SRC" ]; then
  echo "[ERROR] Missing icon source: $ICON_SRC" >&2
  exit 1
fi

rm -rf "$ICONSET_DIR"
mkdir -p "$ICONSET_DIR"
sips -s format png "$ICON_SRC" --out "$ICONSET_DIR/base.png" >/dev/null
for size in 16 32 128 256 512; do
  sips -z "$size" "$size" "$ICONSET_DIR/base.png" --out "$ICONSET_DIR/icon_${size}x${size}.png" >/dev/null
  retina=$((size * 2))
  sips -z "$retina" "$retina" "$ICONSET_DIR/base.png" --out "$ICONSET_DIR/icon_${size}x${size}@2x.png" >/dev/null
done
iconutil -c icns "$ICONSET_DIR" -o "$ICON_ICNS"

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
  --icon "$ICON_ICNS" \
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
rm -rf "$ICONSET_DIR"

echo
echo "Build complete:"
echo "  $APP_BUNDLE"
echo "  $ZIP_PATH"
echo
