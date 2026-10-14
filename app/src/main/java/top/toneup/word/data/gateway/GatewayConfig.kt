package top.toneup.word.data.gateway

import top.toneup.word.data.settings.UserSettings

/**
 * Everything the HTTP stack needs, projected out of [UserSettings].
 *
 * Kept as its own value type so the client provider can compare two snapshots for equality and
 * rebuild only when something actually changed, and so tests can point the stack at a local
 * MockWebServer without standing up a DataStore.
 */
data class GatewayConfig(
    val baseUrl: String,
    val key: String,
    val dictPath: String,
    val enPath: String,
    val mtPath: String,
)

/** Reads the gateway slice of the user's settings. */
fun UserSettings.toGatewayConfig(): GatewayConfig = GatewayConfig(
    baseUrl = gatewayBaseUrl,
    key = gatewayKey,
    dictPath = dictPath,
    enPath = enPath,
    mtPath = mtPath,
)
