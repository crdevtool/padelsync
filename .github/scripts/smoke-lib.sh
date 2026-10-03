#!/usr/bin/env bash
# Helpers for driving an app on an emulator by what is on screen, using only
# adb. Sourced by the smoke-test scripts.
#
# The first check that fails stops the run: wait_for, tap, tap_many and expect
# call fail, which saves a screenshot and the screen's text, looks for a
# crash, and exits non-zero. Nothing carries on past a missing screen.

mkdir -p shots
STEP=0
FAILED=0

# Written by fail. If a failure was ever raised from inside $(...), where
# "exit" only ends the subshell, the marker still stops the run at the next
# helper and makes the script's exit status non-zero.
FAIL_MARKER=shots/FAILED.txt
rm -f "$FAIL_MARKER"
trap 'if [ -e "$FAIL_MARKER" ]; then exit 1; fi' EXIT

# One adb call that hangs must not use up the whole job: the short ones are
# given this many seconds.
ADB_TIMEOUT=${ADB_TIMEOUT:-20}

log() { echo "[smoke] $*"; }

# Turns a label into something that can go in a file name.
slug() {
  printf '%s' "$1" | tr -c 'A-Za-z0-9' '-' | tr -s '-' | sed 's/^-//; s/-$//' | cut -c1-40
}

# Stops the run. $1 names the failure, for the screenshot; $2 says what went
# wrong. Call it from the script's own shell only, never inside $(...).
fail() {
  log "FAIL: $2"
  echo "$2" >> "$FAIL_MARKER"
  if [ -z "${FAILING:-}" ]; then
    FAILING=1
    # A script that drives several devices defines on_fail to record them all.
    if declare -F on_fail >/dev/null; then on_fail "$1"; else fail_snapshot "$1"; fi
  fi
  log "RESULT: stopped at the first failed check"
  exit 1
}

# What a failure leaves behind for the current device: a screenshot named
# after the failure, the text on screen, and any crash.
fail_snapshot() {
  shot "FAILED-$1"
  texts
  check_crashes
}

stop_if_failed() {
  if [ -e "$FAIL_MARKER" ]; then exit 1; fi
}

# Switches off Android's "... isn't responding" and "... keeps stopping"
# dialogs on the current device. A slow emulator puts them up for system apps
# such as the launcher, over the app under test, and a run then waits behind
# a dialog nobody will answer. Call this on every emulator before launching
# the app. With the dialogs off, an app that really stops responding is closed
# by the system instead, and the next check for its screen fails.
quiet_system_dialogs() {
  adb shell settings put global hide_error_dialogs 1 || true
  adb shell settings put secure anr_show_background 0 || true
  log "system 'not responding' dialogs switched off on ${ANDROID_SERIAL:-the emulator}"
}

# Starts activity $1 and waits for $2 to be on screen, for up to $3 seconds
# (default 180). A watch emulator can take over a minute to bring an app up
# the first time, and sometimes drops the first request altogether, so the
# app is started again every 20 seconds until it shows.
launch_app() {
  stop_if_failed
  local deadline=$(( SECONDS + ${3:-180} ))
  while [ "$SECONDS" -lt "$deadline" ]; do
    # LAUNCH_EXTRAS carries options for the app, such as test switches.
    adb shell am start -n "$1" ${LAUNCH_EXTRAS:-} >/dev/null 2>&1 || true
    for _ in 1 2 3 4 5 6 7 8 9 10; do
      if find_center "$2" >/dev/null; then return 0; fi
      sleep 2
    done
    log "'$2' is not on screen yet; starting the app again"
  done
  fail "launch-$(slug "$2")" "'$2' never appeared after starting $1"
}

# Prints "x y" for the centre of the first element whose text equals $1 or
# whose accessibility description starts with $1.
find_center() {
  dump_ui
  locate "$1"
}

