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
}
