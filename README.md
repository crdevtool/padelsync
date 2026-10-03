# PadelSync

Padel and tennis scoring for phones and watches, where every device on the
court shows the same score. Any player can score from an iPhone, an Android
phone, an Apple Watch or a Wear OS watch. It works over Bluetooth, with no
internet and no account.

## Status

| Part | State |
| --- | --- |
| Shared rules and sync core | Built and tested (140 automated tests) |
| Android phone app | Builds and runs on an emulator. Not yet tried on a real phone. |
| Wear OS watch app | Builds and runs on an emulator. Not yet tried on a real watch. |
| iPhone app | Builds for simulator and device, and runs on a simulator. Not yet tried on a real iPhone. |
| Apple Watch app | Builds for simulator and device, and runs on a simulator. Not yet tried on a real watch. |
| Sync between Android devices | Passes an automated test between two emulators over simulated Bluetooth (see below). **Not yet tried on real hardware.** |
| Sync involving Apple devices | **Untested.** Apple simulators have no Bluetooth, so this needs a real iPhone or Apple Watch. |
| Match history | On the phone apps. Not on the watch apps yet. |

The emulator sync test hosts a court on one device and joins it from another.
It checks that a point scored on either device appears on both, that undo
works across devices, that the same rally scored on both devices counts once,
that a device can leave and rejoin, and that guests are told when the host
closes the court. Real radios, real distances and real watches can still
behave differently, so treat this as "the logic and the Bluetooth plumbing
work", not as "it is proven on a court".

## Try it on Android

1. Open the [latest test build](../../releases/tag/test-build) on the phone
   (sign in to GitHub; the repository is private).
2. Download `PadelSync-phone.apk` and open it. Allow installing from the
   browser when Android asks.
3. For a Wear OS watch, `PadelSync-watch.apk` has to be installed with `adb`
   from a computer: enable Developer options and Wireless debugging on the
   watch, then run `adb pair`, `adb connect` and
   `adb install PadelSync-watch.apk`.

To test syncing: start a match on one device, open the menu and choose
**Play with others**, then on a second device choose **Join a court** and
enter the 4-digit code.

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
xcodegen generate --spec apple/project.yml
open apple/PadelSync.xcodeproj
```

Every push to `main` runs all of this on GitHub: see the **Actions** tab.

## Documentation

- [Architecture](docs/architecture.md): how it fits together, what each
  device can and cannot do, and the known limits.
- [Bluetooth protocol](docs/ble-protocol.md): the exact contract between
  devices.
