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
  point and star point; tiebreak and advantage sets; a full, advantage or
  match-tiebreak final set; and playing every set after the match is already
  decided, as social padel usually is. It also works out who serves next (the
  team, which of its two players in doubles, and from which side) and when to
  change ends.
- `match/` `MatchLog`, the host's authoritative record, `MatchSnapshot`, the
  small value every device holds a copy of, and `Roster`, the players' names.
- `sync/` The wire format, packet framing, and the host and guest session
  logic. No Bluetooth code: the sessions are told what happened and answer
  with the packets to send.
- `ui/` `ScoreView`, the already-formatted scoreboard every app draws, so the
  wording and the call-outs (game point, golden point, ...) cannot differ
  between devices; `ScoreSpeech`, the sentences a device says out loud
  ("30 15", "Game, Ana and Leo", "Change ends"), with per-device settings for
  what is announced; and `MatchStats`, the numbers on the result screen
  (points, breaks of serve, best run).

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
| A device joins mid-match | It receives the whole match in the first message, players' names included. |
| The host wants only some people to score | The host makes a device view-only, or lets only chosen devices score. A view-only device shows the score and is told why its tap did not count. The host refuses its commands even if the app on it were modified. |
| The app shows the wrong player serving | In doubles each team chooses its serving order every set. Anyone allowed to score can swap a team's server; the swap is replicated like a point. |
| The host's app is closed | The match is saved on the host and can be resumed under the same join code, and guests still looking for the court come back by themselves. |
| The host's device dies or leaves | Any guest that can host holds a full copy of the match and is offered "Host this court from this device" after 20 seconds. The other guests follow without typing anything. See "Taking over as host". |

Every row except the buzz is covered by tests in `core/`, including a randomised test
that throws 200,000 events at a simulated eight-device court (taps, server
swaps, permission changes, renames, lost packets, dropped links) and checks
that every device ends up on the host's score.

### Voice

Each device decides for itself what to say; nothing about speech is sent
between devices. A device announces only scores the host has confirmed,
never its own unconfirmed tap, so the voice cannot call a point that is then
refused. By default the phone hosting the match speaks and guests stay
quiet, so four phones on one court do not all talk at once.

### Finding the host again

A guest whose link drops first retries the Bluetooth address it joined. That
is enough for a short gap, but phones change their Bluetooth address from
time to time (iPhones always, recent Android versions too), and after a long
gap the old address leads nowhere.

So if the link has been down for 15 seconds, the guest also scans for a
court advertising the name it joined, while the direct retry carries on. It
connects to what it finds and sends the join code the player already
entered. The court's first answer decides:

- the same match, at the same hosting epoch or a later one: accepted, and
  this becomes the device to reconnect to from now on;
- a different match, an earlier epoch, or a refusal of the code: somebody
  else's court under the same name. The guest disconnects, leaves that court
  alone for two minutes, and keeps looking. The player is not shown a
  "wrong code" message for a code they did not just type.

A host that is closed and reopened keeps its join code, and a match resumed
after the app was closed reopens its court by itself if the court was open,
so guests still looking for it are let back in without anyone typing
anything.

Courts are advertised as the device's name followed by three characters of
its id (`Pixel 8 A3F`, `iPhone 7C2`). Device names alone are rarely unique:
iOS no longer gives apps the owner's name for the phone, so every iPhone
would otherwise host a court called "iPhone".

A host's app can also stop while its Bluetooth link stays up: the app is
closed or crashes, and the phone's radio keeps the connection. Nothing
reports that, so a guest goes by the host's silence. A live host repeats the
match every two seconds. After 8 silent seconds the guest sends its hello
again, which every host answers with the match, and which wakes an iPhone
host that was only suspended. After 16 silent seconds the guest treats the
link as broken and starts reconnecting and looking.

### Taking over as host

If the host has been out of reach for 20 seconds, or has closed the court, a
guest that is able to host (Android phone, Wear OS watch, iPhone) offers
**Host this court from this device**, with a warning that only one player
should do it. On confirmation the guest:

