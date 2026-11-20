package ru.school12.schedule.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.school12.schedule.alarm.ScheduleAlarmScheduler
import ru.school12.schedule.data.ScheduleRepository
import ru.school12.schedule.domain.ScheduleEngine
import ru.school12.schedule.model.ScheduleStatus
import ru.school12.schedule.notification.LiveUpdateManager
import ru.school12.schedule.widget.ScheduleGlanceWidget

class LiveScheduleService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickerJob: Job? = null
    private var isForegroundStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopAndClear()
            return START_NOT_STICKY
        }

        tickerJob?.cancel()
        tickerJob = serviceScope.launch {
            runLiveUpdateLoop()
        }

        return START_NOT_STICKY
    }

    private suspend fun runLiveUpdateLoop() {
        val repository = ScheduleRepository(applicationContext)
        var lastPhaseKey: String? = null

        while (serviceScope.isActive) {
            val enabled = repository.isLiveUpdatesEnabled()
            val payload = repository.getCachedSchedule()
            val selectedClass = repository.getSelectedClass()
            val testModeStartMs = repository.getTestModeStartMs()

            if (!enabled) {
                stopAndClear()
                return
            }

            val now = ScheduleEngine.nowSchool()
            val status = ScheduleEngine.computeStatus(
                payload = payload,
                selectedClass = selectedClass,
                now = now,
                testModeStartMs = testModeStartMs,
            )

            val notification = LiveUpdateManager.buildNotification(
                context = applicationContext,
                status = status,
                selectedClass = selectedClass,
            )

            if (notification == null) {
                runCatching { ScheduleGlanceWidget().updateAll(applicationContext) }
                ScheduleAlarmScheduler.scheduleNextTick(
                    context = applicationContext,
                    payload = payload,
                    selectedClass = selectedClass,
                    testModeStartMs = testModeStartMs,
                )
                stopAndClear()
                return
            }

            if (!isForegroundStarted) {
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
                isForegroundStarted = true
            } else {
                LiveUpdateManager.post(applicationContext, notification)
            }

            val currentPhaseKey = phaseKeyFor(status)
            if (currentPhaseKey != lastPhaseKey) {
                lastPhaseKey = currentPhaseKey
                runCatching { ScheduleGlanceWidget().updateAll(applicationContext) }
                ScheduleAlarmScheduler.scheduleNextTick(
                    context = applicationContext,
                    payload = payload,
                    selectedClass = selectedClass,
                    testModeStartMs = testModeStartMs,
                )
            }

            val nowMs = System.currentTimeMillis()
            val boundaryMs = boundaryEpochMillis(status)
            val isTestMode = testModeStartMs > 0L
            val stepMs = if (isTestMode) TEST_PROGRESS_TICK_MS else NORMAL_PROGRESS_TICK_MS

            val sleepMs = if (boundaryMs != null) {
                val remainingMs = (boundaryMs - nowMs).coerceAtLeast(0L)
                if (remainingMs <= stepMs) {
                    remainingMs + BOUNDARY_GRACE_MS
                } else {
                    stepMs
                }
            } else {
                stepMs
            }

            delay(sleepMs)
        }
    }

    private fun boundaryEpochMillis(status: ScheduleStatus): Long? = when (status) {
        is ScheduleStatus.InLesson -> status.endEpochMillis
        is ScheduleStatus.InBreak -> status.endEpochMillis
        is ScheduleStatus.BeforeSchool -> status.startEpochMillis
        is ScheduleStatus.AfterSchool,
        ScheduleStatus.NoData -> null
    }

    private fun phaseKeyFor(status: ScheduleStatus): String = when (status) {
        is ScheduleStatus.InLesson -> "lesson_${status.currentLesson.number}_${status.endEpochMillis}"
        is ScheduleStatus.InBreak -> "break_${status.nextLesson.number}_${status.endEpochMillis}"
        is ScheduleStatus.BeforeSchool -> "before_${status.startEpochMillis}"
        is ScheduleStatus.AfterSchool -> "after"
        ScheduleStatus.NoData -> "nodata"
    }

    private fun stopAndClear() {
        tickerJob?.cancel()
        isForegroundStarted = false
        LiveUpdateManager.cancel(applicationContext)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        tickerJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_REFRESH = "ru.school12.schedule.action.LIVE_REFRESH"
        private const val ACTION_STOP = "ru.school12.schedule.action.LIVE_STOP"

        private const val TEST_PROGRESS_TICK_MS = 1_000L
        private const val NORMAL_PROGRESS_TICK_MS = 15_000L
        private const val BOUNDARY_GRACE_MS = 60L

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
