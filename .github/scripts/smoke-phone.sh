#!/usr/bin/env bash
# Drives the phone app through solo matches on an emulator and takes
# screenshots along the way. Bluetooth is not exercised: emulators have none.
# The run stops at the first screen or button that is missing; see smoke-lib.sh.
source .github/scripts/smoke-lib.sh
SWIPE_X=540; SWIPE_FROM=1800; SWIPE_TO=900

# Give the freshly booted system a moment to settle before starting.
sleep 20
quiet_system_dialogs
adb install -r PadelSync-phone.apk
grant_permissions
adb logcat -c
adb shell am start -n "$PHONE_ACTIVITY"
wait_for "New match" 60 && shot home
texts

# --- Setup: every option can be changed and changed back ---------------------
tap "New match" && wait_for "Start match"
shot setup-default
tap "Tennis"; tap "Best of 5"; tap "Match tiebreak"; tap "Team B"
shot setup-changed
# Back to the top of the form: tapping only ever scrolls down to find things.
for _ in 1 2 3; do adb shell input swipe "$SWIPE_X" "$SWIPE_TO" "$SWIPE_X" "$SWIPE_FROM" 200; done
sleep 1
tap "Padel"; tap "Team A"; tap "Best of 3"; tap "Full set"
shot setup-padel
tap "Start match"

# --- A whole padel match, all three sets, without names ----------------------
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
shot set-won
texts
# Two sets to love decides the match, but all three sets are played.
tap_many "Team A" 24
shot match-decided
texts
tap_many "Team B" 23
shot last-set-point
tap_many "Team B" 1
sleep 2
shot match-won
texts
tap "Undo last point"
shot match-reopened
texts
tap_many "Team B" 1
sleep 2
tap "Scoreboard"
shot final-scoreboard
texts

# --- Menus -------------------------------------------------------------------
tap "Menu" && shot menu
tap "Voice"; sleep 1
shot voice
texts
tap "Done"
tap "Menu"; tap "Who can score"; sleep 1
shot who-can-score
tap "Done"
tap "Menu"; tap "Play with others"
sleep 3
shot court-open-attempt
texts

# --- A second match with names -----------------------------------------------
tap "Menu"; tap "New match"
wait_for "Start match"
for _ in 1 2 3; do adb shell input swipe "$SWIPE_X" "$SWIPE_TO" "$SWIPE_X" "$SWIPE_FROM" 200; done
sleep 1
tap "Player 1"; adb shell input text "Ana"; sleep 1
shot setup-names
tap "Start match"
wait_for "Ana" && shot named-start
texts
tap_many "Ana" 4
shot named-game
texts

tap "Menu"; tap "End match"; sleep 1
shot end-confirm
tap "End match"
wait_for "Join a court" && shot home-again
tap "Host a match"; wait_for "Start and open the court" && shot host-setup
tap "Back"
wait_for "Join a court"
tap "Join a court"; sleep 3
shot join
texts

check_crashes
log "RESULT: $([ "$FAILED" = 0 ] && echo all checks passed || echo the app crashed)"
exit $FAILED
