package ru.school12.schedule.model

import kotlinx.serialization.Serializable
import java.time.LocalDate

@Serializable
data class SchedulePayload(
    val school: String = "МОУ СОШ №12 им. В.Ф. Суханова",
    val timezone: String = "Europe/Saratov",
    val updatedAt: String = "",
    val defaultClass: String = DEFAULT_CLASS_NAME,
    val bells: Map<String, BellSlot> = emptyMap(),
    val days: Map<String, DaySchedule> = emptyMap(),
)

@Serializable
data class BellSlot(
    val start: String,
    val end: String,
)

@Serializable
data class DaySchedule(
    val dayOfWeek: String = "",
    val sourceUrl: String = "",
    val classes: Map<String, List<Lesson>> = emptyMap(),
)

@Serializable
data class Lesson(
    val number: Int,
    val subject: String,
    val teacher: String = "",
    val room: String = "",
    val startTime: String = "",
    val endTime: String = "",
)

enum class DayTab {
    TODAY,
    TOMORROW,
}

sealed interface ScheduleStatus {
    data class InLesson(
        val currentLesson: Lesson,
        val nextLesson: Lesson?,
        val startEpochMillis: Long,
        val endEpochMillis: Long,
        val progressPercent: Int,
        val minutesRemaining: Long,
        val secondsRemaining: Long = minutesRemaining * 60L,
    ) : ScheduleStatus

    data class InBreak(
        val previousLesson: Lesson,
        val nextLesson: Lesson,
        val startEpochMillis: Long,
        val endEpochMillis: Long,
        val progressPercent: Int,
        val minutesRemaining: Long,
        val secondsRemaining: Long = minutesRemaining * 60L,
    ) : ScheduleStatus

    data class BeforeSchool(
        val firstLesson: Lesson,
        val startEpochMillis: Long,
        val minutesUntilStart: Long,
    ) : ScheduleStatus

    data class AfterSchool(
        val tomorrowDate: LocalDate?,
        val tomorrowDayOfWeek: String?,
        val tomorrowFirstLesson: Lesson?,
    ) : ScheduleStatus

    data object NoData : ScheduleStatus
}

data class ResolvedDaySchedule(
    val date: LocalDate,
    val dayOfWeek: String,
    val lessons: List<Lesson>,
)

const val DEFAULT_CLASS_NAME = "11б (ТЕХ)"

fun normalizeClassKey(raw: String): String =
    raw.trim()
        .lowercase()
        .replace(Regex("\\s+"), "")
        .replace('ё', 'е')

fun formatRemainingLabel(minutesRemaining: Long, secondsRemaining: Long): String =
    if (secondsRemaining < 60L) {
        "${secondsRemaining.coerceAtLeast(0L)} сек"
    } else {
        "$minutesRemaining мин"
    }
