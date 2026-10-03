#!/usr/bin/env bash
# Checks a built iPhone app for the things the App Store refuses an upload
# over: the identifiers, the watch app inside it, matching version numbers,
# icons, the export-compliance answer and the privacy declaration.
#
# Usage: check-apple-app.sh path/to/PadelSync.app [device]
#
# With "device" it also checks that the binaries are built for real devices
# only. Prints every problem it finds, then fails if there was any.
set -euo pipefail

app="$1"
kind="${2:-any}"
watch="$app/Watch/PadelSyncWatch.app"
problems=0

APP_ID=com.crdevtool.padelsync
WATCH_ID=com.crdevtool.padelsync.watchkitapp

problem() {
  echo "PROBLEM: $*"
  problems=$((problems + 1))
}

# Prints one value from a bundle's Info.plist, or nothing if it is missing.
plist() {
  local value
  # Older versions of plutil print their error message as if it were the value.
  if value=$(plutil -extract "$2" raw -o - "$1/Info.plist" 2>/dev/null); then
    printf '%s' "$value"
  fi
}

# expect_plist bundle key wanted what-it-is
expect_plist() {
  local got
  got=$(plist "$1" "$2")
  if [ "$got" = "$3" ]; then
    echo "ok: $4 is $got"
  else
    problem "$4 is '${got:-missing}', expected '$3' ($2 in $(basename "$1"))"
  fi
}

# The build writes the icon's name into Info.plist, at the top or under
# CFBundleIcons depending on the platform. Without it there is no icon.
expect_icon() {
  local got
  got=$(plist "$1" CFBundleIconName)
  [ -n "$got" ] || got=$(plist "$1" CFBundleIcons.CFBundlePrimaryIcon.CFBundleIconName)
  if [ "$got" = AppIcon ]; then
    echo "ok: $2 is named in its Info.plist"
  else
    problem "$2 is not named in its Info.plist (found '${got:-nothing}')"
  fi
}

if [ ! -d "$app" ]; then
  echo "PROBLEM: there is no app at $app"
  exit 1
fi
if [ ! -d "$watch" ]; then
  problem "the watch app is not inside the iPhone app (expected $watch)"
fi

expect_plist "$app" CFBundleIdentifier "$APP_ID" "the iPhone app's ID"
expect_plist "$app" ITSAppUsesNonExemptEncryption false "the iPhone app's encryption answer"
expect_icon "$app" "the iPhone app's icon"

if [ -d "$watch" ]; then
  expect_plist "$watch" CFBundleIdentifier "$WATCH_ID" "the watch app's ID"
  expect_plist "$watch" WKCompanionAppBundleIdentifier "$APP_ID" "the iPhone app the watch app belongs to"
  expect_plist "$watch" WKRunsIndependentlyOfCompanionApp true "the watch app running on its own"
  expect_plist "$watch" ITSAppUsesNonExemptEncryption false "the watch app's encryption answer"
  expect_icon "$watch" "the watch app's icon"
  # Apple refuses an upload whose watch app and iPhone app disagree on either number.
  for key in CFBundleShortVersionString CFBundleVersion; do
    phone_value=$(plist "$app" "$key")
    watch_value=$(plist "$watch" "$key")
    if [ -n "$phone_value" ] && [ "$phone_value" = "$watch_value" ]; then
      echo "ok: $key is $phone_value in both apps"
    else
      problem "$key differs: iPhone app '${phone_value:-missing}', watch app '${watch_value:-missing}'"
    fi
  done
fi

for bundle in "$app" "$watch"; do
  [ -d "$bundle" ] || continue
  name=$(basename "$bundle")
  # The compiled asset catalogue holds the icon.
  if [ -f "$bundle/Assets.car" ]; then
    echo "ok: $name has its compiled icons"
  else
    problem "$name has no Assets.car, so no icon"
  fi
  if [ -f "$bundle/PrivacyInfo.xcprivacy" ] && plutil -lint "$bundle/PrivacyInfo.xcprivacy" >/dev/null; then
    echo "ok: $name has its privacy declaration"
  elif [ -f "$bundle/PrivacyInfo.xcprivacy" ]; then
    problem "$name has a PrivacyInfo.xcprivacy that is not a valid property list"
  else
    problem "$name has no PrivacyInfo.xcprivacy"
  fi
done

if [ "$kind" = device ]; then
  phone_archs=$(lipo -archs "$app/PadelSync" 2>/dev/null || true)
  echo "iPhone app architectures: ${phone_archs:-none}"
  if [ "$phone_archs" != "arm64" ]; then
    problem "the iPhone app should be built for arm64 only, found '${phone_archs:-none}'"
  fi
  if [ -d "$watch" ]; then
    watch_archs=$(lipo -archs "$watch/PadelSyncWatch" 2>/dev/null || true)
    echo "watch app architectures: ${watch_archs:-none}"
    # Every watch that runs watchOS 10 up to Series 8 needs arm64_32; newer
    # ones use arm64. A build without arm64_32 would leave most watches out.
    case " $watch_archs " in
      *" arm64_32 "*) ;;
      *) problem "the watch app has no arm64_32 build, found '${watch_archs:-none}'" ;;
    esac
    case " $watch_archs " in
      *" x86_64 "*) problem "the watch app contains a simulator architecture: $watch_archs" ;;
    esac
  fi
fi

if [ "$problems" != 0 ]; then
  echo "FAILED: $problems problem(s) in $app"
  exit 1
fi
echo "ok: $(basename "$app") $(plist "$app" CFBundleShortVersionString) ($(plist "$app" CFBundleVersion)) has what an upload needs"
