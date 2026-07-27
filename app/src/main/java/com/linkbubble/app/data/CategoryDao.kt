package com.linkbubble.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {

    // Categorías horizontales (nivel superior). El contador suma los links de TODAS sus subcategorías.
    @Query(
        """
        SELECT c.id as id, c.name as name, c.color as color, COUNT(l.id) as linkCount
        FROM categories c
        LEFT JOIN categories child ON child.parentId = c.id
        LEFT JOIN links l ON l.categoryId = child.id
        WHERE c.parentId IS NULL
        GROUP BY c.id
        ORDER BY c.createdAt ASC
        """
    )
    fun getTopLevelCategories(): Flow<List<CategoryWithCount>>

    // Subcategorías verticales de una horizontal específica, con su propio contador de links.
    @Query(
        """
        SELECT c.id as id, c.name as name, c.color as color, COUNT(l.id) as linkCount
        FROM categories c
        LEFT JOIN links l ON l.categoryId = c.id
        WHERE c.parentId = :parentId
        GROUP BY c.id
        ORDER BY c.createdAt ASC
        """
    )
    fun getChildCategories(parentId: Long): Flow<List<CategoryWithCount>>

    @Insert
    suspend fun insert(category: Category): Long

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE categories SET name = :name, color = :color WHERE id = :id")
    suspend fun updateCategory(id: Long, name: String, color: String)

    // Todas las subcategorías (las que pueden contener links), con el nombre de su horizontal — para el flujo de "Compartir".
    @Query(
        """
        SELECT child.id as id, child.name as name, parent.name as parentName
        FROM categories child
        JOIN categories parent ON parent.id = child.parentId
        ORDER BY parent.createdAt ASC, child.createdAt ASC
        """
    )
    suspend fun getAllLeafCategoriesOnce(): List<LeafCategory>
}

data class LeafCategory(
    val id: Long,
    val name: String,
    val parentName: String
)
