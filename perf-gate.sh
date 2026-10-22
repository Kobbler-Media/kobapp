#!/usr/bin/env bash
# Perf gate for the TV build: cold-start time, frame timing, and — critically — a check
# that the run did not silently land on an error screen.
#
# Frame metrics alone are NOT a regression gate. A screen that fails to load draws a cheap
# error card and so *improves* every timing number; a 15s request timeout once shipped
# looking like a win. The assertions at the end are what catch that class of bug.
#
# Usage: KOB_PIN=1234 ./perf-gate.sh [label]
set -euo pipefail

SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
SERIAL="${KOB_TV_SERIAL:-emulator-5554}"
ADB_BIN="$SDK/platform-tools/adb"
adb_tv() { "$ADB_BIN" -s "$SERIAL" "$@"; }
PKG="${KOB_PKG:-dev.kobbler.app}"
ACT="$PKG/dev.kobbler.app.MainActivity"
LABEL="${1:-$(date +%H:%M)}"
RUNS="${KOB_PERF_RUNS:-7}"

key() { adb_tv shell input keyevent "$1"; sleep "${2:-0.4}"; }
dump_ui() { adb_tv shell uiautomator dump /sdcard/perfgate.xml >/dev/null 2>&1
            adb_tv shell cat /sdcard/perfgate.xml 2>/dev/null; }
at_home() { dump_ui | grep -q 'content-desc="Home"'; }

echo "### $LABEL   ($PKG on $SERIAL)"
adb_tv logcat -c

echo "-- cold start (am start -W TotalTime, ms) --"
cold=()
for i in $(seq 1 "$RUNS"); do
  adb_tv shell am force-stop "$PKG"; sleep 2
  t=$(adb_tv shell am start -W -n "$ACT" 2>/dev/null | awk '/^TotalTime:/{print $2}')
  cold+=("${t:-0}"); printf '  run %d: %s\n' "$i" "${t:-NA}"
done
printf '  median %s ms | min %s ms\n' \
  "$(printf '%s\n' "${cold[@]}" | sort -n | awk '{a[NR]=$1} END{print a[int((NR+1)/2)]}')" \
  "$(printf '%s\n' "${cold[@]}" | sort -n | head -1)"

adb_tv shell am force-stop "$PKG"; sleep 2
adb_tv shell am start -n "$ACT" >/dev/null 2>&1
sleep 7

# "Show picker on launch" puts the profile picker first; entry focus is its first profile.
if ! at_home && dump_ui | grep -q 'Add profile'; then key 23; sleep 8; fi

# A PIN-locked profile sits between cold start and the library. Never hardcode the PIN.
if ! at_home; then
  [ -n "${KOB_PIN:-}" ] || { echo "!! not at library and KOB_PIN unset — cannot continue"; exit 1; }
  for attempt in 1 2 3; do
    for (( c=0; c<${#KOB_PIN}; c++ )); do
      key $(( 7 + ${KOB_PIN:$c:1} )) 0.45
    done
    key 66 0.4; sleep 9
    at_home && break
    key 19 0.4; key 19 0.4
    [ "$attempt" = 3 ] && { echo "!! never reached the library — aborting"; exit 1; }
  done
fi

jank() { adb_tv shell dumpsys gfxinfo "$PKG" reset >/dev/null 2>&1; "$2"; sleep 2
         adb_tv shell dumpsys gfxinfo "$PKG" 2>/dev/null \
           | grep -E "Total frames rendered|Janky frames|th percentile|Missed Vsync" \
           | sed "s/^ */  [$1] /"; }

walk_slow() { key 21 0.45; for i in 1 2 3; do key 20 0.45; done; key 22 0.45
              for i in 1 2 3 4 5 6; do key 20 0.3; done
              for i in 1 2 3 4 5 6; do key 19 0.3; done; }
walk_fast() { key 21 0.4; for i in $(seq 1 10); do key 20 0.08; done
              for i in $(seq 1 10); do key 19 0.08; done; key 22 0.4; }

echo "-- jank: deliberate walk --"; jank slow walk_slow
echo "-- jank: rapid sweep --";     jank fast walk_fast

echo "-- functional assertions --"
fail=0
bad=$(adb_tv logcat -d 2>/dev/null \
      | grep -iE "Request timeout has expired|FATAL EXCEPTION|ANR in $PKG" \
      | grep -vi uiautomator || true)
if [ -n "$bad" ]; then echo "  FAIL: errors in logcat"; echo "$bad" | sed 's/^/    /'; fail=1
else echo "  ok: no timeouts, crashes or ANRs"; fi

onscreen=$(dump_ui | tr '>' '\n' \
           | grep -oiE '(text|content-desc)="[^"]*(error|retry|failed|timeout)[^"]*"' | sort -u || true)
if [ -n "$onscreen" ]; then echo "  FAIL: error UI on screen"; echo "$onscreen" | sed 's/^/    /'; fail=1
else echo "  ok: no error state on screen"; fi

exit $fail
