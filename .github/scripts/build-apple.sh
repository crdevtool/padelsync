#!/usr/bin/env bash
# Builds the shared core for the Apple simulators, generates the Xcode
# project, and compiles the iPhone and Apple Watch apps. Nothing is signed.
set -euo pipefail

echo "== Xcode =="
xcodebuild -version
xcodebuild -showsdks | grep -iE "ios|watch" || true

echo "== Shared core (simulator slices) =="
./gradlew :core:linkDebugFrameworkIosSimulatorArm64 :core:linkDebugFrameworkWatchosSimulatorArm64 --stacktrace

# Package the two slices the way the Xcode project expects to find them. A
# full build for real devices uses :core:assemblePadelSyncCoreDebugXCFramework.
out=core/build/XCFrameworks/debug
rm -rf "$out" && mkdir -p "$out"
xcodebuild -create-xcframework \
  -framework core/build/bin/iosSimulatorArm64/debugFramework/PadelSyncCore.framework \
  -framework core/build/bin/watchosSimulatorArm64/debugFramework/PadelSyncCore.framework \
  -output "$out/PadelSyncCore.xcframework"

echo "== Xcode project =="
command -v xcodegen >/dev/null || brew install xcodegen
xcodegen generate --spec apple/project.yml

build() {
  # Prints errors in full and keeps the rest of xcodebuild's output short.
  xcodebuild -project apple/PadelSync.xcodeproj -scheme "$1" -sdk "$2" -destination "$3" \
    -configuration Debug -derivedDataPath build/apple \
    CODE_SIGNING_ALLOWED=NO build 2>&1 \
    | grep -E "error|warning: unre|BUILD|\*\*|Undefined|ld:|note: " | grep -v "^note: Using" || true
  # grep hides xcodebuild's own status; check for the product instead.
  test -d "build/apple/Build/Products/Debug-$4/$5.app"
}

echo "== iPhone app =="
build PadelSync iphonesimulator "generic/platform=iOS Simulator" iphonesimulator PadelSync

echo "== Apple Watch app =="
build PadelSyncWatch watchsimulator "generic/platform=watchOS Simulator" watchsimulator PadelSyncWatch

echo "== Run on simulators =="
mkdir -p shots

# Boots the first available simulator whose name contains $1, runs the app
# with a demo match already under way, and saves a screenshot as shots/$4.png.
run_on_simulator() {
  local udid
  udid=$(xcrun simctl list devices available -j | python3 -c "
import json, sys
devices = json.load(sys.stdin)['devices']
for runtime in sorted(devices, reverse=True):
    for device in devices[runtime]:
        if sys.argv[1] in device['name']:
            print(device['udid'])
            raise SystemExit
raise SystemExit(1)
" "$1")
  echo "simulator for '$1': $udid"
  xcrun simctl boot "$udid"
  xcrun simctl bootstatus "$udid" -b >/dev/null
  xcrun simctl install "$udid" "$2"
  xcrun simctl launch "$udid" "$3" -demoMatch
  sleep 10
  xcrun simctl io "$udid" screenshot "shots/$4.png"
  # A crashed app is no longer in the list of running services.
  if xcrun simctl spawn "$udid" launchctl list | grep -q "$3"; then
    echo "RUNNING: $3 is alive on the simulator"
  else
    echo "NOT RUNNING: $3 exited after launch"
  fi
  xcrun simctl shutdown "$udid"
}

run_on_simulator "iPhone" build/apple/Build/Products/Debug-iphonesimulator/PadelSync.app \
  com.padelsync.app iphone-match || echo "iPhone simulator run did not complete"
run_on_simulator "Apple Watch" build/apple/Build/Products/Debug-watchsimulator/PadelSyncWatch.app \
  com.padelsync.app.watchkitapp watch-match || echo "Apple Watch simulator run did not complete"

echo "== Done =="
