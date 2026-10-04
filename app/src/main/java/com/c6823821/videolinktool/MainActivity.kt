package com.c6823821.videolinktool

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
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
    private var pendingInstall: java.io.File? = null
    private var displayedState = TaskUiState()
    private var previewKey: String? = null

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.tvSubtitle.text = "三个功能完全独立，互不串联。  当前版本 v" + appVersionName()
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

        binding.btnInputAction.setOnClickListener { handleInputEndIcon() }

        binding.etUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = updateInputIcon()
        })
        updateInputIcon()

        binding.btnRun.setOnClickListener { startCurrentTaskIfPossible() }


        binding.btnOpenResult.setOnClickListener { openResult() }
        binding.btnCopyResult.setOnClickListener { copyResultText() }

        binding.btnCheckUpdate.setOnClickListener { checkForUpdate(true) }
        checkForUpdate(false)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                TaskBus.states.collect { states ->
                    render(states[currentMode] ?: TaskUiState(mode = currentMode))
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        if (text.isNotBlank()) binding.etUrl.setText(text)
    }

    private fun startCurrentTaskIfPossible() {
        val input = binding.etUrl.text?.toString()?.trim().orEmpty()
        if (input.isBlank()) return
        val running = TaskBus.stateOf(currentMode)
        if (running.state == RunState.RUNNING) {
            AlertDialog.Builder(this)
                .setTitle("还有一个任务没做完")
                .setMessage("现在正在进行：" + running.detail + "。要么等它做完，要么中断它再开始新的。")
                .setPositiveButton("中断并开始新的") { _, _ ->
                    TaskControl.cancel(currentMode)
                    startTask(input)
                }
                .setNegativeButton("继续等它做完", null)
                .show()
            return
        }
        startTask(input)
    }

    private fun startTask(input: String) {
        TaskBus.reset(currentMode)
        TaskService.start(this, currentMode, input)
    }

    private fun updateMode(mode: TaskMode) {
        currentMode = mode
        val hint = when (mode) {
            TaskMode.VIDEO -> "只下载无水印视频源文件，不提取音频。"
            TaskMode.AUDIO -> "只提取音频，不保存整段视频。"
            TaskMode.TEXT -> "只输出文字，结果显示在下方框里，不自动保存。模型内置，不用下载。"
        }
        binding.tvModeHint.text = hint
        render(TaskBus.stateOf(mode))
        binding.btnRun.text = when (mode) {
            TaskMode.VIDEO -> "开始下载视频"
            TaskMode.AUDIO -> "开始提取音频"
            TaskMode.TEXT -> "开始转文字"
        }
    }

    private fun render(state: TaskUiState) {
        displayedState = state
        binding.progressBar.progress = state.progress
        binding.tvProgressPercent.text = state.progress.toString() + "%"
        binding.tvStatus.text = state.title
        binding.tvDetail.text = state.detail
        val running = state.state == RunState.RUNNING
        binding.progressBar.visibility = if (running) android.view.View.VISIBLE else android.view.View.GONE
        binding.tvProgressPercent.visibility = if (running) android.view.View.VISIBLE else android.view.View.GONE
        binding.btnRun.isEnabled = !running

        val succeeded = state.state == RunState.SUCCESS
        val showText = succeeded && state.mode == TaskMode.TEXT && !state.text.isNullOrBlank()
        binding.cardResult.visibility = if (showText) android.view.View.VISIBLE else android.view.View.GONE
        if (showText) binding.tvResultText.text = state.text

        val canOpen = succeeded && state.mode != TaskMode.TEXT &&
            (!state.outputUri.isNullOrBlank() || !state.outputPath.isNullOrBlank())
        binding.btnOpenResult.visibility = if (canOpen) android.view.View.VISIBLE else android.view.View.GONE
        updatePreview(state)
        binding.btnOpenResult.text = when (state.mode) {
            TaskMode.AUDIO -> "在这个页面打开这段音频"
            TaskMode.VIDEO -> "在这个页面打开这个视频/图集"
            TaskMode.TEXT -> "打开已保存的文件"
        }
    }

    private fun updatePreview(state: TaskUiState) {
        val uriText = state.previewUri
        if (state.state != RunState.SUCCESS || uriText.isNullOrBlank()) {
            if (binding.cardPreview.visibility == android.view.View.VISIBLE) {
                runCatching { binding.videoPreview.stopPlayback() }
            }
            binding.cardPreview.visibility = android.view.View.GONE
            previewKey = null
            return
        }
        binding.cardPreview.visibility = android.view.View.VISIBLE
        if (previewKey == uriText) return
        previewKey = uriText
        val uri = if (uriText.startsWith("content://")) {
            Uri.parse(uriText)
        } else {
            runCatching { OutputStore.legacyUri(this, uriText) }.getOrNull()
        }
        if (uri == null) {
            binding.cardPreview.visibility = android.view.View.GONE
            return
        }
        val controller = android.widget.MediaController(this)
        controller.setAnchorView(binding.videoPreview)
        binding.videoPreview.setMediaController(controller)
        binding.videoPreview.setOnPreparedListener { player ->
            player.isLooping = false
            binding.tvPreviewHint.text = "预览"
            runCatching { binding.videoPreview.start() }
        }
        binding.videoPreview.setOnErrorListener { _, _, _ ->
            binding.cardPreview.visibility = android.view.View.GONE
            Toast.makeText(this, "这个文件没法在页面里播放，可以点下面的按钮打开", Toast.LENGTH_LONG).show()
            true
        }
        binding.videoPreview.setVideoURI(uri)
    }
    private fun checkForUpdate(manual: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            val info = runCatching { UpdateManager.check(this@MainActivity) }.getOrNull()
            val cached = info?.let { UpdateManager.cachedApk(this@MainActivity, it.version) }
            withContext(Dispatchers.Main) {
                if (info == null) {
                    binding.updateDot.visibility = android.view.View.GONE
                    if (manual) Toast.makeText(this@MainActivity, "当前已经是最新版", Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                binding.updateDot.visibility = android.view.View.VISIBLE
                if (!manual && UpdateManager.skippedVersion(this@MainActivity) == info.version) {
                    return@withContext
                }
                val ready = cached != null
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("发现新版本 " + info.version)
                    .setMessage(if (ready) "更新包已经下载好了，现在安装？" else "现在下载并安装更新？")
                    .setPositiveButton(if (ready) "立即安装" else "下载更新") { _, _ -> downloadUpdate(info) }
                    .setNeutralButton("浏览器打开") { _, _ ->
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateManager.RELEASES_PAGE))) }
                    }
                    .setNegativeButton("稍后") { _, _ ->
                        if (!manual) UpdateManager.skipVersion(this@MainActivity, info.version)
                    }
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
                    binding.tvStatus.text = "更新包已就绪"
                    launchInstall(file)
                }.onFailure {
                    binding.tvStatus.text = "更新下载失败"
                    Toast.makeText(this@MainActivity, it.message ?: "更新下载失败", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun launchInstall(file: java.io.File) {
        val started = runCatching { UpdateManager.install(this, file) }
            .getOrElse {
                Toast.makeText(this, "无法调起安装：" + (it.message ?: "未知错误"), Toast.LENGTH_LONG).show()
                false
            }
        if (started) {
            pendingInstall = null
            binding.updateDot.visibility = android.view.View.GONE
            binding.tvStatus.text = "请在系统安装界面点“安装”"
        } else {
            pendingInstall = file
            binding.tvStatus.text = "请先允许“安装未知应用”，返回后会自动继续"
        }
    }

    override fun onResume() {
        super.onResume()
        val file = pendingInstall ?: return
        if (!UpdateManager.isInstallAllowed(this)) return
        pendingInstall = null
        launchInstall(file)
    }

    private fun appVersionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()

    private fun updateInputIcon() {
        val empty = binding.etUrl.text.isNullOrBlank()
        binding.btnInputAction.text = if (empty) "粘贴" else "清空"
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
        if (clip == null || clip.itemCount == 0) {
            Toast.makeText(this, "剪贴板里没有可粘贴的内容", Toast.LENGTH_SHORT).show()
            return
        }
        val text = clip.getItemAt(0).coerceToText(this).toString().trim()
        if (text.isBlank()) {
            Toast.makeText(this, "剪贴板里没有文字链接", Toast.LENGTH_SHORT).show()
            return
        }
        binding.etUrl.setText(text)
        binding.etUrl.setSelection(text.length)
        updateInputIcon()
        Toast.makeText(this, "已粘贴", Toast.LENGTH_SHORT).show()
    }

    private fun copyResultText() {
        val text = displayedState.text.orEmpty()
        if (text.isBlank()) return
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("识别结果", text))
        Toast.makeText(this, "文字已复制", Toast.LENGTH_SHORT).show()
    }
    private fun openResult() {
        val state = displayedState
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
