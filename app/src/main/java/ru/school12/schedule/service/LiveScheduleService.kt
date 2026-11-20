package ru.school12.schedule.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import ru.school12.schedule.alarm.ScheduleAlarmScheduler
import ru.school12.schedule.data.ScheduleRepository
import ru.school12.schedule.domain.ScheduleEngine
import ru.school12.schedule.notification.LiveUpdateManager

class LiveScheduleService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopAndClear()
            return START_NOT_STICKY
        }

        serviceScope.launch {
            val repository = ScheduleRepository(applicationContext)
            val enabled = repository.isLiveUpdatesEnabled()
            val payload = repository.getCachedSchedule()
            val selectedClass = repository.getSelectedClass()

            if (!enabled) {
                stopAndClear()
                return@launch
            }

            val status = ScheduleEngine.computeStatus(payload, selectedClass)
            val notification = LiveUpdateManager.buildNotification(
                context = applicationContext,
                status = status,
                selectedClass = selectedClass,
            )

            if (notification == null) {
                ScheduleAlarmScheduler.scheduleNextTick(applicationContext, payload, selectedClass)
                stopAndClear()
                return@launch
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this@LiveScheduleService,
                    LiveUpdateManager.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(LiveUpdateManager.NOTIFICATION_ID, notification)
            }

            ScheduleAlarmScheduler.scheduleNextTick(applicationContext, payload, selectedClass)
        }

        return START_NOT_STICKY
    }

    private fun stopAndClear() {
        LiveUpdateManager.cancel(applicationContext)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_REFRESH = "ru.school12.schedule.action.LIVE_REFRESH"
        private const val ACTION_STOP = "ru.school12.schedule.action.LIVE_STOP"

        fun sync(context: Context, shouldStartForeground: Boolean) {
            val intent = Intent(context, LiveScheduleService::class.java).apply {
                action = if (shouldStartForeground) ACTION_REFRESH else ACTION_STOP
            }
            if (shouldStartForeground) {
                runCatching { ContextCompat.startForegroundService(context, intent) }
            } else {
                context.stopService(Intent(context, LiveScheduleService::class.java))
                LiveUpdateManager.cancel(context)
            }
        }
    }
}
