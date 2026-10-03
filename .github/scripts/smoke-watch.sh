#!/usr/bin/env bash
# Drives the Wear OS app through a solo match on a watch emulator and takes
# screenshots along the way. Bluetooth is not exercised: emulators have none.
source .github/scripts/smoke-lib.sh
SWIPE_X=200; SWIPE_FROM=300; SWIPE_TO=120

# Give the freshly booted system a moment to settle before starting.
sleep 20
adb install -r PadelSync-watch.apk
grant_permissions
adb logcat -c
adb shell am start -n com.padelsync.app/com.padelsync.wear.MainActivity
wait_for "New padel match" 60 && shot home
texts

tap "New padel match"
wait_for "Team A" && shot score-start
texts
tap_many "Team A" 3
tap_many "Team B" 3
shot golden-point
texts
tap_many "Team B" 1
shot game-to-b
tap "UNDO"
shot after-undo
tap_many "Team A" 21
shot set-in-progress
texts
# Two sets to love decides the match, but all three sets are played.
tap_many "Team A" 24
shot match-decided
texts
tap_many "Team B" 24
sleep 2
shot match-won
texts
tap "Scoreboard"
shot final-scoreboard

tap "MENU" && shot menu
texts
tap "Play with others"
sleep 3
shot court-open-attempt
texts
tap "Back to score"
shot back-to-score
tap "MENU"; tap "End match"
wait_for "New padel match" && shot home-again
tap "Join a court"; sleep 3
shot join
texts

check_crashes
exit $FAILED
