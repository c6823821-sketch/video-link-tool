package com.c6823821.videolinktool

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.c6823821.videolinktool.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var currentMode = TaskMode.VIDEO

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        askPermissionsIfNeeded()
        binding.btnModeVideo.isChecked = true
        updateMode(TaskMode.VIDEO)

        binding.modeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                binding.btnModeAudio.id -> TaskMode.AUDIO
                binding.btnModeText.id -> TaskMode.TEXT
                else -> TaskMode.VIDEO
            }
            updateMode(mode)
        }

        binding.btnRun.setOnClickListener {
            val input = binding.etUrl.text?.toString()?.trim().orEmpty()
            if (input.isBlank()) {
                Toast.makeText(this, "先粘贴一个链接", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            TaskBus.reset(currentMode)
            TaskService.start(this, currentMode, input)
        }

        binding.btnDouyinCookie.setOnClickListener {
            startActivity(Intent(this, CookieActivity::class.java))
        }

        binding.btnOpenResult.setOnClickListener { openResult() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                TaskBus.state.collect { render(it) }
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        if (text.isNotBlank()) binding.etUrl.setText(text)
    }

    private fun updateMode(mode: TaskMode) {
        currentMode = mode
        val hint = when (mode) {
            TaskMode.VIDEO -> "只下载无水印视频源文件，不提取音频。"
            TaskMode.AUDIO -> "只提取音频，不保存整段视频。"
            TaskMode.TEXT -> "只输出文字。首次使用会下载约 230MB 离线语音模型。"
        }
        binding.tvModeHint.text = hint
        binding.btnRun.text = when (mode) {
            TaskMode.VIDEO -> "开始下载视频"
            TaskMode.AUDIO -> "开始提取音频"
            TaskMode.TEXT -> "开始转文字"
        }
    }

    private fun render(state: TaskUiState) {
        binding.progressBar.progress = state.progress
        binding.tvStatus.text = state.title
        binding.tvDetail.text = state.detail
        val running = state.state == RunState.RUNNING
        binding.btnRun.isEnabled = !running
        binding.btnOpenResult.visibility = if (state.state == RunState.SUCCESS) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun openResult() {
        val state = TaskBus.state.value
        val uriText = state.outputUri
        val path = state.outputPath
        val mime = when (state.mode) {
            TaskMode.VIDEO -> "video/mp4"
            TaskMode.AUDIO -> "audio/mp4"
            TaskMode.TEXT -> "text/plain"
        }
        try {
            val uri: Uri = when {
                uriText != null && uriText.startsWith("content://") -> Uri.parse(uriText)
                path != null -> OutputStore.legacyUri(this, path)
                else -> return
            }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "打开结果"))
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开结果：" + (e.message ?: "没有可用应用"), Toast.LENGTH_LONG).show()
        }
    }

    private fun askPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1001)
        }
    }
}
