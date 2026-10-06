#!/usr/bin/env bash
# Emulators each sit behind their own virtual router. `link` forwards host port 47000+i to emulator
# i's Formation port; debug builds probe 10.0.2.2:47000-47009, so the emulators find each other.
# Usage: scripts/emulators.sh install | link | unlink | list
set -euo pipefail

ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BASE_PORT=47000

emulators() {
  "$ADB" devices | awk '/^emulator-[0-9]+[[:space:]]+device$/ {print $1}' | sort -t- -k2 -n
}

case "${1:-link}" in
  install)
    (cd "$ROOT/app" && ./gradlew :androidApp:assembleDebug -q)
    apk="$ROOT/app/androidApp/build/outputs/apk/debug/androidApp-debug.apk"
    for serial in $(emulators); do
      echo "Installing on $serial"
      if [ "$("$ADB" -s "$serial" shell getprop debug.hwui.renderer | tr -d '\r')" = "skiagl" ]; then
        "$ADB" -s "$serial" shell setprop debug.hwui.use_partial_updates false
      fi
      "$ADB" -s "$serial" install -r "$apk" >/dev/null
    done
    ;;
  link)
    i=0
    for serial in $(emulators); do
      port=$((BASE_PORT + i))
      "$ADB" -s "$serial" forward "tcp:$port" "tcp:$BASE_PORT" >/dev/null
      echo "$serial  →  10.0.2.2:$port"
      i=$((i + 1))
    done
    if [ "$i" -gt 10 ]; then echo "Only the first 10 emulators are probed." >&2; fi
    if [ "$i" -eq 0 ]; then echo "No emulators running." >&2; fi
    ;;
  unlink)
    for serial in $(emulators); do "$ADB" -s "$serial" forward --remove-all; done
    ;;
  list)
    "$ADB" forward --list
    ;;
  *)
    sed -n '2,15p' "$0"
    exit 1
    ;;
esac
