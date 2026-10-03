#!/usr/bin/env bash
# Helpers for driving an app on an emulator by what is on screen, using only
# adb. Sourced by the smoke-test scripts.

mkdir -p shots
STEP=0
FAILED=0

log() { echo "[smoke] $*"; }

# Prints "x y" for the centre of the first element whose text equals $1 or
# whose accessibility description starts with $1.
find_center() {
  dump_ui
  # Slow emulators sometimes show a "System UI isn't responding" dialog over
  # the app. It is the emulator's problem, not the app's: dismiss it.
  if grep -q "isn't responding" ui.xml 2>/dev/null; then
    log "dismissing an emulator 'not responding' dialog"
    local wait_xy
    if wait_xy=$(locate "Wait"); then adb shell input tap $wait_xy; sleep 3; fi
    dump_ui
  fi
  locate "$1"
}

dump_ui() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || true
  adb exec-out cat /sdcard/ui.xml > ui.xml 2>/dev/null || true
}

locate() {
  python3 - "$1" <<'PY'
import re, sys
import xml.etree.ElementTree as ET
label = sys.argv[1]
try:
    root = ET.parse("ui.xml").getroot()
except Exception:
    sys.exit(1)
for node in root.iter("node"):
    text = node.get("text") or ""
    # A label ending in "*" matches any text that starts with it.
    prefix = label[:-1] if label.endswith("*") else None
    matches_text = text.startswith(prefix) if prefix else text == label
    if matches_text or (node.get("content-desc") or "").startswith(prefix or label):
        x1, y1, x2, y2 = map(int, re.findall(r"-?\d+", node.get("bounds")))
        print((x1 + x2) // 2, (y1 + y2) // 2)
        sys.exit(0)
sys.exit(1)
PY
}

# Waits up to $2 seconds (default 30) for $1 to be on screen.
wait_for() {
  local tries=$(( ${2:-30} / 2 ))
  for _ in $(seq 1 "$tries"); do
    if find_center "$1" >/dev/null; then return 0; fi
    sleep 2
  done
  log "MISSING: '$1' never appeared"
  FAILED=1
  return 1
}

# Taps the element labelled $1, scrolling a list down a few times to find it.
tap() {
  local xy
  for attempt in 1 2 3 4; do
    if xy=$(find_center "$1"); then
      adb shell input tap $xy
      sleep 1
      return 0
    fi
    adb shell input swipe "$SWIPE_X" "$SWIPE_FROM" "$SWIPE_X" "$SWIPE_TO" 300
    sleep 1
  done
  log "MISSING: could not tap '$1'"
  FAILED=1
  return 1
}

# Taps the element labelled $1 a total of $2 times, looking it up once.
tap_many() {
  local xy
  if ! xy=$(find_center "$1"); then
    log "MISSING: could not tap '$1'"
    FAILED=1
    return 1
  fi
  for _ in $(seq 1 "$2"); do
    adb shell input tap $xy
    sleep 0.25
  done
  sleep 1
}

# Saves a screenshot named after the step number and $1.
shot() {
  STEP=$((STEP + 1))
  adb exec-out screencap -p > "shots/$(printf '%02d' "$STEP")-$1.png"
  log "screenshot $STEP: $1"
}

# Records the screen's text, for checking results without reading pixels.
texts() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || true
  adb exec-out cat /sdcard/ui.xml > ui.xml 2>/dev/null || true
  python3 - <<'PY'
import xml.etree.ElementTree as ET
try:
    root = ET.parse("ui.xml").getroot()
except Exception:
    raise SystemExit
seen = []
for node in root.iter("node"):
    for key in ("text", "content-desc"):
        value = node.get(key)
        if value and value not in seen:
            seen.append(value)
print("[screen] " + " | ".join(seen))
PY
}

# Prints everything readable on screen as one line.
screen_text() {
  dump_ui
  python3 - <<'PY'
import xml.etree.ElementTree as ET
try:
    root = ET.parse("ui.xml").getroot()
except Exception:
    raise SystemExit
seen = []
for node in root.iter("node"):
    for key in ("text", "content-desc"):
        value = node.get(key)
        if value and value not in seen:
            seen.append(value)
print(" | ".join(seen))
PY
}

# Waits up to 20 seconds for the screen to contain $1; $2 describes the check.
expect() {
  local seen=""
  for _ in $(seq 1 10); do
    seen=$(screen_text)
    if echo "$seen" | grep -qF "$1"; then
      log "PASS: $2"
      return 0
    fi
    sleep 2
  done
  log "FAIL: $2 (wanted '$1', screen shows: $seen)"
  FAILED=1
  return 1
}

# Fails the run if the app crashed at any point.
check_crashes() {
  adb logcat -d -b crash > "shots/crash-${ANDROID_SERIAL:-device}.txt" 2>/dev/null || true
  if grep -q "com.padelsync" "shots/crash-${ANDROID_SERIAL:-device}.txt"; then
    log "CRASH detected:"
    cat "shots/crash-${ANDROID_SERIAL:-device}.txt"
    FAILED=1
  else
    log "no crashes"
  fi
}

grant_permissions() {
  for permission in BLUETOOTH_SCAN BLUETOOTH_CONNECT BLUETOOTH_ADVERTISE POST_NOTIFICATIONS; do
    adb shell pm grant com.padelsync.app "android.permission.$permission" 2>/dev/null || true
  done
}
