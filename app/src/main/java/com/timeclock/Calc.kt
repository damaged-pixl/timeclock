package com.timeclock

import java.util.Calendar
import java.util.Locale

data class Session(val inTs: Long, val outTs: Long?)

/**
 * Pair punches into sessions: an "in" followed by an "out" closes it.
 * A stray "out" with no preceding "in" is ignored.
 * An "in" followed by another "in" closes the first session at the second "in" —
 * the later punch supersedes the earlier one (leaving it open would silently
 * inflate totals by counting it up to now forever).
 */
fun sessions(entries: List<Punch>): List<Session> {
    val out = ArrayList<Session>()
    var i = 0
    while (i < entries.size) {
        val p = entries[i]
        if (p.isIn) {
            val next = entries.getOrNull(i + 1)
            when {
                next == null -> {
                    out.add(Session(p.ts, null))
                    i += 1
                }
                !next.isIn -> {
                    out.add(Session(p.ts, next.ts))
                    i += 2
                }
                else -> {
                    out.add(Session(p.ts, next.ts))
                    i += 1
                }
            }
        } else {
            i += 1
        }
    }
    return out
}

/** Clock state is derived from the single most recent punch — never tracked separately. */
fun isClockedIn(entries: List<Punch>): Boolean = entries.lastOrNull()?.isIn == true

/** Total time worked within [rangeStart, rangeEnd]; open sessions count up to [now]. */
fun totalInRange(entries: List<Punch>, rangeStart: Long, rangeEnd: Long, now: Long): Long {
    var total = 0L
    for (s in sessions(entries)) {
        val end = s.outTs ?: minOf(rangeEnd, now)
        val a = maxOf(s.inTs, rangeStart)
        val b = minOf(end, rangeEnd)
        if (b > a) total += b - a
    }
    return total
}

fun floorDay(ts: Long): Long = Calendar.getInstance().run {
    timeInMillis = ts
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    timeInMillis
}

fun floorWeek(ts: Long): Long = Calendar.getInstance().run {
    timeInMillis = floorDay(ts)
    firstDayOfWeek = Calendar.MONDAY
    set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
    timeInMillis
}

fun floorMonth(ts: Long): Long = Calendar.getInstance().run {
    timeInMillis = ts
    set(Calendar.DAY_OF_MONTH, 1)
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    timeInMillis
}

/**
 * Midnight at the start of the next day. Calendar-based on purpose: adding a
 * fixed 86 400 000 ms skips/repeats an hour on the two UK DST change days.
 */
fun nextDayStart(dayStart: Long): Long = Calendar.getInstance().run {
    timeInMillis = floorDay(dayStart)
    add(Calendar.DAY_OF_MONTH, 1)
    timeInMillis
}

/** First instant of the month [ts] belongs to, moved [delta] months. */
fun shiftMonth(ts: Long, delta: Int): Long = Calendar.getInstance().run {
    timeInMillis = floorMonth(ts)
    add(Calendar.MONTH, delta)
    timeInMillis
}

data class DayTotal(val dayStart: Long, val totalMs: Long)

data class MonthSummary(
    val monthStart: Long,
    val totalMs: Long,
    val daysWorked: Int,
    /** Only days with time on them, oldest first. */
    val dayTotals: List<DayTotal>
)

/** One month's worked time plus the per-day breakdown that makes it up. */
fun summariseMonth(entries: List<Punch>, monthStart: Long, now: Long): MonthSummary {
    val start = floorMonth(monthStart)
    val end = shiftMonth(start, 1)
    val days = ArrayList<DayTotal>()
    var cursor = start
    while (cursor < end) {
        val next = minOf(nextDayStart(cursor), end)
        val ms = totalInRange(entries, cursor, next, now)
        if (ms > 0) days.add(DayTotal(cursor, ms))
        cursor = next
    }
    return MonthSummary(
        monthStart = start,
        totalMs = days.sumOf { it.totalMs },
        daysWorked = days.size,
        dayTotals = days
    )
}

fun fmtDuration(ms: Long): String {
    val mins = ms / 60000
    return String.format(Locale.UK, "%d:%02d", mins / 60, mins % 60)
}

/** h:mm:ss — for the live running-session readout. */
fun fmtHms(ms: Long): String {
    val s = ms / 1000
    return String.format(Locale.UK, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
}

fun fmtClock(ts: Long): String = Calendar.getInstance().run {
    timeInMillis = ts
    String.format(Locale.UK, "%02d:%02d", get(Calendar.HOUR_OF_DAY), get(Calendar.MINUTE))
}
