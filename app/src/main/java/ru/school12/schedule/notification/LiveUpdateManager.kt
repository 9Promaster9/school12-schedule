package ru.school12.schedule.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import ru.school12.schedule.MainActivity
import ru.school12.schedule.R
import ru.school12.schedule.model.ScheduleStatus

object LiveUpdateManager {
    const val CHANNEL_ID = "school12_live_schedule_updates"
    const val NOTIFICATION_ID = 1201

    private const val PROGRESS_MAX = 1000
    private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Текущий урок (Live Updates)",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Отображение текущего урока, перемены и таймера до звонка на экране блокировки и в статус-баре"
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    fun shouldShowLiveUpdate(status: ScheduleStatus): Boolean = when (status) {
        is ScheduleStatus.InLesson -> true
        is ScheduleStatus.InBreak -> true
        is ScheduleStatus.BeforeSchool -> status.minutesUntilStart <= 25L
        is ScheduleStatus.AfterSchool -> false
        ScheduleStatus.NoData -> false
    }

    fun buildNotification(
        context: Context,
        status: ScheduleStatus,
        selectedClass: String,
    ): Notification? {
        if (!shouldShowLiveUpdate(status)) return null
        ensureChannel(context)

        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val content = mapStatusToContent(status, selectedClass)

        return if (Build.VERSION.SDK_INT >= 36) {
            buildApi36LiveUpdate(context, content, contentIntent)
        } else {
            buildCompatFallback(context, content, contentIntent)
        }
    }

    fun post(context: Context, notification: Notification) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, notification)
    }

    fun cancel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.cancel(NOTIFICATION_ID)
    }

    @RequiresApi(36)
    private fun buildApi36LiveUpdate(
        context: Context,
        content: LiveNotificationContent,
        contentIntent: PendingIntent,
    ): Notification {
        val progressStyle = Notification.ProgressStyle()
            .setProgress(content.progressPermille.coerceIn(0, PROGRESS_MAX))
            .setStyledByProgress(true)
            .addProgressSegment(
                Notification.ProgressStyle.Segment(PROGRESS_MAX)
                    .setColor(content.accentColor)
            )

        val extras = Bundle().apply {
            putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)
        }

        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_school)
            .setContentTitle(content.title)
            .setContentText(content.subtitle)
            .setSubText(content.subText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(contentIntent)
            .setWhen(content.targetEpochMillis)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setShortCriticalText(content.shortChipText)
            .setStyle(progressStyle)
            .addExtras(extras)
            .build()
    }

    private fun buildCompatFallback(
        context: Context,
        content: LiveNotificationContent,
        contentIntent: PendingIntent,
    ): Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_school)
            .setContentTitle(content.title)
            .setContentText(content.subtitle)
            .setSubText(content.subText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentIntent)
            .setWhen(content.targetEpochMillis)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setProgress(PROGRESS_MAX, content.progressPermille.coerceIn(0, PROGRESS_MAX), false)
            .build()
    }

    private fun mapStatusToContent(
        status: ScheduleStatus,
        selectedClass: String,
    ): LiveNotificationContent = when (status) {
        is ScheduleStatus.InLesson -> {
            val roomSuffix = status.currentLesson.room
                .takeIf { it.isNotBlank() }
                ?.let { " · каб. $it" }
                .orEmpty()
            val nextPart = status.nextLesson?.let { next ->
                val nextRoom = next.room.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
                "Далее: ${next.subject}$nextRoom"
            } ?: "Последний урок на сегодня"

            val chip = if (status.currentLesson.room.isNotBlank()) {
                "каб. ${status.currentLesson.room}"
            } else {
                "${status.currentLesson.number} урок"
            }

            LiveNotificationContent(
                title = "${status.currentLesson.number}. ${status.currentLesson.subject}$roomSuffix",
                subtitle = "$nextPart · до ${status.currentLesson.endTime}",
                subText = selectedClass,
                shortChipText = chip.take(12),
                progressPermille = (status.progressFraction * PROGRESS_MAX).toInt().coerceIn(0, PROGRESS_MAX),
                targetEpochMillis = status.endEpochMillis,
                accentColor = Color.parseColor("#1B6EF3"),
            )
        }

        is ScheduleStatus.InBreak -> {
            val nextRoom = status.nextLesson.room
                .takeIf { it.isNotBlank() }
                ?.let { ", каб. $it" }
                .orEmpty()
            LiveNotificationContent(
                title = "Перемена → ${status.nextLesson.subject}$nextRoom",
                subtitle = "${status.nextLesson.number}-й урок в ${status.nextLesson.startTime}",
                subText = selectedClass,
                shortChipText = "Перемена",
                progressPermille = (status.progressFraction * PROGRESS_MAX).toInt().coerceIn(0, PROGRESS_MAX),
                targetEpochMillis = status.endEpochMillis,
                accentColor = Color.parseColor("#0F9D58"),
            )
        }

        is ScheduleStatus.BeforeSchool -> {
            val room = status.firstLesson.room
                .takeIf { it.isNotBlank() }
                ?.let { ", каб. $it" }
                .orEmpty()
            LiveNotificationContent(
                title = "Скоро 1-й урок: ${status.firstLesson.subject}$room",
                subtitle = "Начало в ${status.firstLesson.startTime}",
                subText = selectedClass,
                shortChipText = status.firstLesson.startTime,
                progressPermille = 0,
                targetEpochMillis = status.startEpochMillis,
                accentColor = Color.parseColor("#F57C00"),
            )
        }

        is ScheduleStatus.AfterSchool,
        ScheduleStatus.NoData -> error("Unreachable status for live notification")
    }

    private data class LiveNotificationContent(
        val title: String,
        val subtitle: String,
        val subText: String,
        val shortChipText: String,
        val progressPermille: Int,
        val targetEpochMillis: Long,
        val accentColor: Int,
    )
}
