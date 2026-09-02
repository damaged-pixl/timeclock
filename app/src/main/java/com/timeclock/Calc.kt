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

fun fmtDuration(ms: Long): String {
    val mins = ms / 60000
    return String.format(Locale.UK, "%d:%02d", mins / 60, mins % 60)
}

fun fmtClock(ts: Long): String = Calendar.getInstance().run {
    timeInMillis = ts
    String.format(Locale.UK, "%02d:%02d", get(Calendar.HOUR_OF_DAY), get(Calendar.MINUTE))
}
