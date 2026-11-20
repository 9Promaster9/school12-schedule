package ru.school12.schedule.domain

import ru.school12.schedule.model.BellSlot
import ru.school12.schedule.model.DayTab
import ru.school12.schedule.model.Lesson
import ru.school12.schedule.model.ResolvedDaySchedule
import ru.school12.schedule.model.SchedulePayload
import ru.school12.schedule.model.ScheduleStatus
import ru.school12.schedule.model.normalizeClassKey
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

object ScheduleEngine {
    val SCHOOL_ZONE: ZoneId = ZoneId.of("Europe/Saratov")
    private val RU_LOCALE = Locale.forLanguageTag("ru-RU")
    private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("H:mm")

    val FALLBACK_BELLS: Map<Int, BellSlot> = mapOf(
        1 to BellSlot("08:00", "08:40"),
        2 to BellSlot("08:50", "09:30"),
        3 to BellSlot("09:45", "10:25"),
        4 to BellSlot("10:40", "11:20"),
        5 to BellSlot("11:35", "12:15"),
        6 to BellSlot("12:30", "13:10"),
        7 to BellSlot("13:20", "14:00"),
        8 to BellSlot("14:20", "15:00"),
    )

    fun nowSchool(): ZonedDateTime = ZonedDateTime.now(SCHOOL_ZONE)

    fun availableClasses(payload: SchedulePayload?): List<String> {
        if (payload == null) return emptyList()
        val seen = LinkedHashMap<String, String>()
        payload.days.values.forEach { day ->
            day.classes.keys.forEach { cls ->
                seen.putIfAbsent(normalizeClassKey(cls), cls)
            }
        }
        return seen.values.toList()
    }

    fun lessonsForDate(
        payload: SchedulePayload?,
        date: LocalDate,
        selectedClass: String,
    ): ResolvedDaySchedule? {
        if (payload == null) return null
        val iso = date.toString()
        val day = payload.days[iso] ?: return null
        val targetKey = normalizeClassKey(selectedClass)
        val matchedEntry = day.classes.entries.firstOrNull { (k, _) ->
            normalizeClassKey(k) == targetKey
        } ?: return null

        val enrichedLessons = matchedEntry.value
            .sortedBy { it.number }
            .map { lesson -> enrichLessonTimes(lesson, payload.bells) }

        val dayName = day.dayOfWeek.ifBlank {
            date.dayOfWeek.getDisplayName(TextStyle.FULL, RU_LOCALE)
                .replaceFirstChar { it.uppercase(RU_LOCALE) }
        }
        return ResolvedDaySchedule(
            date = date,
            dayOfWeek = dayName,
            lessons = enrichedLessons,
        )
    }

    fun nextAvailableDay(
        payload: SchedulePayload?,
        today: LocalDate,
        selectedClass: String,
    ): ResolvedDaySchedule? {
        if (payload == null) return null
        val futureDate = payload.days.keys
            .mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
            .filter { it.isAfter(today) }
            .minOrNull()
            ?: return null
        return lessonsForDate(payload, futureDate, selectedClass)
    }

    fun latestAvailableDay(
        payload: SchedulePayload?,
        selectedClass: String,
    ): ResolvedDaySchedule? {
        if (payload == null) return null
        val latest = payload.days.keys
            .mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
            .maxOrNull()
            ?: return null
        return lessonsForDate(payload, latest, selectedClass)
    }

    fun smartDefaultTab(
        payload: SchedulePayload?,
        selectedClass: String,
        now: ZonedDateTime = nowSchool(),
    ): DayTab {
        val today = now.toLocalDate()
        val todaySchedule = lessonsForDate(payload, today, selectedClass)
        val nextSchedule = nextAvailableDay(payload, today, selectedClass)

        if (todaySchedule == null || todaySchedule.lessons.isEmpty()) {
            return if (nextSchedule != null) DayTab.TOMORROW else DayTab.TODAY
        }
        val lastEnd = lessonEndTime(today, todaySchedule.lessons.last())
        if (lastEnd != null && !now.isBefore(lastEnd) && nextSchedule != null) {
            return DayTab.TOMORROW
        }
        return DayTab.TODAY
    }

