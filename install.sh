#!/usr/bin/env bash
# Cozy Cat Launcher installer for macOS and Linux.
#
# Installs the launcher on an Android TV / Google TV connected by USB (or by network adb).
#   curl -fsSL https://raw.githubusercontent.com/AbshkPskr/cozy-cat-launcher/main/install.sh | bash
# or, from a clone of the repo:
#   ./install.sh               # install and open it
#   ./install.sh --set-home    # also make it the TV's home screen
#   ./install.sh --apk file    # install a local build instead of the latest release
#   ./install.sh --restore     # give Home back to the TV's own launcher
set -euo pipefail

PACKAGE=com.cozycat.launcher
ACTIVITY=$PACKAGE/.MainActivity
RELEASE_URL=https://github.com/AbshkPskr/cozy-cat-launcher/releases/latest/download/CozyCatLauncher.apk
WORK="${XDG_CACHE_HOME:-$HOME/.cache}/cozycat-launcher"
mkdir -p "$WORK"
SET_HOME=0; RESTORE=0; APK=""; DEVICE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --set-home) SET_HOME=1 ;;
    --restore) RESTORE=1 ;;
    --apk) APK="$2"; shift ;;
    --device) DEVICE="$2"; shift ;;
    *) echo "unknown option: $1"; exit 1 ;;
  esac; shift
done
say() { echo "  $*"; }
fail() { echo; echo "  ERROR: $*"; echo; exit 1; }

# 1. Find adb (PATH, Android SDK, or download Google's platform-tools)
if command -v adb >/dev/null 2>&1; then ADB=$(command -v adb)
elif [ -x "$HOME/Library/Android/sdk/platform-tools/adb" ]; then ADB="$HOME/Library/Android/sdk/platform-tools/adb"
elif [ -x "$HOME/Android/Sdk/platform-tools/adb" ]; then ADB="$HOME/Android/Sdk/platform-tools/adb"
elif [ -x "$WORK/platform-tools/adb" ]; then ADB="$WORK/platform-tools/adb"
else
  say "adb not found, downloading Google platform-tools..."
  case "$(uname -s)" in Darwin) OS=darwin ;; *) OS=linux ;; esac
  curl -fsSL "https://dl.google.com/android/repository/platform-tools-latest-$OS.zip" -o "$WORK/pt.zip"
  (cd "$WORK" && unzip -qo pt.zip && rm pt.zip)
  ADB="$WORK/platform-tools/adb"
fi
say "Using adb: $ADB"

# 2. Find the TV
"$ADB" start-server >/dev/null
LINES=$("$ADB" devices | tail -n +2 | grep -v '^\s*$' || true)
[ -n "$DEVICE" ] && LINES=$(echo "$LINES" | grep "^$DEVICE" || true)
[ -z "$LINES" ] && fail "No TV found. Turn on USB debugging on the TV, connect the cable, then run this again."
echo "$LINES" | grep -q unauthorized && fail 'The TV is asking to allow USB debugging. Pick "Always allow from this computer" on the TV and run this again.'
READY=$(echo "$LINES" | awk '$2=="device"{print $1}')
[ "$(echo "$READY" | wc -l)" -gt 1 ] && [ -z "$DEVICE" ] && fail "More than one device is connected. Run again with --device <serial>: $READY"
S=$(echo "$READY" | head -1)
A() { "$ADB" -s "$S" "$@"; }
SDK=$(A shell getprop ro.build.version.sdk | tr -d '\r')
say "Found TV: $(A shell getprop ro.product.model | tr -d '\r') (Android API $SDK)"
[ "$SDK" -lt 28 ] && fail "Cozy Cat Launcher needs Android 9 or newer."

if [ $RESTORE = 1 ]; then
  STOCK=$(A shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME \
          | tr -d '\r' | sed 's/^ *//' | grep / | grep -v "$PACKAGE" | grep -v FallbackHome | head -1)
  [ -z "$STOCK" ] && fail "No other home screen found on the TV."
  A shell cmd package set-home-activity "$STOCK" >/dev/null
  A shell input keyevent KEYCODE_HOME
  say "Home screen set back to $STOCK. (Cozy Cat stays installed; uninstall with: adb uninstall $PACKAGE)"
  exit 0
fi

# 3. Get the APK
if [ -z "$APK" ]; then
  HERE=$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" 2>/dev/null && pwd || echo "")
  if [ -n "$HERE" ] && [ -f "$HERE/app/build/outputs/apk/release/app-release.apk" ]; then
    APK="$HERE/app/build/outputs/apk/release/app-release.apk"
  else
    APK="$WORK/CozyCatLauncher.apk"
    say "Downloading the latest Cozy Cat Launcher release..."
    curl -fsSL "$RELEASE_URL" -o "$APK"
  fi
fi
[ -f "$APK" ] || fail "APK not found: $APK"

# 4. Install
say "Installing on the TV (this can take a minute)..."
OUT=$(A install -r "$APK" 2>&1 || true)
if echo "$OUT" | grep -q INSTALL_FAILED_UPDATE_INCOMPATIBLE; then
  say "An older Cozy Cat with a different signature is installed; replacing it..."
  A uninstall "$PACKAGE" >/dev/null || true
  OUT=$(A install "$APK" 2>&1 || true)
fi
echo "$OUT" | grep -q Success || fail "Install failed: $OUT"
say "Installed."
A shell cmd package compile -m speed -f "$PACKAGE" >/dev/null 2>&1 || true

# 5. Optional: home screen, then open it
if [ $SET_HOME = 1 ]; then
  if A shell cmd package set-home-activity "$ACTIVITY" 2>&1 | grep -q Success; then
    say "Cozy Cat is now the home screen. Run with --restore to switch back."
  else
    say "This TV did not accept the change from the computer. Press Home on the remote and pick Cozy Cat -> Always."
  fi
fi
A shell am start -n "$ACTIVITY" >/dev/null
echo; echo "  Done! Cozy Cat Launcher is open on your TV."
[ $SET_HOME = 1 ] || say "To use it as your home screen run again with --set-home, or open it any time from the TV's Apps list."
