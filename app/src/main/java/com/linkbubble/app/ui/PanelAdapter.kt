package com.linkbubble.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.linkbubble.app.R
import com.linkbubble.app.data.CategoryWithCount
import com.linkbubble.app.data.LinkItem

sealed class PanelItem {
    data class Header(val category: CategoryWithCount, val expanded: Boolean) : PanelItem()
    data class LinkRow(val link: LinkItem) : PanelItem()
    data class Empty(val categoryId: Long) : PanelItem()
    object Footer : PanelItem()
}

class PanelAdapter(
    private val onToggleExpand: (categoryId: Long) -> Unit,
    private val onAddLinkClicked: (categoryId: Long) -> Unit,
    private val onCategoryMenuClicked: (categoryId: Long, anchor: View) -> Unit,
    private val onLinkChecked: (link: LinkItem, checked: Boolean) -> Unit,
    private val onLinkClicked: (link: LinkItem) -> Unit,
    private val onLinkMenuClicked: (link: LinkItem, anchor: View) -> Unit,
    private val onAddCategoryClicked: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: List<PanelItem> = emptyList()

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_LINK = 1
        private const val TYPE_EMPTY = 2
        private const val TYPE_FOOTER = 3
    }

    fun submitList(newItems: List<PanelItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is PanelItem.Header -> TYPE_HEADER
        is PanelItem.LinkRow -> TYPE_LINK
        is PanelItem.Empty -> TYPE_EMPTY
        PanelItem.Footer -> TYPE_FOOTER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderVH(inflater.inflate(R.layout.item_panel_header, parent, false))
            TYPE_LINK -> LinkVH(inflater.inflate(R.layout.item_panel_link, parent, false))
            TYPE_EMPTY -> EmptyVH(inflater.inflate(R.layout.item_panel_empty, parent, false))
            else -> FooterVH(inflater.inflate(R.layout.item_panel_footer, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is PanelItem.Header -> (holder as HeaderVH).bind(item)
            is PanelItem.LinkRow -> (holder as LinkVH).bind(item.link)
            is PanelItem.Empty -> Unit
            PanelItem.Footer -> (holder as FooterVH).bind()
        }
    }

    inner class HeaderVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val viewColorDot: View = itemView.findViewById(R.id.viewColorDot)
        private val tvChevron: TextView = itemView.findViewById(R.id.tvHeaderChevron)
        private val tvName: TextView = itemView.findViewById(R.id.tvHeaderName)
        private val btnAdd: TextView = itemView.findViewById(R.id.btnAddLink)
        private val btnMenu: TextView = itemView.findViewById(R.id.btnHeaderMenu)

        fun bind(header: PanelItem.Header) {
            val cat = header.category
            tvName.text = "${cat.name} (${cat.linkCount})"
            tvChevron.text = if (header.expanded) "▾" else "▸"
            try {
                viewColorDot.background.setTint(android.graphics.Color.parseColor(cat.color))
            } catch (_: Exception) {
                viewColorDot.background.setTint(android.graphics.Color.parseColor("#6200EE"))
            }
            itemView.setOnClickListener { onToggleExpand(cat.id) }
            btnAdd.setOnClickListener { onAddLinkClicked(cat.id) }
            btnMenu.setOnClickListener { onCategoryMenuClicked(cat.id, it) }
        }
    }

    inner class LinkVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cb: CheckBox = itemView.findViewById(R.id.cbChecked)
        private val tvText: TextView = itemView.findViewById(R.id.tvLinkText)
        private val btnMenu: TextView = itemView.findViewById(R.id.btnLinkMenu)

        fun bind(link: LinkItem) {
            cb.setOnCheckedChangeListener(null)
            cb.isChecked = link.checked
            tvText.text = link.title
            tvText.paint.isStrikeThruText = link.checked
            tvText.invalidate()

            cb.setOnCheckedChangeListener { _, isChecked -> onLinkChecked(link, isChecked) }
            tvText.setOnClickListener { onLinkClicked(link) }
            btnMenu.setOnClickListener { onLinkMenuClicked(link, it) }
        }
    }

    inner class EmptyVH(itemView: View) : RecyclerView.ViewHolder(itemView)

    inner class FooterVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        fun bind() {
            itemView.setOnClickListener { onAddCategoryClicked() }
        }
    }
}
