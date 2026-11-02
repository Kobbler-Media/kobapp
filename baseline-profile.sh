#!/usr/bin/env bash
# Regenerate the baseline and startup profiles on real devices and merge them into
# androidApp/src/main/generated/baselineProfiles/. Commit the result; the next release
# ships it.
#
# Runs against the paired debug install (dev.kobbler.app.dbg) through the perf build type,
# so no device needs linking again. Each device runs on its own because a run wipes the
# output directory for every device, not just its own.
#
# Usage: ./baseline-profile.sh [serial...]     (default: every connected device)
#        KOB_PIN=#### ./baseline-profile.sh    (if the profile the app opens on is locked)
set -euo pipefail
cd "$(dirname "$0")"

ROOT=$PWD
OUT_DIR=baselineprofile/build/outputs/connected_android_test_additional_output/nonMinifiedPerf/connected
RESULTS_DIR=baselineprofile/build/outputs/androidTest-results/connected/nonMinifiedPerf
TARGET=androidApp/src/main/generated/baselineProfiles
PKG=dev.kobbler.app.dbg
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

die() { echo "!! $*" >&2; exit 1; }

# One device can be listed twice — by IP and by its mDNS TLS name — so keep the first
# serial per hardware serial number.
devices() {
  local seen=" " serial hw
  for serial in $(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }'); do
    hw=$(adb -s "$serial" shell getprop ro.serialno 2>/dev/null | tr -d '\r')
    [[ $seen == *" $hw "* ]] && continue
    seen+="$hw "
    echo "$serial"
  done
}

run_device() {
  local serial=$1 model hw xml
  model=$(adb -s "$serial" shell getprop ro.product.model | tr -d '\r')
  hw=$(adb -s "$serial" shell getprop ro.serialno | tr -d '\r')
  echo "### $model ($serial)"

  adb -s "$serial" shell pm path "$PKG" >/dev/null 2>&1 \
    || die "$model has no $PKG — install and pair the debug build first"
  adb -s "$serial" shell input keyevent KEYCODE_WAKEUP
  adb -s "$serial" shell wm dismiss-keyguard >/dev/null 2>&1 || true
  adb -s "$serial" logcat -c

  local start=$SECONDS
  # The exit code is not the verdict: the test XML and the journey log below are.
  ANDROID_SERIAL=$serial ./gradlew -q :baselineprofile:connectedNonMinifiedPerfAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=dev.kobbler.app.baselineprofile.BaselineProfileGenerator \
    -Pandroid.testInstrumentationRunnerArguments.targetAppId="$PKG" \
    -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
    ${KOB_PIN:+-Pandroid.testInstrumentationRunnerArguments.profilePin=$KOB_PIN} \
    || true
  echo "   took $(( (SECONDS - start) / 60 )) min"

  xml=$(ls -t "$RESULTS_DIR"/TEST-*.xml 2>/dev/null | head -1)
  [[ -n $xml ]] || die "$model: no test results"
  grep -q '<testsuite[^>]* failures="0" errors="0"' "$xml" || die "$model: generator tests failed — see $xml"
  for test in startup journey; do
    grep -q "<testcase name=\"$test\"" "$xml" || die "$model: $test test never ran"
  done
  grep -q '<skipped' "$xml" && die "$model: a generator test was skipped — see $xml"

  # Unpaired, at an unexpected screen or behind a PIN, the journey degrades to cold start
  # only and still passes. A profile from that run would quietly drop everything after it.
  # The runner keeps each test's whole logcat; the device's own buffer rolls over long before
  # a TV run ends.
  local skips
  skips=$(grep -ho "Browse journey skipped — .*" "$RESULTS_DIR"/*/logcat-*.txt 2>/dev/null | sort | uniq -c || true)
  if [[ -n $skips ]]; then
    echo "$skips" >&2
    die "$model: browse journey skipped, profile would cover cold start only"
  fi

  mkdir -p "$WORK/$hw"
  find "$OUT_DIR" -name '*-prof.txt' -exec cp {} "$WORK/$hw/" \;
  ls "$WORK/$hw"/*-baseline-prof.txt >/dev/null 2>&1 || die "$model: no baseline profile written"
  ls "$WORK/$hw"/*-startup-prof.txt >/dev/null 2>&1 || die "$model: no startup profile written"
}

# A rule's leading H/S/P flags are unioned across devices; the rest of the line is the key.
# First-seen order is kept, so the generator's own ordering survives.
merge() {
  awk '
    { match($0, /^[HSP]*/); f = substr($0, 1, RLENGTH); k = substr($0, RLENGTH + 1)
      if (!(k in flags)) { order[++n] = k; flags[k] = "" }
      for (i = 1; i <= length(f); i++) { c = substr(f, i, 1); if (index(flags[k], c) == 0) flags[k] = flags[k] c } }
    END { for (i = 1; i <= n; i++) { k = order[i]; f = ""
            if (index(flags[k], "H")) f = f "H"; if (index(flags[k], "S")) f = f "S"; if (index(flags[k], "P")) f = f "P"
            print f k } }
  ' "$@"
}

serials=("$@")
[[ ${#serials[@]} -gt 0 ]] || mapfile -t serials < <(devices)
[[ ${#serials[@]} -gt 0 ]] || die "no devices connected"

for serial in "${serials[@]}"; do run_device "$serial"; done

for kind in baseline startup; do
  before=$(wc -l < "$TARGET/$kind-prof.txt")
  merge "$WORK"/*/*-"$kind"-prof.txt > "$TARGET/$kind-prof.txt"
  echo "$kind-prof.txt: $before -> $(wc -l < "$TARGET/$kind-prof.txt") rules"
done
echo "Merged ${#serials[@]} device(s) into $ROOT/$TARGET — commit it to ship with the next release."
