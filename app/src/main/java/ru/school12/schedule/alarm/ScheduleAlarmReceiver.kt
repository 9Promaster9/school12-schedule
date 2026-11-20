package ru.school12.schedule.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ru.school12.schedule.data.ScheduleRepository
import ru.school12.schedule.domain.ScheduleEngine
import ru.school12.schedule.notification.LiveUpdateManager
import ru.school12.schedule.service.LiveScheduleService
import ru.school12.schedule.widget.ScheduleGlanceWidget

class ScheduleAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val repo = ScheduleRepository(context)
                val payload = repo.getCachedSchedule()
                val selectedClass = repo.getSelectedClass()
                val liveEnabled = repo.isLiveUpdatesEnabled()

                val status = ScheduleEngine.computeStatus(payload, selectedClass)
                val shouldShow = liveEnabled && LiveUpdateManager.shouldShowLiveUpdate(status)

                LiveScheduleService.sync(context, shouldShow)
                ScheduleGlanceWidget().updateAll(context)
                ScheduleAlarmScheduler.scheduleNextTick(context, payload, selectedClass)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
