package top.toneup.word.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Every destination of the single-activity app.
 *
 * Routes are plain strings with typed builders so that no screen has to know how another
 * screen encodes its arguments, and no screen ever receives the NavController itself.
 */
object Routes {
    const val ONBOARDING = "onboarding"

    // Bottom-navigation destinations.
    const val HOME = "home"
    const val WRITE_WORD = "write-word"
    const val EXAM_SETUP = "exam-setup"
    const val STATS = "stats"
    const val PROFILE = "profile"

    /**
     * The mistake book ([Routes.REVIEW]) is no longer a top-level tab — it lives inside the home
     * screen as an overview card that opens this full-screen flow.
     */
    const val REVIEW = "review"

    // Full-screen flows.
    const val STUDY_SESSION = "study/session?drillIds={drillIds}"
    const val WORD_DETAIL = "word/{wordId}"
    const val EXAM_RUN = "exam/run/{examId}"
    const val EXAM_RESULT = "exam/result/{examId}"
    const val SETTINGS = "settings"
    const val DATA_TRANSFER = "data-transfer"
    const val WORD_BROWSER = "word-browser"
    const val ABOUT = "about"
    const val LICENSES = "licenses"
    const val PLANS = "plans"
    const val PLAN_DETAIL = "plans/{planId}"
    const val IMPORT_WORDS = "import-words/{packId}"

    const val ARG_DRILL_IDS = "drillIds"
    const val ARG_WORD_ID = "wordId"
    const val ARG_EXAM_ID = "examId"
    const val ARG_PACK_ID = "packId"
    const val ARG_PLAN_ID = "planId"

    /** Study session route; [drillIds] empty means "today's normal queue". */
    fun studySession(drillIds: List<Long> = emptyList()): String =
        if (drillIds.isEmpty()) "study/session?drillIds=" else "study/session?drillIds=${drillIds.joinToString(",")}"

    fun wordDetail(wordId: Long): String = "word/$wordId"

    fun examRun(examId: Long): String = "exam/run/$examId"

    fun examResult(examId: Long): String = "exam/result/$examId"

    fun importWords(packId: String): String = "import-words/$packId"

    fun planDetail(planId: String): String = "plans/$planId"
}

/** The five tabs of the bottom bar. */
enum class TopLevelDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    HOME(Routes.HOME, "首页", Icons.Filled.Home),
    WRITE_WORD(Routes.WRITE_WORD, "写词库", Icons.Filled.Add),
    EXAM(Routes.EXAM_SETUP, "考试", Icons.Filled.Edit),
    STATS(Routes.STATS, "统计", Icons.Filled.DateRange),
    PROFILE(Routes.PROFILE, "我的", Icons.Filled.Person),
    ;

    companion object {
        fun fromRoute(route: String?): TopLevelDestination? =
            entries.firstOrNull { it.route == route }
    }
}
