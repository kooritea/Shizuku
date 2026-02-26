package moe.shizuku.manager.utils

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbClient
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import rikka.shizuku.Shizuku
import java.util.regex.Pattern

object CdpInjection {

    private const val JS_SCRIPT = """
        (function(){fetch("https://unpkg.com/vconsole@latest/dist/vconsole.min.js").then(r=>r.text()).then(eval).then(()=>{new VConsole()});})()
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

    /**
     * 通过 /proc/net/tcp6 查找 uid 2000 (AID_SHELL/adbd) 正在监听的 TCP 端口
     */
    private fun discoverAdbPortByUid(): Int {
        try {
            val process = Shizuku.newProcess(arrayOf("cat", "/proc/net/tcp6"), null, null)
            val output = process.inputStream.bufferedReader().readText()
            // /proc/net/tcp6 格式: sl local_address remote_address st ... uid
            // LISTEN 状态 st=0A, uid=2000 是 adbd
            for (line in output.lines()) {
                val fields = line.trim().split("\\s+".toRegex())
                if (fields.size < 8) continue
                val st = fields[3]
                val uid = fields[7]
                if (st == "0A" && uid == "2000") {
                    // local_address 格式为 hex_ip:hex_port
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

    suspend fun injectVConsole(context: Context, pid: Int): Pair<Boolean, String?> {
        return if (Shizuku.getUid() == 0) {
            injectVConsoleViaRoot(context, pid)
        } else {
            injectVConsoleViaAdb(context, pid)
        }
    }

    /**
     * Root 模式：通过 Shizuku 以 root 身份启动 SocketForwarder 进程，
     * 在 root 上下文中创建 TCP 代理桥接到 WebView 的抽象域套接字，绕过 SELinux 限制。
     */
    private suspend fun injectVConsoleViaRoot(context: Context, pid: Int): Pair<Boolean, String?> {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val socketName = "webview_devtools_remote_$pid"
            val apkPath = context.applicationInfo.sourceDir
            val process = try {
                Shizuku.newProcess(
                    arrayOf(
                        "app_process",
                        "-Djava.class.path=$apkPath",
                        "/system/bin",
                        "moe.shizuku.manager.utils.SocketForwarder",
                        socketName
                    ), null, null
                )
            } catch (e: Exception) {
                return@withContext false to "Failed to start forwarder: ${e.message}"
            }
            try {
                val reader = process.inputStream.bufferedReader()
                val portLine = reader.readLine()
                    ?: return@withContext false to "Forwarder process exited without output"
                val localPort = portLine.trim().toIntOrNull()
                    ?: return@withContext false to "Invalid port from forwarder: $portLine"

                performCdpInjection(localPort, pid)
            } finally {
                process.destroy()
            }
        }
    }

    /**
     * ADB 模式：通过 ADB 端口转发连接到 WebView 的抽象域套接字。
     */
    private suspend fun injectVConsoleViaAdb(context: Context, pid: Int): Pair<Boolean, String?> {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val host = "127.0.0.1"
            val port = run {
                val uidPort = discoverAdbPortByUid()
                if (uidPort > 0) return@run uidPort

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        discoverAdbPort(context)
                    } catch (e: Exception) {
                        return@withContext false to "Failed to discover ADB port: ${e.message}"
                    }
                } else {
                    val p = EnvironmentUtils.getAdbTcpPort()
                    if (p <= 0) {
                        return@withContext false to "ADB TCP port not found"
                    } else p
                }
            }
            val pref = ShizukuSettings.getPreferences()

            val key = try {
                AdbKey(PreferenceAdbKeyStore(pref), "shizuku")
            } catch (e: Exception) {
                return@withContext false to "Failed to get ADB key: ${e.message}"
            }

            val adbClient = AdbClient(host, port, key)
            try {
                adbClient.connect()

                val localPort = java.net.ServerSocket(0).use { it.localPort }
                val remoteDestination = "localabstract:webview_devtools_remote_$pid"
                adbClient.createForward(localPort, remoteDestination).use {
                    performCdpInjection(localPort, pid)
                }
            } catch (e: Exception) {
                false to "ADB error or injection failed: ${e.message}"
            } finally {
                adbClient.close()
            }
        }
    }

    private fun performCdpInjection(localPort: Int, pid: Int): Pair<Boolean, String?> {
        val pages = try {
            getCdpPages(localPort)
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

    private fun getCdpPages(port: Int): List<Map<String, Any>> {
        val url = java.net.URL("http://127.0.0.1:$port/json/list")
        val connection = url.openConnection() as java.net.HttpURLConnection
        return try {
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