    fun computeStatus(
        payload: SchedulePayload?,
        selectedClass: String,
        now: ZonedDateTime = nowSchool(),
    ): ScheduleStatus {
        if (payload == null) return ScheduleStatus.NoData
        val today = now.toLocalDate()
        val todaySchedule = lessonsForDate(payload, today, selectedClass)
        val nextSchedule = nextAvailableDay(payload, today, selectedClass)

        if (todaySchedule == null || todaySchedule.lessons.isEmpty()) {
            return if (nextSchedule != null) {
                ScheduleStatus.AfterSchool(
                    tomorrowDate = nextSchedule.date,
                    tomorrowDayOfWeek = nextSchedule.dayOfWeek,
                    tomorrowFirstLesson = nextSchedule.lessons.firstOrNull(),
                )
            } else {
                ScheduleStatus.NoData
            }
        }

        val timed = todaySchedule.lessons.mapNotNull { lesson ->
            val start = lessonStartTime(today, lesson)
            val end = lessonEndTime(today, lesson)
            if (start != null && end != null && end.isAfter(start)) {
                Triple(lesson, start, end)
            } else {
                null
            }
        }
        if (timed.isEmpty()) return ScheduleStatus.NoData

        val firstStart = timed.first().second
        if (now.isBefore(firstStart)) {
            val mins = Duration.between(now, firstStart).toMinutes().coerceAtLeast(1)
            return ScheduleStatus.BeforeSchool(
                firstLesson = timed.first().first,
                startEpochMillis = firstStart.toInstant().toEpochMilli(),
                minutesUntilStart = mins,
            )
        }

        for (i in timed.indices) {
            val (lesson, start, end) = timed[i]
            val nextLesson = timed.getOrNull(i + 1)?.first

            if (!now.isBefore(start) && now.isBefore(end)) {
                val totalMs = Duration.between(start, end).toMillis().coerceAtLeast(1L)
                val elapsedMs = Duration.between(start, now).toMillis().coerceIn(0L, totalMs)
                val progress = ((elapsedMs * 100L) / totalMs).toInt().coerceIn(0, 100)
                val minsLeft = ((totalMs - elapsedMs) / 60_000L).coerceAtLeast(0L)
                return ScheduleStatus.InLesson(
                    currentLesson = lesson,
                    nextLesson = nextLesson,
                    startEpochMillis = start.toInstant().toEpochMilli(),
                    endEpochMillis = end.toInstant().toEpochMilli(),
                    progressPercent = progress,
                    minutesRemaining = minsLeft,
                )
            }

            if (i + 1 < timed.size) {
                val (upcomingLesson, upcomingStart, _) = timed[i + 1]
                if (!now.isBefore(end) && now.isBefore(upcomingStart)) {
                    val totalMs = Duration.between(end, upcomingStart).toMillis().coerceAtLeast(1L)
                    val elapsedMs = Duration.between(end, now).toMillis().coerceIn(0L, totalMs)
                    val progress = ((elapsedMs * 100L) / totalMs).toInt().coerceIn(0, 100)
                    val minsLeft = ((totalMs - elapsedMs) / 60_000L).coerceAtLeast(0L)
                    return ScheduleStatus.InBreak(
                        previousLesson = lesson,
                        nextLesson = upcomingLesson,
                        startEpochMillis = end.toInstant().toEpochMilli(),
                        endEpochMillis = upcomingStart.toInstant().toEpochMilli(),
                        progressPercent = progress,
                        minutesRemaining = minsLeft,
                    )
                }
            }
        }

        return ScheduleStatus.AfterSchool(
            tomorrowDate = nextSchedule?.date,
            tomorrowDayOfWeek = nextSchedule?.dayOfWeek,
            tomorrowFirstLesson = nextSchedule?.lessons?.firstOrNull(),
        )
    }

    fun nextAlarmTriggerTime(
        payload: SchedulePayload?,
        selectedClass: String,
        now: ZonedDateTime = nowSchool(),
    ): ZonedDateTime? {
        if (payload == null) return null
        val today = now.toLocalDate()
        val todaySchedule = lessonsForDate(payload, today, selectedClass)

        if (todaySchedule != null && todaySchedule.lessons.isNotEmpty()) {
            val boundaries = mutableListOf<ZonedDateTime>()
            val firstStart = lessonStartTime(today, todaySchedule.lessons.first())
            if (firstStart != null) {
                boundaries.add(firstStart.minusMinutes(20))
            }
            todaySchedule.lessons.forEach { lesson ->
                lessonStartTime(today, lesson)?.let { boundaries.add(it) }
                lessonEndTime(today, lesson)?.let { boundaries.add(it) }
            }
            val nextBoundary = boundaries
                .filter { it.isAfter(now.plusSeconds(2)) }
                .minOrNull()
            if (nextBoundary != null) return nextBoundary
        }

        val nextSchedule = nextAvailableDay(payload, today, selectedClass)
        if (nextSchedule != null && nextSchedule.lessons.isNotEmpty()) {
            val firstStart = lessonStartTime(nextSchedule.date, nextSchedule.lessons.first())
            if (firstStart != null) {
                return firstStart.minusMinutes(20)
            }
        }

        return today.plusDays(1).atTime(7, 40).atZone(SCHOOL_ZONE)
    }

    private fun enrichLessonTimes(lesson: Lesson, bells: Map<String, BellSlot>): Lesson {
        if (lesson.startTime.isNotBlank() && lesson.endTime.isNotBlank()) return lesson
        val slot = bells[lesson.number.toString()] ?: FALLBACK_BELLS[lesson.number]
        return if (slot != null) {
            lesson.copy(
                startTime = lesson.startTime.ifBlank { slot.start },
                endTime = lesson.endTime.ifBlank { slot.end },
            )
        } else {
            lesson
        }
    }

    fun lessonStartTime(date: LocalDate, lesson: Lesson): ZonedDateTime? =
        parseTime(lesson.startTime)?.atDate(date)?.atZone(SCHOOL_ZONE)

    fun lessonEndTime(date: LocalDate, lesson: Lesson): ZonedDateTime? =
        parseTime(lesson.endTime)?.atDate(date)?.atZone(SCHOOL_ZONE)

    private fun parseTime(raw: String): LocalTime? =
        runCatching { LocalTime.parse(raw.trim(), TIME_FORMATTER) }.getOrNull()
}
