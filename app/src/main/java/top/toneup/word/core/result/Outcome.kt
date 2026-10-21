package top.toneup.word.core.result

/**
 * Small result wrapper used by repositories and importers.
 *
 * The app deliberately keeps domain failures as *values* (instead of exceptions) so that
 * every screen can render a real Loading / Empty / Error state, and so a single bad row in
 * an imported file can never crash the process.
 */
sealed interface Outcome<out T> {
    data class Success<T>(val value: T) : Outcome<T>
    data class Failure(val error: AppError) : Outcome<Nothing>

    fun getOrNull(): T? = (this as? Success)?.value
    val isSuccess: Boolean get() = this is Success
}

inline fun <T> Outcome<T>.onSuccess(block: (T) -> Unit): Outcome<T> {
    if (this is Outcome.Success) block(value)
    return this
}

inline fun <T> Outcome<T>.onFailure(block: (AppError) -> Unit): Outcome<T> {
    if (this is Outcome.Failure) block(error)
    return this
}

/** A user-facing failure: [message] is shown in Chinese, [debugDetail] never contains user data. */
data class AppError(
    val code: ErrorCode,
    val message: String,
    val debugDetail: String? = null,
    /**
     * True when retrying the very same operation later can plausibly succeed (timeout, 5xx,
     * connection refused). The meaning-sync worker keeps such a word `PENDING` instead of
     * marking it `FAILED`, so a flaky network never burns the three-attempt budget.
     */
    val retryable: Boolean = false,
) {
    companion object {
        fun io(detail: String? = null) = AppError(ErrorCode.IO, "读写失败，请重试或检查存储空间。", detail)
        fun parse(detail: String? = null) = AppError(ErrorCode.PARSE, "文件格式不正确，请检查后重新导入。", detail)
        fun tooLarge() = AppError(ErrorCode.TOO_LARGE, "文件过大，请拆分后再导入。")
        fun empty() = AppError(ErrorCode.EMPTY, "文件中没有可用的单词记录。")
        fun database(detail: String? = null) = AppError(ErrorCode.DATABASE, "本地数据操作失败，请重试。", detail)
        fun unknown(detail: String? = null) = AppError(ErrorCode.UNKNOWN, "操作失败，请稍后再试。", detail)

        // --- gateway (释义补全网关, plan §7.1) -------------------------------------------
        /** Configuration problem: http base URL, malformed URL or HTTP 401 from the gateway. */
        fun config(detail: String? = null) = AppError(ErrorCode.CONFIG, "网关配置有误，请检查网关地址与密钥。", detail)

        /** Transport-level failure (timeout / 5xx / no connection): always worth one more try. */
        fun network(detail: String? = null) =
            AppError(ErrorCode.NETWORK, "网络请求失败，请检查网络后重试。", detail, retryable = true)

        /** Every source answered "no such word" — the cascade is exhausted (negative cache). */
        fun notFound(detail: String? = null) =
            AppError(ErrorCode.NOT_FOUND, "网关未查到该词的释义，可手动补充。", detail)

        // --- in-app update (see data/update/) --------------------------------------------
        /** `version.json` is malformed or violates the release contract. */
        fun updateParse(detail: String? = null) =
            AppError(ErrorCode.PARSE, "更新信息格式不正确，请稍后重试。", detail)

        /** Downloaded bytes do not match the manifest's SHA-256; the file must not be installed. */
        fun checksum(detail: String? = null) =
            AppError(ErrorCode.IO, "安装包校验未通过，已丢弃，请稍后重试。", detail)
    }
}

enum class ErrorCode { IO, PARSE, TOO_LARGE, EMPTY, DATABASE, UNKNOWN, CONFIG, NETWORK, NOT_FOUND }
