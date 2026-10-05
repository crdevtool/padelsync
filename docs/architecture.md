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
- Testing through TestFlight. The first signed build, version 1.0 build 5,
  was uploaded on 4 October 2026 (section 8); nobody has installed it yet.
- A first real Google Play upload. The bundles are built and signed with the
  upload key (section 9); uploading them is done by hand.

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
   a Mac. It then asks App Store Connect about the key, which takes a few
   seconds: that Apple accepts it, that it has the Admin role, that it
   belongs to the team named in `APPLE_TEAM_ID`, and that the app and both
   App IDs exist. Whatever is wrong is named at the top of the run's page.
   Without this, any of them shows only at the end of the Mac build, as "No
   Accounts with App Store Connect Access";
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

The default way, signing at export only, is the one that has run: it
produced the first upload. Signing the archive as well has never run.

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

## 9. Releasing to Google Play

The phone app and the Wear OS app are published as one Play app,
`com.crdevtool.padelsync`, from two separate app bundles (`.aab`). The
workflow builds and signs both; uploading them is done by hand in Play
Console.

### Version numbers

Play keeps one list of version codes per app ID, shared by the phone and the
watch, and refuses a code it has seen. So the two apps take their codes from
one release number:

| | Version code | Release 7 | Release 8 |
| --- | --- | --- | --- |
| Phone app | release number x 10 + 1 | 71 | 81 |
| Wear OS app | release number x 10 + 2 | 72 | 82 |

The release number is the workflow's run number, so it rises by itself. The
watch's code is the higher of each pair, so a watch that could be offered
both is given the watch app. The version name players see is
`padelsync.versionName` in `gradle.properties`; raise it there. The logic is
in `buildSrc/src/main/kotlin/PlayRelease.kt`. Builds made without a release
number, which includes every debug build, are release 0: codes 1 and 2.

### The upload key

Google Play signs what players install with a key it keeps itself (Play App
Signing). What you hold is the **upload key**: it proves that a bundle comes
from you. It is created once, on your own computer, and never stored in this
repository.

Create it on Windows, in PowerShell, with the `keytool` that comes with
Android Studio:

```powershell
& "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -genkeypair -v -storetype PKCS12 -keystore "$HOME\padelsync-upload.jks" -alias padelsync-upload -keyalg RSA -keysize 4096 -validity 10000
```

It asks for a password (twice), then for your name, organisation, city and
two-letter country code, then for `yes` to confirm. The file appears as
`padelsync-upload.jks` in your user folder, outside the project on purpose.
With this kind of keystore the key has no password of its own: its password
is the keystore's.

**Back the keystore and its password up, in two places that are not this
computer** (a password manager that stores files, and an offline copy). If
either is lost, no further update can be uploaded until Google has reset the
upload key, which is a support request that takes days. If the file is ever
exposed, ask for the same reset.

Then add four repository secrets on GitHub (Settings > Secrets and variables
> Actions):

| Secret | Value |
| --- | --- |
| `PLAY_KEYSTORE_BASE64` | The keystore file as base64 text (command below) |
| `PLAY_KEYSTORE_PASSWORD` | The password you chose |
| `PLAY_KEY_ALIAS` | `padelsync-upload` |
| `PLAY_KEY_PASSWORD` | The same password again |

