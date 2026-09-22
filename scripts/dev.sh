#!/usr/bin/env bash
# Conduit dev helper. Usage: ./scripts/dev.sh <command>
#
#   install   build the debug APK and install it on the connected device
#   run       install, then launch the app
#   logs      tail logcat, filtered to Conduit and its crashes
#   trigger   fire a broadcast-triggered flow by name
#   devices   list attached devices
#   test      run the unit tests
#
# Everything here assumes the CLI toolchain installed via Homebrew; no Android
# Studio is required.

set -euo pipefail

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
PKG="com.revlv.conduit"

require_device() {
  local count
  count="$(adb devices | awk 'NR>1 && $2=="device"' | wc -l | tr -d ' ')"
  if [ "$count" -eq 0 ]; then
    echo "No device found." >&2
    echo >&2
    echo "  1. On the phone: Settings > About phone > tap 'Build number' 7 times" >&2
    echo "  2. Settings > System > Developer options > enable 'USB debugging'" >&2
    echo "  3. Plug in over USB and tap 'Allow' on the RSA fingerprint prompt" >&2
    echo "  4. Re-run this command" >&2
    exit 1
  fi
}

cmd_install() {
  ( cd "$ROOT" && ./gradlew :app:assembleDebug )
  require_device
  # -r reinstalls over an existing copy and keeps its data, so your flows
  # survive an upgrade.
  adb install -r "$APK"
  echo "Installed $(basename "$APK")"
}

cmd_run() {
  cmd_install
  adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  echo "Launched $PKG"
}

cmd_logs() {
  require_device
  adb logcat -c
  echo "Tailing logs — Ctrl+C to stop."
  # AndroidRuntime catches crashes; the package filter catches everything else.
  adb logcat --pid="$(adb shell pidof -s "$PKG" 2>/dev/null || echo 0)" \
    2>/dev/null || adb logcat AndroidRuntime:E "$PKG":V "*:S"
}

cmd_trigger() {
  require_device
  local name="${1:-}"
  if [ -z "$name" ]; then
    echo "Usage: ./scripts/dev.sh trigger <flow-broadcast-name>" >&2
    exit 1
  fi
  adb shell am broadcast -a com.revlv.conduit.TRIGGER --es name "$name"
}

cmd_devices() { adb devices -l; }
cmd_test()    { ( cd "$ROOT" && ./gradlew :app:testDebugUnitTest ); }

case "${1:-run}" in
  install)  cmd_install ;;
  run)      cmd_run ;;
  logs)     cmd_logs ;;
  trigger)  shift; cmd_trigger "$@" ;;
  devices)  cmd_devices ;;
  test)     cmd_test ;;
  *)        sed -n '2,12p' "${BASH_SOURCE[0]}" ;;
esac
