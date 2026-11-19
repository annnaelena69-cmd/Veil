package com.veil.browser

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import java.net.URLEncoder

/**
 * Runs in a dedicated :private process. On Android 9+, WebView receives a
 * separate data directory suffix before its first instance is created.
 * Closing this activity clears the private profile's cookies, cache and storage.
 */
class PrivateActivity : Activity() {
    private var browser: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WebView.setDataDirectorySuffix("veil_private")
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF050506.toInt())
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 8, 8, 8)
        }
        val address = EditText(this).apply {
            hint = "Private search or website"
            setSingleLine(true)
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF999999.toInt())
            setBackgroundColor(0xFF202024.toInt())
            imeOptions = EditorInfo.IME_ACTION_GO
        }
        val go = Button(this).apply { text = "Go" }
        bar.addView(address, LinearLayout.LayoutParams(0, 48, 1f))
        bar.addView(go)
        root.addView(bar)

        val view = WebView(this)
        browser = view
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.settings.allowFileAccess = false
        view.settings.allowContentAccess = false
        view.settings.javaScriptCanOpenWindowsAutomatically = false
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
        view.webChromeClient = WebChromeClient()
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                return uri.scheme != "https" && uri.scheme != "http"
            }
            override fun onPageStarted(v: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(v, url, favicon)
                if (url != null) address.setText(url)
            }
        }
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
        val close = Button(this).apply {
            text = "Close private session"
            setOnClickListener { finish() }
        }
        root.addView(close)
        setContentView(root)

        fun navigate(raw: String) {
            val value = raw.trim()
            if (value.isEmpty()) return
            val target = when {
                value.startsWith("https://") || value.startsWith("http://") -> value
                value.contains(".") && !value.contains(" ") -> "https://$value"
                else -> "https://duckduckgo.com/?q=" + URLEncoder.encode(value, "UTF-8")
            }
            view.loadUrl(target)
        }
        go.setOnClickListener { navigate(address.text.toString()) }
        address.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_GO) { navigate(address.text.toString()); true } else false
        }
        view.loadUrl("https://duckduckgo.com/")
        Toast.makeText(this, "Private session uses separate WebView storage", Toast.LENGTH_LONG).show()
    }

    override fun onBackPressed() {
        val view = browser
        if (view?.canGoBack() == true) view.goBack() else finish()
    }

    override fun onDestroy() {
        browser?.let {
            it.stopLoading()
            it.clearHistory()
            it.clearCache(true)
            it.clearFormData()
            it.destroy()
        }
        browser = null
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        super.onDestroy()
    }
}
