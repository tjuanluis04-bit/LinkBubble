package com.linkbubble.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

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
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val color: String = "#6200EE",
    // null = categoría horizontal (nivel superior). Con valor = subcategoría vertical, anidada dentro de esa horizontal.
    val parentId: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
