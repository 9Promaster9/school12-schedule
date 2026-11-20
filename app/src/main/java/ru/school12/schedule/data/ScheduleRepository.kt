package ru.school12.schedule.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.school12.schedule.BuildConfig
import ru.school12.schedule.model.DEFAULT_CLASS_NAME
import ru.school12.schedule.model.SchedulePayload
import java.util.concurrent.TimeUnit

private val Context.scheduleDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "school12_schedule_prefs"
)

class ScheduleRepository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    val selectedClassFlow: Flow<String> = context.scheduleDataStore.data.map { prefs ->
        prefs[KEY_SELECTED_CLASS] ?: DEFAULT_CLASS_NAME
    }

    val liveUpdatesEnabledFlow: Flow<Boolean> = context.scheduleDataStore.data.map { prefs ->
        prefs[KEY_LIVE_UPDATES_ENABLED] ?: true
    }

    val testModeStartMsFlow: Flow<Long> = context.scheduleDataStore.data.map { prefs ->
        prefs[KEY_TEST_MODE_START_MS] ?: 0L
    }

    val scheduleFlow: Flow<SchedulePayload?> = context.scheduleDataStore.data.map { prefs ->
        val raw = prefs[KEY_SCHEDULE_JSON] ?: return@map null
        runCatching { json.decodeFromString<SchedulePayload>(raw) }.getOrNull()
    }

    val lastSyncEpochMsFlow: Flow<Long> = context.scheduleDataStore.data.map { prefs ->
        prefs[KEY_LAST_SYNC_MS] ?: 0L
    }

    suspend fun getSelectedClass(): String = selectedClassFlow.first()

    suspend fun isLiveUpdatesEnabled(): Boolean = liveUpdatesEnabledFlow.first()

    suspend fun getTestModeStartMs(): Long = testModeStartMsFlow.first()

    suspend fun getCachedSchedule(): SchedulePayload? = scheduleFlow.first()

    suspend fun setSelectedClass(className: String) {
        context.scheduleDataStore.edit { prefs ->
            prefs[KEY_SELECTED_CLASS] = className
        }
    }

    suspend fun setLiveUpdatesEnabled(enabled: Boolean) {
        context.scheduleDataStore.edit { prefs ->
            prefs[KEY_LIVE_UPDATES_ENABLED] = enabled
        }
    }

    suspend fun setTestModeStartMs(startEpochMs: Long) {
        context.scheduleDataStore.edit { prefs ->
            prefs[KEY_TEST_MODE_START_MS] = startEpochMs
        }
    }

    suspend fun refreshFromRemote(): Result<SchedulePayload> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("${BuildConfig.SCHEDULE_URL}?t=${System.currentTimeMillis()}")
                .header("Accept", "application/json")
                .header("Cache-Control", "no-cache")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("HTTP ${response.code} при загрузке расписания")
                }
                val body = response.body?.string()
                    ?.takeIf { it.isNotBlank() }
                    ?: error("Пустой ответ сервера")

                val parsed = json.decodeFromString<SchedulePayload>(body)
                context.scheduleDataStore.edit { prefs ->
                    prefs[KEY_SCHEDULE_JSON] = body
                    prefs[KEY_LAST_SYNC_MS] = System.currentTimeMillis()
                }
                parsed
            }
        }
    }

    companion object {
        private val KEY_SCHEDULE_JSON = stringPreferencesKey("schedule_json")
        private val KEY_SELECTED_CLASS = stringPreferencesKey("selected_class")
        private val KEY_LIVE_UPDATES_ENABLED = booleanPreferencesKey("live_updates_enabled")
        private val KEY_TEST_MODE_START_MS = longPreferencesKey("test_mode_start_ms")
        private val KEY_LAST_SYNC_MS = longPreferencesKey("last_sync_epoch_ms")
    }
}
