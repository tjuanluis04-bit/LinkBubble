package com.linkbubble.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.linkbubble.app.R
import com.linkbubble.app.data.LinkItem

sealed class LinkListItem {
    data class Row(val link: LinkItem) : LinkListItem()
    data class EmptyState(val message: String) : LinkListItem()
}

class LinkListAdapter(
    private val onLinkChecked: (link: LinkItem, checked: Boolean) -> Unit,
    private val onLinkClicked: (link: LinkItem) -> Unit,
    private val onLinkMenuClicked: (link: LinkItem, anchor: View) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: List<LinkListItem> = emptyList()

    companion object {
        private const val TYPE_LINK = 0
        private const val TYPE_EMPTY = 1
    }

    fun submitList(newItems: List<LinkListItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is LinkListItem.Row -> TYPE_LINK
        is LinkListItem.EmptyState -> TYPE_EMPTY
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_LINK) {
            LinkVH(inflater.inflate(R.layout.item_panel_link, parent, false))
        } else {
            EmptyVH(inflater.inflate(R.layout.item_panel_empty, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is LinkListItem.Row -> (holder as LinkVH).bind(item.link)
            is LinkListItem.EmptyState -> (holder as EmptyVH).bind(item.message)
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

    inner class EmptyVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        fun bind(message: String) {
            (itemView as TextView).text = message
        }
    }
}
