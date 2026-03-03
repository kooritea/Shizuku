package moe.shizuku.manager.home

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.PopupMenu
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppBarActivity
import moe.shizuku.manager.databinding.ActivityScriptListBinding
import moe.shizuku.manager.databinding.DialogEditScriptBinding

class ScriptListActivity : AppBarActivity() {

    companion object {
        const val EXTRA_SELECTED_SCRIPT_ID = "selected_script_id"
    }

    private lateinit var binding: ActivityScriptListBinding
    private lateinit var adapter: ScriptAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_close_24)
        supportActionBar?.title = getString(R.string.script_list_title)

        binding = ActivityScriptListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = ScriptAdapter(
            onSelectionChanged = { selected ->
                binding.buttonConfirm.isEnabled = selected != null
            },
            onLongClick = { script ->
                showEditDeleteMenu(script)
            }
        )
        binding.scriptList.adapter = adapter

        binding.fabAdd.setOnClickListener {
            showEditDialog(null)
        }

        binding.buttonConfirm.setOnClickListener {
            val selected = adapter.getSelectedScript()
            if (selected != null) {
                val resultIntent = Intent()
                resultIntent.putExtra(EXTRA_SELECTED_SCRIPT_ID, selected.id)
                setResult(Activity.RESULT_OK, resultIntent)
                finish()
            }
        }

        loadScripts()
    }

    private fun loadScripts() {
        val scripts = UserScriptManager.getAll(this)
        adapter.setScripts(scripts)
        binding.emptyText.visibility = if (scripts.isEmpty()) View.VISIBLE else View.GONE
        binding.scriptList.visibility = if (scripts.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showEditDialog(existing: UserScript?) {
        val dialogBinding = DialogEditScriptBinding.inflate(layoutInflater)
        if (existing != null) {
            dialogBinding.editName.setText(existing.name)
            dialogBinding.editCode.setText(existing.code)
        }

        val title = if (existing != null) R.string.script_edit else R.string.script_add
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.script_save) { _, _ ->
                val name = dialogBinding.editName.text?.toString()?.trim() ?: ""
                val code = dialogBinding.editCode.text?.toString() ?: ""
                if (name.isNotEmpty() && code.isNotEmpty()) {
                    if (existing != null) {
                        UserScriptManager.update(this, existing.copy(name = name, code = code))
                    } else {
                        UserScriptManager.add(this, UserScript(name = name, code = code))
                    }
                    loadScripts()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showEditDeleteMenu(script: UserScript) {
        val anchor = binding.scriptList.findViewHolderForAdapterPosition(
            UserScriptManager.getAll(this).indexOfFirst { it.id == script.id }
        )?.itemView ?: return

        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, R.string.script_edit)
        popup.menu.add(0, 2, 1, R.string.script_delete)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    showEditDialog(script)
                    true
                }
                2 -> {
                    showDeleteConfirmDialog(script)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showDeleteConfirmDialog(script: UserScript) {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.script_delete_confirm)
            .setPositiveButton(R.string.script_delete) { _, _ ->
                UserScriptManager.delete(this, script.id)
                loadScripts()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
