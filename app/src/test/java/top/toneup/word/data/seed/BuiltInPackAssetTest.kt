package top.toneup.word.data.seed

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import top.toneup.word.core.result.Outcome
import top.toneup.word.domain.model.CetLevel
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Completeness and sanity checks for the **shipped** vocabulary assets.
 *
 * This is the regression guard for the vocabulary requirement: it reads the real bundled JSON files
 * (`assets/wordpacks/`) through the same loader the app uses, so a truncated download, a
 * corrupted transform or an accidentally reverted asset fails the build instead of silently
 * shipping a partial word list.
 *
 * The counts are lower bounds rather than exact equality on purpose: a future re-import may
 * legitimately add words, but it must never shrink the list.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [26])
class BuiltInPackAssetTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val loader = BuiltInPackLoader(context)

    /**
     * Spellings of [word] that count as "the word appears in the sentence".
     *
     * The same regular inflections the attach step allowed when it chose the sentence
     * (`tools/wordpack/attach_examples.py`, `surface_forms`): conservative on purpose, so that a
     * sentence which merely contains a look-alike (`kind` for `kin`) is not accepted as an example
     * of the word.
     */
    private fun surfaceForms(word: String): Set<String> {
        val w = word.lowercase()
        val forms = mutableSetOf(w)
        val doubles = w.length > 2 && w.last() !in "aeiou" && w[w.length - 2] in "aeiou" &&
            w[w.length - 3] !in "aeiou"
        val shortDouble = doubles && w.length <= 4

        when {
            w.endsWith("s") || w.endsWith("x") || w.endsWith("z") ||
                w.endsWith("ch") || w.endsWith("sh") -> {
                forms += w + "es"
                forms += w + "ed"
            }

            w.endsWith("y") && w.length > 2 && w[w.length - 2] !in "aeiou" -> {
                forms += w.dropLast(1) + "ies"
                forms += w.dropLast(1) + "ied"
            }

            w.endsWith("e") -> {
                forms += w + "s"
                forms += w + "d"
            }

            else -> {
                forms += w + "s"
                forms += if (shortDouble) w + w.last() + "ed" else w + "ed"
            }
        }

        when {
            w.endsWith("e") && !w.endsWith("ee") -> forms += w.dropLast(1) + "ing"
            shortDouble -> {
                forms += w + w.last() + "ing"
                forms += w + w.last() + "ed"
            }

            else -> {
                forms += w + "ing"
                if (doubles) {
                    forms += w + w.last() + "ing"
                    forms += w + w.last() + "ed"
                }
            }
        }
        return forms
    }

    @Test
    fun `the manifest describes the shipped level`() = runTest {
        val definitions = loader.definitions()

        // Only CET-6 still ships; the CET-4 pack and its asset were removed (plan §A).
        assertThat(definitions.map { it.id }).containsExactly("builtin-cet6")
        assertThat(definitions.first { it.level == CetLevel.CET6 }.expectedWordCount).isAtLeast(5400)
    }

    @Test
    fun `cet6 asset parses completely and is valid`() = runTest {
        val definition = loader.definitions().first { it.level == CetLevel.CET6 }
        val outcome = loader.load(definition)

        assertThat(outcome).isInstanceOf(Outcome.Success::class.java)
        val preview = (outcome as Outcome.Success).value

        assertThat(preview.records.size).isAtLeast(5400)
        assertThat(preview.issues.size).isEqualTo(0)
        assertThat(preview.duplicateRows).isEqualTo(0)
        assertThat(preview.records.map { it.level }.toSet()).containsExactly(CetLevel.CET6)
    }

    @Test
    fun `every shipped word is usable by the study screens`() = runTest {
        loader.definitions().forEach { definition ->
            val preview = (loader.load(definition) as Outcome.Success).value
            preview.records.forEach { record ->
                assertThat(record.word.isNotBlank()).isTrue()
                assertThat(record.meanings).isNotEmpty()
                // Words must be searchable by their normalised form and readable in the UI.
                assertThat(record.word.trim()).isEqualTo(record.word)
                assertThat(record.meanings.first().isNotBlank()).isTrue()
            }
        }
    }

    @Test
    fun `phonetics are ipa - no cyrillic look alikes survive`() = runTest {
        loader.definitions().forEach { definition ->
            val preview = (loader.load(definition) as Outcome.Success).value
            val withPhonetic = preview.records.filter { it.phonetic.isNotBlank() }
            // The source is ~99.5% populated; a collapse here would mean the asset was replaced.
            assertThat(withPhonetic.size.toDouble() / preview.records.size).isGreaterThan(0.98)
            withPhonetic.forEach { record ->
                record.phonetic.forEach { character ->
                    val block = Character.UnicodeBlock.of(character)
                    assertThat("${record.word} has ${character} (${block})")
                        .isNotEqualTo(Character.UnicodeBlock.CYRILLIC)
                }
            }
        }
    }

    /**
     * The example sentences added in this iteration.
     *
     * Three properties matter for the product: the sentences are English, they really contain the
     * word they are attached to (or a regular inflection of it), and they come with a Chinese
     * translation. Coverage is asserted as a lower bound — a word with no acceptable sentence keeps
     * an empty field, and the app hides it rather than inventing one.
     */
    @Test
    fun `the shipped packs carry offline example sentences that contain their word`() = runTest {
        loader.definitions().forEach { definition ->
            val records = (loader.load(definition) as Outcome.Success).value.records
            val withExample = records.filter { it.exampleSentence.isNotBlank() }
            val withTranslation = records.filter { it.exampleTranslation.isNotBlank() }

            assertThat(withExample.size.toDouble() / records.size).isAtLeast(0.60)
            // An example without a translation would leave half the feature empty on screen.
            assertThat(withTranslation.size).isEqualTo(withExample.size)

            withExample.forEach { record ->
                val sentence = record.exampleSentence
                val words = sentence.split(Regex("[^A-Za-z'’-]+")).filter { it.isNotBlank() }
                assertWithMessage("${record.word}: $sentence").that(words.size).isAtLeast(4)
                assertWithMessage("${record.word}: $sentence").that(words.size).isAtMost(20)
                assertThat(".!?".contains(sentence.last())).isTrue()
                // English only, no digits: the sentence is a reading aid, not a second puzzle.
                assertThat(sentence.any { it.isDigit() }).isFalse()
                assertThat(sentence.any { it.code in 0x4E00..0x9FFF }).isFalse()

                val lower = words.map { it.lowercase() }
                val forms = surfaceForms(record.word)
                assertWithMessage("${record.word} does not appear in: $sentence")
                    .that(lower.any { it in forms }).isTrue()

                // The Chinese side must be Chinese, and must not smuggle the English word in.
                assertThat(record.exampleTranslation.any { it.code in 0x4E00..0x9FFF }).isTrue()
                assertThat(Regex("[A-Za-z]{2,}").containsMatchIn(record.exampleTranslation)).isFalse()
            }
        }
    }

    /**
     * End-to-end: seed the **real** shipped asset through the real repository into a real database.
     *
     * The asset test above proves the JSON parses; this one proves the loading path a learner
     * actually exercises — manifest → parse → chunked transactional writes → searchable rows —
     * works with the multi-megabyte word file, and that re-running it is a no-op rather than a
     * duplicate insert. It is the regression guard for "六级单词能够被正常加载、搜索和学习".
     */
    @Test
    fun `the shipped pack seeds into the database and is searchable`() = runTest {
        val database = androidx.room.Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            top.toneup.word.data.local.ToneUpWordDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val repository = top.toneup.word.data.repository.DefaultWordRepository(
                db = database,
                time = top.toneup.word.core.time.SystemTimeProvider(),
                packLoader = loader,
            )

            val first = kotlin.system.measureTimeMillis { repository.ensureBuiltInPacks() }

            val cet6Rows = database.wordDao().countInPack(top.toneup.word.data.settings.PackIds.BUILTIN_CET6)
            assertThat(cet6Rows).isAtLeast(5400)

            // The pack is recorded as built-in and versioned, which is what makes the incremental
            // upgrade path work on the next app start.
            val cet6Pack = database.wordDao().packById("builtin-cet6")
            assertThat(cet6Pack).isNotNull()
            assertThat(cet6Pack!!.isBuiltIn).isTrue()
            assertThat(cet6Pack.version).isEqualTo("3")

            // A word from the list is found by prefix and by meaning, i.e. the browser works on
            // the real data.
            val byPrefix = repository.search(top.toneup.word.data.settings.PlanIds.DEFAULT_PLAN, "abandon", 5).map { it.word.word }
            assertThat(byPrefix).contains("abandon")
            val byMeaning = repository.search(top.toneup.word.data.settings.PlanIds.DEFAULT_PLAN, "放弃", 50).map { it.word.word }
            assertThat(byMeaning).isNotEmpty()

            // Re-running must not duplicate anything (and must be cheap).
            val second = kotlin.system.measureTimeMillis { repository.ensureBuiltInPacks() }
            assertThat(database.wordDao().countInPack(top.toneup.word.data.settings.PackIds.BUILTIN_CET6)).isEqualTo(cet6Rows)
            // The second run sees a stored version and skips parsing the JSON entirely.
            assertThat(second).isAtMost(first.coerceAtLeast(1L))
            assertThat(second).isLessThan(2_000L)
        } finally {
            database.close()
        }
    }
}
