#!/usr/bin/env bash
# A rehearsal of the App Store build with no Apple account: the release
# build of the shared core for device architectures, then the iPhone app,
# with the watch app inside it, as an unsigned Xcode archive, checked for
# everything an upload needs. The real, signed build runs the same script
# with "upload"; see .github/workflows/release-apple.yml.
set -euo pipefail
exec bash .github/scripts/release-apple.sh rehearse
