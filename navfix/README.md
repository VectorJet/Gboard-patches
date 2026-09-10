# navfix overlay

Shrinks the Pixel Launcher Taskbar nav inset on Lineage lyriq so Gboard sits
just on top of the gesture pill instead of floating above a black strip.

Target: `com.google.android.apps.nexuslauncher`
(That app provides `navigationBars bottom=60` via its `Taskbar` window on this ROM.)

## Install (root)

1. Build: Actions → "Build navfix overlay" → download `navfix-apk`
   (or build locally with Android SDK 34 + `aapt2`, see workflow).
2. Install: `adb install -r navfix-signed.apk`
3. Enable: `su -c 'cmd overlay enable --user 0 dev.vectorjet.navfix'`
4. Reboot, open Gboard, check gap.
5. Tune `navfix/res/values/dimens.xml` (0–24dp), rebuild if needed.
6. Revert anytime: `su -c 'cmd overlay disable --user 0 dev.vectorjet.navfix'` + reboot.

If `aapt2 link` warns a dimen name doesn't exist in your NexusLauncher version,
it skips it — harmless. Check with `aapt2 dump resources`.
