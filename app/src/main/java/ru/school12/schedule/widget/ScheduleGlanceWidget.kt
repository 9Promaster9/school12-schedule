package ru.school12.schedule.widget

import android.content.Context
import android.os.Build
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import ru.school12.schedule.MainActivity
import ru.school12.schedule.alarm.ScheduleAlarmScheduler
import ru.school12.schedule.data.ScheduleRepository
import ru.school12.schedule.domain.ScheduleEngine
import ru.school12.schedule.model.ScheduleStatus
import ru.school12.schedule.model.formatRemainingLabel
import ru.school12.schedule.ui.theme.FallbackDarkColorScheme
import ru.school12.schedule.ui.theme.FallbackLightColorScheme

class ScheduleWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ScheduleGlanceWidget()
}

class ScheduleGlanceWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repository = ScheduleRepository(context)
        val payload = repository.getCachedSchedule()
        val selectedClass = repository.getSelectedClass()
        val testModeStartMs = repository.getTestModeStartMs()
        val status = ScheduleEngine.computeStatus(
            payload = payload,
            selectedClass = selectedClass,
            testModeStartMs = testModeStartMs,
        )

        provideContent {
            val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ColorProviders(
                    light = dynamicLightColorScheme(context),
                    dark = dynamicDarkColorScheme(context),
                )
            } else {
                ColorProviders(
                    light = FallbackLightColorScheme,
                    dark = FallbackDarkColorScheme,
                )
            }

            GlanceTheme(colors = colors) {
                WidgetCard(
                    selectedClass = if (testModeStartMs > 0L) "$selectedClass · ТЕСТ" else selectedClass,
                    status = status,
                )
            }
        }
    }
}

@Composable
private fun WidgetCard(
    selectedClass: String,
    status: ScheduleStatus,
) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .cornerRadius(24.dp)
            .background(GlanceTheme.colors.surface)
            .clickable(actionStartActivity<MainActivity>())
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = selectedClass,
                style = TextStyle(
                    color = GlanceTheme.colors.primary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Spacer(modifier = GlanceModifier.defaultWeight())
            Text(
                text = "↻",
                modifier = GlanceModifier
                    .clickable(actionRunCallback<RefreshWidgetAction>())
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
        }

        Spacer(modifier = GlanceModifier.height(6.dp))

        when (status) {
            is ScheduleStatus.InLesson -> {
                val roomText = status.currentLesson.room
                    .takeIf { it.isNotBlank() }
                    ?.let { "Каб. $it" }
                    ?: "Идёт урок"
                val remain = formatRemainingLabel(status.minutesRemaining, status.secondsRemaining)
                Text(
                    text = "${status.currentLesson.number}. ${status.currentLesson.subject}",
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    maxLines = 2,
                )
                Spacer(modifier = GlanceModifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = roomText,
                        style = TextStyle(
                            color = GlanceTheme.colors.onPrimaryContainer,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        modifier = GlanceModifier
                            .cornerRadius(8.dp)
                            .background(GlanceTheme.colors.primaryContainer)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                    Spacer(modifier = GlanceModifier.width(8.dp))
                    Text(
                        text = "до ${status.currentLesson.endTime} ($remain)",
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurfaceVariant,
                            fontSize = 13.sp,
                        ),
                    )
                }
                Spacer(modifier = GlanceModifier.defaultWeight())
                val nextLine = status.nextLesson?.let { next ->
                    val nr = next.room.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                    "Далее: ${next.subject}$nr"
                } ?: "Последний урок на сегодня"
                Text(
                    text = nextLine,
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 12.sp,
                    ),
                    maxLines = 1,
                )
            }

            is ScheduleStatus.InBreak -> {
                val remain = formatRemainingLabel(status.minutesRemaining, status.secondsRemaining)
                Text(
                    text = "Перемена ($remain)",
                    style = TextStyle(
                        color = GlanceTheme.colors.tertiary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                Spacer(modifier = GlanceModifier.height(4.dp))
                val nextRoom = status.nextLesson.room
                    .takeIf { it.isNotBlank() }
                    ?.let { ", каб. $it" }
                    .orEmpty()
                Text(
                    text = "Далее: ${status.nextLesson.subject}$nextRoom",
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    maxLines = 2,
                )
                Spacer(modifier = GlanceModifier.defaultWeight())
                Text(
                    text = "${status.nextLesson.number}-й урок в ${status.nextLesson.startTime}",
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 12.sp,
                    ),
                )
            }

            is ScheduleStatus.BeforeSchool -> {
                Text(
                    text = "До уроков ${status.minutesUntilStart} мин",
                    style = TextStyle(
                        color = GlanceTheme.colors.primary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                Spacer(modifier = GlanceModifier.height(4.dp))
                val room = status.firstLesson.room
                    .takeIf { it.isNotBlank() }
                    ?.let { " · каб. $it" }
                    .orEmpty()
                Text(
                    text = "1. ${status.firstLesson.subject}$room",
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontSize = 14.sp,
                    ),
                    maxLines = 2,
                )
                Spacer(modifier = GlanceModifier.defaultWeight())
                Text(
                    text = "Начало в ${status.firstLesson.startTime}",
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 12.sp,
                    ),
                )
            }

            is ScheduleStatus.AfterSchool -> {
                val dayLabel = status.tomorrowDayOfWeek ?: "Завтра"
                Text(
                    text = "Уроки окончены",
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
                Spacer(modifier = GlanceModifier.height(4.dp))
                val first = status.tomorrowFirstLesson
                if (first != null) {
                    val room = first.room.takeIf { it.isNotBlank() }?.let { " · каб. $it" }.orEmpty()
                    Text(
                        text = "$dayLabel, 1-й урок: ${first.subject}$room",
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurface,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        maxLines = 2,
                    )
                    Spacer(modifier = GlanceModifier.defaultWeight())
                    Text(
                        text = "Начало в ${first.startTime}",
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurfaceVariant,
                            fontSize = 12.sp,
                        ),
                    )
                } else {
                    Text(
                        text = "Расписание на завтра пока не опубликовано",
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurface,
                            fontSize = 13.sp,
                        ),
                        maxLines = 2,
                    )
                }
            }

            ScheduleStatus.NoData -> {
                Text(
                    text = "Нажмите для загрузки расписания",
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontSize = 14.sp,
                    ),
                )
            }
        }
    }
}

class RefreshWidgetAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val repository = ScheduleRepository(context)
        val payload = repository.refreshFromRemote().getOrNull() ?: repository.getCachedSchedule()
        val selectedClass = repository.getSelectedClass()
        val testModeStartMs = repository.getTestModeStartMs()
        ScheduleGlanceWidget().updateAll(context)
        ScheduleAlarmScheduler.scheduleNextTick(context, payload, selectedClass, testModeStartMs)
    }
}
