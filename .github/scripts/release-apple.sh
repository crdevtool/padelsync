#!/usr/bin/env bash
# Builds the iPhone app, with the Apple Watch app inside it, the way the App
# Store wants it: the release build of the shared core, device architectures
# only, as an Xcode archive.
#
#   release-apple.sh rehearse   Unsigned. Proves that the release build
#                               compiles and has what an upload needs.
#                               Needs no Apple account.
#   release-apple.sh upload     Signed, and uploaded to TestFlight. Needs the
#                               four secrets listed below.
#
# Both modes run the same steps; "upload" only adds signing and the upload.
#
# Where the signing happens. The archive is built unsigned, exactly as in the
# rehearsal, and Apple signs it for the App Store when it is exported. Signing
# the archive itself would need a development profile, which Apple only
# issues to a team that has a device registered, and would leave a new
# development certificate in the account on every run until the account's
# limit stops the build. ARCHIVE_SIGNING=automatic switches to that way; it
# becomes necessary once the app has entitlements (HealthKit), because an
# unsigned archive carries none.
#
# For "upload", in the environment (never printed):
#   ASC_KEY_ID, ASC_ISSUER_ID   the App Store Connect API key's two IDs
#   ASC_KEY_P8                  the contents of the key's .p8 file
#   APPLE_TEAM_ID               the developer team
#
# BUILD_NUMBER                the build number for both apps (default 1)
# ARCHIVE_SIGNING=automatic   sign the archive too; see above (default none)
# FULL_LOG=true               print all of Xcode's output, not only the lines that matter
set -euo pipefail

mode="${1:-rehearse}"
case "$mode" in
  rehearse|upload) ;;
  *) echo "usage: $0 rehearse|upload" >&2; exit 2 ;;
esac

out=build/apple-release
archive="$out/PadelSync.xcarchive"
build_number="${BUILD_NUMBER:-1}"

# Xcode repeats its command line, and with it the key's IDs and the team, in
# its output. This blanks them out before anything is printed.
redact() {
  perl -pe 'BEGIN { $| = 1 }
    for my $name (qw(ASC_KEY_ID ASC_ISSUER_ID APPLE_TEAM_ID)) {
      my $value = $ENV{$name};
      s/\Q$value\E/[hidden]/g if defined $value && length $value;
    }'
}

# Runs xcodebuild and returns its own status. By default only the lines that
# say what happened are printed.
run_xcodebuild() {
  local status=0
  if [ "${FULL_LOG:-false}" = true ]; then
    xcodebuild "$@" 2>&1 | redact || status=${PIPESTATUS[0]}
  else
    xcodebuild "$@" 2>&1 | redact \
      | grep -E "error|warning: unre|\*\* |Undefined|ld: |Provisioning|provisioning|Upload|upload|Progress" \
      || status=${PIPESTATUS[0]}
  fi
  return "$status"
}

echo "== Xcode =="
xcodebuild -version
xcode_major=$(xcodebuild -version | awk 'NR == 1 { split($2, v, "."); print v[1] }')
if [ "$mode" = upload ] && [ "${xcode_major:-0}" -lt 26 ]; then
  echo "FAILED: App Store Connect only accepts builds made with Xcode 26 or later; this Mac has Xcode $xcode_major."
  exit 1
fi

# How the key is handed to Apple, for the archive (if it is signed) and the export.
key_flags=()
signing=(CODE_SIGNING_ALLOWED=NO)
if [ "$mode" = upload ]; then
  echo "== Signing key =="
  for name in ASC_KEY_ID ASC_ISSUER_ID ASC_KEY_P8 APPLE_TEAM_ID; do
    if [ -z "${!name:-}" ]; then
      echo "FAILED: secrets not configured ($name is missing)."
      exit 1
    fi
  done
  # A space or line break pasted along with an ID would make Apple refuse it
  # with a message that says nothing useful.
  ASC_KEY_ID=$(printf '%s' "$ASC_KEY_ID" | tr -d '[:space:]')
  ASC_ISSUER_ID=$(printf '%s' "$ASC_ISSUER_ID" | tr -d '[:space:]')
  APPLE_TEAM_ID=$(printf '%s' "$APPLE_TEAM_ID" | tr -d '[:space:]')
  export ASC_KEY_ID ASC_ISSUER_ID APPLE_TEAM_ID
  # The key is written to a private file for Xcode and deleted when the script ends.
  key_dir=$(mktemp -d "${RUNNER_TEMP:-/tmp}/asc.XXXXXX")
  key_file="$key_dir/AuthKey.p8"
  trap 'rm -rf "$key_dir"' EXIT
  ( umask 077; printf '%s\n' "$ASC_KEY_P8" > "$key_file" )
  if ! grep -q "BEGIN PRIVATE KEY" "$key_file"; then
    # The secret may hold the file base64-encoded instead of as text.
    if printf '%s' "$ASC_KEY_P8" | base64 --decode > "$key_file.decoded" 2>/dev/null \
        && grep -q "BEGIN PRIVATE KEY" "$key_file.decoded"; then
      mv "$key_file.decoded" "$key_file"
    else
      echo "FAILED: ASC_KEY_P8 is not an App Store Connect key. Paste the whole .p8 file, including its BEGIN and END lines."
      exit 1
    fi
  fi
  if ! openssl pkey -in "$key_file" -noout 2>/dev/null; then
    echo "FAILED: ASC_KEY_P8 holds a key that cannot be read. It must be the .p8 file exactly as downloaded, line breaks included."
    exit 1
  fi
  # From here on the key is only in its file; the tools started below do not get it.
  unset ASC_KEY_P8
  echo "key file ready"
  key_flags=(
    -allowProvisioningUpdates
    -authenticationKeyPath "$key_file"
    -authenticationKeyID "$ASC_KEY_ID"
    -authenticationKeyIssuerID "$ASC_ISSUER_ID"
  )
  if [ "${ARCHIVE_SIGNING:-none}" = automatic ]; then
    echo "the archive will be signed as well (needs a registered device at Apple)"
    signing=("${key_flags[@]}" "DEVELOPMENT_TEAM=$APPLE_TEAM_ID" CODE_SIGN_STYLE=Automatic)
  fi
