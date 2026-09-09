# timeclock — work clock in/out tracker

Launcher name **Clock In** (v3.0, 2026-09-09). v2 (2026-09-02) was a rebuild of an
earlier AI-generated build whose clock-in/out silently duplicated "in" punches and lost
clock-outs, corrupting recorded hours; v3 renamed the app, gave it a real adaptive
icon, and replaced the today-only punch list with a full day-grouped history.

## Stack

Kotlin + Jetpack Compose, single `MainActivity`. Package `com.timeclock`, applicationId
`com.timeclock.app`. minSdk 26, targetSdk 36. Storage is a plain `SQLiteOpenHelper`
(`timeclock.db`, table `punches(id, ts, type)` — same schema as the original, type is
`"in"`/`"out"`, ts is epoch millis). Hourly rate lives in SharedPreferences
(`settings`/`rate_pence`, 0 = earnings hidden).

## Build / install

```
./gradlew testDebugUnitTest assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -n com.timeclock.app/com.timeclock.MainActivity
```

Release APKs are signed with `timeclock-release.keystore` (credentials in
`keystore.properties`; both gitignored, build falls back to the debug key if absent).
**The keystore is the app's identity — losing it means uninstall-and-restore-db on
every future reinstall.** Backed up under `~/backups/timeclock-keystore-20260909/`.

## Design rules

- **Clock state is always derived from the most recent punch** (`isClockedIn` in
  `Calc.kt`), never stored separately — that class of bug is what killed v1.
- Session pairing and duration math live in `Calc.kt` as pure functions; keep them
  tested when changed.
- History pairing must come from `sessions()` (see `buildHistory` in MainActivity)
  so displayed durations can never disagree with the totals.
- **First-run seed**: `MainActivity.seedIfEmpty()` inserts Bradley's real punches for
  1–2 Sept 2026 (recovered verbally after the data corruption; 1 Sept times are
  placeholders totalling 1:52). It only runs on an empty db — do not remove without
  checking with Bradley.
- Corrections in-app: "Add missed punch" (date + in/out times), "Undo last",
  long-press a punch row to delete it, "Reset all data".
- Export shares a CSV via FileProvider (same format as v1).
