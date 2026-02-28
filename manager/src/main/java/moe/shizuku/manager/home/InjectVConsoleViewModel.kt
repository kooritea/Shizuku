package moe.shizuku.manager.home

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.utils.CdpInjection
import moe.shizuku.manager.utils.CdpPage
import rikka.shizuku.Shizuku

sealed class VConsoleUiState {
    object Loading : VConsoleUiState()
    data class PageList(val pages: List<CdpPage>) : VConsoleUiState()
    data class Injecting(val selected: List<CdpPage>) : VConsoleUiState()
    data class InjectionResult(val results: List<Pair<CdpPage, Boolean>>) : VConsoleUiState()
    data class Error(val message: String) : VConsoleUiState()
}

class InjectVConsoleViewModel : ViewModel() {

    private val _uiState = MutableLiveData<VConsoleUiState>()
    val uiState: LiveData<VConsoleUiState> = _uiState

    private val _statusMessage = MutableLiveData<String>()
    val statusMessage: LiveData<String> = _statusMessage

    fun discoverPages(context: Context) {
        if (_uiState.value is VConsoleUiState.Loading) return

        _uiState.value = VConsoleUiState.Loading

        viewModelScope.launch {
            try {
                val isAdbMode = Shizuku.getUid() != 0
                if (isAdbMode) {
                    _statusMessage.postValue("正在检查无线调试状态...")
                    val adbEnabled = withContext(Dispatchers.IO) { ensureWirelessDebugging() }
                    if (!adbEnabled) {
                        _uiState.postValue(VConsoleUiState.Error("无法开启无线调试，请手动在开发者选项中开启。"))
                        return@launch
                    }
                }

                _statusMessage.postValue("正在发现可调试的 WebView...")

                val pids = withContext(Dispatchers.IO) {
                    CdpInjection.discoverWebViews()
                }

                if (pids.isEmpty()) {
                    _uiState.postValue(VConsoleUiState.Error("未发现可调试的 WebView。"))
                    return@launch
                }

                _statusMessage.postValue("发现 ${pids.size} 个 WebView 进程，正在获取页面列表...")

                val allPages = mutableListOf<CdpPage>()
                for (pid in pids) {
                    try {
                        val pages = CdpInjection.discoverPages(context, pid)
                        allPages.addAll(pages)
                    } catch (e: Exception) {
                        android.util.Log.e("VConsole", "Failed to discover pages for pid $pid", e)
                    }
                }

                if (allPages.isEmpty()) {
                    _uiState.postValue(VConsoleUiState.Error("未发现可注入的页面。所有 WebView 进程中没有 HTTP/HTTPS 页面。"))
                    return@launch
                }

                _uiState.postValue(VConsoleUiState.PageList(allPages))
            } catch (e: Exception) {
                _uiState.postValue(VConsoleUiState.Error("发生错误: ${e.message}"))
            }
        }
    }

    fun injectSelected(context: Context, selectedPages: List<CdpPage>) {
        _uiState.value = VConsoleUiState.Injecting(selectedPages)

        viewModelScope.launch {
            try {
                val results = CdpInjection.injectIntoPages(context, selectedPages)
                _uiState.postValue(VConsoleUiState.InjectionResult(results))
            } catch (e: Exception) {
                _uiState.postValue(VConsoleUiState.Error("注入失败: ${e.message}"))
            }
        }
    }

    private suspend fun ensureWirelessDebugging(): Boolean {
        fun isWirelessAdbEnabled(): Boolean {
            return try {
                val process = Shizuku.newProcess(
                    arrayOf("settings", "get", "global", "adb_wifi_enabled"), null, null
                )
                val output = process.inputStream.bufferedReader().readText().trim()
                process.waitFor()
                output == "1"
            } catch (e: Exception) {
                false
            }
        }

        if (isWirelessAdbEnabled()) return true

        try {
            val process = Shizuku.newProcess(
                arrayOf("settings", "put", "global", "adb_wifi_enabled", "1"), null, null
            )
            process.waitFor()
        } catch (e: Exception) {
            return false
        }

        repeat(10) {
            delay(500)
            if (isWirelessAdbEnabled()) return true
        }
        return false
    }
}
