# PadelSync

Padel and tennis scoring for phones and watches, where every device on the
court shows the same score. Any player can score from an iPhone, an Android
phone, an Apple Watch or a Wear OS watch. It works over Bluetooth, with no
internet and no account.

## Status

| Part | State |
| --- | --- |
| Shared rules and sync core | Built and tested (127 tests) |
| Android phone app | Builds; runs on an emulator; **not yet tried on real devices** |
| Wear OS watch app | Builds; runs on an emulator; **not yet tried on real devices** |
| iPhone app | Written; see the Actions tab for whether it compiles |
| Apple Watch app | Written; see the Actions tab for whether it compiles |
| Bluetooth sync between real devices | **Untested.** Emulators have no usable Bluetooth, so this needs real hardware. |
| Match history and stats | Not built yet |

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
