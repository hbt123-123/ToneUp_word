package top.toneup.word.data.update

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import top.toneup.word.core.json.JsonCodec
import java.io.IOException

/**
 * Constants for the GitHub-Releases update pipeline.
 *
 * **The asset names are a hard contract shared with `tool/release.ps1`; the protocol shape is
 * shared with `ToneUp_read`** (same `version.json` fields, version rule and mirror list).
 * The client never calls `api.github.com` (unreliable from mainland China); it only uses the
 * fixed `releases/latest/download/<asset>` links, optionally through the mirror list below.
 */
object AppUpdateDefaults {
    const val REPO_OWNER = "hbt123-123"
    const val REPO_NAME = "ToneUp_word"

    const val RELEASE_BASE = "https://github.com/$REPO_OWNER/$REPO_NAME/releases/latest/download"
    const val APK_ASSET = "ToneUpWord.apk"
    const val MANIFEST_ASSET = "version.json"
    const val APK_URL = "$RELEASE_BASE/$APK_ASSET"
    const val MANIFEST_URL = "$RELEASE_BASE/$MANIFEST_ASSET"

    const val USER_AGENT = "ToneUpWord-Update"

    /** Auto check at most once a day; a manual check is always allowed. */
    const val AUTO_CHECK_INTERVAL_MILLIS = 24L * 60 * 60 * 1000

    /** Metadata probe timeout. Mirrors the Dart side's 5 s budget. */
    const val ENDPOINT_TIMEOUT_MILLIS = 5_000L

    /** How many hosts one check / one download may try before giving up. */
    const val PROBE_BUDGET = 3
    const val DOWNLOAD_BUDGET = 3

    /** A failing host is skipped for this long, so the next launch goes straight to a good one. */
    const val DEAD_HOST_COOLDOWN_MILLIS = 30L * 60 * 1000

    /** Downloaded APKs live under `cacheDir/updates/<versionCode>.apk(.part)`. */
    const val UPDATE_DIR = "updates"

    /** `""` means the direct GitHub URL; every other entry is a path-prefix mirror. */
    const val DIRECT_HOST = ""

    /**
     * Path-prefix mirrors: `https://<host>/<full original URL>`.
     * Order matches `ToneUp_read/lib/services/update_service.dart`; keep the two lists identical.
     */
    val MIRROR_HOSTS = listOf(
        "gh.acmsz.top",
        "gh-proxy.com",
        "gh.felicity.ac.cn",
        "github.dpik.top",
        "gh.jjj.gv.uy",
        "github.chenc.dev",
        "github.mxw.qzz.io",
        "tvv.tw",
        "gitproxy.mrhjx.cn",
        "cdn.akaere.online",
        "jiashu.1win.eu.org",
        "github-proxy.memory-echoes.cn",
        "cdn.gh-proxy.com",
        "gh.inkchills.cn",
        "ghproxy.net",
    )
}

/** Everything the update flow remembers between app starts. */
data class AppUpdateStoredState(
    val lastCheckedMillis: Long? = null,
    /** versionCode the learner chose to skip; never prompt for it again. */
    val dismissedVersionCode: Int = 0,
    /** Last host that served the manifest successfully (`""` = direct). */
    val okHost: String = AppUpdateDefaults.DIRECT_HOST,
    /** host → epoch millis until which it stays skipped. */
    val deadHosts: Map<String, Long> = emptyMap(),
    /** Metadata of the `<versionCode>.apk` currently on disk. */
    val readyVersionCode: Int = 0,
    val readyVersionName: String = "",
    val readySize: Long = 0,
)

/**
 * Persistence for the update state (its own tiny DataStore, not the user settings file).
 *
 * `markChecked` is only written after a *successful* manifest fetch: a network failure must not
 * push the next attempt out by a whole day.
 */
interface AppUpdateStore {
    fun state(): Flow<AppUpdateStoredState>
    suspend fun read(): AppUpdateStoredState
    suspend fun markChecked(nowMillis: Long)
    suspend fun setDismissed(versionCode: Int)
    suspend fun setOkHost(host: String)
    suspend fun setDeadHosts(deadHosts: Map<String, Long>)
    suspend fun setReady(versionCode: Int, versionName: String, size: Long)
    suspend fun clearReady()
}

