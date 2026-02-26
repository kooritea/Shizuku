package moe.shizuku.manager.home

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.utils.CdpInjection
import rikka.lifecycle.Resource

class InjectVConsoleViewModel : ViewModel() {

    private val sb = StringBuilder()
    private val _output = MutableLiveData<Resource<StringBuilder>>()

    val output = _output as LiveData<Resource<StringBuilder>>

    fun start(context: Context) {
        if (_output.value != null) return

        sb.append("正在发现可调试的 WebView...").append('\n')
        postResult()

        viewModelScope.launch {
            try {
                val pids = withContext(Dispatchers.IO) {
                    CdpInjection.discoverWebViews()
                }

                if (pids.isEmpty()) {
                    sb.append('\n').append("未发现可调试的 WebView。").append('\n')
                    postResult()
                    return@launch
                }

                sb.append("发现 ${pids.size} 个 WebView 进程: ${pids.joinToString(", ")}").append('\n')
                postResult()

                for (pid in pids) {
                    sb.append('\n').append("正在向 PID $pid 注入 vConsole...").append('\n')
                    postResult()

                    val (success, message) = CdpInjection.injectVConsole(context, pid)
                    if (success) {
                        sb.append("PID $pid: 注入成功\n$message").append('\n')
                    } else {
                        sb.append("PID $pid: 注入失败\n$message").append('\n')
                    }
                    postResult()
                }

                sb.append('\n').append("所有注入操作已完成。").append('\n')
                postResult()
            } catch (e: Exception) {
                sb.append('\n').append("发生错误: ${e.message}").append('\n')
                postResult(e)
            }
        }
    }

    private fun postResult(throwable: Throwable? = null) {
        if (throwable == null)
            _output.postValue(Resource.success(sb))
        else
            _output.postValue(Resource.error(throwable, sb))
    }
}
