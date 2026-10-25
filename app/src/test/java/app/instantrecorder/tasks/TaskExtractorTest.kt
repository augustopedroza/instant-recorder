package app.instantrecorder.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class TaskExtractorTest {
    private val zone = ZoneId.of("America/New_York")

    /** Friday, 16 Oct 2026, 09:00. */
    private val now = ZonedDateTime.of(2026, 10, 16, 9, 0, 0, 0, zone)

    private fun extract(text: String) = TaskExtractor.extract(text, now)
    private fun Due.at(): LocalDateTime = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDateTime()

    @Test
    fun `lead-in and date-time are removed from the title`() {
        val t = extract("Remind me to call mom tomorrow at 5 p.m.").single()
        assertEquals("Call mom", t.title)
        assertTrue(t.actionable)
        assertEquals(LocalDateTime.of(2026, 10, 17, 17, 0), t.due!!.at())
        assertTrue(t.due!!.hasTime)
    }

    @Test
    fun `several tasks in one note`() {
        val tasks = extract("Okay so I need to buy milk and then email Sarah about the budget. Also book the dentist for next Tuesday.")
        assertEquals(listOf("Buy milk", "Email Sarah about the budget", "Book the dentist"), tasks.map { it.title })
        assertNull(tasks[0].due)
        assertEquals(LocalDateTime.of(2026, 10, 20, 0, 0), tasks[2].due!!.at())
        assertFalse(tasks[2].due!!.hasTime)
    }

    @Test
    fun `weekday deadline with by`() {
        val t = extract("I have to submit the expense report by Friday.").single()
        assertEquals("Submit the expense report", t.title)
        // Saying "Friday" on a Friday means next week's.
        assertEquals(LocalDateTime.of(2026, 10, 23, 0, 0), t.due!!.at())
    }

    @Test
    fun `next weekday skips the current week`() {
        assertEquals(LocalDateTime.of(2026, 10, 24, 0, 0), extract("Clean the garage next Saturday").single().due!!.at())
        assertEquals(LocalDateTime.of(2026, 10, 19, 0, 0), extract("Call the bank next Monday").single().due!!.at())
    }

    @Test
    fun `time without am or pm`() {
        val t = extract("Pick up the kids at 3").single()
        assertEquals("Pick up the kids", t.title)
        assertEquals(LocalDateTime.of(2026, 10, 16, 15, 0), t.due!!.at())
    }

    @Test
    fun `time already past today means tomorrow`() {
        val t = extract("Take out the trash at 8 am").single()
        assertEquals(LocalDateTime.of(2026, 10, 17, 8, 0), t.due!!.at())
    }

    @Test
    fun `relative durations`() {
        assertEquals(LocalDateTime.of(2026, 10, 16, 11, 0), extract("Check the oven in two hours").single().due!!.at())
        assertEquals(LocalDateTime.of(2026, 10, 16, 9, 30), extract("Move the car in half an hour").single().due!!.at())
    }

    @Test
    fun `month and day`() {
        val t = extract("Renew my passport on March 3rd").single()
        assertEquals("Renew my passport", t.title)
        assertEquals(LocalDateTime.of(2027, 3, 3, 0, 0), t.due!!.at())
    }

    @Test
    fun `may as a verb is not a date`() {
        val t = extract("I may need to call the plumber").single()
        assertNull(t.due)
    }

    @Test
    fun `part of day`() {
        val t = extract("Tomorrow morning I need to water the plants").single()
        assertEquals("Water the plants", t.title)
        assertEquals(LocalDateTime.of(2026, 10, 17, 9, 0), t.due!!.at())
    }

    @Test
    fun `narration is kept but not marked actionable`() {
        val tasks = extract("The meeting went well today. Call John to follow up.")
        assertEquals(2, tasks.size)
        assertFalse(tasks[0].actionable)
        assertTrue(tasks[1].actionable)
        assertEquals("Call John to follow up", tasks[1].title)
    }

    @Test
    fun `comma and verb splits tasks (real whisper output)`() {
        val tasks = extract(
            "Okay, so remind me to call Mom tomorrow at 5pm and I need to buy milk and eggs on the way home. " +
                "Also, book a dentist appointment for next Tuesday, and email Sarah the Budget Numbers by Friday."
        )
        assertEquals(
            listOf("Call Mom", "Buy milk and eggs on the way home", "Book a dentist appointment", "Email Sarah the Budget Numbers"),
            tasks.map { it.title },
        )
        assertEquals(LocalDateTime.of(2026, 10, 17, 17, 0), tasks[0].due!!.at())
        assertEquals(LocalDateTime.of(2026, 10, 20, 0, 0), tasks[2].due!!.at())
        assertEquals(LocalDateTime.of(2026, 10, 23, 0, 0), tasks[3].due!!.at())
    }

    @Test
    fun `comma and noun does not split`() {
        assertEquals(listOf("Buy apples, and pears"), extract("Buy apples, and pears").map { it.title })
    }

    private val personal = CategoryRule(1, "Personal", emptyList())
    private val work = CategoryRule(2, "Work", listOf("budget", "standup meeting"))
    private val church = CategoryRule(3, "Church", listOf("ward council"))
    private val categories = listOf(personal, work, church)

    private fun categorized(text: String) =
        TaskExtractor.extract(text, now, categories).map { it.title to it.categoryId }

    @Test
    fun `spoken category prefix files the task and is removed from the title`() {
        assertEquals(listOf("Email Sarah" to 2L), categorized("Work: email Sarah."))
        assertEquals(listOf("Call mom" to 1L), categorized("Okay, personal, call mom tomorrow."))
    }

    @Test
    fun `a spoken category carries over until another is named`() {
        assertEquals(
            listOf("Book the room" to 2L, "Send the slides" to 2L, "Call mom" to 1L),
            categorized("Work. Book the room. Also send the slides. Personal: call mom."),
        )
    }

    @Test
    fun `keywords suggest a category and beat a carried-over one`() {
        assertEquals(
            listOf("Call mom" to 1L, "Prepare the ward council agenda" to 3L),
            categorized("Personal: call mom. Prepare the ward council agenda."),
        )
        assertEquals(listOf("Review the budget" to 2L), categorized("Review the budget"))
    }

    @Test
    fun `a category word inside a sentence is not a prefix`() {
        assertEquals(listOf("Work on the garden fence" to null), categorized("I need to work on the garden fence"))
    }

    @Test
    fun `no categories configured`() {
        assertEquals(null, TaskExtractor.extract("Work: email Sarah", now).single().categoryId)
    }

    private fun repeat(text: String) = extract(text).single().let { Triple(it.title, it.repeatDays, it.due!!.at()) }

    @Test
    fun `every weekday at a time repeats on weekdays from the next one`() {
        // Friday 9:00 now; "at 9" today has passed, so the first is Monday.
        assertEquals(
            Triple("Stand-up", Repeat.WEEKDAYS, LocalDateTime.of(2026, 10, 19, 9, 0)),
            repeat("Stand-up every weekday at 9 am"),
        )
    }

    @Test
    fun `every monday and thursday`() {
        assertEquals(
            Triple("Water the plants", Repeat.of(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.THURSDAY), LocalDateTime.of(2026, 10, 19, 0, 0)),
            repeat("Remind me to water the plants every Monday and Thursday"),
        )
    }

    @Test
    fun `daily and plural weekdays repeat, a single on-weekday does not`() {
        assertEquals(Repeat.EVERY_DAY, extract("Take my vitamins daily").single().repeatDays)
        assertEquals(Repeat.of(java.time.DayOfWeek.TUESDAY), extract("Take out the trash on Tuesdays").single().repeatDays)
        val once = extract("Call the bank on Tuesday").single()
        assertEquals(Repeat.NONE, once.repeatDays)
        assertEquals(LocalDateTime.of(2026, 10, 20, 0, 0), once.due!!.at())
    }

    @Test
    fun `before an event - action, offset and event name`() {
        val t = extract("Remind me to prepare the slides 2 days before the board meeting.").single()
        assertEquals("Prepare the slides", t.title)
        assertEquals(EventRequest("the board meeting", 2 * EventTiming.DAY, "2 days before the board meeting"), t.event)
        assertNull(t.due) // comes from the calendar
    }

    @Test
    fun `before an event - other phrasings`() {
        assertEquals(EventTiming.DAY, extract("Buy flowers the day before Mother's Day").single().event!!.offsetMinutes)
        assertEquals(EventTiming.WEEK, extract("Book the caterer a week before the wedding").single().event!!.offsetMinutes)
        assertEquals(2 * EventTiming.HOUR, extract("Leave for the airport two hours before my flight").single().event!!.offsetMinutes)
        assertEquals("ward council", extract("Send the agenda the night before ward council").single().event!!.query)
    }

    @Test
    fun `before every occurrence of an event`() {
        val t = extract("Send the meeting agenda a week before every ward council").single()
        assertEquals("Send the meeting agenda", t.title)
        assertEquals(EventRequest("ward council", EventTiming.WEEK, "a week before every ward council", everyTime = true), t.event)
        assertEquals(false, extract("Send the agenda a week before the ward council").single().event!!.everyTime)
    }

    @Test
    fun `before a plain date is not an event`() {
        assertNull(extract("Pay the rent before Friday").single().event)
    }

    @Test
    fun `empty transcript`() {
        assertTrue(extract("   ").isEmpty())
    }

    // Whisper writes a pause in speech as a sentence end and capitalizes the next word. The inputs below are
    // its real output (both models) for sentences spoken with a 1.5–3 s pause in the middle.

    @Test
    fun `a pause inside a task does not split it`() {
        val t = extract("I need to call the dentist. About my appointment tomorrow.").single()
        assertEquals("Call the dentist about my appointment", t.title)
        assertEquals(LocalDateTime.of(2026, 10, 17, 0, 0), t.due!!.at())
        assertEquals(
            listOf("Call the plumber because the sink is leaking again"),
            extract("Call the plumber. Because the sink is leaking again.").map { it.title },
        )
        val report = extract("Remind me to send the report. To Sarah. By Friday.").single()
        assertEquals("Send the report to Sarah", report.title)
        assertEquals(LocalDateTime.of(2026, 10, 23, 0, 0), report.due!!.at())
    }

    @Test
    fun `timing after a pause belongs to the task before it`() {
        val friday = extract("Email Sarah the budget numbers. By Friday.").single()
        assertEquals("Email Sarah the budget numbers", friday.title)
        assertEquals(LocalDateTime.of(2026, 10, 23, 0, 0), friday.due!!.at())
        val dentist = extract("Call the dentist. Tomorrow at 3.").single()
        assertEquals("Call the dentist", dentist.title)
        assertEquals(LocalDateTime.of(2026, 10, 17, 15, 0), dentist.due!!.at())
        val plants = extract("Water the plants. Every Monday and Thursday.").single()
        assertEquals("Water the plants", plants.title)
        assertEquals(Repeat.of(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.THURSDAY), plants.repeatDays)
        val slides = extract("Prepare the slides. Two days before the board meeting.").single()
        assertEquals("Prepare the slides", slides.title)
        assertEquals("the board meeting", slides.event!!.query)
    }

    @Test
    fun `a pause after the lead-in`() {
        for (text in listOf(
            "Remind me to. Call the dentist tomorrow at 3.",
            "Remind me to... Call the dentist tomorrow at 3.",
            "Remind me too. Call the dentist tomorrow at 3.",
            "I need to. Call the dentist tomorrow at 3.",
        )) {
            val t = extract(text).single()
            assertEquals(text, "Call the dentist", t.title)
            assertEquals(text, LocalDateTime.of(2026, 10, 17, 15, 0), t.due!!.at())
        }
        // A lead-in with nothing after it isn't a task either.
        assertEquals(listOf("Call mom"), extract("Call mom. Remind me to.").map { it.title })
    }

    @Test
    fun `a pause after a bare verb or a dangling word`() {
        assertEquals(
            listOf("Buy some milk, eggs and bread and some butter"),
            extract("I need to buy. Some milk, eggs and bread. And some butter.").map { it.title },
        )
        // The capital after the pause stays when the word may be a name.
        assertEquals(listOf("Email Sarah about the budget"), extract("I need to email. Sarah about the budget.").map { it.title })
        assertEquals(listOf("Follow up with Sarah"), extract("Follow up. With Sarah.").map { it.title })
        assertEquals(listOf("Send the slides to Sarah"), extract("Send the slides to. Sarah.").map { it.title })
    }

    @Test
    fun `an afterthought without a verb continues, one with a verb is a new task`() {
        assertEquals(listOf("Buy milk and eggs for the cake"), extract("Buy milk. And eggs. For the cake.").map { it.title })
        assertEquals(listOf("Buy milk", "Call mom"), extract("Buy milk. And call mom tomorrow.").map { it.title })
        assertEquals(
            listOf("Email Sarah the budget numbers", "Book the dentist"),
            extract("Email Sarah the budget numbers. Also book the dentist for next Tuesday.").map { it.title },
        )
    }

    @Test
    fun `a category name is never glued to a task`() {
        assertEquals(
            listOf("Book the room" to null, "Call mom" to 1L),
            categorized("I need to book. The room. Personal: call mom."),
        )
    }
}
