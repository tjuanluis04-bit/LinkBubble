package com.linkbubble.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Query(
        """
        SELECT categories.id as id, categories.name as name, categories.color as color, COUNT(links.id) as linkCount
        FROM categories LEFT JOIN links ON links.categoryId = categories.id
        GROUP BY categories.id
        ORDER BY categories.createdAt ASC
        """
    )
    fun getCategoriesWithCount(): Flow<List<CategoryWithCount>>

    @Insert
    suspend fun insert(category: Category): Long

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM categories ORDER BY createdAt ASC")
    suspend fun getAllOnce(): List<Category>
}
