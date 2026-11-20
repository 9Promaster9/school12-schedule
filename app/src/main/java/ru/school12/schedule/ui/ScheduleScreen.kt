package ru.school12.schedule.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.school12.schedule.model.DayTab
import ru.school12.schedule.model.Lesson
import ru.school12.schedule.model.ScheduleStatus
import ru.school12.schedule.model.formatRemainingLabel
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DATE_LABEL_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ru-RU"))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(
    state: ScheduleUiState,
    needsNotificationPermission: Boolean,
    needsExactAlarmPermission: Boolean,
    onRequestNotificationPermission: () -> Unit,
    onRequestExactAlarmPermission: () -> Unit,
    onSelectTab: (DayTab) -> Unit,
    onSelectClass: (String) -> Unit,
    onToggleLiveUpdates: (Boolean) -> Unit,
    onToggleTestMode: (Boolean) -> Unit,
    onRestartTestMode: () -> Unit,
    onRefresh: () -> Unit,
) {
    var showClassPicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "СОШ №12",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (state.updatedAt.isNotBlank()) {
                            Text(
                                text = "Обновлено: ${state.updatedAt.take(16).replace('T', ' ')}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    AssistChip(
                        onClick = { showClassPicker = true },
                        label = {
                            Text(
                                text = state.selectedClass,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.School,
                                contentDescription = "Выбор класса",
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )
                    IconButton(onClick = onRefresh, enabled = !state.isRefreshing) {
                        if (state.isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "Обновить расписание",
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (needsNotificationPermission || needsExactAlarmPermission) {
                item(key = "permissions") {
                    PermissionsCard(
                        needsNotification = needsNotificationPermission,
                        needsExactAlarm = needsExactAlarmPermission,
                        onRequestNotification = onRequestNotificationPermission,
                        onRequestExactAlarm = onRequestExactAlarmPermission,
                    )
                }
            }

            if (state.errorMessage != null) {
                item(key = "error") {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = state.errorMessage,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                }
            }

            item(key = "status_hero") {
                LiveStatusCard(
                    status = state.status,
                    liveUpdatesEnabled = state.liveUpdatesEnabled,
                    onToggleLiveUpdates = onToggleLiveUpdates,
                )
            }

            item(key = "test_mode") {
                TestModeCard(
                    isTestMode = state.isTestMode,
                    onToggleTestMode = onToggleTestMode,
                    onRestartTestMode = onRestartTestMode,
                )
            }

            item(key = "day_tabs") {
                DaySwitcher(
                    activeTab = state.activeTab,
                    hasToday = state.todaySchedule != null,
                    hasTomorrow = state.tomorrowSchedule != null,
                    onSelectTab = onSelectTab,
                )
            }

            val schedule = state.displayedSchedule
            if (schedule != null) {
                item(key = "day_header") {
                    Text(
                        text = "${schedule.dayOfWeek}, ${schedule.date.format(DATE_LABEL_FMT)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                val currentLessonNumber = if (state.activeTab == DayTab.TODAY) {
                    (state.status as? ScheduleStatus.InLesson)?.currentLesson?.number
                } else {
                    null
                }

                items(
                    items = schedule.lessons,
                    key = { "${schedule.date}_${it.number}_${it.subject}_${it.startTime}" },
                ) { lesson ->
                    LessonCard(
                        lesson = lesson,
                        isCurrent = lesson.number == currentLessonNumber,
                    )
                }
            } else {
                item(key = "empty") {
                    OutlinedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = "Нет расписания на выбранный день",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Нажмите кнопку обновления справа вверху или проверьте класс.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item(key = "bottom_spacer") {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    if (showClassPicker) {
        ModalBottomSheet(
            onDismissRequest = { showClassPicker = false },
        ) {
            Text(
                text = "Выберите класс",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            val classes = state.availableClasses.ifEmpty { listOf(state.selectedClass) }
            LazyColumn(
                contentPadding = PaddingValues(bottom = 32.dp),
            ) {
                items(classes) { cls ->
                    val isSelected = cls.equals(state.selectedClass, ignoreCase = true)
                    ListItem(
                        headlineContent = {
                            Text(
                                text = cls,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            )
                        },
                        trailingContent = {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = "Выбрано",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        },
                        modifier = Modifier.clickable {
                            onSelectClass(cls)
                            showClassPicker = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TestModeCard(
    isTestMode: Boolean,
    onToggleTestMode: (Boolean) -> Unit,
    onRestartTestMode: () -> Unit,
) {
    OutlinedCard(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (isTestMode) {
                MaterialTheme.colorScheme.tertiaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Science,
                        contentDescription = null,
                        tint = if (isTestMode) {
                            MaterialTheme.colorScheme.onTertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Тестовый режим",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isTestMode) {
                                MaterialTheme.colorScheme.onTertiaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                        Text(
                            text = "3 урока по 20 сек · перемены по 10 сек",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isTestMode) {
                                MaterialTheme.colorScheme.onTertiaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
                Switch(
                    checked = isTestMode,
                    onCheckedChange = onToggleTestMode,
                )
            }
            if (isTestMode) {
                Spacer(modifier = Modifier.height(8.dp))
                FilledTonalButton(
                    onClick = onRestartTestMode,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Запустить цикл заново")
                }
            }
        }
    }
}

@Composable
private fun PermissionsCard(
    needsNotification: Boolean,
    needsExactAlarm: Boolean,
    onRequestNotification: () -> Unit,
    onRequestExactAlarm: () -> Unit,
) {
    ElevatedCard(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Настройка Live Updates",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Для отображения живого таймера на экране блокировки и переключения ровно по звонку нужны разрешения.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (needsNotification) {
                    TextButton(onClick = onRequestNotification) {
                        Icon(
                            imageVector = Icons.Rounded.NotificationsActive,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Уведомления")
                    }
                }
                if (needsExactAlarm) {
                    TextButton(onClick = onRequestExactAlarm) {
                        Icon(
                            imageVector = Icons.Rounded.Alarm,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Точные будильники")
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveStatusCard(
    status: ScheduleStatus,
    liveUpdatesEnabled: Boolean,
    onToggleLiveUpdates: (Boolean) -> Unit,
) {
    ElevatedCard(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                val badge = when (status) {
                    is ScheduleStatus.InLesson -> "ИДЁТ ${status.currentLesson.number}-Й УРОК"
                    is ScheduleStatus.InBreak -> "ПЕРЕМЕНА"
                    is ScheduleStatus.BeforeSchool -> "ДО НАЧАЛА УРОКОВ"
                    is ScheduleStatus.AfterSchool -> "УРОКИ ОКОНЧЕНЫ"
                    ScheduleStatus.NoData -> "НЕТ ДАННЫХ"
                }
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Live Update",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = liveUpdatesEnabled,
                        onCheckedChange = onToggleLiveUpdates,
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            when (status) {
                is ScheduleStatus.InLesson -> {
                    val room = status.currentLesson.room.takeIf { it.isNotBlank() }?.let { " · каб. $it" }.orEmpty()
                    val remain = formatRemainingLabel(status.minutesRemaining, status.secondsRemaining)
                    Text(
                        text = "${status.currentLesson.subject}$room",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${status.currentLesson.startTime} – ${status.currentLesson.endTime} · осталось $remain",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { status.progressPercent / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                    )
                    status.nextLesson?.let { next ->
                        Spacer(modifier = Modifier.height(8.dp))
                        val nr = next.room.takeIf { it.isNotBlank() }?.let { ", каб. $it" }.orEmpty()
                        Text(
                            text = "Далее: ${next.subject}$nr (${next.startTime})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }

                is ScheduleStatus.InBreak -> {
                    val nr = status.nextLesson.room.takeIf { it.isNotBlank() }?.let { ", каб. $it" }.orEmpty()
                    val remain = formatRemainingLabel(status.minutesRemaining, status.secondsRemaining)
                    Text(
                        text = "Следующий: ${status.nextLesson.subject}$nr",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "До звонка $remain (в ${status.nextLesson.startTime})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { status.progressPercent / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                    )
                }

                is ScheduleStatus.BeforeSchool -> {
                    val room = status.firstLesson.room.takeIf { it.isNotBlank() }?.let { ", каб. $it" }.orEmpty()
                    Text(
                        text = "1-й урок: ${status.firstLesson.subject}$room",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Начало в ${status.firstLesson.startTime} (через ${status.minutesUntilStart} мин)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }

                is ScheduleStatus.AfterSchool -> {
                    val next = status.tomorrowFirstLesson
                    if (next != null) {
                        val day = status.tomorrowDayOfWeek ?: "Завтра"
                        val room = next.room.takeIf { it.isNotBlank() }?.let { ", каб. $it" }.orEmpty()
                        Text(
                            text = "$day: ${next.subject}$room",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "1-й урок в ${next.startTime}. Live Update скрыт до утра.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    } else {
                        Text(
                            text = "На сегодня всё",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Live Update автоматически появится утром перед первым уроком.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }

                ScheduleStatus.NoData -> {
                    Text(
                        text = "Загрузите расписание",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DaySwitcher(
    activeTab: DayTab,
    hasToday: Boolean,
    hasTomorrow: Boolean,
    onSelectTab: (DayTab) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = activeTab == DayTab.TODAY,
            onClick = { onSelectTab(DayTab.TODAY) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            enabled = hasToday || !hasTomorrow,
        ) {
            Text("Сегодня")
        }
        SegmentedButton(
            selected = activeTab == DayTab.TOMORROW,
            onClick = { onSelectTab(DayTab.TOMORROW) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            enabled = hasTomorrow,
        ) {
            Text("Завтра")
        }
    }
}

@Composable
private fun LessonCard(
    lesson: Lesson,
    isCurrent: Boolean,
) {
    val containerColor = if (isCurrent) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    val border = if (isCurrent) {
        BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    }

    OutlinedCard(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = containerColor),
        border = border,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (isCurrent) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                modifier = Modifier.size(38.dp),
            ) {
                Column(
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = lesson.number.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isCurrent) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = lesson.subject,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${lesson.startTime} – ${lesson.endTime}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (lesson.room.isNotBlank()) {
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        text = lesson.room,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
        }
    }
}
