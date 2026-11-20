package ru.school12.schedule

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.school12.schedule.alarm.ScheduleAlarmScheduler
import ru.school12.schedule.ui.ScheduleScreen
import ru.school12.schedule.ui.ScheduleViewModel
import ru.school12.schedule.ui.theme.School12Theme

class MainActivity : ComponentActivity() {

    private val viewModel: ScheduleViewModel by viewModels()
    private var needsNotificationPermission by mutableStateOf(false)
    private var needsExactAlarmPermission by mutableStateOf(false)

    private val requestNotificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        refreshPermissionFlags()
        viewModel.syncBackgroundServices()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refreshPermissionFlags()

        setContent {
            School12Theme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                ScheduleScreen(
                    state = state,
                    needsNotificationPermission = needsNotificationPermission,
                    needsExactAlarmPermission = needsExactAlarmPermission,
                    onRequestNotificationPermission = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            requestNotificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onRequestExactAlarmPermission = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                data = Uri.parse("package:$packageName")
                            }
                            startActivity(intent)
                        }
                    },
                    onSelectTab = viewModel::selectTab,
                    onSelectClass = viewModel::selectClass,
                    onToggleLiveUpdates = viewModel::setLiveUpdatesEnabled,
                    onRefresh = viewModel::refreshSchedule,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionFlags()
        viewModel.syncBackgroundServices()
    }

    private fun refreshPermissionFlags() {
        needsNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        } else {
            false
        }
        needsExactAlarmPermission = !ScheduleAlarmScheduler.canScheduleExactAlarms(this)
    }
}
