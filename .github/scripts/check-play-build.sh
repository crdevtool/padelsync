#!/usr/bin/env bash
# Checks one release build of an Android app for the things Google Play
# refuses an upload over, and for what the phone app and the Wear OS app
# must each declare.
#
# Usage: check-play-build.sh phone|watch path/to/app.aab path/to/app.apk
#
# The bundle is what gets uploaded. The APK is the same build packaged the
# other way, made in the same run from the same manifest; it is checked
# because the Android tools can read an APK's manifest directly.
#
# KEY_FINGERPRINT   the SHA-256 fingerprint both must be signed with
#
# Prints every problem it finds, then fails if there was any. Its last line
# is "RESULT <version name> <version code>".
set -euo pipefail

kind="$1"
aab="$2"
apk="$3"
problems=0

APP_ID=com.crdevtool.padelsync
if [ "$kind" = watch ]; then
  # Google Play: Wear OS apps must target Android 15 (API 35) or newer.
  min_target=35
  slot=2
else
  # Google Play: phone apps must target Android 16 (API 36) or newer.
  min_target=36
  slot=1
fi

problem() {
  echo "PROBLEM ($kind): $*"
  problems=$((problems + 1))
}

for file in "$aab" "$apk"; do
  if [ ! -s "$file" ]; then
    echo "PROBLEM ($kind): $file was not built"
    exit 1
  fi
done

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
tools=$(ls -d "$sdk"/build-tools/* 2>/dev/null | sort -V | tail -1)
if [ -z "$tools" ] || [ ! -x "$tools/aapt2" ]; then
  echo "PROBLEM: the Android build tools were not found under '$sdk'"
  exit 1
fi

badging=$("$tools/aapt2" dump badging "$apk")
manifest=$("$tools/aapt2" dump xmltree --file AndroidManifest.xml "$apk")

# Pulls name='value' out of aapt2's "package:" line.
package_field() {
  printf '%s\n' "$badging" | sed -n "s/^package:.* $1='\([^']*\)'.*/\1/p" | head -1
}

app_id=$(package_field name)
version_code=$(package_field versionCode)
version_name=$(package_field versionName)
target=$(printf '%s\n' "$badging" | sed -n "s/^targetSdkVersion:'\([0-9]*\)'.*/\1/p" | head -1)

if [ "$app_id" = "$APP_ID" ]; then
  echo "ok: the app ID is $app_id"
else
  problem "the app ID is '${app_id:-missing}', expected $APP_ID"
fi

if [ -n "$version_name" ]; then
  echo "ok: version $version_name, version code $version_code"
else
  problem "the build has no version name"
fi
# The last digit says which app it is, which is what keeps the phone's and
# the watch's codes apart under their shared app ID.
case "$version_code" in
  *"$slot") echo "ok: the version code ends in $slot, as the $kind app's must" ;;
  *) problem "the version code is '${version_code:-missing}'; the $kind app's must end in $slot" ;;
esac

if [ -n "$target" ] && [ "$target" -ge "$min_target" ]; then
  echo "ok: it targets API $target (Google Play asks for $min_target or newer)"
else
  problem "it targets API '${target:-missing}'; Google Play asks for $min_target or newer"
fi

if printf '%s\n' "$badging" | grep -q "^application-debuggable"; then
  problem "the build is debuggable; Google Play refuses debuggable builds"
else
  echo "ok: the build is not debuggable"
fi

icon=$(printf '%s\n' "$badging" | sed -n "s/^application: .*icon='\([^']*\)'.*/\1/p" | head -1)
label=$(printf '%s\n' "$badging" | sed -n "s/^application: label='\([^']*\)'.*/\1/p" | head -1)
if [ -n "$icon" ] && unzip -l "$apk" "$icon" >/dev/null 2>&1; then
  echo "ok: it has a launcher icon ($icon) and is called '$label'"
else
  problem "it has no launcher icon (the manifest names '${icon:-nothing}')"
fi

has_watch_feature=no
if printf '%s\n' "$badging" | grep -q "^  uses-feature: name='android.hardware.type.watch'"; then
  has_watch_feature=yes
fi
# What follows the standalone entry's name in the manifest is its value.
standalone=$(printf '%s\n' "$manifest" | grep -A3 "com.google.android.wearable.standalone" \
  | sed -n 's/.*:value([^)]*)=\(.*\)$/\1/p' | head -1 || true)
if [ "$kind" = watch ]; then
  if [ "$has_watch_feature" = yes ]; then
    echo "ok: it declares itself a watch app, so Play offers it to watches only"
  else
    problem "it does not require android.hardware.type.watch, so Play would not treat it as a Wear OS app"
  fi
  if [ "$standalone" = true ]; then
    echo "ok: it declares that it works without a phone (standalone)"
  else
    problem "the standalone declaration is '${standalone:-missing}'; it must be true for a watch app that needs no phone"
  fi
else
  if [ "$has_watch_feature" = no ] && ! printf '%s\n' "$badging" | grep -q "android.hardware.type.watch"; then
    echo "ok: it does not ask for a watch, so Play offers it to phones"
  else
    problem "the phone app mentions android.hardware.type.watch"
  fi
