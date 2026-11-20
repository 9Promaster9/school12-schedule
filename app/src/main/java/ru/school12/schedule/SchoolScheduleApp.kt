package ru.school12.schedule

import android.app.Application
import ru.school12.schedule.notification.LiveUpdateManager
import ru.school12.schedule.worker.ScheduleSyncWorker

class SchoolScheduleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        LiveUpdateManager.ensureChannel(this)
        ScheduleSyncWorker.enqueuePeriodic(this)
    }
}
