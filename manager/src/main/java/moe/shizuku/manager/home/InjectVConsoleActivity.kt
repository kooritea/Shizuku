package moe.shizuku.manager.home

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppBarActivity
import moe.shizuku.manager.databinding.ActivityInjectVconsoleBinding
import moe.shizuku.manager.utils.CdpPage
import rikka.lifecycle.viewModels

class InjectVConsoleActivity : AppBarActivity() {

    companion object {
        const val EXTRA_AUTO_INJECT_ALL = "extra_auto_inject_all"
    }

    private val viewModel by viewModels { InjectVConsoleViewModel() }
    private lateinit var binding: ActivityInjectVconsoleBinding
    private lateinit var adapter: VConsolePageAdapter

    private var pendingForwardPid: Int? = null
    private var currentForwardingPid: Int? = null // 用于记录当前正在转发的 PID

    private val serviceStopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == PortForwardingService.ACTION_SERVICE_STOPPED) {
                // 重置所有转发状态
                currentForwardingPid = null
                adapter.resetAllForwardingStates()
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            pendingForwardPid?.let { pid ->
                PortForwardingService.start(this, pid)
                pendingForwardPid = null
            }
        } else {
            Toast.makeText(this, R.string.vconsole_forwarding_needs_notification, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ContextCompat.registerReceiver(
            this,
            serviceStopReceiver,
            IntentFilter(PortForwardingService.ACTION_SERVICE_STOPPED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_close_24)
        supportActionBar?.title = getString(R.string.home_inject_vconsole_title)

        binding = ActivityInjectVconsoleBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = VConsolePageAdapter(
            onSelectionChanged = { selectedPages ->
                updateBottomBar(selectedPages)
            },
            onForwardClicked = { pid ->
                handleForwardClick(pid)
            }
        )
        // 增加广播或服务连接监听以通知 adapter 更新状态。由于目前结构最简单的方式是在启动服务后直接更新 adapter
        // 并且在停止时重置。更健壮的做法可以监听 Service 状态或使用 LiveData，但目前我们可以先在 Activity 控制
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
                is VConsoleUiState.PageList -> {
                    showPageList(state.pages)
                    if (intent.getBooleanExtra(EXTRA_AUTO_INJECT_ALL, false)) {
                        intent.removeExtra(EXTRA_AUTO_INJECT_ALL)
                        if (state.pages.isNotEmpty()) {
                            adapter.selectAll()
                            viewModel.injectSelected(this, state.pages)
                        } else {
                            // 如果页面为空，直接显示一下提示
                            showError("No inspectable pages found.")
                        }
                    }
                }
                is VConsoleUiState.Injecting -> showInjecting()
                is VConsoleUiState.InjectionResult -> showResults(state.results)
                is VConsoleUiState.Error -> showError(state.message)
            }
            binding.swipeRefresh.isRefreshing = false
        }

        binding.swipeRefresh.setOnRefreshListener {
            viewModel.discoverPages(this)
        }

        viewModel.discoverPages(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(serviceStopReceiver)
        } catch (e: Exception) {
            // Already unregistered
        }
        PortForwardingService.stop(this)
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
        binding.swipeRefresh.visibility = View.VISIBLE
        binding.pageList.visibility = View.GONE
        binding.bottomBar.visibility = View.GONE
    }

    private fun showPageList(pages: List<CdpPage>) {
        binding.statusContainer.visibility = View.GONE
        binding.swipeRefresh.visibility = View.VISIBLE
        binding.pageList.visibility = View.VISIBLE
        binding.bottomBar.visibility = View.VISIBLE
        adapter.setPages(pages)
        // 刷新列表时恢复先前的转发状态UI
        currentForwardingPid?.let { pid ->
            adapter.setForwardingState(pid, true)
        }
        updateBottomBar(emptyList())
    }

    private fun showInjecting() {
        binding.statusContainer.visibility = View.VISIBLE
        binding.progress.visibility = View.VISIBLE
        binding.statusText.text = getString(R.string.vconsole_injecting)
        binding.statusText.visibility = View.VISIBLE
        binding.swipeRefresh.visibility = View.VISIBLE
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

        binding.swipeRefresh.visibility = View.VISIBLE
        binding.pageList.visibility = View.GONE
        binding.bottomBar.visibility = View.GONE
    }

    private fun showError(message: String) {
        binding.statusContainer.visibility = View.VISIBLE
        binding.progress.visibility = View.GONE
        binding.statusText.text = message
        binding.statusText.visibility = View.VISIBLE
        binding.swipeRefresh.visibility = View.VISIBLE
        binding.pageList.visibility = View.GONE
        binding.bottomBar.visibility = View.GONE
    }

    private fun handleForwardClick(pid: Int) {
        // 如果当前点击的已经是正在转发的PID，则停止
        if (adapter.isForwarding(pid)) {
            adapter.setForwardingState(pid, false)
            PortForwardingService.stop(this)
        } else {
            startForwardingWithPermissionCheck(pid)
        }
    }

    private fun startForwardingWithPermissionCheck(pid: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    // 已获得权限，直接启动
                    currentForwardingPid = pid
                    adapter.setForwardingState(pid, true)
                    PortForwardingService.start(this, pid)
                }
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    // 用户拒绝过，解释原因并再次请求
                    Toast.makeText(this, R.string.vconsole_forwarding_needs_notification, Toast.LENGTH_LONG).show()
                    pendingForwardPid = pid
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                else -> {
                    // 首次请求权限
                    pendingForwardPid = pid
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else {
            // Android 13 之前不需要运行时通知权限
            currentForwardingPid = pid
            adapter.setForwardingState(pid, true)
            PortForwardingService.start(this, pid)
        }
    }

    private fun updateBottomBar(selectedPages: List<CdpPage>) {
        val count = selectedPages.size
        binding.selectInfo.text = getString(R.string.vconsole_selected_count, count)
        binding.buttonInject.isEnabled = count > 0
    }
}
