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

/* ---------- Work-limit maths ---------- */

const val LIMIT_MORNING_END_HOUR = 11
const val LIMIT_AFTERNOON_START_HOUR = 13
const val LIMIT_HALF_MS = 30L * 60_000
const val LIMIT_DAY_MS = 60L * 60_000

enum class LimitKind { HALF_MORNING, HALF_AFTERNOON, DAY }

data class LimitMark(val kind: LimitKind, val at: Long)

/** 11:00 on the day of [ts] — the morning window ends here. */
fun limitMorningEnd(ts: Long): Long = Calendar.getInstance().run {
    timeInMillis = floorDay(ts)
    set(Calendar.HOUR_OF_DAY, LIMIT_MORNING_END_HOUR)
    set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    timeInMillis
}

/** 13:00 on the day of [ts] — the afternoon window starts here. */
fun limitAfternoonStart(ts: Long): Long = Calendar.getInstance().run {
    timeInMillis = floorDay(ts)
    set(Calendar.HOUR_OF_DAY, LIMIT_AFTERNOON_START_HOUR)
    set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    timeInMillis
}

/** Worked time inside the morning window, counting open sessions only up to [cutoff]. */
fun morningWork(entries: List<Punch>, dayStart: Long, cutoff: Long): Long =
    totalInRange(entries, floorDay(dayStart), limitMorningEnd(dayStart), cutoff)

/** Worked time inside the afternoon window, counting open sessions only up to [cutoff]. */
fun afternoonWork(entries: List<Punch>, dayStart: Long, cutoff: Long): Long =
    totalInRange(entries, limitAfternoonStart(dayStart), nextDayStart(dayStart), cutoff)

/**
 * When each work-limit mark will be crossed, assuming the open session that
 * starts at [openIn] keeps running until a mark stops it. Call only while
 * clocked in; returns crossings in time order (possibly already in the past,
 * which is how a missed alarm is detected).
 *
 * Windows: morning [00:00, 11:00), free gap [11:00, 13:00) — unlimited and
 * counting toward nothing — then afternoon [13:00, 24:00). Marks: 30 min per
 * window and 60 min of window work per day. The day mark's accrual is
 * deliberately NOT capped by the half marks, so it backstops a session that
 * clocks back in after a half-hour stop.
 */
fun limitCrossings(entries: List<Punch>, openIn: Long): List<LimitMark> {
    val dayStart = floorDay(openIn)
    val mEnd = limitMorningEnd(dayStart)
    val aStart = limitAfternoonStart(dayStart)
    val marks = ArrayList<LimitMark>()

    val mDone = morningWork(entries, dayStart, openIn)
    val aDone = afternoonWork(entries, dayStart, openIn)

    val remM = LIMIT_HALF_MS - mDone
    if (remM > 0) {
        val t = maxOf(openIn, dayStart) + remM
        if (t <= mEnd) marks += LimitMark(LimitKind.HALF_MORNING, t)
    }
    val remA = LIMIT_HALF_MS - aDone
    if (remA > 0) {
        val t = maxOf(openIn, aStart) + remA
        if (t < nextDayStart(dayStart)) marks += LimitMark(LimitKind.HALF_AFTERNOON, t)
    }
    val remD0 = LIMIT_DAY_MS - (mDone + aDone)
    if (remD0 > 0) {
        var remD = remD0
        var t = maxOf(openIn, dayStart)
        if (t < mEnd) {
            val chunk = minOf(remD, mEnd - t)
            t += chunk
            remD -= chunk
        }
        if (remD > 0) {
            if (t < aStart) t = aStart // the free gap moves the clock on, not the allowance
            t += remD
        }
        marks += LimitMark(LimitKind.DAY, t)
    }
    return marks.sortedBy { it.at }
}
