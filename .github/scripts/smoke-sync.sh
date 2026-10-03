#!/usr/bin/env bash
# Runs the phone app on two emulators at once, hosts a court on one, joins it
# from the other over the emulators' virtual Bluetooth, and checks that taps
# on either side reach both.
#
# This depends on the emulators' Bluetooth simulation, which is not available
# on every image. If the guest never sees the court, that says nothing about
# the app: read the log before drawing conclusions.
source .github/scripts/smoke-lib.sh
SWIPE_X=540; SWIPE_FROM=1800; SWIPE_TO=900
HOST=emulator-5554
GUEST=emulator-5556

on() { export ANDROID_SERIAL="$1"; }

log "starting a second emulator"
AVDMANAGER=$(ls "$ANDROID_HOME"/cmdline-tools/*/bin/avdmanager 2>/dev/null | head -1)
echo no | "$AVDMANAGER" create avd --force -n guest -k "system-images;android-34;default;x86_64" -d pixel_5 >/dev/null
"$ANDROID_HOME/emulator/emulator" -avd guest -port 5556 -no-window -gpu swiftshader_indirect \
  -no-snapshot -noaudio -no-boot-anim -cores 2 -memory 3072 >emulator-guest.log 2>&1 &
adb -s "$GUEST" wait-for-device
for _ in $(seq 1 120); do
  [ "$(adb -s "$GUEST" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
  sleep 5
done
log "second emulator booted: $(adb -s "$GUEST" shell getprop sys.boot_completed | tr -d '\r')"
adb -s "$GUEST" shell settings put global window_animation_scale 0 || true
adb -s "$GUEST" shell settings put global transition_animation_scale 0 || true
adb -s "$GUEST" shell settings put global animator_duration_scale 0 || true
# Two emulators share one small build machine; let both finish starting up.
sleep 60

for device in "$HOST" "$GUEST"; do
  on "$device"
  adb install -r PadelSync-phone.apk
  grant_permissions
  adb logcat -c
  log "$device bluetooth: $(adb shell settings get global bluetooth_on | tr -d '\r')"
done

log "--- host: start a match and open the court"
on "$HOST"
adb shell am start -n com.padelsync.app/.MainActivity
wait_for "New match" 90
tap "New match" && wait_for "Start match" 60
tap "Start match" && wait_for "Team A" 60
tap "Menu"; tap "Play with others"
expect "Court open" "host opened the court"
CODE=$(screen_text | grep -oE "Code [0-9]{4}" | grep -oE "[0-9]{4}" | head -1)
log "join code: ${CODE:-none}"
shot host-court-open

log "--- guest: find the court and join"
on "$GUEST"
adb shell am start -n com.padelsync.app/.MainActivity
wait_for "New match" 90
tap "Join a court"
sleep 15
log "guest sees: $(screen_text)"
shot guest-scanning
if tap "Very close"; then
  tap "Code"
  adb shell input text "$CODE"
  sleep 1
  shot guest-code
  tap "Join"
  if wait_for "Team A" 60; then
    shot guest-joined
    expect "2 devices" "guest shows two devices in the session"

    on "$HOST"
    expect "2 devices" "host shows two devices in the session"
    log "--- a point scored on the host reaches the guest"
    tap_many "Team A" 2
    on "$GUEST"
    expect "Team A. Points 30." "guest shows 30 after two host taps"

    log "--- a point scored on the guest reaches the host"
    tap_many "Team B" 1
    expect "Team B. Points 15." "guest shows its own tap"
    on "$HOST"
    expect "Team B. Points 15." "host shows the guest's tap"

    log "--- undo from the guest"
    on "$GUEST"
    tap "Undo"
    on "$HOST"
    expect "Team B. Points 0." "host shows the guest's undo"

    log "--- both tap the same point at the same moment"
    on "$HOST"; host_xy=$(find_center "Team A")
    on "$GUEST"; guest_xy=$(find_center "Team A")
    adb -s "$HOST" shell input tap $host_xy &
    host_tap=$!
    adb -s "$GUEST" shell input tap $guest_xy &
    guest_tap=$!
    # Wait for the two taps only: a bare "wait" would also wait for the
    # second emulator, which runs in the background for the whole script.
    wait "$host_tap" "$guest_tap"
    sleep 4
    on "$HOST"; log "host after simultaneous taps: $(screen_text)"
    shot host-final
    on "$GUEST"; log "guest after simultaneous taps: $(screen_text)"
    shot guest-final

    log "--- guest leaves"
    tap "Menu"; tap "Leave court"; tap "Leave"
    on "$HOST"
    expect "1 device" "host notices the guest leaving"
  fi
else
  log "the guest did not see the court; emulator Bluetooth may not link the two emulators"
  on "$HOST"; adb logcat -d | grep -iE "padelsync|BtGatt|advertis" | tail -30 || true
  on "$GUEST"; adb logcat -d | grep -iE "padelsync|BtGatt|BtScan|ScanManager" | tail -30 || true
fi

for device in "$HOST" "$GUEST"; do
  on "$device"
  check_crashes
done
adb -s "$GUEST" emu kill >/dev/null 2>&1 || true
exit $FAILED
