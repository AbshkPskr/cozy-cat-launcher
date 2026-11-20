# Instructions for Claude: installing Cozy Cat Launcher on a TV

You are helping someone install this launcher on their Android TV / Google TV. They are probably
not a developer, so keep messages short and plain, and say what you need them to do on the TV.

## Ground rules
- Only touch the TV with `adb`. Install this app and, **only if the user says yes**, set it as
  the home screen. Never disable, uninstall or change other apps or settings.
- Ask before setting it as the home screen. It is easy to undo, but it is their TV.
- If several devices are connected, ask which one (show model names from
  `adb -s <serial> shell getprop ro.product.model`).

## Steps

1. **Get adb.** Use `adb` from PATH or from the Android SDK (`%LOCALAPPDATA%\Android\Sdk\platform-tools`
   on Windows, `~/Library/Android/sdk/platform-tools` on macOS, `~/Android/Sdk/platform-tools` on Linux).
   If missing, download Google's official platform-tools zip
   (`https://dl.google.com/android/repository/platform-tools-latest-<windows|darwin|linux>.zip`)
   into a folder of its own and use it from there. The install scripts below do this automatically.

2. **Find the TV.** Run `adb devices`.
   - Nothing listed: ask them to check USB debugging is on (see README step 1) and that the cable
     is USB-A to USB-A (phone cables usually don't work with TVs). For a TV with Network/Wireless
     debugging, ask for the IP shown there and run `adb connect <ip>:5555`.
   - `unauthorized`: ask them to accept "Allow USB debugging" on the TV (tick "Always allow").
   - Check Android version: `adb shell getprop ro.build.version.sdk` must be **28 or higher**
     (Android 9+). If lower, tell them it isn't supported.

3. **Install.** Prefer the bundled script, which downloads the latest release APK and installs it:
   - Windows: `powershell -ExecutionPolicy Bypass -File install.ps1`
   - macOS / Linux: `bash install.sh`
   (Run from a clone of this repo, or use the one-liners in README.md.)
   It handles a signature mismatch from an older build by uninstalling the old Cozy Cat first and
   pre-compiles the app (`cmd package compile -m speed`) so animations are smooth on slow TVs.

   Manual equivalent:
   ```
   curl -L -o CozyCatLauncher.apk https://github.com/AbshkPskr/cozy-cat-launcher/releases/latest/download/CozyCatLauncher.apk
   adb install -r CozyCatLauncher.apk
   adb shell cmd package compile -m speed -f com.cozycat.launcher
   adb shell am start -n com.cozycat.launcher/.MainActivity
   ```

4. **Confirm it works.** Take a screenshot to check the kitten scene is showing:
   `adb exec-out screencap -p > screen.png` (on Windows Git Bash, prefer
   `adb shell screencap -p /sdcard/s.png && adb pull /sdcard/s.png`). Look at it.

5. **Ask about the home screen.** If they want Cozy Cat when pressing Home:
   - `install.ps1 -SetHome` / `install.sh --set-home`, or
     `adb shell cmd package set-home-activity com.cozycat.launcher/.MainActivity`.
   - If the TV refuses, tell them to press Home on the remote and choose Cozy Cat → Always.
   - Some TVs (many Google TV models) always open their own home; then Cozy Cat is opened from
     the TV's Apps list instead. Do **not** disable the stock launcher to force it.

6. **Tell them how to undo it:** `install.ps1 -Restore` / `install.sh --restore` gives Home back
   to the TV's launcher, and `adb uninstall com.cozycat.launcher` removes the app.

## If they want to build from source
`./gradlew assembleRelease` (needs JDK 17 + Android SDK; set `sdk.dir` in `local.properties` if
the SDK isn't found), then run the install script from the repo root: it picks up
`app/build/outputs/apk/release/app-release.apk` automatically.
