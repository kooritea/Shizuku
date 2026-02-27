package moe.shizuku.manager.home

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppBarActivity
import moe.shizuku.manager.databinding.ActivityInjectVconsoleBinding
import moe.shizuku.manager.utils.CdpPage
import rikka.lifecycle.viewModels

class InjectVConsoleActivity : AppBarActivity() {

    private val viewModel by viewModels { InjectVConsoleViewModel() }
    private lateinit var binding: ActivityInjectVconsoleBinding
    private lateinit var adapter: VConsolePageAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_close_24)
        supportActionBar?.title = getString(R.string.home_inject_vconsole_title)

        binding = ActivityInjectVconsoleBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = VConsolePageAdapter { selectedPages ->
            updateBottomBar(selectedPages)
        }
        binding.pageList.adapter = adapter

        binding.buttonInject.setOnClickListener {
            val selected = adapter.getSelectedPages()
            if (selected.isNotEmpty()) {
                viewModel.injectSelected(this, selected)
            }
        }

        viewModel.statusMessage.observe(this) { message ->
            binding.statusText.text = message
        }

        viewModel.uiState.observe(this) { state ->
            when (state) {
                is VConsoleUiState.Loading -> showLoading()
                is VConsoleUiState.PageList -> showPageList(state.pages)
                is VConsoleUiState.Injecting -> showInjecting()
                is VConsoleUiState.InjectionResult -> showResults(state.results)
                is VConsoleUiState.Error -> showError(state.message)
            }
        }

        viewModel.discoverPages(this)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, R.id.action_select_all, 0, R.string.vconsole_select_all)
        menu.add(0, R.id.action_deselect_all, 1, R.string.vconsole_deselect_all)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_select_all -> {
                adapter.selectAll()
                true
            }
            R.id.action_deselect_all -> {
                adapter.deselectAll()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showLoading() {
        binding.statusContainer.visibility = View.VISIBLE
        binding.progress.visibility = View.VISIBLE
        binding.statusText.visibility = View.VISIBLE
        binding.pageList.visibility = View.GONE
        binding.bottomBar.visibility = View.GONE
    }

    private fun showPageList(pages: List<CdpPage>) {
        binding.statusContainer.visibility = View.GONE
        binding.pageList.visibility = View.VISIBLE
        binding.bottomBar.visibility = View.VISIBLE
        adapter.setPages(pages)
        updateBottomBar(emptyList())
    }

    private fun showInjecting() {
        binding.statusContainer.visibility = View.VISIBLE
        binding.progress.visibility = View.VISIBLE
        binding.statusText.text = getString(R.string.vconsole_injecting)
        binding.statusText.visibility = View.VISIBLE
        binding.pageList.visibility = View.GONE
        binding.bottomBar.visibility = View.GONE
    }

    private fun showResults(results: List<Pair<CdpPage, Boolean>>) {
        val successCount = results.count { it.second }
        val failCount = results.size - successCount

        binding.statusContainer.visibility = View.VISIBLE
        binding.progress.visibility = View.GONE
        binding.statusText.visibility = View.VISIBLE

        val sb = StringBuilder()
        sb.append(getString(R.string.vconsole_result_summary, successCount, failCount))
        sb.append("\n\n")
        for ((page, success) in results) {
            val status = if (success) "✓" else "✗"
            sb.append("$status ${page.title.ifEmpty { page.url }}\n")
        }
        binding.statusText.text = sb.toString()

        binding.pageList.visibility = View.GONE
        binding.bottomBar.visibility = View.GONE
    }

    private fun showError(message: String) {
        binding.statusContainer.visibility = View.VISIBLE
        binding.progress.visibility = View.GONE
        binding.statusText.text = message
        binding.statusText.visibility = View.VISIBLE
        binding.pageList.visibility = View.GONE
        binding.bottomBar.visibility = View.GONE
    }

    private fun updateBottomBar(selectedPages: List<CdpPage>) {
        val count = selectedPages.size
        binding.selectInfo.text = getString(R.string.vconsole_selected_count, count)
        binding.buttonInject.isEnabled = count > 0
    }
}
