package com.linkbubble.app.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.linkbubble.app.R
import com.linkbubble.app.data.CategoryWithCount

sealed class TopChipItem {
    data class Chip(val category: CategoryWithCount) : TopChipItem()
    object AddChip : TopChipItem()
}

class TopLevelChipAdapter(
    private val onClick: (CategoryWithCount) -> Unit,
    private val onLongClick: (CategoryWithCount) -> Unit,
    private val onAddClick: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<TopChipItem>()
    private var selectedId: String? = null

    companion object {
        private const val TYPE_CHIP = 0
        private const val TYPE_ADD = 1
    }

    fun submitList(categories: List<CategoryWithCount>, selected: String?) {
        selectedId = selected
        items.clear()
        items.addAll(categories.map { TopChipItem.Chip(it) })
        items.add(TopChipItem.AddChip)
        notifyDataSetChanged()
    }

    fun getItemAt(position: Int): TopChipItem? = items.getOrNull(position)

    fun moveItem(from: Int, to: Int) {
        if (from < 0 || to < 0 || from >= items.size || to >= items.size) return
        val item = items.removeAt(from)
        items.add(to, item)
        notifyItemMoved(from, to)
    }

    fun snapshotCategoryOrder(): List<String> =
        items.filterIsInstance<TopChipItem.Chip>().map { it.category.id }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int =
        if (items[position] is TopChipItem.AddChip) TYPE_ADD else TYPE_CHIP

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_top_chip, parent, false)
        return ChipVH(view)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        (holder as ChipVH).bind(items[position])
    }

    inner class ChipVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tv: TextView = itemView.findViewById(R.id.tvChip)
        private val density = itemView.resources.displayMetrics.density

        fun bind(item: TopChipItem) {
            tv.setPadding((14 * density).toInt(), (8 * density).toInt(), (14 * density).toInt(), (8 * density).toInt())
            val lp = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (8 * density).toInt() }
            tv.layoutParams = lp

            when (item) {
                is TopChipItem.Chip -> {
                    val cat = item.category
                    tv.text = cat.name
                    val isSelected = cat.id == selectedId
                    val color = runCatching { Color.parseColor(cat.color) }.getOrDefault(Color.parseColor("#6200EE"))
                    tv.background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 40f
                        if (isSelected) {
                            setColor(color)
                        } else {
                            setColor(Color.WHITE)
                            setStroke((1.5f * density).toInt(), color)
                        }
                    }
                    tv.setTextColor(if (isSelected) Color.WHITE else color)
                    tv.setOnClickListener { onClick(cat) }
                    tv.setOnLongClickListener { onLongClick(cat); true }
                }
                TopChipItem.AddChip -> {
                    tv.text = "＋"
                    tv.textSize = 16f
                    tv.setTextColor(Color.parseColor("#6200EE"))
                    tv.background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 40f
                        setColor(Color.WHITE)
                        setStroke((1.5f * density).toInt(), Color.parseColor("#CCCCCC"))
                    }
                    tv.setOnClickListener { onAddClick() }
                    tv.setOnLongClickListener { false }
                }
            }
        }
    }
}
