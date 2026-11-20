package ru.school12.schedule.ui

import android.app.Application
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.school12.schedule.alarm.ScheduleAlarmScheduler
import ru.school12.schedule.data.ScheduleRepository
import ru.school12.schedule.domain.ScheduleEngine
import ru.school12.schedule.model.DEFAULT_CLASS_NAME
import ru.school12.schedule.model.DayTab
import ru.school12.schedule.model.ResolvedDaySchedule
import ru.school12.schedule.model.SchedulePayload
import ru.school12.schedule.model.ScheduleStatus
import ru.school12.schedule.notification.LiveUpdateManager
import ru.school12.schedule.service.LiveScheduleService
import ru.school12.schedule.widget.ScheduleGlanceWidget

data class ScheduleUiState(
    val selectedClass: String = DEFAULT_CLASS_NAME,
    val availableClasses: List<String> = emptyList(),
    val activeTab: DayTab = DayTab.TODAY,
    val todaySchedule: ResolvedDaySchedule? = null,
    val tomorrowSchedule: ResolvedDaySchedule? = null,
    val fallbackLatestSchedule: ResolvedDaySchedule? = null,
    val status: ScheduleStatus = ScheduleStatus.NoData,
    val liveUpdatesEnabled: Boolean = true,
    val updatedAt: String = "",
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
) {
    val displayedSchedule: ResolvedDaySchedule?
        get() = when (activeTab) {
            DayTab.TODAY -> todaySchedule ?: fallbackLatestSchedule
            DayTab.TOMORROW -> tomorrowSchedule ?: fallbackLatestSchedule
        }
}

class ScheduleViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ScheduleRepository(application)
    private val manualTabOverride = MutableStateFlow<DayTab?>(null)
    private val isRefreshing = MutableStateFlow(false)
    private val errorMessage = MutableStateFlow<String?>(null)

    private val tickerFlow = flow {
        while (true) {
            emit(ScheduleEngine.nowSchool())
            delay(15_000L)
        }
    }

    private data class CoreInputs(
        val payload: SchedulePayload?,
        val selectedClass: String,
        val liveEnabled: Boolean,
        val manualTab: DayTab?,
    )

    private val coreInputsFlow = combine(
        repository.scheduleFlow,
        repository.selectedClassFlow,
        repository.liveUpdatesEnabledFlow,
        manualTabOverride,
    ) { payload, selectedClass, liveEnabled, manualTab ->
        CoreInputs(payload, selectedClass, liveEnabled, manualTab)
    }

    val uiState: StateFlow<ScheduleUiState> = combine(
        coreInputsFlow,
        tickerFlow,
        isRefreshing,
        errorMessage,
    ) { core, now, refreshing, error ->
        val today = now.toLocalDate()
        val todaySchedule = ScheduleEngine.lessonsForDate(core.payload, today, core.selectedClass)
        val tomorrowSchedule = ScheduleEngine.nextAvailableDay(core.payload, today, core.selectedClass)
        val fallbackLatest = if (todaySchedule == null && tomorrowSchedule == null) {
            ScheduleEngine.latestAvailableDay(core.payload, core.selectedClass)
        } else {
            null
        }

        val smartTab = ScheduleEngine.smartDefaultTab(core.payload, core.selectedClass, now)
        val activeTab = core.manualTab ?: smartTab
        val status = ScheduleEngine.computeStatus(core.payload, core.selectedClass, now)

        ScheduleUiState(
            selectedClass = core.selectedClass,
            availableClasses = ScheduleEngine.availableClasses(core.payload),
            activeTab = activeTab,
            todaySchedule = todaySchedule,
            tomorrowSchedule = tomorrowSchedule,
            fallbackLatestSchedule = fallbackLatest,
            status = status,
            liveUpdatesEnabled = core.liveEnabled,
            updatedAt = core.payload?.updatedAt.orEmpty(),
            isRefreshing = refreshing,
            errorMessage = error,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = ScheduleUiState(),
    )

    init {
        refreshSchedule()
    }

    fun selectTab(tab: DayTab) {
        manualTabOverride.value = tab
    }

    fun selectClass(className: String) {
        viewModelScope.launch {
            repository.setSelectedClass(className)
            manualTabOverride.value = null
            syncBackgroundServices()
        }
    }

    fun setLiveUpdatesEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.setLiveUpdatesEnabled(enabled)
            syncBackgroundServices()
        }
    }

    fun refreshSchedule() {
        if (isRefreshing.value) return
        viewModelScope.launch {
            isRefreshing.value = true
            errorMessage.value = null
            val result = repository.refreshFromRemote()
            result.onFailure { err ->
                errorMessage.value = err.message ?: "Не удалось обновить расписание"
            }
            syncBackgroundServices()
            isRefreshing.value = false
        }
    }

    fun syncBackgroundServices() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val payload = repository.getCachedSchedule()
            val selectedClass = repository.getSelectedClass()
            val liveEnabled = repository.isLiveUpdatesEnabled()
            val status = ScheduleEngine.computeStatus(payload, selectedClass)

            LiveScheduleService.sync(
                ctx,
                liveEnabled && LiveUpdateManager.shouldShowLiveUpdate(status),
            )
            ScheduleGlanceWidget().updateAll(ctx)
            ScheduleAlarmScheduler.scheduleNextTick(ctx, payload, selectedClass)
        }
    }
}
