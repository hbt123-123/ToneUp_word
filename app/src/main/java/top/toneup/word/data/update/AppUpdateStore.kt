package top.toneup.word.data.update

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** Constants for the GitHub-Releases update pipeline. */
object AppUpdateDefaults {
    const val REPO_OWNER = "hbt123-123"
    const val REPO_NAME = "ToneUp_word"
    const val RELEASES_LATEST_URL = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"
    const val USER_AGENT = "ToneUpWord-Update"

    /** Unique WorkManager name so repeated app starts never stack checks. */
    const val WORK_NAME = "app-update-check"
    const val WORK_TAG = "app-update"
    const val BACKOFF_SECONDS = 30L

    /** Unauthenticated GitHub quota is 60/h per IP; one start-up check per half day is plenty. */
    const val MIN_CHECK_INTERVAL_MILLIS = 12L * 60 * 60 * 1000

    /** APK lives under the app cache dir; the OS may wipe it — the flow treats that as "no file". */
    const val UPDATE_DIR = "updates"
    const val APK_FILE_NAME = "toneup-update.apk"
}

/** Everything the update flow remembers between app starts. */
data class AppUpdateStoredState(
    val lastCheckedMillis: Long? = null,
    val downloadedTag: String? = null,
    val downloadedVersion: String? = null,
    val downloadedSize: Long? = null,
    val dismissedTag: String? = null,
)

/**
 * Persistence for the update state (own tiny DataStore, not the user settings file).
 *
 * `markChecked` is only written on a *successful* check: a network failure must not push the
 * next attempt out by [AppUpdateDefaults.MIN_CHECK_INTERVAL_MILLIS].
 */
interface AppUpdateStore {
    fun state(): Flow<AppUpdateStoredState>
    suspend fun read(): AppUpdateStoredState
    suspend fun markChecked(nowMillis: Long)
    suspend fun setDownloaded(tag: String, version: String, size: Long)
    suspend fun setDismissed(tag: String)
}

private val Context.updateDataStore by preferencesDataStore(name = "app_update_prefs")

class DataStoreAppUpdateStore(private val context: Context) : AppUpdateStore {

    override fun state(): Flow<AppUpdateStoredState> = context.updateDataStore.data
        .map(::toState)
        .flowOn(Dispatchers.IO)

    override suspend fun read(): AppUpdateStoredState = toState(context.updateDataStore.data.first())

    override suspend fun markChecked(nowMillis: Long) {
        context.updateDataStore.edit { it[Keys.LAST_CHECKED] = nowMillis }
    }

    override suspend fun setDownloaded(tag: String, version: String, size: Long) {
        context.updateDataStore.edit { prefs ->
            prefs[Keys.DOWNLOADED_TAG] = tag
            prefs[Keys.DOWNLOADED_VERSION] = version
            prefs[Keys.DOWNLOADED_SIZE] = size
        }
    }

    override suspend fun setDismissed(tag: String) {
        context.updateDataStore.edit { it[Keys.DISMISSED_TAG] = tag }
    }

    private fun toState(prefs: androidx.datastore.preferences.core.Preferences) = AppUpdateStoredState(
        lastCheckedMillis = prefs[Keys.LAST_CHECKED],
        downloadedTag = prefs[Keys.DOWNLOADED_TAG],
        downloadedVersion = prefs[Keys.DOWNLOADED_VERSION],
        downloadedSize = prefs[Keys.DOWNLOADED_SIZE],
        dismissedTag = prefs[Keys.DISMISSED_TAG],
    )

    private object Keys {
        val LAST_CHECKED = longPreferencesKey("update_last_checked_millis")
        val DOWNLOADED_TAG = stringPreferencesKey("update_downloaded_tag")
        val DOWNLOADED_VERSION = stringPreferencesKey("update_downloaded_version")
        val DOWNLOADED_SIZE = longPreferencesKey("update_downloaded_size")
        val DISMISSED_TAG = stringPreferencesKey("update_dismissed_tag")
    }
}
