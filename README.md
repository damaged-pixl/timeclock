# timeclock

Work clock in/out tracker. v3.2 (2026-11-11): adaptive icon, day-grouped punch
history, monthly reports, in-place punch editing, CSV restore, DST-safe day
boundaries, and an optional work-limit alarm (30 min per half-day window +
1 hour per day, with "skip Thursdays").

## Stack

Kotlin + Jetpack Compose, single `MainActivity`. Package `com.timeclock`,
applicationId `com.timeclock.app`. minSdk 26, targetSdk 36.

Storage is a plain `SQLiteOpenHelper` (`timeclock.db`, table
`punches(id, ts, type)`), type is `"in"`/`"out"`, ts is epoch millis.
Hourly rate lives in SharedPreferences (`settings`/`rate_pence`, 0 = earnings
hidden).

## Design invariants

- Clock state is always derived from the last punch — no redundant stored state.
- Limit maths are pure functions in `Calc.kt` (`limitCrossings()`), unit-tested
  in `CalcTest.kt` — sessions spanning the morning/afternoon windows are
  handled exactly.
- Auto clock-outs are real `"out"` punches at the exact mark instant.

## Build

```
./gradlew assembleDebug
```

Tests: `./gradlew testDebugUnitTest`
