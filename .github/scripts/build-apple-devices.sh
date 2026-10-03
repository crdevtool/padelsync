#!/usr/bin/env bash
# Builds the shared core for every Apple architecture (iPhone, iPhone
# simulator, and the three Apple Watch variants), then compiles both apps for
# real devices. Nothing is signed, so the result cannot be installed; the
# point is to prove that everything compiles and links.
set -euo pipefail

echo "== Shared core, all Apple architectures =="
./gradlew :core:assemblePadelSyncCoreDebugXCFramework --stacktrace
ls core/build/XCFrameworks/debug/PadelSyncCore.xcframework

echo "== Xcode project =="
command -v xcodegen >/dev/null || brew install xcodegen
xcodegen generate --spec apple/project.yml

build() {
  xcodebuild -project apple/PadelSync.xcodeproj -scheme "$1" -sdk "$2" -destination "$3" \
    -configuration Debug -derivedDataPath build/apple-devices \
    CODE_SIGNING_ALLOWED=NO build 2>&1 \
    | grep -E "error|BUILD|\*\*|Undefined|ld:" || true
  test -d "build/apple-devices/Build/Products/Debug-$2/$1.app"
  echo "architectures in $1: $(lipo -archs "build/apple-devices/Build/Products/Debug-$2/$1.app/$1")"
}

echo "== iPhone app (device) =="
build PadelSync iphoneos "generic/platform=iOS"

echo "== Apple Watch app (device) =="
build PadelSyncWatch watchos "generic/platform=watchOS"

echo "== Done =="
