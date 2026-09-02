# timeclock — work clock in/out tracker

Rebuild (2026-09-02, v2.0) of an earlier AI-generated build whose clock-in/out silently
duplicated "in" punches and lost clock-outs, corrupting recorded hours. The old build was
a view-based app with no source on disk; this is a clean rewrite from the decompiled APK.

## Stack

Kotlin + Jetpack Compose, single `MainActivity`. Package `com.timeclock`, applicationId
`com.timeclock.app`. minSdk 26, targetSdk 36. Storage is a plain `SQLiteOpenHelper`
(`timeclock.db`, table `punches(id, ts, type)` — same schema as the original, type is
`"in"`/`"out"`, ts is epoch millis).

## Build / install

```
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.timeclock.app/com.timeclock.MainActivity
```

Unit tests for the duration/session math: `./gradlew testDebugUnitTest` (`CalcTest`).

## Design rules

- **Clock state is always derived from the most recent punch** (`isClockedIn` in
  `Calc.kt`), never stored separately — that class of bug is what killed v1.
- Session pairing and duration math live in `Calc.kt` as pure functions; keep them
  tested when changed.
- **First-run seed**: `MainActivity.seedIfEmpty()` inserts Bradley's real punches for
  1–2 Sept 2026 (recovered verbally after the data corruption; 1 Sept times are
  placeholders totalling 1:52). It only runs on an empty db — do not remove without
  checking with Bradley.
- Corrections are supported in-app: "Add missed punch" (date + in/out times) and
  long-press a punch row to delete it.
- Export shares a CSV via FileProvider (same format as v1).
