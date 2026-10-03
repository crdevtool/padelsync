# Manual tests

Checks that need real devices, because emulators and simulators cannot show
the behaviour. Run them before a release and whenever the Bluetooth code
changes. Each says what to do, what should happen, and what it proves.

## 1. A guest finds an iPhone host again after a long gap

**Why by hand:** an iPhone changes its Bluetooth address about every 15
minutes. No emulator does that, so the automated test can only show that a
guest finds a court again by scanning, not that it survives a real change of
address.

**Needs:** an iPhone and an Android phone (or Wear OS watch), both with the
current build.

1. On the iPhone, **Host a match** and start it. Note the join code.
2. On the Android phone, **Join a court**, pick the iPhone's court, enter
   the code. Score a point on each device and check both show it.
3. Separate the two devices so the link drops: carry the Android phone out
   of range (another floor, or about 50 metres outdoors), or switch its
   Bluetooth off. Its status line shows **Reconnecting…**.
4. Leave them apart for **at least 20 minutes**. Keep the iPhone app on
   screen for the last minute of that time: an iPhone in the background is
   not visible to Android devices.
5. Bring the Android phone back (or switch its Bluetooth on) and put it next
   to the iPhone. Do not touch either app.

**Expected:** within about 30 seconds of being back in range the Android
phone shows the court name and "2 devices" again, with the current score,
and nobody has typed the code again. A point scored on either device then
shows on both.

**If it fails:** the Android phone stays on "Reconnecting…". Note how long
the gap was and whether the iPhone app was on screen, then leave the court on
the Android phone and join again by hand to confirm the court itself is
still reachable.

**Also worth trying:** the same with the roles swapped (Android hosts, iPhone
joins), and with a Wear OS watch or an Apple Watch as the guest.
