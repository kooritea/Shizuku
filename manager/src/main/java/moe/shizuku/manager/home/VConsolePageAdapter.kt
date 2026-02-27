package moe.shizuku.manager.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import moe.shizuku.manager.databinding.ItemVconsolePageBinding
import moe.shizuku.manager.databinding.ItemVconsoleProcessHeaderBinding
import moe.shizuku.manager.utils.CdpPage

class VConsolePageAdapter(
    private val onSelectionChanged: (List<CdpPage>) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_PAGE = 1
    }

    sealed class ListItem {
        data class Header(val processName: String, val pid: Int) : ListItem()
        data class Page(val page: CdpPage, var selected: Boolean = false) : ListItem()
    }

    private val items = mutableListOf<ListItem>()

    fun setPages(pages: List<CdpPage>) {
        items.clear()
        val grouped = pages.groupBy { it.pid }
        for ((pid, pagesForPid) in grouped) {
            val processName = pagesForPid.first().processName
            items.add(ListItem.Header(processName, pid))
            pagesForPid.forEach { items.add(ListItem.Page(it)) }
        }
        notifyDataSetChanged()
    }

    fun getSelectedPages(): List<CdpPage> {
        return items.filterIsInstance<ListItem.Page>()
            .filter { it.selected }
            .map { it.page }
    }

    fun selectAll() {
        items.forEachIndexed { index, item ->
            if (item is ListItem.Page && !item.selected) {
                item.selected = true
                notifyItemChanged(index)
            }
        }
        onSelectionChanged(getSelectedPages())
    }

    fun deselectAll() {
        items.forEachIndexed { index, item ->
            if (item is ListItem.Page && item.selected) {
                item.selected = false
                notifyItemChanged(index)
            }
        }
        onSelectionChanged(getSelectedPages())
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is ListItem.Header -> TYPE_HEADER
            is ListItem.Page -> TYPE_PAGE
        }
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> {
                val binding = ItemVconsoleProcessHeaderBinding.inflate(inflater, parent, false)
                HeaderViewHolder(binding)
            }
            else -> {
                val binding = ItemVconsolePageBinding.inflate(inflater, parent, false)
                PageViewHolder(binding)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is ListItem.Header -> (holder as HeaderViewHolder).bind(item)
            is ListItem.Page -> (holder as PageViewHolder).bind(item)
        }
    }

    inner class HeaderViewHolder(
        private val binding: ItemVconsoleProcessHeaderBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ListItem.Header) {
            binding.processName.text = "${item.processName} (PID: ${item.pid})"
        }
    }

    inner class PageViewHolder(
        private val binding: ItemVconsolePageBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ListItem.Page) {
            binding.pageTitle.text = item.page.title.ifEmpty { "(无标题)" }
            binding.pageUrl.text = item.page.url
            binding.checkbox.isChecked = item.selected

            val toggleSelection = {
                item.selected = !item.selected
                binding.checkbox.isChecked = item.selected
                onSelectionChanged(getSelectedPages())
            }

            binding.checkbox.setOnClickListener { toggleSelection() }
            binding.root.setOnClickListener { toggleSelection() }
        }
    }
}
