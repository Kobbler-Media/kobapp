#!/usr/bin/env bash
# Android TV dev loop. Usage: ./tv-emu.sh {create|up|install|run|shot|key|log|down}
set -euo pipefail

SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
# A phone emulator may also be attached, so every adb call is pinned to the TV.
SERIAL="${KOB_TV_SERIAL:-emulator-5554}"
ADB_BIN="$SDK/platform-tools/adb"
adb_tv() { "$ADB_BIN" -s "$SERIAL" "$@"; }
AVD=kob_tv
IMAGE="system-images;android-36;google-tv;x86_64"
PKG=dev.kobbler.app.dbg
ACT="$PKG/dev.kobbler.app.android.TvActivity"
CFG="$HOME/.android/avd/$AVD.avd/config.ini"

case "${1:-run}" in
create)
  "$SDK/cmdline-tools/latest/bin/sdkmanager" "$IMAGE"
  echo no | "$SDK/cmdline-tools/latest/bin/avdmanager" create avd -n "$AVD" -k "$IMAGE" -d tv_1080p --force
  # avdmanager's tv_1080p defaults are 2G RAM / 4 cores / swiftshader — unusable.
  sed -i -e 's/^hw.gpu.enabled=.*/hw.gpu.enabled=yes/' -e 's/^hw.gpu.mode=.*/hw.gpu.mode=host/' \
         -e 's/^hw.ramSize=.*/hw.ramSize=4096/' -e 's/^hw.cpu.ncore=.*/hw.cpu.ncore=6/' \
         -e 's/^vm.heapSize=.*/vm.heapSize=576/' -e 's/^disk.dataPartition.size=.*/disk.dataPartition.size=8G/' \
         -e 's/^hw.keyboard=no/hw.keyboard=yes/' "$CFG"
  ;;
up)
  adb_tv devices | grep -q emulator-5554 && { echo "already up"; exit 0; }
  nohup "$SDK/emulator/emulator" -avd "$AVD" -gpu host -no-boot-anim >/tmp/kob-tv-emu.log 2>&1 &
  adb_tv wait-for-device
  until [ "$(adb_tv shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 2; done
  echo "booted"
  ;;
install) ./gradlew :androidApp:installDebug ;;
run)     "$0" up; "$0" install; adb_tv shell am start -n "$ACT" ;;
shot)    adb_tv exec-out screencap -p > "${2:-/tmp/kob-tv.png}"; echo "${2:-/tmp/kob-tv.png}" ;;
# D-pad injection: up down left right center back home menu
key)     shift; for k in "$@"; do
           case "$k" in
             up) c=19;; down) c=20;; left) c=21;; right) c=22;; center|ok) c=23;;
             back) c=4;; home) c=3;; menu) c=82;; play) c=85;; *) c="$k";;
           esac
           adb_tv shell input keyevent "$c"
         done ;;
log)     adb_tv logcat -s "kobbler.app.dbg:*" AndroidRuntime:E "*:F" ;;
# Preview the targetSdk-37 ACCESS_LOCAL_NETWORK gate. Denial surfaces as a TCP
# timeout, not an error — so test LAN server discovery/playback under this.
lan-restrict)
         case "${2:-on}" in
           on)  adb_tv shell am compat enable 365139289 "$PKG" ;;
           off) adb_tv shell am compat reset  365139289 "$PKG" ;;
         esac ;;
down)    adb_tv emu kill ;;
*) echo "usage: $0 {create|up|install|run|shot [path]|key <k...>|log|lan-restrict on|off|down}"; exit 1 ;;
esac
