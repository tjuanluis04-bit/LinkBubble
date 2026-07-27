package com.linkbubble.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "categories",
    foreignKeys = [ForeignKey(
        entity = Category::class,
        parentColumns = ["id"],
        childColumns = ["parentId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("parentId")]
)
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val color: String = "#6200EE",
    // null = categoría horizontal (nivel superior). Con valor = subcategoría vertical, anidada dentro de esa horizontal.
    val parentId: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)
