#!/usr/bin/env bash
# Runs the app on two emulators at once, hosts a court on a phone, joins it
# from a second phone or from a watch over the emulators' virtual Bluetooth,
# and checks that taps on either side reach both.
#
# Usage: smoke-sync.sh phone|watch     (the kind of device that joins)
#
# This depends on the emulators' Bluetooth simulation. If the guest never
# sees the court, that says nothing about the app: read the log before
# drawing conclusions.
#
# The run stops at the first check that fails; see smoke-lib.sh.
source .github/scripts/smoke-lib.sh
GUEST_KIND="${1:-phone}"
HOST=emulator-5554
GUEST=emulator-5556

if [ "$GUEST_KIND" = watch ]; then
  GUEST_IMAGE="system-images;android-33;android-wear;x86_64"
  GUEST_PROFILE=wearos_large_round
  GUEST_APK=PadelSync-watch.apk
  GUEST_ACTIVITY=com.padelsync.app/com.padelsync.wear.MainActivity
  UNDO=UNDO
else
  GUEST_IMAGE="system-images;android-34;default;x86_64"
  GUEST_PROFILE=pixel_5
  GUEST_APK=PadelSync-phone.apk
  GUEST_ACTIVITY=com.padelsync.app/.MainActivity
  UNDO=Undo
fi

# Points adb, and the scroll gesture used to find off-screen items, at one device.
on() {
  export ANDROID_SERIAL="$1"
  if [ "$1" = "$GUEST" ] && [ "$GUEST_KIND" = watch ]; then
    SWIPE_X=200; SWIPE_FROM=300; SWIPE_TO=120
  else
    SWIPE_X=540; SWIPE_FROM=1800; SWIPE_TO=900
  fi
}

# What a failed check leaves behind: both devices' screens, and what their
# Bluetooth had to say. Called by fail, in place of a single screenshot.
on_fail() {
  local device
  for device in "$HOST" "$GUEST"; do
    on "$device"
    fail_snapshot "$1-$([ "$device" = "$HOST" ] && echo host || echo guest)"
  done
  log "if the guest never got into the court, the emulators' virtual Bluetooth may not link the two emulators; their logs follow"
  on "$HOST"; timeout 30 adb logcat -d | grep -iE "padelsync|BtGatt|advertis" | tail -30 || true
  on "$GUEST"; timeout 30 adb logcat -d | grep -iE "padelsync|BtGatt|BtScan|ScanManager" | tail -30 || true
  timeout 20 adb -s "$GUEST" emu kill >/dev/null 2>&1 || true
}

# The guest picks the court from the list and enters the join code.
guest_join() {
  tap "Join a court"
  sleep 12
  log "guest sees: $(screen_text)"
  if [ "$GUEST_KIND" = watch ]; then
    tap "Android SDK*"
    for digit in $(echo "$CODE" | grep -o .); do tap "$digit"; done
    tap "OK"
  else
    tap "Very close"
    tap "Code"
    adb shell input text "$CODE"
    sleep 1
    tap "Join"
  fi
  wait_for "Team A" 60
}

guest_leave() {
  if [ "$GUEST_KIND" = watch ]; then
    tap "MENU"; tap "Leave court"
  else
    tap "Menu"; tap "Leave court"; tap "Leave"
  fi
}

log "guest device: $GUEST_KIND"
log "starting a second emulator"
SDKMANAGER=$(ls "$ANDROID_HOME"/cmdline-tools/*/bin/sdkmanager 2>/dev/null | head -1)
AVDMANAGER=$(ls "$ANDROID_HOME"/cmdline-tools/*/bin/avdmanager 2>/dev/null | head -1)
yes | "$SDKMANAGER" "$GUEST_IMAGE" 2>&1 | tail -2 || true
echo no | "$AVDMANAGER" create avd --force -n guest -k "$GUEST_IMAGE" -d "$GUEST_PROFILE" 2>&1 | tail -3
# The default data partition is larger than the build machine has room for.
config="$HOME/.android/avd/guest.avd/config.ini"
sed -i '/^disk.dataPartition.size/d' "$config"
echo "disk.dataPartition.size=2G" >> "$config"
"$ANDROID_HOME/emulator/emulator" -avd guest -port 5556 -no-window -gpu swiftshader_indirect \
  -no-snapshot -noaudio -no-boot-anim -cores 2 -memory 3072 -partition-size 2048 >emulator-guest.log 2>&1 &
