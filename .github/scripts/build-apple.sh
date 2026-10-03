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

echo "== Package the iPhone simulator build =="
# A zipped simulator build can be uploaded to a browser-based simulator
# service, which is the only way to try the iPhone app without a Mac.
(cd build/apple/Build/Products/Debug-iphonesimulator && zip -qr "$OLDPWD/PadelSync-iOS-simulator.zip" PadelSync.app)
ls -la PadelSync-iOS-simulator.zip
if [ -n "${GH_TOKEN:-}" ]; then
  gh release upload test-build PadelSync-iOS-simulator.zip --clobber || echo "could not publish the simulator build"
fi

echo "== Apple Watch app =="
build PadelSyncWatch watchsimulator "generic/platform=watchOS Simulator" watchsimulator PadelSyncWatch

mkdir -p shots

echo "== iPhone walkthrough =="
# Plays a whole match on an iPhone simulator, recording a video and a
# screenshot of every screen, so the app can be reviewed without a Mac.
# Returns the test's own status, after the video and screenshots are saved.
walkthrough() {
  local udid test_status=0
  udid=$(xcrun simctl list devices available -j | python3 -c "
import json, sys
devices = json.load(sys.stdin)['devices']
for runtime in sorted(devices, reverse=True):
    if 'iOS' not in runtime:
        continue
    for device in devices[runtime]:
        if 'iPhone' in device['name']:
            print(device['udid'])
            raise SystemExit
raise SystemExit(1)
") || true
  if [ -z "$udid" ]; then
    echo "no iPhone simulator is available for the walkthrough"
    return 1
  fi
  echo "walkthrough simulator: $udid"
  xcrun simctl boot "$udid" || true
  xcrun simctl bootstatus "$udid" -b >/dev/null
  rm -rf /tmp/padelsync-shots build/walkthrough.xcresult

  xcrun simctl io "$udid" recordVideo --codec h264 --force shots/iphone-walkthrough.mp4 &
  local recorder=$!
  sleep 2
  # grep only shortens the output. Its status must not stand in for the
  # test's: with pipefail the pipeline fails if either side does, and
  # PIPESTATUS[0] is then xcodebuild's own status (0 if only grep found
  # nothing to print).
  xcodebuild test -project apple/PadelSync.xcodeproj -scheme PadelSync \
    -destination "platform=iOS Simulator,id=$udid" -derivedDataPath build/apple \
    -resultBundlePath build/walkthrough.xcresult 2>&1 \
    | grep -E "Test Case|Test Suite .* (passed|failed)|error:|XCTAssert|Executed|\*\* TEST" \
    || test_status=${PIPESTATUS[0]}
  kill -INT "$recorder" 2>/dev/null || true
  wait "$recorder" 2>/dev/null || true

  if ls /tmp/padelsync-shots/*.png >/dev/null 2>&1; then
    mkdir -p shots/iphone && cp /tmp/padelsync-shots/*.png shots/iphone/
  else
    # The named copies were not written; fall back to the test attachments.
    xcrun xcresulttool export attachments --path build/walkthrough.xcresult \
      --output-path shots/iphone 2>&1 | tail -2 || true
  fi
  echo "walkthrough screenshots: $(ls shots/iphone 2>/dev/null | wc -l | tr -d ' ')"
  ls -la shots/iphone-walkthrough.mp4 2>/dev/null || echo "no video was recorded"
  xcrun simctl shutdown "$udid" || true
  return "$test_status"
}
walkthrough_status=0
walkthrough || walkthrough_status=$?
if [ "$walkthrough_status" != 0 ]; then
  # The workflow publishes shots/ whatever happens, so the video and the
  # screenshots taken up to the failure are still there to look at.
  echo "FAILED: the iPhone walkthrough test did not pass (status $walkthrough_status)"
  exit "$walkthrough_status"
fi

echo "== Run on simulators =="

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
