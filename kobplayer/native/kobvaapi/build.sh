#!/usr/bin/env bash
# Builds the kobvaapi VLC plugin into $1 (default: ./build). Exits 0 without building when
# the host lacks the VLC plugin, libva or libavcodec development files — the player then
# falls back to VDPAU or software decoding.
set -euo pipefail

cd "$(dirname "$0")"
OUT="${1:-build}"
DEPS="vlc-plugin libva-drm libva libavcodec libavutil"

if ! pkg-config --exists $DEPS 2>/dev/null; then
    echo "kobvaapi: skipped, missing development files for one of: $DEPS" >&2
    exit 0
fi

mkdir -p "$OUT"
${CC:-cc} -shared -fPIC -O2 -std=gnu11 \
    -D__PLUGIN__ -DMODULE_STRING='"kobvaapi"' -D_FILE_OFFSET_BITS=64 -D_REENTRANT -D_THREAD_SAFE \
    $(pkg-config --cflags $DEPS) \
    -o "$OUT/libkobvaapi_plugin.so" kobvaapi.c vlc_vaapi.c \
    $(pkg-config --libs $DEPS)