This copies the keystore to the clipboard as base64, ready to paste into the
first secret:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("$HOME\padelsync-upload.jks")) | Set-Clipboard
```

The build reads the key from four environment variables:
`PLAY_KEYSTORE_FILE` (the keystore's path), `PLAY_KEYSTORE_PASSWORD`,
`PLAY_KEY_ALIAS` and `PLAY_KEY_PASSWORD`. With none of them set, a release
build comes out unsigned. With only some set, the build stops and says which
are missing. A release build is never signed with `debug.keystore`.

### Building the bundles on your own computer

No GitHub is needed. On Windows, in PowerShell, from the project folder:

```powershell
.\build_release.bat
```

or double-click `build_release.bat`. It asks for the release number and the
keystore password, builds and signs both bundles, checks that they are
signed, and copies them to `builds\` as
`PadelSync-phone-<version>-<code>.aab` and
`PadelSync-watch-<version>-<code>.aab`. The password is handed to the build
and removed again; it is not written anywhere. The keystore,
`padelsync-upload.jks`, is looked for in your user folder, then in this
project's `docs` folder, then in the project folder itself.
`build_debug.bat` makes the two test APKs the same way. Both batch files run
`build-android.ps1`, which takes `-Keystore <path>` for a keystore kept
anywhere else.

**The release number is yours to choose here**, and Play refuses a version
code it has already been given. Use a number higher than every release
uploaded so far, whichever way it was built: Play Console shows the last
version code, and dropping its last digit gives that release's number. The
GitHub workflow uses its run number, so if you use both, check the number
before each upload.

The script does less checking than the workflow: it confirms the signatures,
but not the target API levels, icons or watch declarations. Those are checked
on every CI run.

### Building the bundles on GitHub

Actions > **Google Play release (bundles)** > Run workflow. It

1. checks that the four secrets are set. If any is missing it says "Secrets
   not configured", names the missing ones, and ends there;
2. checks the key before building: that the keystore opens, that it holds the
   named key, that the key can sign, and that it stays valid past October
   2033, which Play requires. It prints the key's SHA-256 fingerprint, which
   is not a secret and should match what Play Console shows as the upload
   key certificate;
3. builds both bundles and checks each one: app ID, version code, target API
   level, launcher icon, not debuggable, signed with the upload key, and for
   the watch that it declares itself a standalone watch app;
4. offers `PadelSync-phone-<version>-<code>.aab` and
   `PadelSync-watch-<version>-<code>.aab` as one download, at the bottom of
   the run's page, for 30 days.

"Release number" can be filled in to set the number by hand; it is only
needed if the run number ever falls behind what Play has already seen.

**Rehearsal on every CI run.** The CI workflow runs the same script with a
throwaway key it makes on the spot (`release-play.sh rehearse`), so a change
that breaks the release build is caught long before a release. CI's "build"
option set to "release" runs the emulator tests on that build in place of the
debug one.

### What Play asks of an upload, and where it is

| Need | Where |
| --- | --- |
| Phone app targets Android 16 (API 36) | `targetSdk` in `mobile/build.gradle.kts` |
| Wear OS app targets Android 15 (API 35) or newer | `targetSdk` in `wear/build.gradle.kts` |
| Launcher icon | `androidkit/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`, used by both apps |
| Wear OS: watch-only and standalone | `uses-feature android.hardware.type.watch` and `com.google.android.wearable.standalone` in `wear/src/main/AndroidManifest.xml` |
| App bundles, signed, not debuggable | The release build type of both apps |
| Readable crash reports | The release build is shrunk with R8; its mapping file travels inside each bundle |
| Native libraries built for 16 KB memory pages | The apps have none of their own; the check fails if a library brings one that is not |

The release build differs from the debug build in three ways: it is signed
with the upload key, it cannot be debugged, and its code is shrunk and
renamed by R8. The emulator tests are run on it to show that it behaves the
same.

### Uploading by hand

Once, before the first upload: create the app in Play Console (All apps >
Create app). The app ID is fixed by the first bundle uploaded.

**Phone app, Internal testing track**

1. Test and release > Testing > Internal testing > Create new release.
2. On the first release, accept Play App Signing when asked.
3. Upload `PadelSync-phone-<version>-<code>.aab`, give the release a name,
   then Next > Save and publish.
4. On the Testers tab, add the testers' Google accounts and send them the
   opt-in link shown there.

**Wear OS app, Wear OS track**

1. Test and release > Advanced settings > Form factors > Add form factor >
   Wear OS.
2. Back on Internal testing, choose **Wear OS** in the form factor selector
   at the top of the page, so that the release goes to the Wear OS track and
   not the phone one. Create new release, upload
   `PadelSync-watch-<version>-<code>.aab`, save and publish.
3. Add at least one Wear OS screenshot to the store listing, and mention Wear
   OS in the description; Play asks for both.
4. In Advanced settings > Form factors, opt in to Wear OS and accept its
   review policy. Wear OS releases are reviewed against the Wear OS quality
   guidelines. Do not skip this: until Wear OS shows as Active there, no
   watch receives the app.

Testers install both from the Play Store once they have opened the opt-in
link: the phone app on the phone, the watch app from the Play Store on the
watch.

### Phone and watch on Play: form factors and tracks

What was learned on the first watch update (5 October 2026), so it does not
have to be worked out again.

- **Two sets of tracks.** A Wear OS bundle cannot go into a phone track.
  Since September 2023 Google only serves watches from dedicated Wear OS
  tracks; dropping both `.aab` files into one release is the old method and
  no longer works. Advice that says otherwise, including from AI assistants,
  describes that old method.
- **The phone never delivers the watch app.** A watch gets it from the Play
  Store on the watch, from whichever Wear OS track the bundle is in. Updating
  the phone app does nothing to the watch.
- **The opt-in must be finished.** Advanced settings > Form factors must show
  Wear OS as **Active**. It has three steps: Wear OS screenshots, a bundle on
  a testing track, and **Opt-in to Wear OS and agree to the review policy**.
  With the third step open (shown as "2 of 3 complete") a Wear OS track can
  exist and say "Available to internal testers" while no watch receives
  anything. The opt-in starts Google's Wear OS quality review.
- **Where each build goes during testing.** Phone bundle: the phone closed
  track, with the testers' Google Group. Watch bundle: the Wear OS
  **internal** track; add the testers who own a watch to the internal tester
  list (up to 100, no review, live within minutes).
- **No second group of 12 testers.** The rule for new personal accounts, 12
  testers opted in for 14 days, is stated per app, and the phone closed test
  meets it. Google's page does not mention form factors; guides from
  tester-recruiting sites say it is shared between phone and watch. A Wear OS
  closed track is therefore optional. One named "Wear OS closed" was created
  and left empty in case Play asks for it when applying for production.
- **Promote only lists tracks that exist.** To use a Wear OS closed track,
  create it first under Testing > Closed testing with the form factor
  selector on **Wear OS only**, set its testers and countries (a track with
  no countries delivers to nobody), then add the bundle with **Add from
  library**. Uploading the same file again is refused, because the version
  code is taken.
- **Same version label on both.** The phone and watch builds of one release
  share a version name, so the label does not show whether a watch updated;
  look for what changed in the app instead.
- **A copy installed from an APK blocks the Play version.** It is signed with
  a different key. Uninstall it from the watch (or phone) before installing
  from the Play Store.

Play Console also asks for things that are not in this repository before a
release can go beyond testing: a privacy policy address, the Data safety
form, a declaration for the foreground service that keeps a court connected
(type "connected device"), a content rating, and the store listing's icon,
feature graphic and screenshots.
