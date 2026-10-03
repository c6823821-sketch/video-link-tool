package com.c6823821.videolinktool

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.c6823821.videolinktool.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

        binding.modeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                binding.btnModeAudio.id -> TaskMode.AUDIO
                binding.btnModeText.id -> TaskMode.TEXT
                else -> TaskMode.VIDEO
            }
            updateMode(mode)
        }

        binding.etUrl.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val drawable = binding.etUrl.compoundDrawables[2]
                if (drawable != null && event.x >= binding.etUrl.width - binding.etUrl.paddingEnd - drawable.bounds.width()) {
                    handleInputEndIcon()
                    return@setOnTouchListener true
                }
            }
            false
        }

        binding.etUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = updateInputIcon()
        })
        updateInputIcon()

        binding.btnRun.setOnClickListener {
            val input = binding.etUrl.text?.toString()?.trim().orEmpty()
            if (input.isBlank()) {
                Toast.makeText(this, "先粘贴一个链接", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            TaskBus.reset(currentMode)
            TaskService.start(this, currentMode, input)
        }


        binding.btnOpenResult.setOnClickListener { openResult() }

        binding.btnCheckUpdate.setOnClickListener { checkForUpdate(true) }
        checkForUpdate(false)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                TaskBus.state.collect { render(it) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
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
        binding.tvProgressPercent.text = state.progress.toString() + "%"
        binding.tvStatus.text = state.title
        binding.tvDetail.text = state.detail
        val running = state.state == RunState.RUNNING
        binding.progressBar.visibility = if (running) android.view.View.VISIBLE else android.view.View.GONE
        binding.tvProgressPercent.visibility = if (running) android.view.View.VISIBLE else android.view.View.GONE
        binding.btnRun.isEnabled = !running
        binding.btnOpenResult.visibility = if (state.state == RunState.SUCCESS) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun checkForUpdate(manual: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            val info = runCatching { UpdateManager.check(this@MainActivity) }.getOrNull()
            withContext(Dispatchers.Main) {
                if (info == null) {
                    binding.updateDot.visibility = android.view.View.GONE
                    if (manual) Toast.makeText(this@MainActivity, "当前已经是最新版", Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                binding.updateDot.visibility = android.view.View.VISIBLE
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("发现新版本 " + info.version)
                    .setMessage("现在下载并安装更新？")
                    .setPositiveButton("下载更新") { _, _ -> downloadUpdate(info) }
                    .setNeutralButton("浏览器打开") { _, _ ->
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateManager.RELEASES_PAGE))) }
                    }
                    .setNegativeButton("稍后", null)
                    .show()
            }
        }
    }

    private fun downloadUpdate(info: UpdateManager.UpdateInfo) {
        binding.progressBar.visibility = android.view.View.VISIBLE
        binding.tvProgressPercent.visibility = android.view.View.VISIBLE
        binding.tvStatus.text = "正在下载更新"
        binding.btnCheckUpdate.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching {
                UpdateManager.download(this@MainActivity, info) { progress ->
                    runOnUiThread {
                        binding.progressBar.progress = progress
                        binding.tvProgressPercent.text = progress.toString() + "%"
                    }
                }
            }
            withContext(Dispatchers.Main) {
                binding.btnCheckUpdate.isEnabled = true
                binding.progressBar.visibility = android.view.View.GONE
                binding.tvProgressPercent.visibility = android.view.View.GONE
                result.onSuccess { file ->
                    binding.updateDot.visibility = android.view.View.GONE
                    binding.tvStatus.text = "更新包下载完成"
                    UpdateManager.install(this@MainActivity, file)
                }.onFailure {
                    binding.tvStatus.text = "更新下载失败"
                    Toast.makeText(this@MainActivity, it.message ?: "更新下载失败", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun updateInputIcon() {
        val icon = if (binding.etUrl.text.isNullOrBlank()) R.drawable.ic_paste else R.drawable.ic_clear
        binding.etUrl.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, icon, 0)
    }

    private fun handleInputEndIcon() {
        val value = binding.etUrl.text?.toString().orEmpty()
        if (value.isNotBlank()) {
            binding.etUrl.text?.clear()
            updateInputIcon()
            return
        }
        val clipboard = getSystemService(ClipboardManager::class.java)
        val clip = clipboard?.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).coerceToText(this).toString()
            if (text.isNotBlank()) {
                binding.etUrl.setText(text)
                binding.etUrl.setSelection(text.length)
                updateInputIcon()
            }
        }
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
