package com.linkbubble.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface LinkDao {
    @Query("SELECT * FROM links WHERE categoryId = :categoryId ORDER BY orderIndex ASC")
    fun getByCategory(categoryId: String): Flow<List<LinkItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(link: LinkItem)

    @Update
    suspend fun update(link: LinkItem)

    @Query("DELETE FROM links WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE links SET orderIndex = :order WHERE id = :id")
    suspend fun updateOrder(id: String, order: Int)

    @Query("SELECT COALESCE(MAX(orderIndex), -1) FROM links WHERE categoryId = :categoryId")
    suspend fun getMaxOrder(categoryId: String): Int

    @Query("SELECT * FROM links")
    suspend fun getAllOnce(): List<LinkItem>
}
