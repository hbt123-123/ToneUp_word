package top.toneup.word.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A study plan: a named collection of vocabulary packs (schema v4).
 *
 * The plan is the **study scope** since v4 — home, review centre, exams, statistics and
 * reminders all aggregate over the plan's packs, with duplicate words collapsed inside the
 * plan by the `word_with_progress` view. A pack remains the content/import unit and records
 * which plan it currently belongs to through `word_pack.plan_id`.
 *
 * `isBuiltIn` guards the seeded `plan-default` (D7): it may be renamed but never deleted.
 */
@Entity(
    tableName = "plan",
    indices = [Index("is_built_in")],
)
data class PlanEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "description") val description: String = "",
    @ColumnInfo(name = "is_built_in") val isBuiltIn: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long = 0L,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = 0L,
)
