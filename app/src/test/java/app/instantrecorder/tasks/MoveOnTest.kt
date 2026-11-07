package app.instantrecorder.tasks

import app.instantrecorder.data.TaskItem
import app.instantrecorder.tasks.EventTiming.WEEK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** "Send a meeting agenda 1 week before every Ward Council", as on the Fold in November 2026. */
class MoveOnTest {
    private val zone = ZoneId.of("America/Denver")
    private fun at(m: Int, d: Int, h: Int, min: Int = 0) = ZonedDateTime.of(2026, m, d, h, min, 0, 0, zone).toInstant().toEpochMilli()
    private fun day(m: Int, d: Int) = LocalDate.of(2026, m, d).atStartOfDay(zone).toInstant().toEpochMilli()

    private val agenda = TaskItem(
        id = 7, title = "Send a meeting agenda", dueAt = day(11, 1), dueHasTime = false,
        eventId = 1018, eventTitle = "Ward Council", eventBegin = at(11, 8, 9), eventOffsetMinutes = WEEK, eventRepeats = true,
    )
    private val calendar = listOf(
        EventRef(1018, "Ward Council", at(11, 8, 9), allDay = false, recurring = true),
        EventRef(1097, "Ward Council", at(11, 22, 9), allDay = false, recurring = true),
        EventRef(1018, "Ward Council", at(12, 13, 9), allDay = false, recurring = true),
    )

    @Test
    fun `an open every-meeting task moves on once its meeting starts`() {
        assertFalse(agenda.missedItsMeeting(at(11, 8, 8, 59)))
        assertTrue(agenda.missedItsMeeting(at(11, 8, 9)))
        assertTrue(agenda.missedItsMeeting(at(11, 20, 12)))
    }

    @Test
    fun `finished tasks, one-off event tasks and weekday repeats stay put`() {
        val now = at(11, 9, 12)
        assertFalse(agenda.copy(done = true).missedItsMeeting(now))
        assertFalse(agenda.copy(eventRepeats = false).missedItsMeeting(now))
        val plants = TaskItem(title = "Water the plants", dueAt = day(11, 2), repeatDays = Repeat.of(java.time.DayOfWeek.MONDAY))
        assertFalse(plants.missedItsMeeting(now))
    }

    @Test
    fun `moving on keeps the task and aims it at the next meeting by that name`() {
        val now = at(11, 8, 9, 1)
        val next = EventTiming.nextOccurrence(calendar, agenda.eventId!!, agenda.eventTitle, now)!!
        val moved = agenda.movedTo(next, zone)
        assertEquals(agenda.id, moved.id)
        assertEquals(agenda.title, moved.title)
        assertEquals(1097L, moved.eventId)
        assertEquals(at(11, 22, 9), moved.eventBegin)
        assertEquals(day(11, 15), moved.dueAt) // a week before Nov 22
        assertFalse(moved.dueHasTime)
        assertTrue(moved.eventRepeats)
        assertFalse(moved.done)
    }

    @Test
    fun `an all-day event keeps its calendar date`() {
        val birthday = EventRef(2, "Sarah Birthday", LocalDate.of(2026, 11, 21).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(), allDay = true)
        val moved = agenda.copy(eventOffsetMinutes = 3 * EventTiming.DAY).movedTo(birthday, zone)
        assertTrue(moved.eventAllDay)
        assertEquals(day(11, 18), moved.dueAt)
        assertEquals("Sat, Nov 21", EventTiming.dateLabel(birthday, zone, java.util.Locale.US))
    }
}