# Reads the screen into ui.xml. If a system "... isn't responding" dialog is
# up in spite of quiet_system_dialogs, it is dismissed here, for every caller.
# A read that fails leaves ui.xml empty, never showing an earlier screen.
#
# Callers capture this function's output to get coordinates, so anything it
# says goes to stderr: a message on stdout would end up in the middle of a
# tap command.
dump_ui() {
  local button_xy status
  for _ in 1 2 3 4; do
    : > ui.xml
    timeout "$ADB_TIMEOUT" adb shell 'rm -f /sdcard/ui.xml; uiautomator dump /sdcard/ui.xml' >/dev/null 2>&1
    status=$?
    if [ "$status" = 124 ]; then
      # adb is stuck, not the screen: retrying here would only add to the wait.
      log "adb did not answer within $ADB_TIMEOUT seconds" >&2
      return 1
    fi
    timeout "$ADB_TIMEOUT" adb exec-out cat /sdcard/ui.xml > ui.xml 2>/dev/null || true
    if ! grep -q "<node" ui.xml; then
      # The screen could not be read just now; try again.
      sleep 1
      continue
    fi
    grep -qE "isn.{1,6}t responding" ui.xml || return 0
    log "dismissing an emulator 'not responding' dialog" >&2
    if button_xy=$(locate "Wait") || button_xy=$(locate "Close app"); then
      timeout "$ADB_TIMEOUT" adb shell input tap $button_xy
    fi
    sleep 5
  done
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

# Waits for $1 to be on screen, looking every 2 seconds, $2 seconds' worth of
# times (default 30). Stops the run if it never appears. Reading the screen
# takes time too, so the real wait is longer; if adb itself is stuck, it gives
# up after three times $2 seconds.
wait_for() {
  stop_if_failed
  local seconds=${2:-30}
  local tries=$(( seconds / 2 ))
  local deadline=$(( SECONDS + seconds * 3 ))
  for _ in $(seq 1 "$tries"); do
    if find_center "$1" >/dev/null; then return 0; fi
    if [ "$SECONDS" -ge "$deadline" ]; then break; fi
    sleep 2
  done
  fail "missing-$(slug "$1")" "MISSING: '$1' never appeared"
}

# Taps the element labelled $1, scrolling a list down a few times to find it.
# Stops the run if it is not there.
tap() {
  stop_if_failed
  local xy
  for attempt in 1 2 3 4; do
    if xy=$(find_center "$1"); then
      timeout "$ADB_TIMEOUT" adb shell input tap $xy
      sleep 1
      return 0
    fi
    timeout "$ADB_TIMEOUT" adb shell input swipe "$SWIPE_X" "$SWIPE_FROM" "$SWIPE_X" "$SWIPE_TO" 300
    sleep 1
  done
  fail "tap-$(slug "$1")" "MISSING: could not tap '$1'"
}

# Taps the element labelled $1 a total of $2 times, looking it up once.
# Stops the run if it is not there.
tap_many() {
  stop_if_failed
  local xy
  if ! xy=$(find_center "$1"); then
    fail "tap-$(slug "$1")" "MISSING: could not tap '$1'"
  fi
  for _ in $(seq 1 "$2"); do
    timeout "$ADB_TIMEOUT" adb shell input tap $xy
    sleep 0.25
  done
  sleep 1
}

# Saves a screenshot named after the step number and $1.
shot() {
  STEP=$((STEP + 1))
  timeout "$ADB_TIMEOUT" adb exec-out screencap -p > "shots/$(printf '%02d' "$STEP")-$1.png"
  log "screenshot $STEP: $1"
}

# Records the screen's text, for checking results without reading pixels.
texts() {
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

# Waits for the screen to contain $1, reading it up to $3 times (default 10,
# which is about 20 seconds plus the reads); $2 describes the check. Stops the
# run if it never does.
expect() {
  stop_if_failed
  local seen=""
  local tries=${3:-10}
  local deadline=$(( SECONDS + tries * 6 ))
  for _ in $(seq 1 "$tries"); do
    seen=$(screen_text)
    if echo "$seen" | grep -qF "$1"; then
      log "PASS: $2"
      return 0
    fi
    if [ "$SECONDS" -ge "$deadline" ]; then break; fi
    sleep 2
  done
  fail "expect-$(slug "$2")" "$2 (wanted '$1', screen shows: $seen)"
}

# Fails the run if the app crashed at any point.
check_crashes() {
  timeout "$ADB_TIMEOUT" adb logcat -d -b crash > "shots/crash-${ANDROID_SERIAL:-device}.txt" 2>/dev/null || true
  if grep -q "com.padelsync" "shots/crash-${ANDROID_SERIAL:-device}.txt"; then
    log "CRASH detected:"
    cat "shots/crash-${ANDROID_SERIAL:-device}.txt"
    FAILED=1
  else
    log "no crashes"
  fi
}

grant_permissions() {
  # ACCESS_FINE_LOCATION is what Android 11 and older ask for instead of the
  # Bluetooth permissions; each version refuses the ones it does not have.
  for permission in BLUETOOTH_SCAN BLUETOOTH_CONNECT BLUETOOTH_ADVERTISE POST_NOTIFICATIONS ACCESS_FINE_LOCATION; do
    adb shell pm grant com.padelsync.app "android.permission.$permission" 2>/dev/null || true
  done
}
