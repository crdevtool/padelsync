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

# --- Setup: one short screen, with the format behind "Change" ----------------
tap "New match" && wait_for "Start match"
shot setup-default
texts
expect "At 6-6: advantage set, no limit" "the format card says the format in words"
# Every option of the format can be changed and changed back. In the order of
# the form: tapping only ever scrolls down to find things.
tap "Change" && wait_for "Done"
shot format-default
tap "Tennis"; tap "Best of 5"
# Short sets played on without a tiebreak, stopped at seven games.
tap "4 games"; tap "Advantage set"; tap "First to 7"
shot format-advantage-set
tap "Match tiebreak"
shot format-changed
tap "Done" && wait_for "Start match"
tap "Team B"
expect "At 4-4: advantage set, first to 7" "the card follows the changes"
shot setup-changed
texts
# The match below is scripted for golden point and tiebreak sets, which are
# not what the form starts with. The format screen opens at its top again.
tap "Change" && wait_for "Done"
tap "Padel"; tap "Best of 3"; tap "6 games"; tap "Golden point"; tap "Tiebreak"; tap "Full set"
shot format-padel
tap "Done" && wait_for "Start match"
tap "Team A"
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
expect "Golden point at deuce" "a new match starts from the format that was kept"
tap "Player 1"; adb shell input text "Ana"; sleep 1
shot setup-names
tap "Start match"
wait_for "Ana" && shot named-start
texts
tap_many "Ana" 4
shot named-game
texts

# --- Americano: a match of points, which can end level -----------------------
tap "Menu"; tap "New match"
wait_for "Start match"
tap "Change" && wait_for "Done"
tap "Americano"; tap "16 points"
shot format-americano
tap "Done" && wait_for "Start match"
expect "Americano to 16 points" "the card describes an Americano match"
shot setup-americano
texts
tap "Start match"
expect "POINT 1 OF 16" "an Americano match counts its points"
shot americano-start
tap_many "Ana" 8
tap_many "Team B" 7
shot americano-last-point
texts
tap_many "Team B" 1
expect "It's a draw!" "eight points each is a draw"
shot americano-draw
texts
tap "Undo last point"
tap_many "Ana" 1
expect "Ana wins!" "nine points to seven wins the match"
shot americano-won
texts

# --- A timed match: no last point, it is ended from the menu -----------------
tap "New match"
wait_for "Start match"
tap "Change" && wait_for "Done"
tap "Timed"
tap "Done" && wait_for "Start match"
shot setup-timed
tap "Start match"
expect "POINT 1" "a timed match counts its points"
tap_many "Team B" 3
tap_many "Ana" 2
shot timed-playing
tap "Menu"; tap "Finish match"
expect "Team B win!" "finishing a timed match gives the result"
shot timed-finished
texts
tap "Carry on playing"
expect "POINT 6" "a finished timed match can be carried on"
shot timed-reopened

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
