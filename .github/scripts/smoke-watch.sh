#!/usr/bin/env bash
# Drives the Wear OS app through a solo match on a watch emulator and takes
# screenshots along the way. Bluetooth is not exercised: emulators have none.
# The run stops at the first screen or button that is missing; see smoke-lib.sh.
source .github/scripts/smoke-lib.sh
SWIPE_X=200; SWIPE_FROM=300; SWIPE_TO=120

# Give the freshly booted system a moment to settle before starting.
sleep 20
quiet_system_dialogs
adb install -r PadelSync-watch.apk
grant_permissions
adb logcat -c
launch_app "$WATCH_ACTIVITY" "New padel match"
shot home
texts

tap "New padel match"
wait_for "Team A" && shot score-start
texts
tap_many "Team A" 3
tap_many "Team B" 3
shot deuce
texts
# A match started from the watch plays advantage: one point is not the game.
tap_many "Team B" 1
shot advantage-b
tap "UNDO"
shot after-undo
# Advantage, the game, then five games to love.
tap_many "Team A" 22
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
log "RESULT: $([ "$FAILED" = 0 ] && echo all checks passed || echo the app crashed)"
exit $FAILED
