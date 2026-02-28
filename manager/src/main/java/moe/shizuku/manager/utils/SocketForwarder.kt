package moe.shizuku.manager.utils

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * 以 root 身份运行的 TCP-to-AbstractSocket 转发器。
 * 通过 app_process 启动，创建 TCP 服务桥接到 WebView 的抽象域套接字。
 */
object SocketForwarder {

    @JvmStatic
    fun main(args: Array<String>) {
        val socketName = args[0]
        val targetPort = if (args.size > 1) args[1].toInt() else 0
        val serverSocket = ServerSocket(targetPort)
        println(serverSocket.localPort)
        System.out.flush()

        while (true) {
            val client = serverSocket.accept()
            Thread {
                try {
                    val local = LocalSocket()
                    local.connect(
                        LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT)
                    )
                    bridge(client, local)
                } catch (_: Exception) {
                    try { client.close() } catch (_: Exception) {}
                }
            }.start()
        }
    }

    private fun bridge(tcp: Socket, local: LocalSocket) {
        val t = Thread {
            try {
                tcp.getInputStream().copyTo(local.outputStream)
            } catch (_: Exception) {
            } finally {
                try { local.shutdownOutput() } catch (_: Exception) {}
            }
        }
        t.start()
        try {
            local.inputStream.copyTo(tcp.getOutputStream())
        } catch (_: Exception) {
        } finally {
            try { tcp.shutdownOutput() } catch (_: Exception) {}
        }
        t.join()
        tcp.close()
        local.close()
    }
}
