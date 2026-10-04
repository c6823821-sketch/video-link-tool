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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs one job per function. 下视频 / 下音频 / 转文字 each get their own coroutine,
 * their own notification and their own progress channel, so they can run together
 * and never overwrite each other's display.
 */
class TaskService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<TaskMode, Job>()
    private val generations = ConcurrentHashMap<TaskMode, AtomicInteger>()

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
            stopIfIdle()
            return START_NOT_STICKY
        }
        startForegroundCompat(mode, "准备中...", 0)

        TaskControl.cancel(mode)
        jobs[mode]?.cancel()
        val counter = generations.getOrPut(mode) { AtomicInteger(0) }
        val token = counter.incrementAndGet()
        val processId = "video-link-tool-" + mode.key + "-" + token
        TaskControl.begin(mode, processId)

        val job = scope.launch {
            val alive = { counter.get() == token && !TaskControl.isCancelled(mode) }
            try {
                if (alive()) TaskBus.update(mode, RunState.RUNNING, 0, "开始处理", "正在解析链接...")
                TaskRunner.run(this@TaskService, mode, input) { progress, detail ->
                    if (alive()) {
                        TaskBus.update(mode, RunState.RUNNING, progress, detail, "任务进行中")
                        updateNotification(mode, detail, progress)
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
                TaskControl.finish(mode, processId)
                if (counter.get() == token) {
                    jobs.remove(mode)
                    if (jobs.isEmpty()) stopIfIdle()
                }
            }
        }
        jobs[mode] = job
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        scope.cancel()
        super.onDestroy()
    }

    private fun stopIfIdle() {
        if (jobs.isEmpty()) {
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(CHANNEL_ID, "视频工具箱任务", NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundCompat(mode: TaskMode, text: String, progress: Int) {
        val notification = buildNotification(mode, text, progress)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID + mode.ordinal, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID + mode.ordinal, notification)
        }
    }

    private fun updateNotification(mode: TaskMode, text: String, progress: Int) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID + mode.ordinal, buildNotification(mode, text, progress))
    }

    private fun buildNotification(mode: TaskMode, text: String, progress: Int) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("视频工具箱 · " + modeLabel(mode))
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, progress.coerceIn(0, 100), progress <= 0)
            .build()

    private fun modeLabel(mode: TaskMode): String = when (mode) {
        TaskMode.VIDEO -> "下视频"
        TaskMode.AUDIO -> "下音频"
        TaskMode.TEXT -> "转文字"
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }
}