private val Context.updateDataStore by preferencesDataStore(name = "app_update_prefs")

class DataStoreAppUpdateStore(private val context: Context) : AppUpdateStore {

    override fun state(): Flow<AppUpdateStoredState> = context.updateDataStore.data
        .catch { throwable ->
            // A corrupted preferences file must never crash the updater: fall back to defaults.
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map(::toState)
        .flowOn(Dispatchers.IO)

    override suspend fun read(): AppUpdateStoredState = try {
        toState(context.updateDataStore.data.first())
    } catch (_: IOException) {
        // Same degradation as state(): the update check proceeds from a clean slate instead of
        // leaving the UI stuck on "正在检查…".
        AppUpdateStoredState()
    }

    override suspend fun markChecked(nowMillis: Long) {
        safeEdit { it[Keys.LAST_CHECKED] = nowMillis }
    }

    override suspend fun setDismissed(versionCode: Int) {
        safeEdit { it[Keys.DISMISSED_VERSION_CODE] = versionCode }
    }

    override suspend fun setOkHost(host: String) {
        safeEdit { it[Keys.OK_HOST] = host }
    }

    override suspend fun setDeadHosts(deadHosts: Map<String, Long>) {
        safeEdit {
            it[Keys.DEAD_HOSTS] = JsonCodec.json.encodeToString(DEAD_HOSTS_SERIALIZER, deadHosts)
        }
    }

    override suspend fun setReady(versionCode: Int, versionName: String, size: Long) {
        safeEdit { prefs ->
            prefs[Keys.READY_VERSION_CODE] = versionCode
            prefs[Keys.READY_VERSION_NAME] = versionName
            prefs[Keys.READY_SIZE] = size
        }
    }

    override suspend fun clearReady() {
        safeEdit { prefs ->
            prefs.remove(Keys.READY_VERSION_CODE)
            prefs.remove(Keys.READY_VERSION_NAME)
            prefs.remove(Keys.READY_SIZE)
        }
    }

    /**
     * Writes are best-effort: a full disk must not abort an update flow and leave the UI stuck
     * between phases. The next launch re-derives ready/checked state from disk and re-checks.
     */
    private suspend fun safeEdit(block: suspend (MutablePreferences) -> Unit) {
        try {
            context.updateDataStore.edit(block)
        } catch (_: IOException) {
            // Ignored on purpose — see the KDoc.
        }
    }

    private fun toState(prefs: Preferences) = AppUpdateStoredState(
        lastCheckedMillis = prefs[Keys.LAST_CHECKED],
        dismissedVersionCode = prefs[Keys.DISMISSED_VERSION_CODE] ?: 0,
        okHost = prefs[Keys.OK_HOST] ?: AppUpdateDefaults.DIRECT_HOST,
        deadHosts = decodeDeadHosts(prefs[Keys.DEAD_HOSTS]),
        readyVersionCode = prefs[Keys.READY_VERSION_CODE] ?: 0,
        readyVersionName = prefs[Keys.READY_VERSION_NAME] ?: "",
        readySize = prefs[Keys.READY_SIZE] ?: 0,
    )

    private fun decodeDeadHosts(raw: String?): Map<String, Long> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            JsonCodec.json.decodeFromString(DEAD_HOSTS_SERIALIZER, raw)
        }.getOrDefault(emptyMap())
    }

    private object Keys {
        val LAST_CHECKED = longPreferencesKey("update_last_checked_millis")
        val DISMISSED_VERSION_CODE = intPreferencesKey("update_dismissed_version_code")
        val OK_HOST = stringPreferencesKey("update_ok_host")
        val DEAD_HOSTS = stringPreferencesKey("update_dead_hosts")
        val READY_VERSION_CODE = intPreferencesKey("update_ready_version_code")
        val READY_VERSION_NAME = stringPreferencesKey("update_ready_version_name")
        val READY_SIZE = longPreferencesKey("update_ready_size")
    }

    private companion object {
        val DEAD_HOSTS_SERIALIZER = MapSerializer(String.serializer(), Long.serializer())
    }
}
