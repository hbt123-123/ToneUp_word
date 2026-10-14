package top.toneup.word.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import top.toneup.word.data.local.dao.DailySummaryDao
import top.toneup.word.data.local.dao.ExamDao
import top.toneup.word.data.local.dao.ImportHistoryDao
import top.toneup.word.data.local.dao.MeaningCacheDao
import top.toneup.word.data.local.dao.MistakeBookDao
import top.toneup.word.data.local.dao.ProgressDao
import top.toneup.word.data.local.dao.ReviewEventDao
import top.toneup.word.data.local.dao.StudySessionDao
import top.toneup.word.data.local.dao.WordDao
import top.toneup.word.data.local.dao.WordWithProgressView
import top.toneup.word.data.local.entity.DailyStudySummaryEntity
import top.toneup.word.data.local.entity.ExamQuestionEntity
import top.toneup.word.data.local.entity.ExamSessionEntity
import top.toneup.word.data.local.entity.ImportHistoryEntity
import top.toneup.word.data.local.entity.MeaningCacheEntity
import top.toneup.word.data.local.entity.ReviewEventEntity
import top.toneup.word.data.local.entity.StudySessionEntity
import top.toneup.word.data.local.entity.UserWordProgressEntity
import top.toneup.word.data.local.entity.WordEntity
import top.toneup.word.data.local.entity.WordPackEntity
import top.toneup.word.data.local.entity.WrongAnswerEntity

/**
 * The single local database. Everything the app needs lives here, in the app's private
 * storage, which is why the whole product works with no network permission at all.
 *
 * Vocabulary content (`word`, `word_pack`) is separated from user data
 * (`user_word_progress`, `review_event`, `wrong_answer`, sessions, summaries), so
 * re-importing or deleting a word list cannot silently destroy study history.
 */
@Database(
    entities = [
        WordPackEntity::class,
        WordEntity::class,
        UserWordProgressEntity::class,
        ReviewEventEntity::class,
        WrongAnswerEntity::class,
        StudySessionEntity::class,
        ExamSessionEntity::class,
        ExamQuestionEntity::class,
        DailyStudySummaryEntity::class,
        ImportHistoryEntity::class,
        MeaningCacheEntity::class,
    ],
    views = [WordWithProgressView::class],
    // Kept as a literal: the annotation processor requires a compile-time constant, while
    // `Migrations.CURRENT_VERSION` mirrors it and a test asserts the two stay equal.
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class ToneUpWordDatabase : RoomDatabase() {

    abstract fun wordDao(): WordDao
    abstract fun progressDao(): ProgressDao
    abstract fun mistakeBookDao(): MistakeBookDao
    abstract fun reviewEventDao(): ReviewEventDao
    abstract fun studySessionDao(): StudySessionDao
    abstract fun examDao(): ExamDao
    abstract fun dailySummaryDao(): DailySummaryDao
    abstract fun importHistoryDao(): ImportHistoryDao
    abstract fun meaningCacheDao(): MeaningCacheDao

    companion object {
        const val NAME = "toneup_word.db"

        /** Builds the database with the full migration chain attached. */
        fun build(context: Context, databaseName: String = NAME): ToneUpWordDatabase =
            Room.databaseBuilder(context.applicationContext, ToneUpWordDatabase::class.java, databaseName)
                .addMigrations(*Migrations.ALL_MIGRATIONS)
                .build()
    }
}
