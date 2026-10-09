# Release log

Which build went to which store, and the exact code it was made from. Add a
line whenever a build is uploaded.

| Store | Version | Build | Built (UTC) | Commit | Built by | Where the file is |
| --- | --- | --- | --- | --- | --- | --- |
| Google Play | 1.0 | 71 (phone), 72 (watch) | 9 Oct 2026, 13:30 | `09753fc` | Play release workflow, [run 7](https://github.com/crdevtool/padelsync/actions/runs/37936893716) | On the run's page until 8 Nov 2026; in Play Console once uploaded. Adds Install on watch. Same app code as `7281694`, tested by [CI run 44](https://github.com/crdevtool/padelsync/actions/runs/37935000436). Android only; no new TestFlight build. |
| TestFlight | 1.0 | 7 | 6 Oct 2026, 21:26 | `89581f0` | Apple release workflow, [run 7](https://github.com/crdevtool/padelsync/actions/runs/37532763063) | At Apple only: App Store Connect > TestFlight. Same app code as `d4ba458`; only the release log differs. First build with the quick setup, advantage sets, Americano and the other new formats. |
| Google Play | 1.0 | 61 (phone), 62 (watch) | 6 Oct 2026, 21:12 | `d4ba458` | Play release workflow, [run 6](https://github.com/crdevtool/padelsync/actions/runs/37531686535) | On the run's page until 5 Nov 2026; in Play Console once uploaded. First build with the quick setup, advantage sets, Americano and the other new formats. Tested by [CI run 42](https://github.com/crdevtool/padelsync/actions/runs/37528360420). |
| TestFlight | 1.0 | 6 | 5 Oct 2026, 12:21 | `ad4362b` | Apple release workflow, [run 6](https://github.com/crdevtool/padelsync/actions/runs/37307840237) | At Apple only: App Store Connect > TestFlight. Same app code as `626baac`; first build with the serve line on the watch. |
| Google Play | 1.0 | 51 (phone), 52 (watch) | 5 Oct 2026, 11:57 | `626baac` | Play release workflow, [run 5](https://github.com/crdevtool/padelsync/actions/runs/37305889517) | On the run's page until 4 Nov 2026; in Play Console once uploaded. First build with the serve line on the watch. |
| TestFlight | 1.0 | 5 | 4 Oct 2026, 12:22 | `16b1734` | Apple release workflow, [run 5](https://github.com/crdevtool/padelsync/actions/runs/37201436169) | At Apple only: App Store Connect > TestFlight. GitHub keeps no copy. |
| Google Play | 1.0 | 41 (phone), 42 (watch) | 4 Oct 2026, 12:00 | `ba9b31a` | Play release workflow, [run 4](https://github.com/crdevtool/padelsync/actions/runs/37200493282) | On the run's page until 3 Nov 2026; in Play Console once uploaded |
| Google Play | 0.1.0 | 31 (phone), 32 (watch) | 4 Oct 2026, 11:15 | `d8cd905` | Play release workflow, [run 3](https://github.com/crdevtool/padelsync/actions/runs/37197972012) | On the run's page until 3 Nov 2026. Replaced by the 1.0 build before upload. |

The app's code is the same in the three builds of 4 October: between those
commits only the version label and the build scripts changed. From `626baac`
on, both watch apps name the server and the side on the serving team's half.

From `d4ba458` on, a match can use formats that earlier builds do not know:
an advantage set with a limit, sets to 4, 8 or 9 games, Fast4, a final-set
tiebreak to 10, and Americano. A device on an earlier build cannot follow a
court that uses one of them, so everyone on such a court needs this build or
a later one. The formats the earlier builds know still work between old and
new.

## Sending out an update: the routine

The steps that worked on 5 October 2026 for the serve-line update (Play
51 and 52, TestFlight build 6). Follow them in this order.

**Before starting**

- The version label stays the same on both stores. It lives in two places
  that change together: `padelsync.versionName` in `gradle.properties` and
  `MARKETING_VERSION` in `apple/project.yml`. A new label on the Apple side
  means a new beta review; a new build of the same label usually does not.
- The next Google Play release number is the last one in the table above
  plus one (after 51 and 52, use 6, which gives 61 and 62). Play refuses a
  version code it has already seen.
- GitHub runs nothing while the repository is private. Make it public for
  the builds, about an hour in all, and private again afterwards. The
  signing secrets are not exposed by this.

**Build**

1. Push the change to `main`.
2. Test run: Actions > **CI** > Run workflow, with emulators `all`, build
   `debug` and the Apple build switched on. About 30 minutes. Look at the
   screenshots it publishes before going on.
3. Google Play bundles: Actions > **Google Play release (bundles)** > Run
   workflow, with the release number. Download `PadelSync-google-play-bundles`
   from the run's page. The same bundles can be built on Windows with
   `build_release.bat` and the same number.
4. Apple: Actions > **Apple release (TestFlight)** > Run workflow, options
   left as they are. It signs and uploads by itself; the build number is the
   run's number. This one has to be started by hand on GitHub: an assistant
   session is not allowed to start a store upload.

**Google Play Console**

5. Phone bundle: Testing > Closed testing > **Alpha** > Create new release.
   Upload `PadelSync-phone-<version>-<code>.aab`, paste the release notes,
   Review release > Start rollout.
6. Watch bundle: Testing > Closed testing, with the form factor selector at
   the top right on **Wear OS only** > **Wear OS closed** > Create new
   release. Upload `PadelSync-watch-<version>-<code>.aab`, the same notes,
   Review release > Start rollout. It never goes into the phone track.
7. Publishing overview shows both under "Changes in review". Quick checks
   run for a few minutes, then Google reviews; testers get nothing until
   that passes.

Both closed tracks use the same Google Group of testers and the same
countries. These are set once per track and stay.

**TestFlight**

8. The build appears in App Store Connect > TestFlight once Apple has
   processed it. Fill in "What to Test" with the same notes and add the
   build to the tester groups.

**On the devices**

9. The phone updates from the Play Store on the phone. The watch updates
   from the Play Store on the watch (Manage apps > Updates); the phone does
   not pass it on. A copy installed from an APK file must be uninstalled
   first.
10. Both builds carry the same version label, so check for the change itself
    to see that a device updated.

**Afterwards**

11. Add the builds to the table at the top of this page.
12. Once the bundles are downloaded and the Apple run has finished, make the
    repository private again.

Why the watch needs its own track, and what else can stop a watch from
receiving the app, is in `docs/architecture.md` under "Phone and watch on
Play: form factors and tracks".

## Seeing or rebuilding the code of a release

On GitHub, `https://github.com/crdevtool/padelsync/tree/<commit>` shows the
code exactly as it was. In a git checkout, `git checkout <commit>` does the
same on your computer.

The Android apps can be rebuilt from that code on Windows with
`build_release.bat` or `build_debug.bat`. The iPhone and Apple Watch apps can
only be built on a Mac, which is what the GitHub workflows are for.

## Keeping the files

GitHub deletes a run's downloads after 30 days. To keep a Google Play
bundle, download `PadelSync-google-play-bundles` from its run page and store
it with your keystore backup; Play Console also offers every uploaded bundle
under App bundle explorer. Apple keeps the TestFlight builds; they cannot be
downloaded again.
