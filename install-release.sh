#!/usr/bin/env bash
# Swap the debug-signed timeclock v2 for the release-signed Clock In v3,
# preserving the punches database. Run from ~/dev/timeclock with the phone
# connected over adb. Order matters: fresh DB pull BEFORE uninstall.
set -euo pipefail
cd "$(dirname "$0")"

APK=app/build/outputs/apk/release/app-release.apk
PKG=com.timeclock.app
STAMP=$(date +%Y%m%d-%H%M%S)
BACKUP=~/backups/timeclock-db-$STAMP

[ -f "$APK" ] || { echo "missing $APK — build first"; exit 1; }
adb wait-for-device

echo "== Pulling current DB =="
mkdir -p "$BACKUP"
adb shell am force-stop "$PKG"
adb shell run-as "$PKG" tar -cf - databases/ | tar -xf - -C "$BACKUP"
ls -la "$BACKUP/databases/"

echo "== Uninstalling old (debug-signed) build =="
adb uninstall "$PKG"

echo "== Installing release APK =="
adb install "$APK"

echo "== Restoring DB =="
adb shell run-as "$PKG" tar -xf - -C . < <(tar -cf - -C "$BACKUP" databases)
adb shell run-as "$PKG" ls -la databases/

echo "== Launching =="
adb shell am start -n "$PKG/com.timeclock.MainActivity"
echo "Done. Backup of pre-swap DB at $BACKUP"