if ! timeout 300 adb -s "$GUEST" wait-for-device; then
  log "FAIL: the second emulator never appeared"
  tail -30 emulator-guest.log
  exit 1
fi
booted=""
for _ in $(seq 1 60); do
  booted=$(timeout 20 adb -s "$GUEST" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
  [ "$booted" = "1" ] && break
  sleep 5
done
if [ "$booted" != "1" ]; then
  log "FAIL: the second emulator did not finish starting up"
  tail -30 emulator-guest.log
  timeout 20 adb -s "$GUEST" emu kill >/dev/null 2>&1 || true
  exit 1
fi
log "second emulator booted"
for scale in window_animation_scale transition_animation_scale animator_duration_scale; do
  adb -s "$GUEST" shell settings put global "$scale" 0 || true
done
# Two emulators share one small build machine; let both finish starting up.
sleep 60

on "$HOST"; adb install -r PadelSync-phone.apk
on "$GUEST"; adb install -r "$GUEST_APK"
for device in "$HOST" "$GUEST"; do
  on "$device"
  quiet_system_dialogs
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
if [ -z "$CODE" ]; then fail "no-join-code" "the host's screen shows no join code"; fi
shot host-court-open

log "--- guest: find the court and join"
on "$GUEST"
adb shell am start -n "$GUEST_ACTIVITY"
wait_for "Join a court" 90
guest_join
shot guest-joined
on "$HOST"
expect "2 devices" "host shows two devices in the session"

log "--- a point scored on the host reaches the guest"
tap_many "Team A" 2
on "$GUEST"
expect "Team A. Points 30." "guest shows 30 after two host taps"

log "--- a point scored on the guest reaches the host"
# Leave a gap, so this is a new rally and not the previous one reported twice.
sleep 5
tap_many "Team B" 1
expect "Team B. Points 15." "guest shows its own tap"
on "$HOST"
expect "Team B. Points 15." "host shows the guest's tap"

log "--- undo from the guest"
on "$GUEST"
tap "$UNDO"
on "$HOST"
expect "Team B. Points 0." "host shows the guest's undo"

log "--- both tap the same point at the same moment"
sleep 5
# fail must not run inside $(...), so each lookup is checked on its own line.
on "$HOST"
if ! host_xy=$(find_center "Team A"); then fail "tap-Team-A" "MISSING: 'Team A' is not on the host's screen"; fi
on "$GUEST"
if ! guest_xy=$(find_center "Team A"); then fail "tap-Team-A" "MISSING: 'Team A' is not on the guest's screen"; fi
adb -s "$HOST" shell input tap $host_xy &
host_tap=$!
adb -s "$GUEST" shell input tap $guest_xy &
guest_tap=$!
# Wait for the two taps only: a bare "wait" would also wait for the
# second emulator, which runs in the background for the whole script.
wait "$host_tap" "$guest_tap"
sleep 4
on "$HOST"
expect "Team A. Points 40." "host counted the simultaneous taps once"
shot host-after-race
on "$GUEST"
expect "Team A. Points 40." "guest shows the same score"
shot guest-after-race

log "--- the guest scores the same rally a second after the host"
sleep 5
adb -s "$HOST" shell input tap $host_xy
sleep 1
adb -s "$GUEST" shell input tap $guest_xy
sleep 3
on "$HOST"
expect "Team A. Points 0. Games 1." "host counted the rally once"
on "$GUEST"
expect "Team A. Points 0. Games 1." "guest shows one game, not a second point"
shot guest-same-rally
sleep 5

log "--- guest leaves, then joins again"
guest_leave
on "$HOST"
expect "1 device" "host notices the guest leaving"
on "$GUEST"
wait_for "Join a court" 30
guest_join
expect "Team A. Points" "guest is back in the match after rejoining"
on "$HOST"
expect "2 devices" "host shows the guest again"

log "--- host stops sharing"
tap "Menu"; tap "Stop sharing this court"
expect "This device only" "host carries on alone"
on "$GUEST"
expect "Court closed" "guest is told the court closed"
shot guest-court-closed
tap "Back"
expect "Join a court" "guest is back on its home screen"

for device in "$HOST" "$GUEST"; do
  on "$device"
  check_crashes
done
timeout 20 adb -s "$GUEST" emu kill >/dev/null 2>&1 || true
log "RESULT: $([ "$FAILED" = 0 ] && echo all checks passed || echo the app crashed)"
exit $FAILED
