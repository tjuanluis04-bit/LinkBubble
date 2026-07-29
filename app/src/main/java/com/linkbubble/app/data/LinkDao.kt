package com.linkbubble.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface LinkDao {
    @Query("SELECT * FROM links WHERE categoryId = :categoryId ORDER BY createdAt ASC")
    fun getByCategory(categoryId: String): Flow<List<LinkItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(link: LinkItem)

    @Update
    suspend fun update(link: LinkItem)

    @Query("DELETE FROM links WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM links")
    suspend fun getAllOnce(): List<LinkItem>
}
