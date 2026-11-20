package ru.school12.schedule.worker

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ru.school12.schedule.alarm.ScheduleAlarmScheduler
import ru.school12.schedule.data.ScheduleRepository
import ru.school12.schedule.domain.ScheduleEngine
import ru.school12.schedule.notification.LiveUpdateManager
import ru.school12.schedule.service.LiveScheduleService
import ru.school12.schedule.widget.ScheduleGlanceWidget
import java.util.concurrent.TimeUnit

class ScheduleSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val repository = ScheduleRepository(applicationContext)
        val result = repository.refreshFromRemote()
        val payload = result.getOrNull() ?: repository.getCachedSchedule()
        val selectedClass = repository.getSelectedClass()
        val liveEnabled = repository.isLiveUpdatesEnabled()

        val status = ScheduleEngine.computeStatus(payload, selectedClass)
        LiveScheduleService.sync(
            applicationContext,
            liveEnabled && LiveUpdateManager.shouldShowLiveUpdate(status),
        )
        ScheduleGlanceWidget().updateAll(applicationContext)
        ScheduleAlarmScheduler.scheduleNextTick(applicationContext, payload, selectedClass)

        return if (result.isSuccess) Result.success() else Result.retry()
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "school12_hourly_schedule_sync"

        fun enqueuePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<ScheduleSyncWorker>(
                repeatInterval = 1,
                repeatIntervalTimeUnit = TimeUnit.HOURS,
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