1. starts hosting from its last confirmed copy of the match, with the
   hosting epoch raised (by a random 1 to 64, for the reason below);
2. opens the court under the **same name** and the **same join code** the
   guests already used.

The other guests are by then looking for that name (see "Finding the host
again"). They find the new court, send the code they already have, see the
same match at a later epoch, and accept it. Nobody types anything.

Two things can go wrong, and both end with two hosts for one match:

**Two guests take over at the same moment**, or **a guest takes over while
the real host is only out of range and still playing.** The guests cannot
settle this: each is linked to one host and cannot see the other. So every
host looks for another court under its own name every 30 seconds, joins one
it finds for a moment with its own join code, as a guest would, and compares
(`HostSession.judgeRival`):

1. The court with **more devices** keeps the match. Fewer players are
   disturbed, and a guest who took over by mistake comes back to the real
   host without anyone losing a point.
2. With equal numbers, the **later hosting epoch** keeps it. The random
   step makes two simultaneous takeovers land on different epochs 63 times
   in 64.
3. With equal epochs, the court that has **recorded more** keeps it.

The host that gives way closes its court and joins the other one as an
ordinary guest. Its goodbye releases its guests (see "When the host closes
the court"): they look for the court by name at once and accept the other
host whatever its epoch. The host that stays also raises its epoch above the
other's when it is the one to ask, so that a guest of the other court which
only lost its link, and never heard the goodbye, accepts it too.

**The old host comes back with the old epoch.** It is still advertising the
same name and the same code, alone. Guests of the new host that come across
it see the same match at an earlier epoch and refuse it. The old host's own
lookout finds the new court, sees that it has more devices, gives way and
joins it as a guest. Points it recorded alone after the takeover are lost;
points on the court with the players are not.

An Apple Watch cannot host, so it cannot take over; it tells the wearer to
ask a player with a phone.

A tap made while the match was changing hands is not lost. If the new host
shows the very score the tap was made against (same match, same point, only
the hosting epoch is later), the guest sends the tap again addressed to the
new epoch. If the score has moved on, the tap is dropped and the player told.

### When the host closes the court

A host that stops sharing, ends the match, or gives way to another court
says goodbye to its guests. They show "Court closed" and stop chasing that
device, but for three minutes they keep looking for the court's name:
another player may take the match over, the host may reopen the court, or
the match may have moved to another court. A guest released this way accepts
the same match from whoever carries it on, at any hosting epoch. After 10
seconds without finding it, a guest that can host is offered the host's
place.

### Bluetooth switched off and on

Android tells an app nothing when a scan, an advertisement or a pending
connection dies with the Bluetooth switch, and restarts none of them when it
comes back. The app watches the switch itself. When Bluetooth goes off, a
guest drops what it was doing and a host closes its court without a goodbye
(none can be sent). When it comes back, the guest reconnects and looks for
the court again, and the host reopens the court under the same name and
code.

### Joining

The host shows a 4-digit code. A guest picks the court from a list of nearby
courts and enters the code. The code is checked by the host and is never
broadcast, so someone on the next court cannot join by accident.

## 5. What each device can do

| Device | Score alone | Join a court | Host a court | Take over as host |
| --- | --- | --- | --- | --- |
| Android phone | Yes | Yes | Yes | Yes |
| Wear OS watch | Yes | Yes | Yes | Yes |
| iPhone | Yes | Yes | Yes, while the app is on screen (see below) | Yes |
| Apple Watch | Yes | Yes | **No** | **No**: it asks for a phone |

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
advertising with the screen off. The iPhone app says so where it matters:
while a court is open it shows "Keep PadelSync on screen so others can
join" under the join code, keeps the screen awake on every screen, and after
a spell in the background tells the host that Android devices could not join
meanwhile. For the same reason an iPhone host in the background cannot be
found again by a guest that has lost it, nor by another host looking for a
rival court.

**Android 11 and older need Location switched on to find courts.** On those
versions (which include Wear OS 3 watches) Android returns no Bluetooth scan
results while the device's Location switch is off. The app does not use the
player's location; the join screen explains this and offers a button to the
setting. It also affects finding a lost host again by scanning, which is
silent: with Location off, such a device relies on the direct reconnect.

## 6. Limits to know about

- **Up to 8 devices per court** (the host plus 7). Phones generally sustain
  about seven simultaneous Bluetooth LE connections.
- **Apple Watch app runs while it is on screen.** watchOS suspends an app
  shortly after the wrist is lowered, which drops the link; it reconnects and
  catches up when the wrist is raised. Running as a workout session would
  keep it connected throughout and is the planned fix.
- **Court names are matched as advertised.** A guest looking for its court
  again, and a host looking for a rival, go by the advertised name, which
  Android cuts to 12 bytes and an iPhone may cut shorter. Names are compared
  leniently (one may be the beginning of the other), and the match id
  settles it, but how iPhones shorten names in practice has not been checked
  on real hardware.
- **Anything involving Bluetooth on Apple devices is untested.** Apple's
  simulators have no Bluetooth. The iPhone and Apple Watch apps build, and
  the iPhone app plays a whole match on a simulator, but joining, hosting,
  finding a host again and taking over have never run on Apple hardware.
- **A guest away from a host that then starts a new match** has to join
  again by hand. A guest that finds its court again by name accepts it only
  if it still carries the same match (see "Finding the host again" above),
  and a new match is a different match.

## 7. Not built yet

- Voice in languages other than English.
- Choosing which team "you" are, so your own side is always on the same half
  of the screen.
- Apple Watch workout session (see above).
- A first real TestFlight upload. The release workflow is in place (section
  8) but has never run with signing, because the Apple developer account is
  not enrolled yet.
- A Google Play release, which needs a release signing key and a Play
  account.

## 8. App identifiers and releasing to TestFlight

### Identifiers

| App | ID |
| --- | --- |
| iPhone app | `com.crdevtool.padelsync` |
| Apple Watch app | `com.crdevtool.padelsync.watchkitapp` |
| iPhone UI test bundle | `com.crdevtool.padelsync.uitests` |
| Android phone app and Wear OS app (one shared ID) | `com.crdevtool.padelsync` |

These are the IDs the apps are installed and published under. They are not
the code's package names: the Kotlin code stays in `com.padelsync.app`,
`com.padelsync.wear` and `com.padelsync.kit`. So on Android the start
screens are `com.crdevtool.padelsync/com.padelsync.app.MainActivity` (phone)
and `com.crdevtool.padelsync/com.padelsync.wear.MainActivity` (watch). The
build server's scripts keep the IDs in one place, at the top of
`.github/scripts/smoke-lib.sh`.

### The watch app travels inside the iPhone app

Apple delivers a watch app through its iPhone app. The `PadelSync` target
therefore depends on `PadelSyncWatch` and copies it into
`PadelSync.app/Watch/`, so building or archiving the iPhone app builds both.
The watch app still runs on its own (`WKRunsIndependentlyOfCompanionApp`),
joining a court directly over Bluetooth with no iPhone involved.

Both apps link the shared core from `apple/Frameworks`. `apple/use-core.sh
debug` or `release` copies the wanted build of the core there, which lets
one project file serve test builds and App Store builds.

### Once, at Apple, before the first release

1. Enrol in the Apple Developer Program.
2. Register the two App IDs under Certificates, Identifiers & Profiles >
   Identifiers: `com.crdevtool.padelsync`, and
   `com.crdevtool.padelsync.watchkitapp` **with the HealthKit capability
   switched on**. HealthKit is what the planned workout session needs; the
   app does not use it yet.
3. Create the app in App Store Connect (Apps > New App) with the bundle ID
   `com.crdevtool.padelsync`. An upload has nowhere to go without it.
4. Create an API key in App Store Connect under Users and Access >
   Integrations > App Store Connect API > **Team Keys**, with the role
   **Admin**. The first time, the Account Holder has to request access to
   the API on that page before a key can be created. Download the key's
   `.p8` file; Apple offers it once.
5. Add four repository secrets on GitHub (Settings > Secrets and variables >
   Actions):

   | Secret | Value |
   | --- | --- |
   | `ASC_KEY_ID` | The key's Key ID |
   | `ASC_ISSUER_ID` | The Issuer ID shown above the list of team keys |
   | `ASC_KEY_P8` | The whole contents of the `.p8` file, including the BEGIN and END lines |
   | `APPLE_TEAM_ID` | The team ID, under Membership details in the developer account |

**Why the key must be Admin.** The workflow uses automatic signing. For an
App Store build that means Apple signs with a distribution certificate it
keeps on its own servers. Only the Account Holder and Admins may use that
certificate by default. Other people can be given access with a checkbox in
App Store Connect, but that checkbox does not exist for API keys, so a key
with the App Manager or Developer role stops at "Cloud signing permission
error" during export. It must also be a team key: an individual key has no
Issuer ID.

### Running a release

On GitHub: Actions > **Apple release (TestFlight)** > Run workflow. It

1. checks that the four secrets are set. If any is missing it says "Secrets
   not configured", names the missing ones, and ends there without starting
   a Mac;
2. builds the release build of the shared core for device architectures;
3. archives the iPhone app, with the watch app inside;
4. checks the archive for what an upload needs (IDs, matching version
   numbers, icons, the encryption answer, the privacy declaration,
   architectures);
5. exports it with automatic signing, which is where Apple signs it for the
   App Store, and uploads it to App Store Connect. It shows up in TestFlight
   when Apple has finished processing it.

The version comes from `MARKETING_VERSION` in `apple/project.yml`; raise it
there for a new version. The build number is the workflow's run number, so
each release is higher than the last.

The workflow runs on GitHub's `macos-26` machine, because App Store Connect
has refused builds made with anything older than Xcode 26 since April 2026.
The secrets are passed to the build as environment values, written to a
temporary key file that is deleted at the end, and blanked out of Xcode's
output.

**Rehearsal without an Apple account.** The workflow "Apple device build
(release rehearsal)" runs the same script on the same machine without signing
or uploading (`.github/scripts/release-apple.sh rehearse`). It proves the
release build compiles for real devices and passes the same checks.

**Where the signing happens, and the choice that will come up later.** By
default the archive is built unsigned, exactly as in the rehearsal, and
signed once, at export. That works on a new developer account with nothing
registered. The workflow's "archive signing" option set to "automatic" signs
the archive as well, which has two costs:

- Apple only issues the development profile it needs to a team with at least
  one device registered. With none, the build stops at "Your team has no
  devices from which to generate a provisioning profile".
- Every run on a fresh build machine creates one more "Apple Development"
  certificate, marked as created via the API. An account holds only a limited
  number; when it is full the build fails until old ones are revoked.

It becomes necessary all the same once the app has entitlements, which the
HealthKit workout session will bring: an unsigned archive carries no
entitlements. At that point register a device, switch the option to
"automatic", and revoke the piled-up certificates from time to time.

Neither way has run yet, because the developer account is not enrolled.

### What an upload needs, and where it is

| Need | Where |
| --- | --- |
| 1024-pixel icon, iPhone app | `apple/iOS/Assets.xcassets/AppIcon.appiconset` |
| Icon, watch app | `apple/watchOS/Assets.xcassets/AppIcon.appiconset` |
| "No encryption of its own" (`ITSAppUsesNonExemptEncryption` = NO) | Both Info.plists, set in `apple/project.yml` |
| Privacy declaration | `apple/Shared/PrivacyInfo.xcprivacy`, in both apps |
| Release configuration that builds | Checked by the rehearsal workflow |
| No simulator-only settings in device builds | The x86_64 exclusion applies to simulator builds only; the release script fails if a device build leaves any architecture out |

The icon is the Android launcher icon redrawn at 1024 pixels: a placeholder
until there is real artwork.