fi

echo "== Shared core, release build, device architectures =="
./gradlew :core:assemblePadelSyncCoreReleaseXCFramework -PappleDevicesOnly=true --stacktrace
apple/use-core.sh release

echo "== Xcode project =="
command -v xcodegen >/dev/null || brew install xcodegen
xcodegen generate --spec apple/project.yml

echo "== Build settings for real devices =="
# The project leaves x86_64 out of simulator builds. That must not reach a
# device build, where it would be at best noise and at worst an empty app.
for pair in "PadelSync iphoneos" "PadelSyncWatch watchos"; do
  read -r target sdk <<< "$pair"
  if ! settings=$(xcodebuild -project apple/PadelSync.xcodeproj -target "$target" -configuration Release \
      -sdk "$sdk" -showBuildSettings 2>&1); then
    echo "FAILED: could not read the build settings of $target for $sdk:"
    printf '%s\n' "$settings" | tail -20
    exit 1
  fi
  excluded=$(printf '%s\n' "$settings" | awk -F' = ' '/^ *EXCLUDED_ARCHS = / { print $2 }')
  if [ -n "$excluded" ]; then
    echo "FAILED: $target leaves out '$excluded' when built for $sdk; only simulator builds may leave an architecture out."
    exit 1
  fi
  echo "ok: $target for $sdk leaves no architecture out"
done

echo "== Archive (version from the project, build $build_number) =="
rm -rf "$out"
mkdir -p "$out"
archive_status=0
run_xcodebuild archive \
  -project apple/PadelSync.xcodeproj -scheme PadelSync -configuration Release \
  -destination "generic/platform=iOS" \
  -archivePath "$archive" -derivedDataPath "$out/derived" \
  "CURRENT_PROJECT_VERSION=$build_number" \
  "${signing[@]}" || archive_status=$?
if [ "$archive_status" != 0 ] || [ ! -d "$archive/Products/Applications/PadelSync.app" ]; then
  echo "FAILED: the archive was not built (xcodebuild status $archive_status)."
  exit 1
fi
# The watch app belongs inside the iPhone app. If it were also archived as an
# app of its own, the archive could not be exported for the App Store.
app_count=$(find "$archive/Products/Applications" -maxdepth 1 -name "*.app" | wc -l | tr -d ' ')
if [ "$app_count" != 1 ]; then
  echo "FAILED: the archive holds $app_count apps at its top level, expected the iPhone app alone:"
  ls "$archive/Products/Applications"
  exit 1
fi

echo "== What is in the archive =="
bash .github/scripts/check-apple-app.sh "$archive/Products/Applications/PadelSync.app" device

if [ "$mode" = rehearse ]; then
  echo "== Done: the release build is ready to be signed and uploaded =="
  exit 0
fi

echo "== Export and upload to TestFlight =="
options="$key_dir/ExportOptions.plist"
cat > "$options" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>method</key>
	<string>app-store-connect</string>
	<key>destination</key>
	<string>upload</string>
	<key>signingStyle</key>
	<string>automatic</string>
	<key>teamID</key>
	<string>$APPLE_TEAM_ID</string>
	<key>manageAppVersionAndBuildNumber</key>
	<false/>
	<key>uploadSymbols</key>
	<true/>
</dict>
</plist>
PLIST
upload_status=0
run_xcodebuild -exportArchive \
  -archivePath "$archive" -exportOptionsPlist "$options" -exportPath "$out/export" \
  "${key_flags[@]}" || upload_status=$?
if [ "$upload_status" != 0 ]; then
  echo "FAILED: the build was not uploaded (xcodebuild status $upload_status). Run again with the full log switched on to see why."
  exit 1
fi
echo "== Done: build $build_number is uploaded. It appears in TestFlight once Apple has processed it. =="
