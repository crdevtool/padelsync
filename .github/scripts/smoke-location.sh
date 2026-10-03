#!/usr/bin/env bash
# Android 11 and older find no courts over Bluetooth while the phone's
# location is switched off. This checks, on an Android 11 phone emulator, that
# "Join a court" says so and offers the way to the switch, and that it starts
# searching by itself once location is back on.
# The run stops at the first screen or button that is missing; see smoke-lib.sh.
source .github/scripts/smoke-lib.sh
SWIPE_X=540; SWIPE_FROM=1800; SWIPE_TO=900

# The words the app uses, from androidkit's Labels.kt and the phone's JoinScreen.kt.
NOTICE_TITLE="Location is switched off"
NOTICE_PROMISE="PadelSync does not use or store your location"
NOTICE_BUTTON="Open location settings"
SEARCHING="Looking for courts nearby"
NEEDS_BLUETOOTH="Turn on Bluetooth to play with others."

# Switches the emulator's location on or off, by both routes: older and newer
# system images each listen to one of them.
set_location() {
  local mode=3 enabled=true
  if [ "$1" = off ]; then mode=0; enabled=false; fi
  adb shell settings put secure location_mode "$mode" || true
  adb shell cmd location set-location-enabled "$enabled" >/dev/null 2>&1 || true
  sleep 2
  LOCATION_MODE=$(adb shell settings get secure location_mode | tr -d '\r')
  log "location switched $1 (location_mode is now $LOCATION_MODE)"
}

# Give the freshly booted system a moment to settle before starting.
sleep 20
quiet_system_dialogs

sdk=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
log "emulator runs Android API level ${sdk:-unknown}"
if [ "${sdk:-99}" -gt 30 ]; then
  fail "wrong-android-version" "this check needs Android 11 (API 30) or older: newer versions do not tie Bluetooth to location"
fi

adb install -r PadelSync-phone.apk
grant_permissions
set_location off
if [ "$LOCATION_MODE" != 0 ]; then
  fail "location-still-on" "could not switch the emulator's location off"
fi
adb logcat -c
adb shell am start -n com.padelsync.app/.MainActivity
wait_for "Join a court" 60 && shot home
texts

# --- Location off: an explanation and a button, not an empty list -------------
tap "Join a court"
wait_for "$NOTICE_TITLE"
expect "$NOTICE_PROMISE" "the notice says the app does not use the player's location"
wait_for "$NOTICE_BUTTON"
shot location-off
texts
if screen_text | grep -qF "$SEARCHING"; then
  fail "searching-with-location-off" "the app claims to be searching while location is off"
fi

# --- The button opens the system's location settings -------------------------
tap "$NOTICE_BUTTON"
sleep 3
shot location-settings
texts
# texts has just read the screen into ui.xml; every element there names its app.
if ! grep -q "<node" ui.xml || grep -q 'package="com.padelsync.app"' ui.xml; then
  fail "settings-did-not-open" "'$NOTICE_BUTTON' did not leave the app for the system's location settings"
fi
log "PASS: '$NOTICE_BUTTON' opened another screen"

# --- Location on again: searching starts with no further taps ----------------
set_location on
if [ "$LOCATION_MODE" = 0 ]; then
  fail "location-still-off" "could not switch the emulator's location back on"
fi
# Back to the app, as the player would come back from the settings screen.
adb shell am start -n com.padelsync.app/.MainActivity
sleep 2
shot back-in-app

# The notice has to go by itself. What replaces it depends on the emulator:
# with Bluetooth, the normal searching text; without one (Android 11 images
# usually have none) the app has moved on to asking for Bluetooth, which also
# shows that the location check let it through.
outcome=""
for _ in $(seq 1 20); do
  seen=$(screen_text)
  if echo "$seen" | grep -qF "$SEARCHING"; then outcome=searching; break; fi
  if echo "$seen" | grep -qF "$NEEDS_BLUETOOTH"; then outcome=bluetooth; break; fi
  # The system may first ask whether the app may turn Bluetooth on.
  if allow_xy=$(locate "Allow"); then adb shell input tap $allow_xy; fi
  sleep 2
done
shot location-on
texts
case "$outcome" in
  searching)
    log "PASS: searching started by itself once location was on" ;;
  bluetooth)
    if echo "$seen" | grep -qF "$NOTICE_TITLE"; then
      fail "notice-still-shown" "the location notice is still on screen after location was switched on"
    fi
    log "PASS: the location notice went away by itself once location was on"
    log "NOTE: this emulator has no working Bluetooth (bluetooth_on: $(adb shell settings get global bluetooth_on | tr -d '\r')), so the app asks for Bluetooth instead of showing '$SEARCHING'" ;;
  *)
    fail "not-searching" "after location was switched on, the screen shows neither '$SEARCHING' nor a request for Bluetooth (screen shows: $seen)" ;;
esac

check_crashes
log "RESULT: $([ "$FAILED" = 0 ] && echo all checks passed || echo the app crashed)"
exit $FAILED
