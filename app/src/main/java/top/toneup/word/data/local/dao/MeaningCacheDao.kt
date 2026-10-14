package top.toneup.word.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import top.toneup.word.data.local.entity.MeaningCacheEntity

/**
 * Cross-pack meaning lookup cache (§4.2, plan D3).
 *
 * One row per normalized word, shared by every pack that contains it, so the gateway is hit
 * at most once per distinct token. `status = FAILED` is a negative cache: a word the gateway
 * does not know is retried only when the row is evicted or the fetch window moves on.
 *
 * Reads/writes for the gateway cascade (M4) happen in bulk through WorkManager, not in the
 * answer path — this DAO deliberately has no Flow observers.
 */
@Dao
interface MeaningCacheDao {

    @Query("SELECT * FROM meaning_cache WHERE normalized_word = :normalized")
    suspend fun byNormalized(normalized: String): MeaningCacheEntity?

    @Upsert
    suspend fun upsert(cache: MeaningCacheEntity)

    @Upsert
    suspend fun upsertAll(caches: List<MeaningCacheEntity>)

    @Query("DELETE FROM meaning_cache")
    suspend fun deleteAll()
}
