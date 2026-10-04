# Release log

Which build went to which store, and the exact code it was made from. Add a
line whenever a build is uploaded.

| Store | Version | Build | Built (UTC) | Commit | Built by | Where the file is |
| --- | --- | --- | --- | --- | --- | --- |
| TestFlight | 1.0 | 5 | 4 Oct 2026, 12:22 | `16b1734` | Apple release workflow, [run 5](https://github.com/crdevtool/padelsync/actions/runs/37201436169) | At Apple only: App Store Connect > TestFlight. GitHub keeps no copy. |
| Google Play | 1.0 | 41 (phone), 42 (watch) | 4 Oct 2026, 12:00 | `ba9b31a` | Play release workflow, [run 4](https://github.com/crdevtool/padelsync/actions/runs/37200493282) | On the run's page until 3 Nov 2026; in Play Console once uploaded |
| Google Play | 0.1.0 | 31 (phone), 32 (watch) | 4 Oct 2026, 11:15 | `d8cd905` | Play release workflow, [run 3](https://github.com/crdevtool/padelsync/actions/runs/37197972012) | On the run's page until 3 Nov 2026. Replaced by the 1.0 build before upload. |

The app's code is the same in all three: between those commits only the
version label and the build scripts changed.

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
