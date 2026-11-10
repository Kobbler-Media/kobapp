#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

PREFIX="${PREFIX:-$HOME/.local}"
APP_DIR="$PREFIX/share/kobapp"
BIN_DIR="$PREFIX/bin"
DESKTOP_DIR="$PREFIX/share/applications"

# Run from an extracted release tarball, the app is already built next to this script.
if [[ -x kobapp/bin/kobapp ]]; then
    BUILT="kobapp"
else
    ./gradlew :composeApp:createReleaseDistributable --console=plain
    BUILT="composeApp/build/compose/binaries/main-release/app/kobapp"
fi
if [[ ! -d "$BUILT" ]]; then
    echo "error: gradle did not produce $BUILT" >&2
    exit 1
fi

mkdir -p "$APP_DIR" "$BIN_DIR" "$DESKTOP_DIR"
rsync -a --delete "$BUILT/" "$APP_DIR/"

ln -sf "$APP_DIR/bin/kobapp" "$BIN_DIR/kobapp"

CFG="$APP_DIR/lib/app/kobapp.cfg"
AOT_LINE='java-options=-XX:AOTCache=$APPDIR/kobapp.aot'

cfg_with() {
    awk -v line="$AOT_LINE" -v opts="$(printf 'java-options=%s\n' "$@")" \
        '$0 == line { print opts; next } 1' "$CFG.orig" >"$CFG"
}

# The launcher ignores JDK_JAVA_OPTIONS, and the jlinked runtime has no bin/java for the JVM's
# one-step AOTCacheOutput to fork, so record and create run through the launcher with its cfg
# swapped. The record run is a real launch to the first screen, which doubles as a smoke test.
train_aot() {
    local seconds="${KOBAPP_TRAIN_SECONDS:-15}" log="$APP_DIR/aot-training.log" pid
    cp "$CFG" "$CFG.orig"
    trap 'mv -f "$CFG.orig" "$CFG"' EXIT

    echo "training startup cache: kobapp opens for ${seconds}s and closes on its own"
    cfg_with '-XX:AOTMode=record' '-XX:AOTConfiguration=$APPDIR/kobapp.aotconf'
    "$APP_DIR/bin/kobapp" >"$log" 2>&1 &
    pid=$!
    sleep "$seconds"
    if ! kill -TERM "$pid" 2>/dev/null; then
        echo "error: kobapp exited during its first ${seconds}s; see $log" >&2
        exit 1
    fi
    wait "$pid" || true

    cfg_with '-XX:AOTMode=create' '-XX:AOTConfiguration=$APPDIR/kobapp.aotconf' '-XX:AOTCache=$APPDIR/kobapp.aot'
    "$APP_DIR/bin/kobapp" >>"$log" 2>&1
    rm -f "$APP_DIR/lib/app/kobapp.aotconf"
}

if ! grep -qxF "$AOT_LINE" "$CFG"; then
    echo "error: $CFG has no '$AOT_LINE' to train against" >&2
    exit 1
elif [[ -n "${KOBAPP_NO_TRAIN:-}" || -z "${DISPLAY:-}" ]]; then
    echo "skipping startup cache training (needs DISPLAY and KOBAPP_NO_TRAIN unset)"
else
    train_aot
fi

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
