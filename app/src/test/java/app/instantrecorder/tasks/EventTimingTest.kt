package app.instantrecorder.tasks

import app.instantrecorder.tasks.EventTiming.DAY
import app.instantrecorder.tasks.EventTiming.HOUR
import app.instantrecorder.tasks.EventTiming.WEEK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

class EventTimingTest {
    private val zone = ZoneId.of("America/Denver")
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0) = ZonedDateTime.of(y, m, d, h, min, 0, 0, zone).toInstant().toEpochMilli()
    private fun Due.at(): LocalDateTime = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDateTime()

    /** Board meeting Friday 20 Nov 2026, 19:00 local. */
    private val board = EventRef(1, "Q4 Board Meeting", at(2026, 11, 20, 19), allDay = false)

    /** All-day birthday Saturday 21 Nov 2026: stored as UTC midnight, which is the evening before in Denver. */
    private val birthday = EventRef(2, "Sarah Birthday 🎂", LocalDate.of(2026, 11, 21).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), allDay = true)

    @Test
    fun `day offsets are date-only, counted from the event's date`() {
        val due = EventTiming.due(board, DAY, zone)
        assertEquals(LocalDateTime.of(2026, 11, 19, 0, 0), due.at())
        assertFalse(due.hasTime)
        assertEquals(LocalDateTime.of(2026, 11, 13, 0, 0), EventTiming.due(board, WEEK, zone).at())
    }

    @Test
    fun `all-day events use their calendar date, not the UTC instant`() {
        assertEquals(LocalDateTime.of(2026, 11, 18, 0, 0), EventTiming.due(birthday, 3 * DAY, zone).at())
    }

    @Test
    fun `hour offsets are exact times`() {
        val due = EventTiming.due(board, 2 * HOUR, zone)
        assertEquals(LocalDateTime.of(2026, 11, 20, 17, 0), due.at())
        assertTrue(due.hasTime)
    }

    @Test
    fun `descriptions`() {
        assertEquals("the day before", EventTiming.describe(DAY))
        assertEquals("3 days before", EventTiming.describe(3 * DAY))
        assertEquals("1 week before", EventTiming.describe(WEEK))
        assertEquals("2 hours before", EventTiming.describe(2 * HOUR))
    }

    @Test
    fun `name matching ignores filler, possessives and plurals`() {
        assertEquals(1.0, EventTiming.matchScore("Sarah's birthday", "Sarah Birthday 🎂"), 0.0)
        assertEquals(1.0, EventTiming.matchScore("the board meeting", "Q4 Board Meeting"), 0.0)
        assertEquals(1.0, EventTiming.matchScore("ward council", "Ward Council meetings"), 0.0)
        assertEquals(0.0, EventTiming.matchScore("dentist", "Q4 Board Meeting"), 0.0)
    }

    @Test
    fun `best match prefers the better name, then the earliest`() {
        val laterBoard = board.copy(eventId = 3, begin = at(2027, 2, 19, 19))
        val boardTraining = EventRef(4, "Board training", at(2026, 11, 1, 9), allDay = false)
        assertEquals(1L, EventTiming.bestMatch("board meeting", listOf(laterBoard, boardTraining, board))?.eventId)
        assertNull(EventTiming.bestMatch("dentist", listOf(board, birthday)))
    }

    // The Fold's calendar on 2026-11-06: Ward Council at 9:00 on 2nd Sundays (event 1018, copied in another
    // calendar as 6945) and on 4th Sundays (event 1097).
    private fun wardCouncil(id: Long, day: Int, month: Int = 11) =
        EventRef(id, "Ward Council", at(2026, month, day, 9), allDay = false, recurring = true)
    private val calendar = listOf(
        wardCouncil(1018, 8), wardCouncil(6945, 8), wardCouncil(1097, 22), wardCouncil(1018, 13, 12), wardCouncil(6945, 13, 12),
        EventRef(50, "Bishopric meeting", at(2026, 11, 15, 8), allDay = false, recurring = true),
    )

    @Test
    fun `the next meeting starts after the current one, not the one still listed for that moment`() {
        // Completing the task for Nov 8: a calendar query from 9:00 Nov 8 still returns the Nov 8 meeting.
        val next = EventTiming.nextOccurrence(calendar, eventId = 1018, title = "Ward Council", after = at(2026, 11, 8, 9))
        assertEquals(at(2026, 11, 22, 9), next?.begin)
    }

    @Test
    fun `every meeting with that name counts, across separate series`() {
        val next = EventTiming.nextOccurrence(calendar, eventId = 1018, title = "ward council ", after = at(2026, 11, 8, 9))
        assertEquals(1097L, next?.eventId)
        // And from the 4th-Sunday series back to the 2nd-Sunday one.
        val after = EventTiming.nextOccurrence(calendar, eventId = 1097, title = "Ward Council", after = at(2026, 11, 22, 9))
        assertEquals(at(2026, 12, 13, 9), after?.begin)
    }

    @Test
    fun `the same meeting in two calendars stays on the task's own event`() {
        val next = EventTiming.nextOccurrence(calendar, eventId = 6945, title = "Ward Council", after = at(2026, 11, 1, 9))
        assertEquals(6945L, next?.eventId)
        assertEquals(at(2026, 11, 8, 9), next?.begin)
    }

    @Test
    fun `a renamed event is still followed by its id, and other names never match`() {
        val renamed = listOf(wardCouncil(1018, 8).copy(title = "Ward Council (ward building)"), calendar.last())
        assertEquals(1018L, EventTiming.nextOccurrence(renamed, eventId = 1018, title = "Ward Council", after = at(2026, 11, 1, 9))?.eventId)
        assertNull(EventTiming.nextOccurrence(listOf(calendar.last()), eventId = 1018, title = "Ward Council", after = at(2026, 11, 1, 9)))
        assertNull(EventTiming.nextOccurrence(calendar, eventId = 1018, title = null, after = at(2026, 12, 13, 9)))
    }
}
