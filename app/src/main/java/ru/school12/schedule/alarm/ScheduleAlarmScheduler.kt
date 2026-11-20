package ru.school12.schedule.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import ru.school12.schedule.domain.ScheduleEngine
import ru.school12.schedule.model.SchedulePayload

object ScheduleAlarmScheduler {
    private const val REQUEST_CODE_BELL_TICK = 4102

    fun scheduleNextTick(
        context: Context,
        payload: SchedulePayload?,
        selectedClass: String,
        testModeStartMs: Long = 0L,
    ) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val nextTime = ScheduleEngine.nextAlarmTriggerTime(
            payload = payload,
            selectedClass = selectedClass,
            testModeStartMs = testModeStartMs,
        ) ?: return
        val triggerAtMillis = nextTime.toInstant().toEpochMilli()

        val pendingIntent = buildPendingIntent(context)
        alarmManager.cancel(pendingIntent)

        val canScheduleExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }

        if (canScheduleExact) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent,
            )
        } else {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent,
            )
        }
    }

    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return false
        return alarmManager.canScheduleExactAlarms()
    }

    private fun buildPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ScheduleAlarmReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_BELL_TICK,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
