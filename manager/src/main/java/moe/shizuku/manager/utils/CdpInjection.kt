package moe.shizuku.manager.utils

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbClient
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import rikka.shizuku.Shizuku
import java.util.regex.Pattern

data class CdpPage(
    val pid: Int,
    val processName: String,
    val pageId: String,
    val title: String,
    val url: String
)

object CdpInjection {

    private const val JS_SCRIPT = """
        (function(){fetch("https://unpkg.com/vconsole@latest/dist/vconsole.min.js").then(r=>r.text()).then(eval).then(()=>{new VConsole();})})()
    """

    fun discoverWebViews(): List<Int> {
        val pids = mutableListOf<Int>()
        try {
            val process = Shizuku.newProcess(arrayOf("cat", "/proc/net/unix"), null, null)
            val output = process.inputStream.bufferedReader().readText()
            val pattern = Pattern.compile("webview_devtools_remote_(\\d+)")
            val matcher = pattern.matcher(output)
            while (matcher.find()) {
                pids.add(matcher.group(1).toInt())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return pids.distinct()
    }

    fun getProcessName(pid: Int): String {
        return try {
            val process = Shizuku.newProcess(arrayOf("cat", "/proc/$pid/cmdline"), null, null)
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output.trim().replace("\u0000", "").ifEmpty { "PID $pid" }
        } catch (e: Exception) {
            "PID $pid"
        }
    }

    /**
     * 发现指定 PID 的所有可注入页面
     */
    suspend fun discoverPages(context: Context, pid: Int): List<CdpPage> {
        val processName = withContext(Dispatchers.IO) { getProcessName(pid) }
        return if (Shizuku.getUid() == 0) {
            discoverPagesViaRoot(context, pid, processName)
        } else {
            discoverPagesViaAdb(context, pid, processName)
        }
    }

    private suspend fun discoverPagesViaRoot(context: Context, pid: Int, processName: String): List<CdpPage> {
        return withContext(Dispatchers.IO) {
            withPortForwardingRoot(context, pid) { localPort ->
                fetchCdpPages(localPort, pid, processName)
            }
        }
    }

    private suspend fun discoverPagesViaAdb(context: Context, pid: Int, processName: String): List<CdpPage> {
        return withContext(Dispatchers.IO) {
            withPortForwardingAdb(context, pid) { localPort ->
                fetchCdpPages(localPort, pid, processName)
            }
        }
    }

    /**
     * 向选中的页面注入 vConsole（按 PID 分组处理）
     */
    suspend fun injectIntoPages(context: Context, pages: List<CdpPage>): List<Pair<CdpPage, Boolean>> {
        val results = mutableListOf<Pair<CdpPage, Boolean>>()
        val grouped = pages.groupBy { it.pid }
        for ((pid, pagesForPid) in grouped) {
            val pageIds = pagesForPid.map { it.pageId }.toSet()
            if (Shizuku.getUid() == 0) {
                injectPagesViaRoot(context, pid, pageIds, pagesForPid, results)
            } else {
                injectPagesViaAdb(context, pid, pageIds, pagesForPid, results)
            }
        }
        return results
    }

    private suspend fun injectPagesViaRoot(
        context: Context, pid: Int, pageIds: Set<String>,
        originalPages: List<CdpPage>, results: MutableList<Pair<CdpPage, Boolean>>
    ) {
        withContext(Dispatchers.IO) {
            try {
                withPortForwardingRoot(context, pid) { localPort ->
                    injectMatchingPages(localPort, pageIds, originalPages, results)
                }
            } catch (e: Exception) {
                originalPages.forEach { results.add(it to false) }
            }
        }
    }

    private suspend fun injectPagesViaAdb(
        context: Context, pid: Int, pageIds: Set<String>,
        originalPages: List<CdpPage>, results: MutableList<Pair<CdpPage, Boolean>>
    ) {
        withContext(Dispatchers.IO) {
            try {
                withPortForwardingAdb(context, pid) { localPort ->
                    injectMatchingPages(localPort, pageIds, originalPages, results)
                }
            } catch (e: Exception) {
                originalPages.forEach { results.add(it to false) }
            }
        }
    }

    private fun injectMatchingPages(
        localPort: Int, pageIds: Set<String>,
        originalPages: List<CdpPage>, results: MutableList<Pair<CdpPage, Boolean>>
    ) {
        val liveCdpPages = try {
            getRawCdpPages(localPort)
        } catch (e: Exception) {
            originalPages.forEach { results.add(it to false) }
            return
        }
        for (page in originalPages) {
            val livePage = liveCdpPages.find { (it["id"] as? String) == page.pageId }
            if (livePage == null) {
                results.add(page to false)
                continue
            }
            val wsUrl = livePage["webSocketDebuggerUrl"] as? String
            if (wsUrl == null) {
                results.add(page to false)
                continue
            }
            try {
                injectJsIntoPage(wsUrl, JS_SCRIPT)
                results.add(page to true)
            } catch (e: Exception) {
                results.add(page to false)
            }
        }
    }

    private var forwarderProcess: java.lang.Process? = null
    private var adbClientForward: AdbClient? = null

    suspend fun startForwarding(context: Context, pid: Int, targetPort: Int) {
        stopForwarding() // 停止之前的转发（如果有）

        if (Shizuku.getUid() == 0) {
            val socketName = "webview_devtools_remote_$pid"
            val apkPath = context.applicationInfo.sourceDir
            // 此处使用的是原应用内的 SocketForwarder 工具，但如果是将其固定暴露到本地的 targetPort 端口需要专门的支持。
            // 为了实现直接将 root 的 socket 转发到宿主机的 targetPort 上，我们用 socat / forwarder
            // 然而, 因为 Shizuku 已经允许创建进程，最简单的实现是通过 socat 或者直接调用内置的代码。
            // 假设我们使用现有的 SocketForwarder 或类似工具需要修改或者我们直接用一个 socat 命令（如果存在）。
            // 鉴于目前已有的环境，如果你允许我们添加一个简单的端口转发器在 root，我们可以这么做。
            // 暂作占位：需实现从 127.0.0.1:targetPort 到 abstract:webview_devtools_remote_$pid 的转发。
            val process = Shizuku.newProcess(
                arrayOf(
                    "app_process",
                    "-Djava.class.path=$apkPath",
                    "/system/bin",
                    "moe.shizuku.manager.utils.SocketForwarder",
                    socketName,
                    targetPort.toString() // 假设你修改了 SocketForwarder 接受第二个参数作为绑定的端口
                ), null, null
            )
            forwarderProcess = process
            // 保持进程运行...
            process.waitFor()
        } else {
             val host = "127.0.0.1"
             val port = run {
                 val uidPort = discoverAdbPortByUid()
                 if (uidPort > 0) return@run uidPort

                 if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                     discoverAdbPort(context)
                 } else {
                     val p = EnvironmentUtils.getAdbTcpPort()
                     if (p <= 0) throw Exception("ADB TCP port not found")
                     p
                 }
             }
             val pref = ShizukuSettings.getPreferences()
             val key = AdbKey(PreferenceAdbKeyStore(pref), "shizuku")
             val adbClient = AdbClient(host, port, key)
             adbClient.connect()
             val remoteDestination = "localabstract:webview_devtools_remote_$pid"
             // AdbClient.createForward 需要创建一个持久的连接
             adbClient.createForward(targetPort, remoteDestination)
             adbClientForward = adbClient

             // 保持协程挂起直到被取消
             kotlinx.coroutines.awaitCancellation()
        }
    }

    suspend fun stopForwarding() {
        withContext(Dispatchers.IO) {
            forwarderProcess?.destroy()
            forwarderProcess = null

            adbClientForward?.close()
            adbClientForward = null
        }
    }

    // ---- 端口转发（Root） ----

    private fun <T> withPortForwardingRoot(context: Context, pid: Int, block: (Int) -> T): T {
        val socketName = "webview_devtools_remote_$pid"
        val apkPath = context.applicationInfo.sourceDir
        val process = Shizuku.newProcess(
            arrayOf(
                "app_process",
                "-Djava.class.path=$apkPath",
                "/system/bin",
                "moe.shizuku.manager.utils.SocketForwarder",
                socketName
            ), null, null
        )
        return try {
            val reader = process.inputStream.bufferedReader()
            val portLine = reader.readLine() ?: throw Exception("Forwarder process exited without output")
            val localPort = portLine.trim().toIntOrNull()
                ?: throw Exception("Invalid port from forwarder: $portLine")
            block(localPort)
        } finally {
            process.destroy()
        }
    }

    // ---- 端口转发（ADB） ----

    private suspend fun <T> withPortForwardingAdb(context: Context, pid: Int, block: (Int) -> T): T {
        val host = "127.0.0.1"
        val port = run {
            val uidPort = discoverAdbPortByUid()
            if (uidPort > 0) return@run uidPort

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                discoverAdbPort(context)
            } else {
                val p = EnvironmentUtils.getAdbTcpPort()
                if (p <= 0) throw Exception("ADB TCP port not found")
                p
            }
        }
        val pref = ShizukuSettings.getPreferences()
        val key = AdbKey(PreferenceAdbKeyStore(pref), "shizuku")
        val adbClient = AdbClient(host, port, key)
        return try {
            adbClient.connect()
            val localPort = java.net.ServerSocket(0).use { it.localPort }
            val remoteDestination = "localabstract:webview_devtools_remote_$pid"
            adbClient.createForward(localPort, remoteDestination).use {
                block(localPort)
            }
        } finally {
            adbClient.close()
        }
    }

