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

echo "== Done =="
