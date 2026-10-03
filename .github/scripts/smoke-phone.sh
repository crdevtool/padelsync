#!/usr/bin/env bash
# Drives the phone app through a solo match on an emulator and takes
# screenshots along the way. Bluetooth is not exercised: emulators have none.
source .github/scripts/smoke-lib.sh
SWIPE_X=540; SWIPE_FROM=1800; SWIPE_TO=900

adb install -r PadelSync-phone.apk
grant_permissions
adb logcat -c
adb shell am start -n com.padelsync.app/.MainActivity
wait_for "New match" 60 && shot home

tap "New match" && wait_for "Start match"
shot setup-default
tap "Tennis"; tap "Best of 5"; tap "Match tiebreak"; tap "Team B"
shot setup-changed
tap "Padel"; tap "Best of 3"; tap "Full set"; tap "Team A"
tap "Start match"

wait_for "Team A" && shot score-start
texts
tap_many "Team A" 3
tap_many "Team B" 3
shot golden-point
texts
tap_many "Team B" 1
shot game-to-b
tap "Undo"
shot after-undo
texts
tap_many "Team A" 1
tap_many "Team A" 20
shot set-in-progress
texts
tap_many "Team A" 24
shot match-won
texts
tap "Undo"
shot match-reopened

tap "Menu" && shot menu
tap "Play with others"
sleep 3
shot court-open-attempt
texts

tap "Menu"; tap "End match"; sleep 1
shot end-confirm
tap "End match"
wait_for "Join a court" && shot home-again
tap "Join a court"; sleep 3
shot join
texts

check_crashes
exit $FAILED
