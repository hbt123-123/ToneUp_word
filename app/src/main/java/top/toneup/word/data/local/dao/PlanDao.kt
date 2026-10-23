package top.toneup.word.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import top.toneup.word.data.local.entity.PlanEntity
import kotlinx.coroutines.flow.Flow

/**
 * Plans (schema v4): the study scope that groups packs.
 *
 * Built-in plans sort first, then oldest first, so `plan-default` stays at the top of the plan
 * list and the order of user plans never changes behind their back.
 */
@Dao
interface PlanDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(plan: PlanEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(plan: PlanEntity)

    @Update
    suspend fun update(plan: PlanEntity)

    @Query("SELECT * FROM plan ORDER BY is_built_in DESC, created_at ASC, id ASC")
    fun observePlans(): Flow<List<PlanEntity>>

    @Query("SELECT * FROM plan WHERE id = :id")
    suspend fun planById(id: String): PlanEntity?

    @Query("SELECT * FROM plan WHERE id = :id")
    fun observePlan(id: String): Flow<PlanEntity?>

    @Query("UPDATE plan SET name = :name, updated_at = :now WHERE id = :id")
    suspend fun rename(id: String, name: String, now: Long)

    @Query("DELETE FROM plan WHERE id = :id")
    suspend fun deletePlan(id: String)

    /** How many packs still belong to the plan — the delete guard (D7) reads this. */
    @Query("SELECT COUNT(*) FROM word_pack WHERE plan_id = :planId")
    suspend fun packCount(planId: String): Int
}
