#!/usr/bin/env bash
# Puts one build of the shared core where the Xcode project looks for it.
#
#   apple/use-core.sh debug      after ./gradlew :core:assemblePadelSyncCoreDebugXCFramework
#   apple/use-core.sh release    after ./gradlew :core:assemblePadelSyncCoreReleaseXCFramework
#
# The project has one fixed place for the core, apple/Frameworks, so that the
# same project file serves test builds (debug core) and App Store builds
# (release core).
set -euo pipefail
kind="${1:-debug}"
root="$(cd "$(dirname "$0")/.." && pwd)"
from="$root/core/build/XCFrameworks/$kind/PadelSyncCore.xcframework"
if [ ! -d "$from" ]; then
  echo "No $kind build of the shared core at $from. Build it first; see the top of this script." >&2
  exit 1
fi
rm -rf "$root/apple/Frameworks/PadelSyncCore.xcframework"
mkdir -p "$root/apple/Frameworks"
cp -R "$from" "$root/apple/Frameworks/"
echo "apple/Frameworks now holds the $kind core: $(ls "$root/apple/Frameworks/PadelSyncCore.xcframework" | tr '\n' ' ')"
