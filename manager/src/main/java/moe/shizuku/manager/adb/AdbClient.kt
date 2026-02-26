package moe.shizuku.manager.adb

import android.util.Log
import moe.shizuku.manager.adb.AdbProtocol.ADB_AUTH_RSAPUBLICKEY
import moe.shizuku.manager.adb.AdbProtocol.ADB_AUTH_SIGNATURE
import moe.shizuku.manager.adb.AdbProtocol.ADB_AUTH_TOKEN
import moe.shizuku.manager.adb.AdbProtocol.A_AUTH
import moe.shizuku.manager.adb.AdbProtocol.A_CLSE
import moe.shizuku.manager.adb.AdbProtocol.A_CNXN
import moe.shizuku.manager.adb.AdbProtocol.A_MAXDATA
import moe.shizuku.manager.adb.AdbProtocol.A_OKAY
import moe.shizuku.manager.adb.AdbProtocol.A_OPEN
import moe.shizuku.manager.adb.AdbProtocol.A_STLS
import moe.shizuku.manager.adb.AdbProtocol.A_STLS_VERSION
import moe.shizuku.manager.adb.AdbProtocol.A_VERSION
import moe.shizuku.manager.adb.AdbProtocol.A_WRTE
import moe.shizuku.manager.adb.AdbProtocol.FORWARD_LOCAL_TCP_PREFIX
import moe.shizuku.manager.adb.AdbProtocol.FORWARD_REMOTE_TCP_PREFIX
import moe.shizuku.manager.adb.AdbProtocol.FORWARD_COMMAND_CREATE
import moe.shizuku.manager.adb.AdbProtocol.FORWARD_COMMAND_REMOVE
import moe.shizuku.manager.adb.AdbProtocol.FORWARD_COMMAND_LIST
import moe.shizuku.manager.ktx.logd
import rikka.core.util.BuildUtils
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket

private const val TAG = "AdbClient"

class AdbClient(private val host: String, private val port: Int, private val key: AdbKey) : Closeable {

    private lateinit var socket: Socket
    private lateinit var plainInputStream: DataInputStream
    private lateinit var plainOutputStream: DataOutputStream

    private var useTls = false

    private lateinit var tlsSocket: SSLSocket
    private lateinit var tlsInputStream: DataInputStream
    private lateinit var tlsOutputStream: DataOutputStream

    private val inputStream get() = if (useTls) tlsInputStream else plainInputStream
    private val outputStream get() = if (useTls) tlsOutputStream else plainOutputStream

    private val streams = java.util.concurrent.ConcurrentHashMap<Int, Stream>()
    private val pendingOpens = java.util.concurrent.ConcurrentHashMap<Int, java.util.concurrent.CompletableFuture<Int>>()
    private var lastLocalId = 1
    private var running = false
    private var readerThread: Thread? = null

    class Stream(val localId: Int, var remoteId: Int, val onData: (ByteArray) -> Unit, val onClose: () -> Unit) {
        var closed = false
    }

    fun connect() {
        socket = Socket(host, port)
        socket.tcpNoDelay = true
        plainInputStream = DataInputStream(socket.getInputStream())
        plainOutputStream = DataOutputStream(socket.getOutputStream())

        write(A_CNXN, A_VERSION, A_MAXDATA, "host::")

        var message = readSync()
        if (message.command == A_STLS) {
            if (!BuildUtils.atLeast29) {
                error("Connect to adb with TLS is not supported before Android 29")
            }
            write(A_STLS, A_STLS_VERSION, 0)

            val sslContext = key.sslContext
            tlsSocket = sslContext.socketFactory.createSocket(socket, host, port, true) as SSLSocket
            tlsSocket.startHandshake()
            Log.d(TAG, "Handshake succeeded.")

            tlsInputStream = DataInputStream(tlsSocket.inputStream)
            tlsOutputStream = DataOutputStream(tlsSocket.outputStream)
            useTls = true

            message = readSync()
        } else if (message.command == A_AUTH) {
            if (message.arg0 != ADB_AUTH_TOKEN) error("not A_AUTH ADB_AUTH_TOKEN")
            write(A_AUTH, ADB_AUTH_SIGNATURE, 0, key.sign(message.data!!))

            message = readSync()
            if (message.command == A_AUTH && message.arg0 == ADB_AUTH_TOKEN) {
                write(A_AUTH, ADB_AUTH_RSAPUBLICKEY, 0, key.adbPublicKey)
                message = readSync()
            }
        }

        if (message.command != A_CNXN) error("not A_CNXN, it is ${message.toStringShort()}")
        
        running = true
        readerThread = Thread({
            try {
                while (running) {
                    val msg = readSync()
                    handleMessage(msg)
                }
            } catch (e: Exception) {
                if (running) {
                    Log.e(TAG, "Reader thread error", e)
                }
            } finally {
                close()
            }
        }, "AdbClient-Reader").apply { start() }
    }

    private fun handleMessage(message: AdbMessage) {
        when (message.command) {
            A_OKAY -> {
                val localId = message.arg1
                val remoteId = message.arg0
                pendingOpens.remove(localId)?.complete(remoteId)
                streams[localId]?.remoteId = remoteId
            }
            A_WRTE -> {
                val localId = message.arg1
                val remoteId = message.arg0
                val stream = streams[localId]
                if (stream != null && !stream.closed) {
                    message.data?.let { stream.onData(it) }
                    write(A_OKAY, localId, remoteId)
                } else {
                    write(A_CLSE, localId, remoteId)
                }
            }
            A_CLSE -> {
                val localId = message.arg1
                val remoteId = message.arg0
                pendingOpens.remove(localId)?.completeExceptionally(Exception("Stream closed by remote"))
                val stream = streams.remove(localId)
                stream?.let {
                    it.closed = true
                    it.onClose()
                }
            }
        }
    }

