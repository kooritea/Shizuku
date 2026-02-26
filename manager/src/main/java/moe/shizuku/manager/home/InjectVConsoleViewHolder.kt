package moe.shizuku.manager.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.databinding.HomeItemContainerBinding
import moe.shizuku.manager.databinding.HomeInjectVconsoleBinding
import moe.shizuku.manager.utils.CdpInjection
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator

class InjectVConsoleViewHolder(binding: HomeInjectVconsoleBinding, root: View) :
    BaseViewHolder<Any?>(root) {

    companion object {
        val CREATOR = Creator<Any> { inflater: LayoutInflater, parent: ViewGroup? ->
            val outer = HomeItemContainerBinding.inflate(inflater, parent, false)
            val inner = HomeInjectVconsoleBinding.inflate(inflater, outer.root, true)
            InjectVConsoleViewHolder(inner, outer.root)
        }
    }

    init {
        binding.buttonInjectVconsole.setOnClickListener { v: View ->
            v.context.startActivity(android.content.Intent(v.context, InjectVConsoleActivity::class.java))
        }
    }

    override fun onBind(payloads: MutableList<Any>) {
        super.onBind(payloads)
    }
}
