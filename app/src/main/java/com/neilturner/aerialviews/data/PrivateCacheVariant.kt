package com.neilturner.aerialviews.data

import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

/** Private cache inventory. Keys are never logged and are scoped to one session and media item. */
@Entity(tableName = "private_cache_variants", primaryKeys = ["namespace", "mediaId", "cacheKey"])
data class PrivateCacheVariant(
    val namespace: String,
    val mediaId: String,
    val cacheKey: String,
)

@Dao
interface PrivateCacheVariantDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun record(variant: PrivateCacheVariant)

    @Query("SELECT cacheKey FROM private_cache_variants WHERE namespace = :namespace AND mediaId = :mediaId")
    suspend fun keysForMedia(
        namespace: String,
        mediaId: String,
    ): List<String>

    @Query("DELETE FROM private_cache_variants WHERE namespace = :namespace AND mediaId = :mediaId")
    suspend fun removeMedia(
        namespace: String,
        mediaId: String,
    )

    @Query("DELETE FROM private_cache_variants WHERE namespace = :namespace")
    suspend fun removeNamespace(namespace: String)

    @Query("DELETE FROM private_cache_variants")
    suspend fun clearAll()
}
