#!/usr/bin/env bash
# Builds what Google Play wants: one signed app bundle (.aab) for the phone
# app and one for the Wear OS app, checked for what Play refuses an upload
# over. Nothing is uploaded; the bundles are left in play-release/.
#
#   release-play.sh release    Signed with the real upload key, taken from
#                              the environment (never printed):
#                                PLAY_KEYSTORE_BASE64    the keystore file, base64-encoded
#                                PLAY_KEYSTORE_PASSWORD  the keystore's password
#                                PLAY_KEY_ALIAS          the key's name in the keystore
#                                PLAY_KEY_PASSWORD       the key's password
#   release-play.sh rehearse   The same steps with a throwaway key made on
#                              the spot and deleted afterwards, so the whole
#                              path can be tested with no secrets. Its
#                              bundles are for checking, not for uploading.
#                              It also leaves the release build as APKs in
#                              release-apks/, for the emulator tests.
#
# PADELSYNC_RELEASE_NUMBER   the release number the version codes are made
#                            from (phone = number x 10 + 1, watch = + 2)
set -euo pipefail

mode="${1:-rehearse}"
case "$mode" in
  rehearse|release) ;;
  *) echo "usage: $0 rehearse|release" >&2; exit 2 ;;
esac

out=play-release
key_dir=$(mktemp -d "${RUNNER_TEMP:-/tmp}/play-key.XXXXXX")
trap 'rm -rf "$key_dir"' EXIT
keystore="$key_dir/upload.jks"

echo "== Upload key =="
if [ "$mode" = release ]; then
  for name in PLAY_KEYSTORE_BASE64 PLAY_KEYSTORE_PASSWORD PLAY_KEY_ALIAS PLAY_KEY_PASSWORD; do
    if [ -z "${!name:-}" ]; then
      echo "FAILED: secrets not configured ($name is missing)."
      exit 1
    fi
  done
  # A line break pasted along with the alias would make it match no key.
  PLAY_KEY_ALIAS=$(printf '%s' "$PLAY_KEY_ALIAS" | tr -d '\r\n')
  # Windows tools wrap base64 text over many lines; the decoder skips the breaks.
  if ! ( umask 077; printf '%s' "$PLAY_KEYSTORE_BASE64" | base64 --decode --ignore-garbage > "$keystore" ) 2>/dev/null \
      || [ ! -s "$keystore" ]; then
    echo "FAILED: PLAY_KEYSTORE_BASE64 is not base64 text. It must be the keystore file encoded as base64, nothing else."
    exit 1
  fi
