package com.c6823821.videolinktool

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.c6823821.videolinktool.databinding.ActivityCookieBinding

class CookieActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCookieBinding

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCookieBinding.inflate(layoutInflater)
        setContentView(binding.root)

        CookieManager.getInstance().setAcceptCookie(true)
        binding.webView.settings.javaScriptEnabled = true
        binding.webView.settings.domStorageEnabled = true
        binding.webView.settings.userAgentString = HttpClient.MOBILE_UA
        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                binding.tvCookieHint.text = "页面加载完成。完成抖音验证后，点下方保存。"
            }
        }
        binding.webView.loadUrl("https://www.douyin.com/")

        binding.btnSaveCookie.setOnClickListener {
            val manager = CookieManager.getInstance()
            val a = manager.getCookie("https://www.douyin.com").orEmpty()
            val b = manager.getCookie("https://www.iesdouyin.com").orEmpty()
            val header = listOf(a, b).filter { it.isNotBlank() }.joinToString("; ")
            runCatching { CookieStore.saveFromHeader(this, header) }
                .onSuccess {
                    Toast.makeText(this, "抖音验证已保存", Toast.LENGTH_SHORT).show()
                    finish()
                }
                .onFailure { Toast.makeText(this, it.message ?: "保存失败", Toast.LENGTH_LONG).show() }
        }
        binding.btnCancelCookie.setOnClickListener { finish() }
    }

    override fun onDestroy() {
        binding.webView.destroy()
        super.onDestroy()
    }
}
