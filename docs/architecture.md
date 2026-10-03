# Architecture

Decisions made so far and the reasoning behind them. Update this file when a
decision changes.

## 1. Goal

Any player on the court can score from whatever they have with them: an
iPhone, an Android phone, an Apple Watch or a Wear OS watch. Every device
shows the same score at once, with no internet and no account.

## 2. Stack

| Layer | Technology | Runs on |
| --- | --- | --- |
| Shared core (`core/`) | Kotlin Multiplatform, no dependencies | All four device types |
| Android phone (`mobile/`) | Jetpack Compose | Android 8+ |
| Wear OS watch (`wear/`) | Compose for Wear OS | Wear OS 3+ |
| iPhone (`apple/iOS`) | SwiftUI | iOS 17+ |
| Apple Watch (`apple/watchOS`) | SwiftUI | watchOS 10+ |

The first version of this project used Flutter. It was dropped because
Flutter does not run on Apple Watch, so the watch would have needed the rules
and the sync protocol written a second time in Swift. With Kotlin
Multiplatform the same compiled core runs on all four device types, and each
platform gets a native UI and direct access to its own Bluetooth APIs, which
is where the hard part of this app lives.

## 3. Shared core

Everything that must behave identically everywhere lives in `core/` and is
tested there, without any device:

- `engine/` The rules of padel and tennis as a pure function:
  `(score, point) -> score`. Covers best of 1, 3 or 5; advantage, golden
  point and star point; tiebreak and advantage sets; and a full, advantage or
  match-tiebreak final set.
- `match/` `MatchLog`, the host's authoritative record, and `MatchSnapshot`,
  the small value every device holds a copy of.
- `sync/` The wire format, packet framing, and the host and guest session
  logic. No Bluetooth code: the sessions are told what happened and answer
  with the packets to send.
- `ui/` `ScoreView`, the already-formatted scoreboard every app draws, so the
  wording and the call-outs (game point, golden point, ...) cannot differ
  between devices.

## 4. How devices stay in sync

### One host, everyone else a guest

One device hosts the court and owns the match. Every other device, phone or
watch, connects **directly** to the host over Bluetooth LE. A watch does not
go through its paired phone, so it does not matter which phone belongs to
which watch, or whether a player brought a phone at all.

```
   Apple Watch    Wear OS watch    iPhone    Android phone
        \               |             |            /
         +--------------+------+------+-----------+
                               |
                          Host device
                    (owns the match, decides
                      which taps count)
```

A single authority is used, rather than devices negotiating among
themselves, because scoring has a natural order (one point at a time) and the
real problem is the same point being tapped on two devices. One authority
solves that with one check.

### What is sent

- **Guest to host: a tap.** "Team A won the point" or "undo", plus the
  version of the score the guest was looking at.
- **Host to everyone: the whole match.** The format, every point played so
  far at one bit per point, and a version number. A long three-set match is
  under 80 bytes.

Sending the whole match every time, instead of only what changed, is what
makes the system forgiving. A device that missed something, joined late, or
dropped out for a minute is fully up to date the moment it receives one
message. The host also repeats the state every two seconds.

### The cases that matter on a court

| Situation | What happens |
| --- | --- |
| Two players tap the same point at the same instant | The host counts the first. The second was made against an older version, so it is refused and that device shows "already scored". |
| A second player scores the same point a second or two later | Also refused. Two real points cannot be scored on different devices within 4 seconds, so the host treats the second as the same rally. One player tapping several times in a row is allowed, for catching up the score. |
| Someone else scores | Every other device buzzes, so players know the point is in without looking. |
| A watch loses the link for a few seconds | Taps made meanwhile are kept. On reconnection they are sent if the score has not moved, and dropped if someone else scored in the meantime. |
| A tap is sent twice because of a retry | Each tap has an id; the host recognises the repeat and ignores it. |
| A player mis-taps and immediately undoes | Taps go to the host one at a time, so an undo can only ever remove that player's own point, never somebody else's. |
| A device joins mid-match | It receives the whole match in the first message. |
| The host's app is closed | The match is saved on the host and can be resumed. Any guest also holds a full copy and can take over hosting (the protocol supports this; the apps do not offer it yet). |

Every row except the buzz is covered by tests in `core/`, including a randomised test
that throws 200,000 events at a simulated eight-device court and checks that
every device ends up on the host's score.

### Joining

The host shows a 4-digit code. A guest picks the court from a list of nearby
courts and enters the code. The code is checked by the host and is never
broadcast, so someone on the next court cannot join by accident.

## 5. What each device can do

| Device | Score alone | Join a court | Host a court |
| --- | --- | --- | --- |
| Android phone | Yes | Yes | Yes |
| Wear OS watch | Yes | Yes | Yes |
| iPhone | Yes | Yes | Yes, while the app is on screen (see below) |
| Apple Watch | Yes | Yes | **No** |

**Apple Watch cannot host.** watchOS lets an app connect to Bluetooth devices
but not advertise as one, and a host has to advertise. This is an Apple
platform rule, not something the app can work around. In practice it means a
court needs at least one phone, or one Wear OS watch, within Bluetooth range
to act as host. Four players wearing only Apple Watches, with every phone out
of range, can each score alone but cannot sync.

**iPhone hosting works best in the foreground.** When an iPhone app goes to
the background, iOS keeps existing Bluetooth connections alive but hides the
advertisement from non-Apple devices. An Android phone or Wear OS watch can
therefore join an iPhone-hosted court only while the host app is on screen.
Where there is a choice, an Android phone makes the better host: it keeps
advertising with the screen off.

## 6. Limits to know about

- **Up to 8 devices per court** (the host plus 7). Phones generally sustain
  about seven simultaneous Bluetooth LE connections.
- **Apple Watch app runs while it is on screen.** watchOS suspends an app
  shortly after the wrist is lowered, which drops the link; it reconnects and
  catches up when the wrist is raised. Running as a workout session would
  keep it connected throughout and is the planned fix.
- **Reconnecting to an iPhone host** from Android after a long gap may need
  the player to re-join, because iPhones change their Bluetooth address
  periodically.

## 7. Not built yet

- Match history and statistics.
- Choosing which team "you" are, so your own side is always on the same half
  of the screen.
- Taking over as host from the app when the host leaves.
- Apple Watch workout session (see above).
- Store releases, which need signing set up for both stores.
