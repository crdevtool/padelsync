# PadelSync

Padel and tennis scoring for phones and watches, where every device on the
court shows the same score. Any player can score from an iPhone, an Android
phone, an Apple Watch or a Wear OS watch. It works over Bluetooth, with no
internet and no account.

## Status

| Part | State |
| --- | --- |
| Shared rules and sync core | Built and tested (243 automated tests) |
| Android phone app | Builds and runs on an emulator. Not yet tried on a real phone. |
| Wear OS watch app | Builds and runs on an emulator. Not yet tried on a real watch. |
| iPhone app | Builds for simulator and device, and runs on a simulator. Not yet tried on a real iPhone. |
| Apple Watch app | Builds for simulator and device, and runs on a simulator. Not yet tried on a real watch. |
| Sync between Android devices | Passes an automated test between two emulators over simulated Bluetooth (see below). **Not yet tried on real hardware.** |
| Finding a lost host again, taking over as host | In the same emulator test for Android and Wear OS. Built for iPhone; Apple Watch can re-find but cannot host. |
| Sync involving Apple devices | **Untested.** Apple simulators have no Bluetooth, so this needs a real iPhone or Apple Watch. |
| Match history | On the phone apps. Not on the watch apps yet. |
| Player names, play all sets, serve side and server, who can score, spoken score, result screen | On all four apps. |
| TestFlight release | Workflow in place and rehearsed unsigned. **Never run with signing:** the Apple developer account is not enrolled yet. |

The emulator sync test hosts a court on one device and joins it from another.
It checks that a point scored on either device appears on both, that undo
works across devices, that the same rally scored on both devices counts once,
that a device can leave and rejoin, that a guest finds the court again by
scanning after the host's app is closed and reopened, that guests are told
when the host closes the court, and that a guest can then take over as host
and be joined by the old one. Real radios, real distances and real watches can still
behave differently, so treat this as "the logic and the Bluetooth plumbing
work", not as "it is proven on a court".

## Try it

The latest builds are on the [test build page](../../releases/tag/test-build):
`PadelSync-phone.apk`, `PadelSync-watch.apk`, and
`PadelSync-iOS-simulator.zip` for trying the iPhone app in a browser.

Step-by-step instructions, including the Wear OS pairing steps and a table of
common error messages, are in [docs/install.md](docs/install.md).

## Layout

```
core/         Shared rules, match record, sync protocol (Kotlin Multiplatform)
androidkit/   Android Bluetooth layer shared by the phone and watch apps
mobile/       Android phone app
wear/         Wear OS watch app
apple/        iPhone and Apple Watch apps (SwiftUI), generated with XcodeGen
docs/         Architecture and the Bluetooth protocol
```

## Building

Android, on any computer with a JDK 17+ and the Android SDK:

```
./gradlew :core:jvmTest                 # run the shared tests
./gradlew :mobile:assembleDebug         # phone APK
./gradlew :wear:assembleDebug           # watch APK
```

Apple, on a Mac with Xcode 16+:

```
brew install xcodegen
./gradlew :core:assemblePadelSyncCoreDebugXCFramework
apple/use-core.sh debug
xcodegen generate --spec apple/project.yml
open apple/PadelSync.xcodeproj
```

The iPhone app contains the Apple Watch app, so building the `PadelSync`
scheme builds both.

The builds and tests also run on GitHub, started by hand from the **Actions**
tab ("Run workflow"), where the emulator tests and the Apple build can be
switched on for a run.

## Releasing to TestFlight

The apps are published as `com.crdevtool.padelsync` (iPhone, and Android
phone and watch) and `com.crdevtool.padelsync.watchkitapp` (Apple Watch).

Before the first release, at Apple: enrol in the developer program, then
register both App IDs, **with HealthKit switched on for the watch one**, and
create the app in App Store Connect.

Then add four repository secrets (Settings > Secrets and variables >
Actions):

| Secret | Value |
| --- | --- |
| `ASC_KEY_ID` | App Store Connect API key: Key ID |
| `ASC_ISSUER_ID` | App Store Connect API key: Issuer ID |
| `ASC_KEY_P8` | The contents of the key's `.p8` file |
| `APPLE_TEAM_ID` | The developer team's ID |

The key must be a **team key with the Admin role**; automatic signing for the
App Store does not work with a lesser role.

To release: Actions > **Apple release (TestFlight)** > Run workflow. It
builds on a Mac with Xcode 26, signs at export, and uploads; the build number
is the run number and the version is `MARKETING_VERSION` in
`apple/project.yml`.
With a secret missing it stops at the first step with "Secrets not
configured". "Apple device build (release rehearsal)" runs the same build
unsigned, with no Apple account.

Details and reasons are in
[docs/architecture.md](docs/architecture.md), section 8.

## Documentation

- [Architecture](docs/architecture.md): how it fits together, what each
  device can and cannot do, and the known limits.
- [Bluetooth protocol](docs/ble-protocol.md): the exact contract between
  devices.
- [Manual tests](docs/manual-tests.md): checks that need real devices.
