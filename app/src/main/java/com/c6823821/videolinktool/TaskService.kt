package com.c6823821.videolinktool

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class TaskService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val generation = java.util.concurrent.atomic.AtomicInteger(0)

    companion object {
        private const val CHANNEL_ID = "video_link_tool_tasks"
        private const val NOTIFICATION_ID = 4101
        const val EXTRA_MODE = "mode"
        const val EXTRA_INPUT = "input"

        fun start(context: Context, mode: TaskMode, input: String) {
            val intent = Intent(context, TaskService::class.java).apply {
                putExtra(EXTRA_MODE, mode.key)
                putExtra(EXTRA_INPUT, input)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        runCatching { YoutubeDlEngine.init(this) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mode = TaskMode.fromKey(intent?.getStringExtra(EXTRA_MODE))
        val input = intent?.getStringExtra(EXTRA_INPUT).orEmpty()
        if (input.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat("准备中...", 0)

        // Kill whatever is still running: cancelling the coroutine alone leaves the
        // native yt-dlp process and the HTTP download going, which is what made the
        // progress bars mix up and what ran the phone out of memory.
        TaskControl.cancel()
        job?.cancel()
        val token = generation.incrementAndGet()
        val processId = "video-link-tool-" + token
        TaskControl.begin(processId)

        job = scope.launch {
            val alive = { generation.get() == token && !TaskControl.cancelled }
            try {
                if (alive()) TaskBus.update(mode, RunState.RUNNING, 0, "开始处理", "正在解析链接...")
                TaskRunner.run(this@TaskService, mode, input) { progress, detail ->
                    if (alive()) {
                        TaskBus.update(mode, RunState.RUNNING, progress, detail, "任务进行中")
                        updateNotification(detail, progress)
                    }
                }
            } catch (e: NeedCookiesException) {
                if (alive()) TaskBus.update(mode, RunState.NEED_COOKIE, 0, "抖音需要验证", e.message ?: "请先刷新抖音验证")
            } catch (e: Throwable) {
                if (alive()) {
                    val message = e.message?.take(300) ?: "处理失败"
                    TaskBus.update(mode, RunState.ERROR, 0, "处理失败", message)
                }
            } finally {
                TaskControl.finish(processId)
                if (generation.get() == token) {
                    stopForegroundCompat()
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(CHANNEL_ID, "视频工具箱任务", NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundCompat(text: String, progress: Int) {
        val notification = buildNotification(text, progress)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String, progress: Int) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text, progress))
    }

    private fun buildNotification(text: String, progress: Int) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("视频工具箱")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, progress.coerceIn(0, 100), progress <= 0)
            .build()

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }
}
