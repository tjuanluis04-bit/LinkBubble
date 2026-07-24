package com.linkbubble.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "links",
    foreignKeys = [ForeignKey(
        entity = Category::class,
        parentColumns = ["id"],
        childColumns = ["categoryId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("categoryId")]
)
data class LinkItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val categoryId: Long,
    val url: String,
    val title: String,
    val checked: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
