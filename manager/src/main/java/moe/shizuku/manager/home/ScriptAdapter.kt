package moe.shizuku.manager.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import moe.shizuku.manager.databinding.ItemScriptBinding

class ScriptAdapter(
    private val onSelectionChanged: (UserScript?) -> Unit,
    private val onLongClick: (UserScript) -> Unit
) : RecyclerView.Adapter<ScriptAdapter.ScriptViewHolder>() {

    private val scripts = mutableListOf<UserScript>()
    private var selectedId: String? = null

    fun setScripts(list: List<UserScript>) {
        scripts.clear()
        scripts.addAll(list)
        if (selectedId != null && scripts.none { it.id == selectedId }) {
            selectedId = null
        }
        notifyDataSetChanged()
        onSelectionChanged(getSelectedScript())
    }

    fun getSelectedScript(): UserScript? {
        return scripts.find { it.id == selectedId }
    }

    override fun getItemCount() = scripts.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ScriptViewHolder {
        val binding = ItemScriptBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ScriptViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ScriptViewHolder, position: Int) {
        holder.bind(scripts[position])
    }

    inner class ScriptViewHolder(
        private val binding: ItemScriptBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(script: UserScript) {
            binding.scriptName.text = script.name
            binding.scriptPreview.text = script.code.lines().firstOrNull()?.take(100) ?: ""
            binding.radio.isChecked = script.id == selectedId

            val select = {
                val oldSelectedId = selectedId
                selectedId = script.id
                if (oldSelectedId != null) {
                    val oldIndex = scripts.indexOfFirst { it.id == oldSelectedId }
                    if (oldIndex >= 0) notifyItemChanged(oldIndex)
                }
                notifyItemChanged(adapterPosition)
                onSelectionChanged(getSelectedScript())
            }

            binding.root.setOnClickListener { select() }
            binding.radio.setOnClickListener { select() }
            binding.root.setOnLongClickListener {
                onLongClick(script)
                true
            }
        }
    }
}
