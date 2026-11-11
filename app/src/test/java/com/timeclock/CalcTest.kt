package com.timeclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalcTest {

    private fun p(id: Long, ts: Long, type: String) = Punch(id, ts, type)

    private fun at(h: Int, m: Int): Long = java.util.Calendar.getInstance().run {
        set(2026, 8, 2, h, m, 0); set(java.util.Calendar.MILLISECOND, 0)
        timeInMillis
    }

    /** Convenience for month-boundary tests: [month] is 1-based. */
    private fun ts(year: Int, month: Int, day: Int, h: Int = 0, m: Int = 0): Long =
        java.util.Calendar.getInstance().run {
            set(year, month - 1, day, h, m, 0); set(java.util.Calendar.MILLISECOND, 0)
            timeInMillis
        }

    @Test
    fun `sessions pairs in-out and leaves stray in open`() {
        val list = listOf(
            p(1, at(9, 0), "in"), p(2, at(10, 0), "out"),
            p(3, at(11, 0), "in")
        )
        val s = sessions(list)
        assertEquals(2, s.size)
        assertEquals(at(10, 0), s[0].outTs)
        assertEquals(null, s[1].outTs)
    }

    @Test
    fun `consecutive ins - earlier one closes at the later in`() {
        val list = listOf(p(1, at(9, 0), "in"), p(2, at(9, 30), "in"), p(3, at(10, 0), "out"))
        val s = sessions(list)
        assertEquals(2, s.size)
        assertEquals(at(9, 30), s[0].outTs)
        assertEquals(at(10, 0), s[1].outTs)
    }

    @Test
    fun `trailing in stays open and counts to now`() {
        val list = listOf(
            p(1, at(9, 0), "in"), p(2, at(9, 30), "in")
        )
        val s = sessions(list)
        assertEquals(2, s.size)
        assertEquals(at(9, 30), s[0].outTs)
        assertEquals(null, s[1].outTs)
        val total = totalInRange(list, Long.MIN_VALUE, Long.MAX_VALUE, at(10, 30))
        assertEquals((30 + 60) * 60000L, total)
    }

    @Test
    fun `stray out with no in is ignored`() {
        val list = listOf(
            p(1, at(8, 0), "out"),
            p(2, at(9, 0), "in"), p(3, at(10, 0), "out")
        )
        val s = sessions(list)
        assertEquals(1, s.size)
        assertEquals(at(9, 0), s[0].inTs)
        assertEquals(at(10, 0), s[0].outTs)
    }

    @Test
    fun `session spanning midnight splits across day ranges`() {
        val inTs = at(23, 0)                       // 2 Sept 23:00
        val outTs = at(23, 0) + 2 * 3600_000L      // 3 Sept 01:00
        val list = listOf(p(1, inTs, "in"), p(2, outTs, "out"))
        val day2 = totalInRange(list, floorDay(inTs), floorDay(inTs) + 86_400_000, outTs)
        val day3 = totalInRange(list, floorDay(outTs), floorDay(outTs) + 86_400_000, outTs)
        assertEquals(60 * 60000L, day2)
        assertEquals(60 * 60000L, day3)
    }

    @Test
    fun `floorWeek always returns Monday even on Sunday`() {
        // 6 Sept 2026 is a Sunday; with a US-locale default firstDayOfWeek this
        // used to roll forward to the *next* Monday.
        val sunday = java.util.Calendar.getInstance().run {
            set(2026, 8, 6, 15, 0, 0); set(java.util.Calendar.MILLISECOND, 0)
            timeInMillis
        }
        val monday = java.util.Calendar.getInstance().run {
            set(2026, 7, 31, 0, 0, 0); set(java.util.Calendar.MILLISECOND, 0)
            timeInMillis
        }
        assertEquals(monday, floorWeek(sunday))
        assertEquals(monday, floorWeek(at(9, 0))) // Wed 2 Sept -> Mon 31 Aug
    }

    @Test
    fun `floorMonth returns first of month at midnight`() {
        val first = java.util.Calendar.getInstance().run {
            set(2026, 8, 1, 0, 0, 0); set(java.util.Calendar.MILLISECOND, 0)
            timeInMillis
        }
        assertEquals(first, floorMonth(at(17, 16)))
    }

    @Test
    fun `clock state comes from last punch only`() {
        assertFalse(isClockedIn(emptyList()))
        assertTrue(isClockedIn(listOf(p(1, at(9, 0), "in"))))
        assertFalse(isClockedIn(listOf(p(1, at(9, 0), "in"), p(2, at(10, 0), "out"))))
    }

    @Test
    fun `total sums completed sessions`() {
        val list = listOf(
            p(1, at(9, 7), "in"), p(2, at(9, 24), "out"),
            p(3, at(15, 12), "in"), p(4, at(16, 11), "out"),
            p(5, at(17, 4), "in"), p(6, at(17, 16), "out")
        )
        val total = totalInRange(list, Long.MIN_VALUE, Long.MAX_VALUE, at(18, 0))
        assertEquals((17 + 59 + 12) * 60000L, total)
    }

    @Test
    fun `open session counts up to now`() {
        val list = listOf(p(1, at(9, 0), "in"))
        val total = totalInRange(list, Long.MIN_VALUE, Long.MAX_VALUE, at(10, 30))
        assertEquals(90 * 60000L, total)
    }

    @Test
    fun `range clamps sessions at edges`() {
        val list = listOf(p(1, at(8, 0), "in"), p(2, at(12, 0), "out"))
        val total = totalInRange(list, at(9, 0), at(11, 0), at(12, 0))
        assertEquals(120 * 60000L, total)
    }

    @Test
    fun `fmtDuration pads minutes`() {
        assertEquals("1:28", fmtDuration(88 * 60000L))
        assertEquals("0:05", fmtDuration(5 * 60000L))
    }

    @Test
    fun `fmtHms formats live session readout`() {
        assertEquals("0:00:05", fmtHms(5_000L))
        assertEquals("1:02:03", fmtHms(3_723_000L))
        assertEquals("12:00:00", fmtHms(43_200_000L))
    }

    @Test
    fun `shiftMonth moves whole months and crosses the year`() {
        assertEquals(ts(2026, 8, 1), shiftMonth(ts(2026, 9, 17, 16, 16), -1))
        assertEquals(ts(2027, 1, 1), shiftMonth(ts(2026, 12, 31, 23, 59), 1))
        assertEquals(ts(2026, 2, 1), shiftMonth(ts(2026, 3, 31, 12, 0), -1))
    }

    @Test
    fun `nextDayStart rolls over month end`() {
        assertEquals(ts(2026, 10, 1), nextDayStart(ts(2026, 9, 30, 23, 59)))
        assertEquals(ts(2026, 9, 3), nextDayStart(ts(2026, 9, 2, 0, 0)))
    }

    @Test
    fun `summariseMonth groups day totals and ignores other months`() {
        val list = listOf(
            // 31 Aug — belongs to the previous month.
            p(1, ts(2026, 8, 31, 9, 0), "in"), p(2, ts(2026, 8, 31, 12, 0), "out"),
            // 2 Sept — 2h.
            p(3, ts(2026, 9, 2, 9, 0), "in"), p(4, ts(2026, 9, 2, 11, 0), "out"),
            // 3 Sept — 1h.
            p(5, ts(2026, 9, 3, 9, 0), "in"), p(6, ts(2026, 9, 3, 10, 0), "out")
        )
        val summary = summariseMonth(list, ts(2026, 9, 15), ts(2026, 9, 30, 18, 0))
        assertEquals(ts(2026, 9, 1), summary.monthStart)
        assertEquals(180 * 60000L, summary.totalMs)
        assertEquals(2, summary.daysWorked)
        assertEquals(ts(2026, 9, 2), summary.dayTotals[0].dayStart)
        assertEquals(120 * 60000L, summary.dayTotals[0].totalMs)
        assertEquals(60 * 60000L, summary.dayTotals[1].totalMs)
    }

    @Test
    fun `summariseMonth includes open sessions only up to now`() {
        val list = listOf(p(1, ts(2026, 9, 2, 9, 0), "in"))
        val summary = summariseMonth(list, ts(2026, 9, 1), ts(2026, 9, 2, 10, 30))
        assertEquals(90 * 60000L, summary.totalMs)
    }

    /* ---------- work-limit maths ---------- */

    private fun kinds(marks: List<LimitMark>) = marks.map { it.kind to it.at }

    @Test
    fun `fresh morning session - half at 30min day at 60min afternoon half at 13-30`() {
        val openIn = ts(2026, 9, 2, 9, 0)
        val marks = limitCrossings(listOf(p(1, openIn, "in")), openIn)
        assertEquals(
            listOf(
                LimitKind.HALF_MORNING to ts(2026, 9, 2, 9, 30),
                LimitKind.DAY to ts(2026, 9, 2, 10, 0),
                LimitKind.HALF_AFTERNOON to ts(2026, 9, 2, 13, 30)
            ),
            kinds(marks)
        )
    }

    @Test
    fun `session starting before 11 - morning can't finish, gap is free`() {
        val openIn = ts(2026, 9, 2, 10, 50)
        val marks = limitCrossings(listOf(p(1, openIn, "in")), openIn)
        // 10 min of morning work accrues, the gap eats the rest of the hour,
        // so the day mark lands 50 min into the afternoon.
        assertEquals(
            listOf(
                LimitKind.HALF_AFTERNOON to ts(2026, 9, 2, 13, 30),
                LimitKind.DAY to ts(2026, 9, 2, 13, 50)
            ),
            kinds(marks)
        )
    }

    @Test
    fun `clock back in after half stop - day backstop fires 30 min later`() {
        val openIn = ts(2026, 9, 2, 9, 35)
        val list = listOf(
            p(1, ts(2026, 9, 2, 9, 0), "in"), p(2, ts(2026, 9, 2, 9, 30), "out"),
            p(3, openIn, "in")
        )
        val marks = limitCrossings(list, openIn)
        assertEquals(
            listOf(
                LimitKind.DAY to ts(2026, 9, 2, 10, 5),
                LimitKind.HALF_AFTERNOON to ts(2026, 9, 2, 13, 30)
            ),
            kinds(marks)
        )
    }

    @Test
    fun `afternoon clock-in gets its own half`() {
        val openIn = ts(2026, 9, 2, 14, 0)
        val marks = limitCrossings(listOf(p(1, openIn, "in")), openIn)
        assertEquals(
            listOf(
                LimitKind.HALF_AFTERNOON to ts(2026, 9, 2, 14, 30),
                LimitKind.DAY to ts(2026, 9, 2, 15, 0)
            ),
            kinds(marks)
        )
    }

    @Test
    fun `session opened in the free gap skips the gap`() {
        val openIn = ts(2026, 9, 2, 12, 0)
        val marks = limitCrossings(listOf(p(1, openIn, "in")), openIn)
        assertEquals(
            listOf(
                LimitKind.HALF_AFTERNOON to ts(2026, 9, 2, 13, 30),
                LimitKind.DAY to ts(2026, 9, 2, 14, 0)
            ),
            kinds(marks)
        )
    }

    @Test
    fun `earlier closed work shifts the marks`() {
        val openIn = ts(2026, 9, 2, 9, 20)
        val list = listOf(
            p(1, ts(2026, 9, 2, 9, 0), "in"), p(2, ts(2026, 9, 2, 9, 10), "out"),
            p(3, openIn, "in")
        )
        val marks = limitCrossings(list, openIn)
        assertEquals(
            listOf(
                LimitKind.HALF_MORNING to ts(2026, 9, 2, 9, 40),
                LimitKind.DAY to ts(2026, 9, 2, 10, 10),
                LimitKind.HALF_AFTERNOON to ts(2026, 9, 2, 13, 30)
            ),
            kinds(marks)
        )
    }

    @Test
    fun `day mark shares the afternoon half instant when morning already stopped`() {
        val openIn = ts(2026, 9, 2, 13, 5)
        val list = listOf(
            p(1, ts(2026, 9, 2, 9, 0), "in"), p(2, ts(2026, 9, 2, 9, 30), "out"),
            p(3, openIn, "in")
        )
        val marks = limitCrossings(list, openIn)
        // Both marks land on 13:35 — checkAndSchedule dedupes them into one alarm.
        assertEquals(2, marks.size)
        assertTrue(marks.all { it.at == ts(2026, 9, 2, 13, 35) })
    }

    @Test
    fun `all marks consumed - no crossings left`() {
        val openIn = ts(2026, 9, 2, 13, 40)
        val list = listOf(
            p(1, ts(2026, 9, 2, 9, 0), "in"), p(2, ts(2026, 9, 2, 9, 30), "out"),
            p(3, ts(2026, 9, 2, 13, 5), "in"), p(4, ts(2026, 9, 2, 13, 35), "out"),
            p(5, openIn, "in")
        )
        assertTrue(limitCrossings(list, openIn).isEmpty())
    }

    @Test
    fun `isThursday matches the calendar day`() {
        assertTrue(Limit.isThursday(ts(2026, 11, 12, 9, 0))) // Thu 12 Nov 2026
        assertFalse(Limit.isThursday(ts(2026, 11, 11, 9, 0))) // Wed 11 Nov 2026
    }

    @Test
    fun `morning and afternoon work split a window-spanning session`() {
        val list = listOf(
            p(1, ts(2026, 9, 2, 10, 50), "in"), p(2, ts(2026, 9, 2, 13, 10), "out")
        )
        val day = ts(2026, 9, 2, 0, 0)
        assertEquals(10 * 60000L, morningWork(list, day, ts(2026, 9, 2, 14, 0)))
        assertEquals(10 * 60000L, afternoonWork(list, day, ts(2026, 9, 2, 14, 0)))
    }
}
