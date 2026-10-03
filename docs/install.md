# Installing the test builds

| File | For |
| --- | --- |
| `PadelSync-phone.apk` | Android phones (Android 8 or newer) |
| `PadelSync-watch.apk` | Wear OS watches (Wear OS 3 or newer) |
| `PadelSync-iOS-simulator.zip` | Trying the iPhone app in a browser (see the end) |

All commands below are typed in PowerShell, opened in the folder that holds
these files.

## 1. Make `adb` available

`adb` is the tool that installs apps from a computer. It comes with Android
Studio but is not on the command line by default. Run this once in each new
PowerShell window:

```powershell
Set-Alias adb "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
adb version
```

`adb version` should print a version number. Note the spelling: `adb`, and
no `.\` in front of it.

## 2. Android phone

The simplest way needs no computer: copy `PadelSync-phone.apk` to the phone
(cable, Google Drive, or email it to yourself), open it on the phone, and
allow the install when Android asks.

With a USB cable instead:

1. On the phone: Settings > About phone, tap **Build number** seven times.
   Then Settings > System > Developer options, turn on **USB debugging**.
2. Plug the phone in, unlock it, and accept the prompt that appears.
3. Run:
   ```powershell
   adb devices
   adb install PadelSync-phone.apk
   ```

## 3. Wear OS watch

The watch and the computer must be on the same Wi-Fi network.

**Turn on debugging on the watch (once):** Settings > System > About, tap
**Build number** seven times. Then Settings > Developer options, turn on
**ADB debugging** and **Wireless debugging**.

**Check the computer can reach the watch.** The watch's address is at the
top of the Wireless debugging screen, for example `192.168.1.37`.

```powershell
ping 192.168.1.37
```

You should see `Reply from ...`. If not, fix the Wi-Fi first.

**Pair, then connect.** These are two different steps that use two
different ports. Mixing them up is the usual reason this fails.

| Step | Command | Where the port comes from | Other |
| --- | --- | --- | --- |
| Pair (first time only) | `adb pair` | The **Pair new device** screen | Asks for the 6-digit code on that screen |
| Connect | `adb connect` | The main **Wireless debugging** screen | Only works after pairing |

1. On the watch tap **Pair new device** and leave that screen open. Then:
   ```powershell
   adb pair 192.168.1.37:<port on the pairing screen>
   ```
   Type the 6-digit code when asked. Expect `Successfully paired`.
2. Close the pairing screen on the watch. The pairing port stops working
   the moment pairing succeeds. Read the port on the main Wireless debugging
   screen and run:
   ```powershell
   adb connect 192.168.1.37:<port on the main screen>
   ```
   Expect `connected to ...`.
3. Install:
   ```powershell
   adb install PadelSync-watch.apk
   ```
   Expect `Success`. PadelSync is now in the watch's app list.

### If it does not work

| Message | Meaning | Fix |
| --- | --- | --- |
| `'adb' is not recognized` | The alias from step 1 is missing in this window | Run step 1 again |
| `failed to connect` before pairing | The watch is not paired with this computer yet | Do the `adb pair` step |
| `actively refused it` | You used the pairing port with `adb connect` | Use the port on the main Wireless debugging screen |
| `no devices/emulators found` | Not connected yet | Run `adb connect` with the main-screen port; `adb devices` shows what is connected |
| Cannot find the connect port | | Run `adb mdns services` and use the address on the `_adb-tls-connect` line |

The ports change whenever Wireless debugging is switched off and on, so
read them fresh each time. Pairing is remembered.

## 4. Updating from an earlier test build

Install the new files over the old ones; nothing needs uninstalling first:

```powershell
adb install -r PadelSync-phone.apk
adb install -r PadelSync-watch.apk
```

Update the phone **and** the watch. The two builds talk a newer version of
the sync protocol, so an old watch cannot join a new phone's court: it is
told "this court uses a different version of the app". Match history saved by
the earlier build is not carried over.

## 5. Trying the sync

1. On the phone choose **Host a match**, set the match up and tap **Start
   and open the court**. A 4-digit code appears at the top of the scoreboard.
2. On the other device choose **Join a court**, pick the court, and enter
   the code.

Allow the Bluetooth permission on both devices when asked. A match started
with **New match** stays on that phone; it can be opened to others later from
**Menu > Play with others**.

## 6. What to try in this build

| Feature | Where |
| --- | --- |
| Player names, singles or doubles | Setup screen. Names are optional and can be corrected mid-match from **Menu > Players**. The last names used are remembered. |
| Play all sets | Setup screen, **Play all 3 sets**: on by default for padel. At two sets to love the app announces the winners and carries on with the third set. |
| Court scoreboard | Team A plays the top half, team B the bottom. Tap a half to score for it. |
| Serve side and server | The ball marker sits on the side the server stands on and names the player (`ANA · RIGHT SIDE`); the box the serve must land in is lit. If the app has the wrong player of a pair serving, use **Menu > Swap server**. |
| Who can score | Hosting only. **Others can score** on the setup screen, then per device in **Menu > Who can score**. A view-only device shows the score but its taps do not count. |
| Voice | **Call the score out loud** on the setup screen; details in **Menu > Voice**: every point, games and sets, big points, who serves, change ends, and a full-score reminder every 2, 5 or 10 minutes. It uses the media volume. By default only the host's phone speaks. |
| End of match | Confetti, the winners, set scores, points won, breaks of serve, best run, **Share the result** and **Rematch**. |
| During play | A banner for each game and set, a flash on the half that won the point, a "3 in a row" badge, a change-ends call, and a match clock. |

Also new:

| Feature | Where |
| --- | --- |
| Finding the host again | Nothing to do. A device that loses its host retries it, and after 15 seconds also looks for the court by name; it comes back by itself even if the host's phone was restarted. |
| Taking over as host | If the host's device dies or leaves, after 20 seconds the other devices offer **Host this court**. One player confirms; the rest follow by themselves under the same code. An Apple Watch cannot host and says to ask a player with a phone. |
| Location notice | Android 11 and older, and Wear OS 3: "Join a court" explains that Location must be switched on, and that the app does not use it. |

On the watch: short names (`A+L`), the server and side on the middle strip
(`LEO · R`), a winner screen, and **Voice** and **Others can score** switches
in the menu.

The voice speaks English. It needs a text-to-speech voice on the device,
which nearly every Android phone has; many watches do not, and the watch
then stays silent.

## 7. iPhone app, from a Windows computer

The iPhone build has the same features as the Android one.

Apple's iOS Simulator only runs on a Mac, so there are two ways to see the
iPhone app from Windows.

### Watch the recorded walkthrough

The folder `iphone-preview` holds a video and a screenshot of every screen,
recorded on an iPhone simulator on the build server: a full match from setup
to history. Nothing to install; this is the quickest way to see the app.

### Use it yourself in a browser

The iPhone app can be tried hands-on in a hosted simulator that runs in a web
browser:

1. Create a free account at https://appetize.io.
2. Choose **Upload**, pick `PadelSync-iOS-simulator.zip`, and select iOS.
3. Open the app from your Appetize dashboard. It runs in the browser page.

What this can and cannot show:

- It shows the real iPhone app: screens, scoring, formats, undo, history.
- It has **no Bluetooth**, so "Play with others" and "Join a court" cannot
  work there. Syncing with an iPhone can only be tested on a real iPhone.
- The **Apple Watch app cannot be run this way**. It needs a Mac, or a real
  Apple Watch.
- The free plan allows about 30 minutes of use a month.