else
  # A key for this run only. It signs nothing that leaves the build machine
  # as a release, and is deleted with its folder when the script ends.
  PLAY_KEYSTORE_PASSWORD=$(head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9')
  PLAY_KEY_PASSWORD="$PLAY_KEYSTORE_PASSWORD"
  PLAY_KEY_ALIAS=rehearsal
  export PLAY_KEYSTORE_PASSWORD
  ( umask 077; keytool -genkeypair -storetype PKCS12 -keystore "$keystore" -storepass:env PLAY_KEYSTORE_PASSWORD \
      -alias "$PLAY_KEY_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
      -dname "CN=PadelSync rehearsal, O=Not for upload" >/dev/null 2>&1 )
  echo "made a throwaway key for this rehearsal"
fi
# The keystore is now a file; the build tools started below do not get the text.
unset PLAY_KEYSTORE_BASE64
export PLAY_KEYSTORE_FILE="$keystore" PLAY_KEYSTORE_PASSWORD PLAY_KEY_ALIAS PLAY_KEY_PASSWORD

# Find out now, in plain words, what would otherwise be a failure deep in the build.
if ! keytool -list -keystore "$keystore" -storepass:env PLAY_KEYSTORE_PASSWORD >/dev/null 2>&1; then
  echo "FAILED: the keystore cannot be opened. Either PLAY_KEYSTORE_PASSWORD is wrong or PLAY_KEYSTORE_BASE64 is not the keystore file."
  exit 1
fi
if ! keytool -list -keystore "$keystore" -storepass:env PLAY_KEYSTORE_PASSWORD -alias "$PLAY_KEY_ALIAS" >/dev/null 2>&1; then
  echo "FAILED: the keystore holds no key under the name given in PLAY_KEY_ALIAS."
  exit 1
fi
# Signing something small is the only way to try the key's own password.
( cd "$key_dir" && echo test > probe.txt && jar cf probe.jar probe.txt 2>/dev/null )
if ! jarsigner -keystore "$keystore" -storepass:env PLAY_KEYSTORE_PASSWORD -keypass:env PLAY_KEY_PASSWORD \
    "$key_dir/probe.jar" "$PLAY_KEY_ALIAS" >/dev/null 2>&1; then
  echo "FAILED: the key cannot sign. PLAY_KEY_PASSWORD is probably wrong."
  exit 1
fi
keytool -exportcert -rfc -keystore "$keystore" -storepass:env PLAY_KEYSTORE_PASSWORD -alias "$PLAY_KEY_ALIAS" \
  > "$key_dir/upload-cert.pem" 2>/dev/null
# Google Play only accepts keys that stay valid past 22 October 2033.
seconds_needed=$(( $(date -u -d 2033-10-23 +%s) - $(date -u +%s) ))
if ! openssl x509 -in "$key_dir/upload-cert.pem" -noout -checkend "$seconds_needed" >/dev/null; then
  echo "FAILED: the key expires before 23 October 2033, which Google Play does not accept. Create it with -validity 10000."
  exit 1
fi
# The fingerprint is not a secret: it is what Play Console shows as the
# "upload key certificate", and comparing the two confirms the right key.
key_fingerprint=$(openssl x509 -in "$key_dir/upload-cert.pem" -noout -fingerprint -sha256 | cut -d= -f2)
echo "the key opens and signs. Its SHA-256 fingerprint: $key_fingerprint"

echo "== Build the bundles, and the same build as APKs for checking =="
./gradlew :mobile:bundleRelease :wear:bundleRelease :mobile:assembleRelease :wear:assembleRelease --stacktrace

echo "== Check them =="
rm -rf "$out" release-apks
mkdir -p "$out"
export KEY_FINGERPRINT="$key_fingerprint"
# check-play-build.sh ends with "RESULT <version name> <version code>".
check() {
  local status=0
  bash .github/scripts/check-play-build.sh "$@" > "$key_dir/check.txt" || status=$?
  cat "$key_dir/check.txt"
  return "$status"
}
check phone mobile/build/outputs/bundle/release/mobile-release.aab mobile/build/outputs/apk/release/mobile-release.apk
read -r _ version phone_code <<< "$(grep '^RESULT ' "$key_dir/check.txt")"
check watch wear/build/outputs/bundle/release/wear-release.aab wear/build/outputs/apk/release/wear-release.apk
read -r _ watch_version watch_code <<< "$(grep '^RESULT ' "$key_dir/check.txt")"
if [ "$version" != "$watch_version" ]; then
  echo "FAILED: the phone app is version $version and the watch app $watch_version; they are released together and must match."
  exit 1
fi
# One app ID, one list of version codes: Play refuses a code it has seen.
if [ "$phone_code" = "$watch_code" ]; then
  echo "FAILED: the phone app and the watch app have the same version code, $phone_code."
  exit 1
fi
echo "ok: version $version; version codes $phone_code (phone) and $watch_code (watch) differ"

cp mobile/build/outputs/bundle/release/mobile-release.aab "$out/PadelSync-phone-$version-$phone_code.aab"
cp wear/build/outputs/bundle/release/wear-release.aab "$out/PadelSync-watch-$version-$watch_code.aab"
if [ "$mode" = rehearse ]; then
  mkdir -p release-apks
  cp mobile/build/outputs/apk/release/mobile-release.apk release-apks/PadelSync-phone.apk
  cp wear/build/outputs/apk/release/wear-release.apk release-apks/PadelSync-watch.apk
fi
ls -la "$out"

if [ -n "${GITHUB_STEP_SUMMARY:-}" ] && [ "$mode" = release ]; then
  {
    echo "### Bundles for Google Play"
    echo "| File | Version | Version code | Upload to |"
    echo "| --- | --- | --- | --- |"
    echo "| PadelSync-phone-$version-$phone_code.aab | $version | $phone_code | Internal testing |"
    echo "| PadelSync-watch-$version-$watch_code.aab | $version | $watch_code | Wear OS track, internal testing |"
    echo
    echo "Signed with the key whose SHA-256 fingerprint is \`$key_fingerprint\`."
  } >> "$GITHUB_STEP_SUMMARY"
fi
if [ "$mode" = release ]; then
  echo "== Done: the two bundles are ready to upload by hand =="
else
  echo "== Done: the release build signs and passes the checks. These bundles carry a throwaway key and are not for upload. =="
fi
