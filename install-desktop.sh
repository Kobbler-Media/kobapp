#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

if ! ldconfig -p 2>/dev/null | grep libvlc >/dev/null; then
    echo "error: libvlc not found on the system." >&2
    echo "install it first — e.g. 'sudo pacman -S vlc' or 'sudo apt install libvlc-dev vlc'." >&2
    exit 1
fi

PREFIX="${PREFIX:-$HOME/.local}"
APP_DIR="$PREFIX/share/kobapp"
BIN_DIR="$PREFIX/bin"
DESKTOP_DIR="$PREFIX/share/applications"

./gradlew :composeApp:createDistributable --console=plain

BUILT="composeApp/build/compose/binaries/main/app/kobapp"
if [[ ! -d "$BUILT" ]]; then
    echo "error: gradle did not produce $BUILT" >&2
    exit 1
fi

mkdir -p "$APP_DIR" "$BIN_DIR" "$DESKTOP_DIR"
rsync -a --delete "$BUILT/" "$APP_DIR/"

ln -sf "$APP_DIR/bin/kobapp" "$BIN_DIR/kobapp"

cat > "$DESKTOP_DIR/kobapp.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=Kobbler
Comment=Self-hosted media library client
Exec=$APP_DIR/bin/kobapp
Icon=$APP_DIR/lib/kobapp.png
Categories=AudioVideo;Player;
Terminal=false
StartupWMClass=Kobbler
EOF

if command -v update-desktop-database >/dev/null 2>&1; then
    update-desktop-database "$DESKTOP_DIR" >/dev/null 2>&1 || true
fi

echo
echo "kobapp installed to $APP_DIR"
echo "  launcher: $BIN_DIR/kobapp"
echo "  desktop entry: $DESKTOP_DIR/kobapp.desktop"
echo "re-run this script to update."
