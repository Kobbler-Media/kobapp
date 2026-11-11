#!/usr/bin/env bash
# Builds the app for an Apple Silicon iOS simulator, launches it and checks it is still running
# after a while. Leaves a screenshot, the app's log and any crash report in $OUT.
set -euo pipefail

cd "$(dirname "$0")"
OUT=${OUT:-../build/ios-smoke}
SETTLE=${SETTLE:-30}
BUNDLE=dev.kobbler.app
mkdir -p "$OUT"

udid=$(xcrun simctl list devices available -j | python3 -c '
import json, sys
devices = json.load(sys.stdin)["devices"]
phones = [d for rt, ds in devices.items() if "iOS" in rt for d in ds if d["name"].startswith("iPhone")]
print(phones[-1]["udid"])')

xcodebuild -project iosApp.xcodeproj -scheme iosApp -configuration Debug \
  -destination "platform=iOS Simulator,id=$udid" -derivedDataPath ../build/ios \
  CODE_SIGNING_ALLOWED=NO build | tee "$OUT/xcodebuild.log" | grep -E '^(\*\*|error:)|: error:' || true
app=../build/ios/Build/Products/Debug-iphonesimulator/Kobbler.app
[ -d "$app" ] || { echo "no app built"; exit 1; }

xcrun simctl bootstatus "$udid" -b
xcrun simctl install "$udid" "$app"
pid=$(xcrun simctl launch "$udid" "$BUNDLE" | awk '{print $NF}')
sleep "$SETTLE"

xcrun simctl io "$udid" screenshot "$OUT/screen.png"
xcrun simctl spawn "$udid" log show --last 5m --style compact --predicate 'process == "Kobbler"' > "$OUT/app.log" || true
cp ~/Library/Logs/DiagnosticReports/Kobbler-*.ips "$OUT/" 2>/dev/null || true

# A simulator app is an ordinary process on the host.
if kill -0 "$pid" 2>/dev/null; then
  echo "Kobbler is running after ${SETTLE}s"
else
  echo "Kobbler is not running after ${SETTLE}s"
  tail -50 "$OUT/app.log"
  exit 1
fi