    private fun openStream(destination: String, onData: (ByteArray) -> Unit, onClose: () -> Unit): Stream {
        val localId = synchronized(this) { lastLocalId++ }
        val future = java.util.concurrent.CompletableFuture<Int>()
        pendingOpens[localId] = future
        
        val stream = Stream(localId, 0, onData, onClose)
        streams[localId] = stream
        
        write(A_OPEN, localId, 0, destination + "\u0000")
        
        try {
            val remoteId = future.get(10, java.util.concurrent.TimeUnit.SECONDS)
            stream.remoteId = remoteId
            return stream
        } catch (e: Exception) {
            streams.remove(localId)
            pendingOpens.remove(localId)
            throw e
        }
    }

    fun createForward(localPort: Int, remoteDestination: String): Closeable {
        val serverSocket = ServerSocket(localPort, 50, java.net.InetAddress.getByName("127.0.0.1"))
        val connectionThreads = java.util.Collections.synchronizedList(mutableListOf<Thread>())
        val thread = Thread({
            try {
                while (!serverSocket.isClosed) {
                    val client = serverSocket.accept()
                    val t = Thread {
                        try {
                            handleForwardConnection(client, remoteDestination)
                        } finally {
                            connectionThreads.remove(Thread.currentThread())
                        }
                    }
                    connectionThreads.add(t)
                    t.start()
                }
            } catch (e: Exception) {
                if (!serverSocket.isClosed) Log.e(TAG, "Forward server error", e)
            }
        }, "AdbForward-$localPort")
        thread.start()

        return Closeable {
            try { serverSocket.close() } catch (e: Exception) {}
            thread.interrupt()
            // 等待所有活跃的 connection 线程完成清理，再让调用方关闭 adbClient
            synchronized(connectionThreads) { connectionThreads.toList() }.forEach {
                try { it.join(3000) } catch (_: Exception) {}
            }
        }
    }

    private fun handleForwardConnection(client: Socket, destination: String) {
        var stream: Stream? = null
        try {
            client.tcpNoDelay = true
            val inputStream = client.getInputStream()
            val outputStream = client.getOutputStream()
            
            stream = openStream(destination, { data ->
                try {
                    outputStream.write(data)
                    outputStream.flush()
                } catch (e: Exception) {
                    closeStream(stream)
                }
            }, {
                try { client.close() } catch (e: Exception) {}
            })
            
            val buffer = ByteArray(A_MAXDATA)
            while (running && !stream.closed) {
                val read = inputStream.read(buffer)
                if (read == -1) break
                if (read > 0) {
                    write(A_WRTE, stream.localId, stream.remoteId, buffer.copyOf(read))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Forward connection error", e)
        } finally {
            closeStream(stream)
            try { client.close() } catch (e: Exception) {}
        }
    }

    private fun closeStream(stream: Stream?) {
        if (stream == null || stream.closed) return
        stream.closed = true
        try { write(A_CLSE, stream.localId, stream.remoteId) } catch (_: Exception) {}
        streams.remove(stream.localId)
        try { stream.onClose() } catch (_: Exception) {}
    }

    fun shellCommand(command: String, listener: ((ByteArray) -> Unit)?) {
        val lock = Object()
        var finished = false
        val stream = openStream("shell:$command", { data ->
            listener?.invoke(data)
        }, {
            synchronized(lock) {
                finished = true
                lock.notifyAll()
            }
        })
        
        synchronized(lock) {
            while (!finished) {
                lock.wait(100)
            }
        }
    }

    private fun write(command: Int, arg0: Int, arg1: Int, data: ByteArray? = null) = write(AdbMessage(command, arg0, arg1, data))
    private fun write(command: Int, arg0: Int, arg1: Int, data: String) = write(AdbMessage(command, arg0, arg1, data))

    private fun write(message: AdbMessage) {
        synchronized(outputStream) {
            outputStream.write(message.toByteArray())
            outputStream.flush()
        }
        Log.d(TAG, "write ${message.toStringShort()}")
    }

    private fun readSync(): AdbMessage {
        val buffer = ByteBuffer.allocate(AdbMessage.HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN)
        inputStream.readFully(buffer.array(), 0, 24)
        val command = buffer.int
        val arg0 = buffer.int
        val arg1 = buffer.int
        val dataLength = buffer.int
        val checksum = buffer.int
        val magic = buffer.int
        val data: ByteArray? = if (dataLength > 0) {
            ByteArray(dataLength).also { inputStream.readFully(it, 0, dataLength) }
        } else null
        val message = AdbMessage(command, arg0, arg1, dataLength, checksum, magic, data)
        message.validateOrThrow()
        Log.d(TAG, "read ${message.toStringShort()}")
        return message
    }

    override fun close() {
        if (!running) return
        running = false
        readerThread?.interrupt()
        streams.values.forEach { 
            try { it.onClose() } catch (e: Exception) {}
        }
        streams.clear()
        try { socket.close() } catch (e: Exception) {}
    }
}
