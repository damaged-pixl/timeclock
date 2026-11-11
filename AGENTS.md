# timeclock — work clock in/out tracker

Launcher name **Clock In** (v3.2, 2026-11-11). v2 (2026-09-02) was a rebuild of an
earlier AI-generated build whose clock-in/out silently duplicated "in" punches and lost
clock-outs, corrupting recorded hours; v3 renamed the app, gave it a real adaptive
icon, and replaced the today-only punch list with a full day-grouped history. v3.1 added
monthly reports, in-place punch editing, CSV restore, and DST-safe day boundaries.
v3.2 added the work-limit alarm.

## Stack

Kotlin + Jetpack Compose, single `MainActivity`. Package `com.timeclock`, applicationId
`com.timeclock.app`. minSdk 26, targetSdk 36. Storage is a plain `SQLiteOpenHelper`
(`timeclock.db`, table `punches(id, ts, type)` — same schema as the original, type is
`"in"`/`"out"`, ts is epoch millis). Hourly rate lives in SharedPreferences
(`settings`/`rate_pence`, 0 = earnings hidden).

## Work-limit alarm (v3.2)

Optional enforcement (settings → "Work limit alarm", master toggle + "Skip
Thursdays"): blares and **auto clock-outs** when work hits the marks — 30 min per
half-day (morning window `[00:00, 11:00)`, afternoon `[13:00, 24:00)`; the gap
11:00–13:00 is free and counts toward nothing) and 1 hour of window work per day.
Mark maths are pure functions in `Calc.kt` (`limitCrossings()`), so sessions spanning
the windows are handled exactly. Key invariants:

- Auto clock-outs are real "out" punches at the exact mark instant — clock state stays
  derived-from-last-punch, no extra stored state anywhere.
- Enforcement state is always recomputed from the db (`Limit.checkAndSchedule()`,
  called on every punch change / app open / settings change / boot). It backdates an
  auto-out if a mark was crossed while nothing could act (missed alarm, reboot).
- `LimitService` is a foreground `mediaPlayback` service: quiet "armed" notification
  while clocked in, blaring loop (alarm ringtone at half the alarm volume, raised
  only if quieter, restored after; repeating vibration, 5-min safety cap) on a
  mark. Exact alarms via
  `LimitReceiver` (USE_EXACT_ALARM) are the doze backup that start it.
- The 1-hour day mark's accrual is deliberately NOT capped by the half marks, so it
  backstops a session that clocks back in after a half-hour stop (+30 min grace).
- Tests: all mark maths in `CalcTest.kt` — keep them passing when touched.

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
  tap a punch row to edit its date/time in place, long-press it to delete,
  "Reset all data".
- **Monthly report**: tap "This month"/"Last month"/"All time" or
  "Monthly report". Any month (including empty ones) with total hours, pay,
  days worked, average per day, day bars and expandable punches. Future months
  are disabled. `summariseMonth()` in `Calc.kt` is the single source of truth.
- **Day boundaries use `nextDayStart()`**, never `+ 86_400_000`, so the two UK
  DST change days don't lose/gain an hour in daily and monthly totals.
- Export shares a CSV via FileProvider (punch rows plus `session_minutes`,
  `day_minutes` and `month_minutes` summary rows). "Import / restore" reads the
  in/out rows back from any such CSV, skipping duplicates — so export is a real
  backup. Summary rows are ignored on import.
- Installed data is sacred: pull and verify `timeclock.db` (`adb exec-out run-as
  com.timeclock.app cat databases/timeclock.db`) before reinstalling, and keep
  the release keystore — a signature mismatch forces uninstall and wipes data.