    // ---- 保留向后兼容的旧接口 ----

    suspend fun injectVConsole(context: Context, pid: Int): Pair<Boolean, String?> {
        return if (Shizuku.getUid() == 0) {
            injectVConsoleViaRoot(context, pid)
        } else {
            injectVConsoleViaAdb(context, pid)
        }
    }

    private suspend fun injectVConsoleViaRoot(context: Context, pid: Int): Pair<Boolean, String?> {
        return withContext(Dispatchers.IO) {
            try {
                withPortForwardingRoot(context, pid) { localPort ->
                    performCdpInjection(localPort, pid)
                }
            } catch (e: Exception) {
                false to "Failed: ${e.message}"
            }
        }
    }

    private suspend fun injectVConsoleViaAdb(context: Context, pid: Int): Pair<Boolean, String?> {
        return withContext(Dispatchers.IO) {
            try {
                withPortForwardingAdb(context, pid) { localPort ->
                    performCdpInjection(localPort, pid)
                }
            } catch (e: Exception) {
                false to "ADB error or injection failed: ${e.message}"
            }
        }
    }

    // ---- 内部工具方法 ----

    private fun discoverAdbPortByUid(): Int {
        try {
            val process = Shizuku.newProcess(arrayOf("cat", "/proc/net/tcp6"), null, null)
            val output = process.inputStream.bufferedReader().readText()
            for (line in output.lines()) {
                val fields = line.trim().split("\\s+".toRegex())
                if (fields.size < 8) continue
                val st = fields[3]
                val uid = fields[7]
                if (st == "0A" && uid == "2000") {
                    val portHex = fields[1].substringAfterLast(":")
                    val port = portHex.toIntOrNull(16) ?: continue
                    if (port in 1..65535) return port
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return -1
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun discoverAdbPort(context: Context): Int {
        val deferred = CompletableDeferred<Int>()
        val mdns = AdbMdns(context, AdbMdns.TLS_CONNECT) { port ->
            if (port in 1..65535) {
                deferred.complete(port)
            }
        }
        mdns.start()
        return try {
            withTimeout(10000) { deferred.await() }
        } finally {
            mdns.stop()
        }
    }

    private fun fetchCdpPages(localPort: Int, pid: Int, processName: String): List<CdpPage> {
        val rawPages = getRawCdpPages(localPort)
        return rawPages.mapNotNull { page ->
            val pageUrl = (page["url"] as? String).orEmpty()
            if (!pageUrl.startsWith("http://") && !pageUrl.startsWith("https://")) return@mapNotNull null
            val pageId = (page["id"] as? String) ?: return@mapNotNull null
            CdpPage(
                pid = pid,
                processName = processName,
                pageId = pageId,
                title = (page["title"] as? String).orEmpty(),
                url = pageUrl
            )
        }
    }

    private fun getRawCdpPages(port: Int): List<Map<String, Any>> {
        val url = java.net.URL("http://127.0.0.1:$port/json/list")
        val connection = url.openConnection() as java.net.HttpURLConnection
        return try {
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.inputStream.bufferedReader().use { reader ->
                val response = reader.readText()
                val jsonArray = org.json.JSONArray(response)
                val pages = mutableListOf<Map<String, Any>>()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val page = mutableMapOf<String, Any>()
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        page[key] = obj.get(key)
                    }
                    pages.add(page)
                }
                pages
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun performCdpInjection(localPort: Int, pid: Int): Pair<Boolean, String?> {
        val pages = try {
            getRawCdpPages(localPort)
        } catch (e: Exception) {
            return false to "Failed to fetch CDP pages from localhost:$localPort: ${e.message}"
        }

        if (pages.isEmpty()) {
            return false to "No inspectable pages found in PID $pid (tried localhost:$localPort)"
        }
        val results = mutableListOf<String>()
        var success = false
        for (page in pages) {
            val pageUrl = (page["url"] as? String).orEmpty()
            if (!pageUrl.startsWith("http://") && !pageUrl.startsWith("https://")) continue
            val wsUrl = page["webSocketDebuggerUrl"] as? String ?: continue
            try {
                injectJsIntoPage(wsUrl, JS_SCRIPT)
                results.add("Injected into ${page["title"] ?: wsUrl}")
                success = true
            } catch (e: Exception) {
                results.add("Failed to inject into $wsUrl: ${e.message}")
            }
        }
        return success to (if (results.isEmpty()) "No suitable pages for injection" else results.joinToString("\n"))
    }

    private fun injectJsIntoPage(wsUrl: String, script: String) {
        val uri = java.net.URI(wsUrl)
        val host = uri.host ?: "127.0.0.1"
        val port = if (uri.port != -1) uri.port else 80
        val path = if (uri.rawPath.isNullOrEmpty()) "/" else uri.rawPath + (if (uri.rawQuery != null) "?" + uri.rawQuery else "")

        java.net.Socket(host, port).use { socket ->
            socket.soTimeout = 5000
            socket.tcpNoDelay = true
            val os = socket.getOutputStream()
            val `is` = socket.getInputStream()

            // WebSocket Handshake
            val handshake = "GET $path HTTP/1.1\r\n" +
                    "Host: $host:$port\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                    "Sec-WebSocket-Version: 13\r\n\r\n"
            os.write(handshake.toByteArray())
            os.flush()

            val buffer = ByteArray(8192)
            val read = `is`.read(buffer)
            if (read == -1) throw Exception("Server closed connection during handshake")

            val response = String(buffer, 0, read)

            if (!response.contains("101 Switching Protocols") && !response.contains("WebSocket Protocol Handshake")) {
                throw Exception("WebSocket handshake failed: $response")
            }

            // Send Runtime.evaluate
            val escapedScript = script.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")

            val json = "{\"id\":1,\"method\":\"Runtime.evaluate\",\"params\":{\"expression\":\"$escapedScript\"}}"
            val frame = encodeWebSocketFrame(json.toByteArray())
            os.write(frame)
            os.flush()

            // Wait for response to ensure command is processed
            try {
                val respRead = `is`.read(buffer)
                if (respRead > 0) {
                    android.util.Log.d("CdpInjection", "Execution response: ${String(buffer, 0, respRead)}")
                }
            } catch (e: Exception) {
                android.util.Log.e("CdpInjection", "Error reading execution response: ${e.message}")
            }
        }
    }

    private fun encodeWebSocketFrame(data: ByteArray): ByteArray {
        val len = data.size
        var headerLen = 2
        if (len > 125) headerLen += 2

        val frame = ByteArray(headerLen + 4 + len)
        var pos = 0
        frame[pos++] = 0x81.toByte() // FIN + Text

        if (len <= 125) {
            frame[pos++] = (0x80 or len).toByte()
        } else {
            frame[pos++] = (0x80 or 126).toByte()
            frame[pos++] = ((len shr 8) and 0xFF).toByte()
            frame[pos++] = (len and 0xFF).toByte()
        }

        val mask = byteArrayOf(0x12, 0x34, 0x56, 0x78)
        System.arraycopy(mask, 0, frame, pos, 4)
        pos += 4

        for (i in data.indices) {
            frame[pos++] = (data[i].toInt() xor mask[i % 4].toInt()).toByte()
        }
        return frame
    }
}
