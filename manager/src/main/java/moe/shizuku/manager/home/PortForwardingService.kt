package moe.shizuku.manager.home

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.R
import moe.shizuku.manager.utils.CdpInjection

import android.content.pm.ServiceInfo
import android.util.Log
import android.widget.Toast

class PortForwardingService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var isForwarding = false
    private var forwardingJob: Job? = null

    companion object {
        const val ACTION_START_FORWARDING = "moe.shizuku.manager.action.START_FORWARDING"
        const val ACTION_STOP_FORWARDING = "moe.shizuku.manager.action.STOP_FORWARDING"
        const val ACTION_SERVICE_STOPPED = "moe.shizuku.manager.action.SERVICE_STOPPED"
        const val EXTRA_PID = "extra_pid"
        const val EXTRA_TARGET_PORT = "extra_target_port"

        private const val NOTIFICATION_ID = 9222
        private const val CHANNEL_ID = "port_forwarding_channel"

        fun start(context: Context, pid: Int, targetPort: Int = 9222) {
            val intent = Intent(context, PortForwardingService::class.java).apply {
                action = ACTION_START_FORWARDING
                putExtra(EXTRA_PID, pid)
                putExtra(EXTRA_TARGET_PORT, targetPort)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, PortForwardingService::class.java).apply {
                action = ACTION_STOP_FORWARDING
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_FORWARDING -> {
                val pid = intent.getIntExtra(EXTRA_PID, -1)
                val targetPort = intent.getIntExtra(EXTRA_TARGET_PORT, 9222)
                if (pid != -1 && !isForwarding) {
                    startForwarding(pid, targetPort)
                }
            }
            ACTION_STOP_FORWARDING -> {
                stopForwardingAndSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForwarding(pid: Int, targetPort: Int) {
        Log.i("PortForward", "startForwarding called with pid=$pid, targetPort=$targetPort")
        isForwarding = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, createNotification(requireNotNull(getString(R.string.vconsole_forwarding))), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, createNotification(requireNotNull(getString(R.string.vconsole_forwarding))))
        }

        Toast.makeText(this, "开始转发 PID $pid 到端口 $targetPort", Toast.LENGTH_SHORT).show()

        forwardingJob = serviceScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    CdpInjection.startForwarding(this@PortForwardingService, pid, targetPort)
                }
            } catch (e: Exception) {
                // If forwarding fails or is interrupted
                Log.e("PortForward", "Forwarding failed or interrupted", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@PortForwardingService, "转发失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    Log.i("PortForward", "Stopping forwarding.")
                    stopForwardingAndSelf()
                }
            }
        }
    }

    private fun stopForwardingAndSelf() {
        if (!isForwarding) return
        isForwarding = false
        forwardingJob?.cancel()
        serviceScope.launch {
            try {
                CdpInjection.stopForwarding()
            } catch (e: Exception) {
            } finally {
                stopForeground(true)
                // 发送广播通知 Activity UI 更新
                val intent = Intent(ACTION_SERVICE_STOPPED)
                intent.setPackage(packageName)
                sendBroadcast(intent)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@PortForwardingService, "停止转发", Toast.LENGTH_SHORT).show()
                }
                stopSelf()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.vconsole_forwarding_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.vconsole_forwarding_channel_desc)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String): android.app.Notification {
        val stopIntent = Intent(this, PortForwardingService::class.java).apply {
            action = ACTION_STOP_FORWARDING
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher) // 确保使用有效的图标
            .addAction(0, getString(R.string.vconsole_stop_forwarding), stopPendingIntent)
            // 不设置 setOngoing(true)
            .setDeleteIntent(stopPendingIntent) // 当用户划走/侧滑清除通知时执行相同逻辑
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}