fi

# --- Signatures -------------------------------------------------------------
want="${KEY_FINGERPRINT:-}"
# Fingerprints are compared as bare upper-case hex.
bare() { tr -d ': \n' | tr 'a-f' 'A-F'; }
if [ -z "$want" ]; then
  problem "no key fingerprint was given to compare the signatures with"
else
  want=$(printf '%s' "$want" | bare)
  bundle_signer=$(keytool -printcert -jarfile "$aab" 2>/dev/null | sed -n 's/^[[:space:]]*SHA256: //p' | head -1 | bare || true)
  if jarsigner -verify "$aab" >/dev/null 2>&1 && [ "$bundle_signer" = "$want" ]; then
    echo "ok: the bundle is signed with the upload key"
  elif [ -z "$bundle_signer" ]; then
    problem "the bundle is not signed"
  else
    problem "the bundle is signed with a different key than the one given"
  fi
  # apksigner words the line differently from version to version; the
  # fingerprint is whatever follows "certificate SHA-256 digest:".
  apk_report=$("$tools/apksigner" verify --print-certs "$apk" 2>&1 || true)
  apk_signer=$(printf '%s\n' "$apk_report" | sed -n 's/^.*certificate SHA-256 digest: //p' | head -1 | bare || true)
  if [ "$apk_signer" = "$want" ]; then
    echo "ok: the APK of the same build is signed with the upload key"
  else
    problem "the APK of the same build is not signed with the upload key. apksigner says:"
    printf '%s\n' "$apk_report" | head -8
  fi
fi

# --- What is inside the bundle ----------------------------------------------
listing=$(unzip -Z1 "$aab")
for entry in base/manifest/AndroidManifest.xml BundleConfig.pb base/dex/classes.dex; do
  if ! printf '%s\n' "$listing" | grep -qx "$entry"; then
    problem "the bundle has no $entry"
  fi
done
# The build shrinks and renames the code; Play needs this file to turn a
# crash report back into readable names.
if printf '%s\n' "$listing" | grep -q "^BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map$"; then
  echo "ok: the bundle carries the file Play uses to make crash reports readable"
else
  problem "the bundle has no proguard.map, so crash reports in Play would be unreadable"
fi

# --- Native code ------------------------------------------------------------
# Google Play asks that native libraries work on devices with 16 KB memory
# pages. The apps have no native code of their own; libraries they use might.
work=$(mktemp -d)
unzip -q -o "$apk" 'lib/*' -d "$work" 2>/dev/null || true
native=$(find "$work" -name '*.so' | wc -l | tr -d ' ')
if [ "$native" = 0 ]; then
  echo "ok: no native libraries, so nothing to align for 16 KB pages"
else
  if python3 - "$work" <<'PY'
import pathlib, struct, sys
bad = 0
for so in sorted(pathlib.Path(sys.argv[1]).rglob("*.so")):
    data = so.read_bytes()
    if data[:4] != b"\x7fELF":
        continue
    is64 = data[4] == 2
    if not is64:
        continue  # 16 KB pages exist on 64-bit devices only
    phoff, = struct.unpack_from("<Q", data, 0x20)
    phentsize, phnum = struct.unpack_from("<HH", data, 0x36)
    for i in range(phnum):
        p_type, = struct.unpack_from("<I", data, phoff + i * phentsize)
        p_align, = struct.unpack_from("<Q", data, phoff + i * phentsize + 0x30)
        if p_type == 1 and p_align < 16384:
            print(f"{so.relative_to(sys.argv[1])}: aligned to {p_align} bytes")
            bad += 1
            break
sys.exit(1 if bad else 0)
PY
  then
    echo "ok: its $native native libraries are built for 16 KB pages"
  else
    problem "native libraries listed above are not built for 16 KB pages, which Google Play requires"
  fi
fi
rm -rf "$work"

# Google Play warns about a bundle with native code and no debug symbols. It
# is not a reason to refuse the upload, so it is reported and not counted.
if [ "$native" != 0 ]; then
  symbols=$(unzip -Z1 "$aab" 2>/dev/null | grep -c '^BUNDLE-METADATA/com.android.tools.build.debugsymbols/.*\.sym$' || true)
  if [ "$symbols" != 0 ]; then
    echo "ok: the bundle carries $symbols native symbol tables"
    echo "::notice::The $kind bundle carries $symbols native symbol tables."
  else
    # Expected: the only native code is libandroidx.graphics.path.so from
    # the Android libraries, which Google ships with its symbol table already
    # removed, so there is nothing to extract.
    echo "note: the bundle has native code and no debug symbols; Play Console shows a warning, which is expected"
    echo "::notice::The $kind bundle has native code without debug symbols (the Android library ships them stripped). Play Console shows a warning; the upload works."
  fi
fi

echo "sizes: bundle $(( $(stat -c %s "$aab") / 1024 )) KB, APK $(( $(stat -c %s "$apk") / 1024 )) KB"
if [ "$problems" != 0 ]; then
  echo "FAILED: $problems problem(s) in the $kind app's release build"
  exit 1
fi
echo "RESULT $version_name $version_code"
