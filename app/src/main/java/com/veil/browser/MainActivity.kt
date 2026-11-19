package com.veil.browser

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import android.content.ClipboardManager
import android.content.ClipData
import android.os.Bundle
import android.os.Environment
import android.os.Build
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.JavascriptInterface
import android.webkit.WebViewClient
import android.webkit.PermissionRequest
import android.webkit.GeolocationPermissions
import android.widget.*
import android.widget.FrameLayout
import android.util.Base64
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var root: LinearLayout
    private lateinit var web: WebView
    private lateinit var address: EditText
    private lateinit var status: TextView
    private val black: Int get() = when (prefs.getString("theme", "Obsidian Full Black")) {
        "Midnight Dark" -> Color.rgb(12, 16, 25)
        "Minimal Grey" -> Color.rgb(28, 28, 31)
        "Light" -> Color.rgb(245, 245, 248)
        "Ocean Blue" -> Color.rgb(7, 27, 45)
        "Forest" -> Color.rgb(16, 37, 30)
        "Sunset" -> Color.rgb(42, 23, 27)
        else -> Color.rgb(4, 4, 5)
    }
    private val panel: Int get() = when (prefs.getString("theme", "Obsidian Full Black")) {
        "Midnight Dark" -> Color.rgb(24, 31, 45)
        "Minimal Grey" -> Color.rgb(43, 43, 47)
        "Light" -> Color.rgb(225, 225, 232)
        "Ocean Blue" -> Color.rgb(15, 49, 72)
        "Forest" -> Color.rgb(26, 57, 43)
        "Sunset" -> Color.rgb(67, 38, 42)
        else -> Color.rgb(19, 19, 22)
    }
    private val foreground: Int get() = if (prefs.getString("theme", "Obsidian Full Black") == "Light") Color.rgb(20, 20, 24) else Color.rgb(240, 240, 244)
    private val accentColor: Int get() = when (prefs.getInt("accent", 0)) {
        1 -> Color.rgb(95, 190, 255)
        2 -> Color.rgb(50, 205, 130)
        3 -> Color.rgb(235, 170, 65)
        4 -> Color.rgb(230, 115, 155)
        else -> Color.rgb(190, 195, 205)
    }
    private val tabs = mutableListOf<WebView>()
    private var activeTab = 0
    private lateinit var webHost: FrameLayout
    private var cachedFilterEngine: FilterEngine? = null
    private var cachedFilterStamp: Long = -1L
    private var cachedBlockedDomains: Set<String> = emptySet()
    private var cachedScriptletEngine: ScriptletEngine? = null
    private var pendingMediaRequest: PermissionRequest? = null
    private var pendingMediaResources: Array<String> = emptyArray()
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null
    private var pendingGeoOrigin: String = ""
    private var fullscreenView: android.view.View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    private fun filterEngine(): FilterEngine {
        val stamp = prefs.getLong("filters_updated_at", 0L)
        if (cachedFilterEngine == null || cachedFilterStamp != stamp) {
            val rules = (prefs.getString("downloaded_filter_rules", "") ?: "").lines().filter { it.isNotBlank() }
            val legacy = (prefs.getString("downloaded_block_domains", "") ?: "").lines().filter { it.isNotBlank() }.map { "||" + it + "^" }
            cachedFilterEngine = FilterEngine(rules + legacy)
            cachedBlockedDomains = (prefs.getString("downloaded_block_domains", "") ?: "").lineSequence().filter { it.isNotBlank() }.toSet()
            cachedScriptletEngine = ScriptletEngine(rules)
            cachedFilterStamp = stamp
        }
        return cachedFilterEngine!!
    }

    // VEIL v1 starts with 50 language choices. Translation coverage is tracked separately.
    private val languageCodes = listOf(
        "en","id","ms","zh","ja","ko","es","fr","de","ar"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("veil_local_settings", MODE_PRIVATE)
        if (prefs.getString("language", null) == null) {
            chooseLanguage(firstRun = true)
        } else {
            applyAppLocale()
            buildBrowser()
            val lastPage = prefs.getString("last_page", null)
            if (!lastPage.isNullOrBlank() && (lastPage.startsWith("https://") || lastPage.startsWith("http://"))) {
                web.loadUrl(lastPage)
            }
        }
        refreshFiltersIfDue()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun label(s: String, size: Float = 14f) = TextView(this).apply {
        text = s; textSize = size; setTextColor(this@MainActivity.foreground); gravity = Gravity.CENTER
        setPadding(dp(8), dp(8), dp(8), dp(8))
    }
    private fun action(s: String, callback: () -> Unit) = label(s).apply {
        setBackgroundColor(panel); setTextColor(accentColor); setOnClickListener { callback() }
    }
    private fun appLocale(): Locale = Locale.forLanguageTag(prefs.getString("language", "en") ?: "en")

    private fun tr(en: String, id: String): String =
        if (appLocale().language == "id") id else en

    @Suppress("DEPRECATION")
    private fun applyAppLocale() {
        val locale = appLocale()
        Locale.setDefault(locale)
        val config = resources.configuration
        config.setLocale(locale)
        resources.updateConfiguration(config, resources.displayMetrics)
    }

    private fun chooseLanguage(firstRun: Boolean = false) {
        val locales = languageCodes.map { Locale.forLanguageTag(it) }
        val names = locales.map { locale ->
            locale.getDisplayName(locale).replaceFirstChar { ch -> ch.uppercase(locale) }
        }
        val current = prefs.getString("language", "en") ?: "en"
        val selected = locales.indexOfFirst { it.toLanguageTag().equals(current, true) }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(if (firstRun) "VEIL • Choose app language" else tr("App language", "Bahasa aplikasi"))
            .setSingleChoiceItems(names.toTypedArray(), selected) { dialog, index ->
                prefs.edit().putString("language", locales[index].toLanguageTag()).apply()
                dialog.dismiss()
                applyAppLocale()
                recreate()
            }
            .setNegativeButton(if (firstRun) null else tr("Cancel", "Batal"), null)
            .setCancelable(!firstRun).show()
    }

    private fun buildBrowser() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(black)
            fitsSystemWindows = true
        }

        // Top address bar stays outside the WebView, so page content cannot cover it.
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(7), dp(12), dp(7))
            setBackgroundColor(black)
        }
        val addressPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(13), 0, dp(8), 0)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(panel)
                cornerRadius = dp(17).toFloat()
            }
        }
        address = EditText(this).apply {
            isSingleLine = true
            textSize = 15f
            setTextColor(this@MainActivity.foreground)
            setHintTextColor(Color.GRAY)
            hint = tr("Search or enter address", "Cari atau masukkan alamat")
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setBackgroundColor(Color.TRANSPARENT)
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSelectAllOnFocus(true)
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_GO || id == EditorInfo.IME_ACTION_SEARCH || id == EditorInfo.IME_ACTION_DONE) {
                    navigate(text.toString())
                    true
                } else false
            }
        }
        addressPill.addView(address, LinearLayout.LayoutParams(0, dp(48), 1f))
        addressPill.addView(action("↻") { if (web.url.isNullOrBlank() || web.url!!.contains("veil.local")) home() else web.reload() }, LinearLayout.LayoutParams(dp(38), dp(42)))
        topBar.addView(addressPill, LinearLayout.LayoutParams(-1, dp(54)))
        root.addView(topBar, LinearLayout.LayoutParams(-1, dp(62)))

        // Web content occupies only the middle area. Bottom navigation is a native view,
        // not HTML inside the page, so taps remain reliable on every website.
        webHost = FrameLayout(this)
        tabs.forEach { old -> runCatching { old.stopLoading(); old.destroy() } }
        tabs.clear()
        web = createBrowserWebView()
        tabs.add(web)
        activeTab = 0
        webHost.addView(web, FrameLayout.LayoutParams(-1, -1))
        root.addView(webHost, LinearLayout.LayoutParams(-1, 0, 1f))

        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(5), dp(4), dp(5), dp(4))
            setBackgroundColor(black)
            elevation = dp(12).toFloat()
        }
        fun navItem(symbol: String, description: String, onTap: () -> Unit): TextView =
            TextView(this).apply {
                text = symbol
                contentDescription = description
                textSize = 23f
                gravity = Gravity.CENTER
                setTextColor(accentColor)
                isClickable = true
                isFocusable = true
                background = android.util.TypedValue().let {
                    val out = android.util.TypedValue()
                    theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, out, true)
                    if (out.resourceId != 0) resources.getDrawable(out.resourceId, theme) else null
                }
                setOnClickListener { onTap() }
                layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f)
            }

        bottomBar.addView(navItem("⌂", "Home") { home() })
        bottomBar.addView(navItem("▣", "Bookmarks") { showBookmarks() })
        bottomBar.addView(navItem("⌕", "Search") {
            address.requestFocus()
            address.setSelection(address.text.length)
            (getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                ?.showSoftInput(address, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        })
        bottomBar.addView(navItem("▢", "Tabs") { tabsDialog() })
        bottomBar.addView(navItem("⋮", "Menu") { showBrowserMenu(bottomBar.getChildAt(4)) })
        root.addView(bottomBar, LinearLayout.LayoutParams(-1, dp(58)))

        status = TextView(this).apply { visibility = android.view.View.GONE }
        setContentView(root)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        home()
    }

    private fun showBrowserMenu(anchor: android.view.View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add("New tab").setOnMenuItemClickListener { newTab(); true }
        popup.menu.add("Private tab").setOnMenuItemClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) startActivity(Intent(this, PrivateActivity::class.java))
            else Toast.makeText(this, "Private mode requires Android 9+", Toast.LENGTH_SHORT).show()
            true
        }
        popup.menu.add("Tabs • ${tabs.size}").setOnMenuItemClickListener { tabsDialog(); true }
        popup.menu.add("Bookmarks").setOnMenuItemClickListener { showBookmarks(); true }
        popup.menu.add("History").setOnMenuItemClickListener { showHistory(); true }
        popup.menu.add("Downloads").setOnMenuItemClickListener {
            runCatching { startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) }
                .onFailure { Toast.makeText(this, "Downloads unavailable", Toast.LENGTH_SHORT).show() }
            true
        }
        popup.menu.add("Site permissions").setOnMenuItemClickListener { sitePermissions(); true }
        popup.menu.add("Settings").setOnMenuItemClickListener { settings(); true }
        popup.menu.add("Refresh").setOnMenuItemClickListener { web.reload(); true }
        popup.show()
    }

    private fun newTab() {
        val next = createBrowserWebView()
        tabs.add(next); activeTab = tabs.lastIndex
        webHost.addView(next, FrameLayout.LayoutParams(-1, -1))
        showActiveTab()
        home()
    }

    private fun createBrowserWebView(): WebView {
        return WebView(this).apply {
            setBackgroundColor(black)
            settings.javaScriptEnabled = true
            settings.setGeolocationEnabled(true)
            settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                settings.mixedContentMode = if (prefs.getBoolean("https_only", true)) android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW else android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) settings.safeBrowsingEnabled = true
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, !prefs.getBoolean("block_third_party_cookies", true))
            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: android.view.View?, callback: CustomViewCallback?) {
                    if (view == null || fullscreenView != null) {
                        callback?.onCustomViewHidden()
                        return
                    }
                    val decor = window.decorView as? FrameLayout
                    if (decor == null) {
                        callback?.onCustomViewHidden()
                        return
                    }
                    fullscreenView = view
                    fullscreenCallback = callback
                    if (::root.isInitialized) root.visibility = android.view.View.GONE
                    decor.addView(view, FrameLayout.LayoutParams(-1, -1))
                    @Suppress("DEPRECATION")
                    window.decorView.systemUiVisibility = (
                        android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                        android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    )
                }
                override fun onHideCustomView() {
                    val decor = window.decorView as? FrameLayout
                    fullscreenView?.let { runCatching { decor?.removeView(it) } }
                    fullscreenView = null
                    fullscreenCallback?.onCustomViewHidden()
                    fullscreenCallback = null
                    @Suppress("DEPRECATION")
                    window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_VISIBLE
                    if (::root.isInitialized) root.visibility = android.view.View.VISIBLE
                }
                override fun onPermissionRequest(request: PermissionRequest) {
                    runOnUiThread { handleWebPermissionRequest(request) }
                }
                override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                    runOnUiThread { handleGeolocationRequest(origin, callback) }
                }
            }
            setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                runCatching {
                    val request = DownloadManager.Request(Uri.parse(url))
                        .setMimeType(mimeType)
                        .addRequestHeader("User-Agent", userAgent)
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType))
                    (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                    Toast.makeText(this@MainActivity, "Download started", Toast.LENGTH_SHORT).show()
                }.onFailure { Toast.makeText(this@MainActivity, "Could not start download", Toast.LENGTH_SHORT).show() }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val uri = request?.url ?: return false
                    if (uri.scheme == "veilsearch") { navigate(uri.getQueryParameter("q").orEmpty()); return true }
                    if (uri.scheme == "veilaction" && uri.host == "settings") { settings(); return true }
                    if (view === web) applySiteJavaScript(uri.host ?: "")
                    if (uri.scheme == "https" || uri.scheme == "http") { if (view === web) address.setText(uri.toString()); return false }
                    return true
                }
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val requestUri = request?.url ?: return null
                    val host = requestUri.host?.lowercase(Locale.ROOT) ?: return null
                    // Never let filter-list false positives cancel top-level navigation.
                    // This is the most important reliability rule for a browser.
                    if (request.isForMainFrame) return null
                    if (host == "veil.local" || host == "veil-private.local") return null
                    // Preserve media, player, and core resources required by YouTube playback.
                    if (host == "youtube.com" || host.endsWith(".youtube.com") ||
                        host == "youtu.be" || host.endsWith(".googlevideo.com") ||
                        host.endsWith(".ytimg.com") || host == "youtubei.googleapis.com" ||
                        host.endsWith(".youtube-nocookie.com")) return null
                    val jsBlocked = prefs.getString("site_js_" + host, "default") == "block"
                    if (jsBlocked && request.url.path?.endsWith(".js", true) == true) return WebResourceResponse("text/plain", "utf-8", 204, "Blocked by site settings", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                    val mode = prefs.getString("block_level", "standard") ?: "standard"
                    val requestUrl = request.url.toString()
                    val pageUrl = view?.url
                    val accept = request.requestHeaders["Accept"]
                    if (filterEngine().shouldBlock(requestUrl, pageUrl, request.isForMainFrame, accept) || blocked(host, mode)) return WebResourceResponse("text/plain", "utf-8", 204, "Blocked by VEIL", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                    return super.shouldInterceptRequest(view, request)
                }
                override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                    val crashedView = view ?: return true
                    val index = tabs.indexOf(crashedView)
                    if (index < 0) {
                        runCatching { crashedView.destroy() }
                        return true
                    }
                    runCatching { webHost.removeView(crashedView) }
                    runCatching { crashedView.stopLoading(); crashedView.destroy() }
                    runOnUiThread {
                        runCatching {
                            val replacement = createBrowserWebView()
                            tabs[index] = replacement
                            webHost.removeAllViews()
                            webHost.addView(replacement, FrameLayout.LayoutParams(-1, -1))
                            if (index == activeTab) {
                                web = replacement
                                home()
                                Toast.makeText(this@MainActivity, "VEIL recovered after the page process stopped.", Toast.LENGTH_LONG).show()
                            }
                        }.onFailure {
                            Toast.makeText(this@MainActivity, "VEIL could not restore this page. Please reopen the tab.", Toast.LENGTH_LONG).show()
                        }
                    }
                    return true
                }
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    if (view === web) url?.let { address.setText(it) }
                    if (url != null && url.startsWith("http")) {
                        val scriptlets = filterEngine().let { cachedScriptletEngine?.scriptFor(url).orEmpty() }
                        if (scriptlets.isNotBlank()) view?.evaluateJavascript(scriptlets, null)
                    }
                }
                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
                    super.onReceivedError(view, request, error)
                    if (view === web && request?.isForMainFrame == true) {
                        status.text = "Page failed • " + (error?.description ?: "Network error")
                        Toast.makeText(this@MainActivity, "Halaman gagal dimuat. Periksa koneksi atau coba muat ulang.", Toast.LENGTH_LONG).show()
                    }
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (url != null && url.startsWith("http")) recordHistory(url)
                    if (view === web && url != null && ::address.isInitialized) address.setText(url)
                    if (view === web && url != null && (url.startsWith("https://") || url.startsWith("http://"))) {
                        prefs.edit().putString("last_page", url).apply()
                    }
                    if (url != null) {
                        val css = filterEngine().cosmeticCss(url)
                        if (css.isNotBlank()) {
                            val safeCss = css.replace("\\", "\\\\").replace("'", "\\'")
                            view?.evaluateJavascript("(function(){var s=document.createElement('style');s.textContent='$safeCss{display:none!important}';(document.head||document.documentElement).appendChild(s);})();", null)
                        }
                    }
                    if (view === web && ::status.isInitialized) status.text = "VEIL protection • " + (prefs.getString("block_level", "standard") ?: "standard").uppercase()
                }
            }
        }
    }

    private fun blocked(host: String, mode: String): Boolean {
        if (!prefs.getBoolean("tracker_protection", true)) return false
        val standard = listOf("doubleclick.net","googlesyndication.com","googleadservices.com","adservice.google.com","adnxs.com")
        val strong = standard + listOf("googletagmanager.com","google-analytics.com","scorecardresearch.com","taboola.com","outbrain.com","criteo.com","hotjar.com","adsrvr.org","rubiconproject.com")
        val superBlock = strong + listOf("connect.facebook.net","facebook.net","branch.io","adjust.com","appsflyer.com","mixpanel.com","segment.io","amplitude.com","onesignal.com","moatads.com","pubmatic.com")
        val rules = when (mode) { "strong" -> strong; "super" -> superBlock; else -> standard }
        val allowed = (prefs.getString("allowed_filter_domains", "") ?: "").split('\n').filter { it.isNotBlank() }
        if (allowed.any { host == it || host.endsWith(".$it") }) return false
        return rules.any { host == it || host.endsWith(".$it") } ||
            cachedBlockedDomains.any { host == it || host.endsWith(".$it") }
    }

    private fun home() {
        if (!::web.isInitialized) return
        val bg = when (prefs.getString("theme", "Obsidian Full Black")) {
            "Midnight Dark" -> "#11131b"
            "Minimal Grey" -> "#222226"
            "Light" -> "#f0f0f4"
            "Ocean Blue" -> "#081b2c"
            "Forest" -> "#10231d"
            "Sunset" -> "#24191d"
            else -> "#19191e"
        }
        val fg = if (prefs.getString("theme", "Obsidian Full Black") == "Light") "#202027" else "#f5f5f7"
        val accent = String.format("#%06X", 0xFFFFFF and accentColor)
        val photoData = runCatching {
            val raw = prefs.getString("home_photo", null) ?: return@runCatching null
            val uri = Uri.parse(raw)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / sample > 900 || bounds.outHeight / sample > 900) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
            contentResolver.openInputStream(uri)?.use { input ->
                val bitmap = BitmapFactory.decodeStream(input, null, options) ?: return@use null
                val output = java.io.ByteArrayOutputStream()
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 58, output)
                bitmap.recycle()
                if (output.size() > 700_000) null
                else "data:image/jpeg;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
            }
        }.getOrNull()
        val backgroundCss = if (photoData != null) "background-image:linear-gradient(rgba(12,12,16,.52),rgba(12,12,16,.72)),url('$photoData');background-size:cover;background-position:center;" else "background:$bg;"
        val searchPrefix = prefs.getString("search_engine", "https://duckduckgo.com/?q=") ?: "https://duckduckgo.com/?q="
        val searchPrefixJs = org.json.JSONObject.quote(searchPrefix)
        val html = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
        <style>
        *{box-sizing:border-box}html,body{margin:0;width:100%;height:100%;overflow:hidden;font-family:Arial,sans-serif;color:$fg}
        body{position:relative;$backgroundCss}
        .waves{position:absolute;inset:0;width:100%;height:100%;pointer-events:none;opacity:.32}
        .gear{position:absolute;top:6%;right:7%;color:#9b9ba5;font-size:25px;text-decoration:none;z-index:2}
        .center{position:absolute;left:0;right:0;top:27%;transform:translateY(-5%);padding:0 18px;text-align:center}
        .logo{font-size:54px;font-weight:800;letter-spacing:-3px;margin-bottom:48px;display:flex;justify-content:center;align-items:center;gap:12px}
        .mark{width:56px;height:62px;background:$accent;clip-path:polygon(20% 0,80% 0,100% 24%,87% 76%,50% 100%,13% 76%,0 24%);display:inline-flex;align-items:center;justify-content:center;color:#fff;font-size:35px;font-weight:900}
        .search{height:68px;border-radius:27px;background:rgba(105,105,115,.22);border:1px solid rgba(255,255,255,.08);display:flex;align-items:center;padding:0 18px;box-shadow:0 8px 28px rgba(0,0,0,.12)}
        input{width:100%;border:0;outline:0;background:transparent;color:$fg;font-size:19px;min-width:0}
        input::placeholder{color:#9999a4}.go{border:0;background:transparent;color:$accent;font-size:25px;padding:8px}
        .suggest{margin-top:38px;text-align:left;opacity:.4;font-size:14px;line-height:2.5;padding:0 20px}
        </style></head><body>
        <svg class="waves" viewBox="0 0 390 850" preserveAspectRatio="none"><g fill="none" stroke="#777783" stroke-width="1">
        <path d="M-30 20 Q150 80 420 5"/><path d="M-30 48 Q150 108 420 33"/><path d="M-30 76 Q150 136 420 61"/><path d="M-30 104 Q150 164 420 89"/><path d="M-30 132 Q150 192 420 117"/><path d="M-30 160 Q150 220 420 145"/>
        <path d="M-30 690 Q160 590 420 700"/><path d="M-30 720 Q160 620 420 730"/><path d="M-30 750 Q160 650 420 760"/><path d="M-30 780 Q160 680 420 790"/><path d="M-30 810 Q160 710 420 820"/><path d="M-30 840 Q160 740 420 850"/></g></svg>
        <a class="gear" href="veilaction://settings">⚙</a>
        <main class="center"><div class="logo"><span class="mark">V</span><span>veil</span></div>
        <div class="search"><input id="q" autocomplete="off" placeholder="Search the web privately..." onkeydown="if(event.key==='Enter')go()"><button class="go" onclick="go()">⌕</button></div>
        <div class="suggest">Private search. Clean browsing.<br>Fast, focused, and yours.</div></main>
        <script>
        function go(){
          var field=document.getElementById('q');
          var value=field.value.trim();
          if(!value)return;
          window.location.href=$searchPrefixJs+encodeURIComponent(value);
        }
        </script>
        </body></html>"""
        web.loadDataWithBaseURL("https://veil.local/", html, "text/html", "UTF-8", null)
        address.setText("")
        address.hint = tr("Search or enter address", "Cari atau masukkan alamat")
    }

    private fun navigate(raw: String) {
        var q = raw.trim()
        if (q.isEmpty()) return
        if (prefs.getBoolean("https_only", true) && q.startsWith("http://", true)) q = "https://" + q.removePrefix("http://")
        if (!q.contains("://") && q.contains(".") && !q.contains(" ")) q = "https://$q"
        else if (!q.startsWith("https://") && !q.startsWith("http://")) {
            val engine = prefs.getString("search_engine", "https://duckduckgo.com/?q=") ?: "https://duckduckgo.com/?q="
            q = engine + URLEncoder.encode(q, "UTF-8")
        }
        runCatching {
            web.removeJavascriptInterface("VEIL")
            web.stopLoading()
            web.loadUrl(q)
            address.clearFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                ?.hideSoftInputFromWindow(address.windowToken, 0)
        }.onFailure {
            Toast.makeText(this, "Tidak dapat membuka alamat tersebut", Toast.LENGTH_SHORT).show()
        }
    }

    private fun settings() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(black)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setBackgroundColor(black)
        }
        val back = TextView(this).apply {
            text = "‹"
            textSize = 32f
            setTextColor(this@MainActivity.foreground)
            gravity = Gravity.CENTER
            setOnClickListener { buildBrowser() }
        }
        header.addView(back, LinearLayout.LayoutParams(dp(44), dp(48)))
        header.addView(TextView(this).apply {
            text = tr("Settings", "Pengaturan")
            textSize = 22f
            setTextColor(this@MainActivity.foreground)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        page.addView(header)

        val search = EditText(this).apply {
            hint = tr("Search settings", "Cari pengaturan")
            textSize = 15f
            setTextColor(this@MainActivity.foreground)
            setHintTextColor(Color.GRAY)
            setSingleLine(true)
            setPadding(dp(16), 0, dp(16), 0)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(panel)
                cornerRadius = dp(18).toFloat()
            }
        }
        val searchWrap = LinearLayout(this).apply {
            setPadding(dp(16), dp(4), dp(16), dp(14))
            addView(search, LinearLayout.LayoutParams(-1, dp(52)))
        }
        page.addView(searchWrap)

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(14), dp(24))
        }
        scroll.addView(content)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        data class SettingEntry(val section: String, val title: String, val summary: String, val action: () -> Unit)
        val entries = listOf(
            SettingEntry("VEIL SHIELDS", "Ad & tracker blocking", "Choose Standard, Strong or Super blocking") { chooseBlock() },
            SettingEntry("VEIL SHIELDS", "Filter lists", "Update EasyList and EasyPrivacy") { refreshFilters(force = true) },
            SettingEntry("VEIL SHIELDS", "Filter engine status", "View loaded rules and last update") { showFilterStatus() },
            SettingEntry("PRIVACY & SECURITY", "Tracker protection", if (prefs.getBoolean("tracker_protection", true)) "On • reduce cross-site tracking" else "Off") {
                val v = !prefs.getBoolean("tracker_protection", true); prefs.edit().putBoolean("tracker_protection", v).apply(); settings()
            },
            SettingEntry("PRIVACY & SECURITY", "Block third-party cookies", if (prefs.getBoolean("block_third_party_cookies", true)) "On • block third-party cookies" else "Off") {
                val v = !prefs.getBoolean("block_third_party_cookies", true); prefs.edit().putBoolean("block_third_party_cookies", v).apply()
                tabs.forEach { CookieManager.getInstance().setAcceptThirdPartyCookies(it, !v) }; settings()
            },
            SettingEntry("PRIVACY & SECURITY", "HTTPS-only mode", if (prefs.getBoolean("https_only", true)) "On • prefer secure connections" else "Off") {
                val v = !prefs.getBoolean("https_only", true); prefs.edit().putBoolean("https_only", v).apply()
                tabs.forEach { it.settings.mixedContentMode = if (v) android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW else android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE }; settings()
            },
            SettingEntry("PRIVACY & SECURITY", "Site permissions", "Camera, microphone and location") { sitePermissions() },
            SettingEntry("PRIVACY & SECURITY", "Private DNS", "Choose a DNS-over-TLS provider") { dnsSettings() },
            SettingEntry("PRIVACY & SECURITY", "Clear browsing data", "History, cookies, cache and site data") { clearData() },
            SettingEntry("GENERAL", "Search engine", "Choose your default search provider") { chooseSearch() },
            SettingEntry("GENERAL", "Homepage wallpaper", "Choose a photo from your gallery") { pickPhoto() },
            SettingEntry("APPEARANCE", "Theme", prefs.getString("theme", "Obsidian Full Black") ?: "Obsidian Full Black") { chooseTheme() },
            SettingEntry("APPEARANCE", "Accent color", "Customize VEIL highlights") { chooseAccent() },
            SettingEntry("APPEARANCE", "App language", "50 language choices • translations are still expanding") { chooseLanguage() },
            SettingEntry("YOUR DATA", "Bookmarks", "Manage saved pages") { showBookmarks() },
            SettingEntry("YOUR DATA", "History", "View visited pages") { showHistory() },
            SettingEntry("ABOUT", "About VEIL", "Privacy information and current feature support") {
                AlertDialog.Builder(this).setTitle("About VEIL").setMessage("VEIL stores settings locally and includes no account or analytics SDK. Ad and tracker filtering currently supports an ABP subset, not full Brave-level protection. Private DNS opens Android system settings and applies device-wide.").setPositiveButton("OK", null).show()
            }
        )

        fun render(query: String = "") {
            content.removeAllViews()
            var lastSection = ""
            entries.filter { (it.title + " " + it.summary + " " + it.section).contains(query, true) }.forEach { entry ->
                if (entry.section != lastSection) {
                    val section = TextView(this).apply {
                        text = entry.section
                        textSize = 12f
                        setTextColor(accentColor)
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setPadding(dp(8), dp(18), dp(8), dp(8))
                    }
                    content.addView(section)
                    lastSection = entry.section
                }
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(15), dp(13), dp(15), dp(13))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(panel)
                        cornerRadius = dp(15).toFloat()
                    }
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { entry.action() }
                }
                card.addView(TextView(this).apply {
                    text = entry.title
                    textSize = 16f
                    setTextColor(this@MainActivity.foreground)
                    setTypeface(null, android.graphics.Typeface.BOLD)
                })
                card.addView(TextView(this).apply {
                    text = entry.summary
                    textSize = 13f
                    setTextColor(if (prefs.getString("theme", "") == "Light") Color.DKGRAY else Color.LTGRAY)
                    setPadding(0, dp(4), 0, 0)
                })
                val lp = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
                content.addView(card, lp)
            }
            if (content.childCount == 0) content.addView(TextView(this).apply {
                text = tr("No settings found", "Pengaturan tidak ditemukan")
                setTextColor(this@MainActivity.foreground); setPadding(dp(12), dp(24), dp(12), dp(24))
            })
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { render(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        render()
        setContentView(page)
    }

    private fun showFilterStatus() {
        val rules = (prefs.getString("downloaded_filter_rules", "") ?: "").lines().count { it.isNotBlank() }
        val hosts = (prefs.getString("downloaded_block_domains", "") ?: "").lines().count { it.isNotBlank() }
        val updated = prefs.getLong("filters_updated_at", 0L)
        val whenText = if (updated == 0L) "Never updated" else java.text.DateFormat.getDateTimeInstance().format(java.util.Date(updated))
        AlertDialog.Builder(this).setTitle("VEIL filter status")
            .setMessage("Parsed source lines: $rules\nBlock host entries: $hosts\nLast successful update: $whenText\n\nThe current engine supports an ABP subset. Unsupported rule syntax is skipped safely.")
            .setPositiveButton("Update now") { _, _ -> refreshFilters(force = true) }
            .setNegativeButton("Close", null).show()
    }

    private fun dnsSettings() {
        val providers = arrayOf(
            "Cloudflare • one.one.one.one",
            "Google • dns.google",
            "Quad9 • dns.quad9.net",
            "AdGuard • dns.adguard-dns.com"
        )
        val hosts = arrayOf("one.one.one.one", "dns.google", "dns.quad9.net", "dns.adguard-dns.com")
        AlertDialog.Builder(this)
            .setTitle("Private DNS")
            .setMessage("Choose a DNS-over-TLS provider. VEIL cannot silently change Android's system DNS. Select a provider to copy its hostname, then enter it in Android Private DNS settings. This setting applies device-wide, not only inside VEIL.")
            .setItems(providers) { _, index ->
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Private DNS hostname", hosts[index]))
                Toast.makeText(this, hosts[index] + " copied", Toast.LENGTH_LONG).show()
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) startActivity(Intent("android.settings.PRIVATE_DNS_SETTINGS"))
                    else startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
            .setNeutralButton("Open DNS settings") { _, _ ->
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) startActivity(Intent("android.settings.PRIVATE_DNS_SETTINGS"))
                    else startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                } catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
            }
            .setNegativeButton("Close", null).show()
    }

    private fun refreshFiltersIfDue() {
        val last = prefs.getLong("filters_updated_at", 0L)
        if (System.currentTimeMillis() - last > 7L * 24 * 60 * 60 * 1000) refreshFilters(force = false)
    }

    private fun refreshFilters(force: Boolean) {
        if (!force && System.currentTimeMillis() - prefs.getLong("filters_updated_at", 0L) < 7L * 24 * 60 * 60 * 1000) return
        Thread {
            val blockedHosts = linkedSetOf<String>()
            val allowedHosts = linkedSetOf<String>()
            val allRules = linkedSetOf<String>()
            val sources = listOf(
                "https://easylist.to/easylist/easylist.txt",
                "https://easylist.to/easylist/easyprivacy.txt"
            )
            try {
                sources.forEach { source ->
                    val connection = (URL(source).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15000
                        readTimeout = 30000
                        setRequestProperty("User-Agent", "VEIL-Browser/1.0")
                    }
                    try {
                        connection.inputStream.bufferedReader().useLines { lines ->
                            lines.forEach lineLoop@{ raw ->
                                val line = raw.trim()
                                if (line.isNotEmpty()) allRules.add(line)
                                if (line.isEmpty() || line.startsWith("!") || line.startsWith("[")) return@lineLoop
                                val exception = line.startsWith("@@||")
                                val candidate = if (exception) line.removePrefix("@@||") else if (line.startsWith("||")) line.removePrefix("||") else return@lineLoop
                                val host = candidate.substringBefore('^').substringBefore('/').substringBefore('$').lowercase(Locale.ROOT)
                                if (host.contains('.') && host.length <= 253 && host.split('.').all { part -> part.isNotEmpty() && part.length <= 63 && part.all { ch -> ch.isLetterOrDigit() || ch == '-' } }) {
                                    if (exception) allowedHosts.add(host) else blockedHosts.add(host)
                                }
                            }
                        }
                    } finally { connection.disconnect() }
                }
                if (blockedHosts.isEmpty()) throw IllegalStateException("No filter rules received")
                prefs.edit()
                    .putString("downloaded_block_domains", blockedHosts.joinToString("\n"))
                    .putString("allowed_filter_domains", allowedHosts.joinToString("\n"))
                    .putString("downloaded_filter_rules", allRules.joinToString("\n"))
                    .putLong("filters_updated_at", System.currentTimeMillis())
                    .apply()
                runOnUiThread {
                    if (::status.isInitialized) status.text = "AdBlock lists updated • " + blockedHosts.size + " block hosts • " + allowedHosts.size + " exceptions"
                    Toast.makeText(this, "Filter lists updated", Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {
                runOnUiThread { if (force) Toast.makeText(this, "Filter update failed. Existing rules kept.", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun chooseBlock() {
        val names = arrayOf("Standard", "Strong", "Super Block • Most aggressive")
        val keys = arrayOf("standard", "strong", "super")
        AlertDialog.Builder(this).setTitle(tr("AdBlock level","Tingkat AdBlock")).setSingleChoiceItems(names, keys.indexOf(prefs.getString("block_level", "standard"))) { dialog, index ->
            prefs.edit().putString("block_level", keys[index]).apply()
            dialog.dismiss()
            status.text = "AdBlock: " + names[index]
        }.setNegativeButton("Cancel", null).show()
    }
    private fun pickPhoto() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) }
        startActivityForResult(i, 77)
    }
    @Deprecated("Use Activity Result API in a later update")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if (requestCode == 77 && resultCode == RESULT_OK) data?.data?.let { uri ->
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            prefs.edit().putString("home_photo",uri.toString()).apply()
            status.text = "Gallery image selected"
            home()
            Toast.makeText(this,"Wallpaper applied to VEIL home",Toast.LENGTH_SHORT).show()
        }
    }
    private fun chooseTheme() {
        val names = arrayOf("Obsidian Full Black","Midnight Dark","Minimal Grey","Light","Ocean Blue","Forest","Sunset")
        AlertDialog.Builder(this).setTitle(tr("Theme","Tema")).setItems(names) { _, i ->
            prefs.edit().putString("theme",names[i]).apply()
            buildBrowser()
            Toast.makeText(this,names[i]+" applied",Toast.LENGTH_SHORT).show()
        }.show()
    }
    private fun chooseAccent() {
        val names = arrayOf("Silver","Ice Blue","Emerald","Amber","Rose")
        AlertDialog.Builder(this).setTitle(tr("Accent","Warna aksen")).setItems(names) { _, i -> prefs.edit().putInt("accent",i).apply(); Toast.makeText(this,names[i]+" saved",Toast.LENGTH_SHORT).show() }.show()
    }
    private fun chooseSearch() {
        val names = arrayOf("DuckDuckGo","Google","Bing","Brave Search")
        val urls = arrayOf("https://duckduckgo.com/?q=","https://www.google.com/search?q=","https://www.bing.com/search?q=","https://search.brave.com/search?q=")
        AlertDialog.Builder(this).setTitle(tr("Search engine","Mesin pencari")).setItems(names) { _, i -> prefs.edit().putString("search_engine",urls[i]).apply() }.show()
    }
    private fun hostKey(origin: String): String =
        runCatching { Uri.parse(origin).host?.lowercase(Locale.ROOT).orEmpty() }.getOrDefault("")

    private fun handleWebPermissionRequest(request: PermissionRequest) {
        val host = hostKey(request.origin.toString())
        val resources = request.resources.filter {
            it == PermissionRequest.RESOURCE_AUDIO_CAPTURE || it == PermissionRequest.RESOURCE_VIDEO_CAPTURE
        }.toTypedArray()
        if (host.isBlank() || resources.isEmpty() || resources.size != request.resources.size) {
            request.deny()
            return
        }
        val key = "site_media_" + host
        when (prefs.getString(key, "ask")) {
            "deny" -> { request.deny(); return }
            "allow" -> { requestMediaAtOsLevel(request, resources); return }
        }
        val labels = resources.map {
            if (it == PermissionRequest.RESOURCE_VIDEO_CAPTURE) "camera" else "microphone"
        }.distinct().joinToString(" and ")
        AlertDialog.Builder(this)
            .setTitle("VEIL permission")
            .setMessage("$host wants to use your $labels.")
            .setPositiveButton("Allow for this site") { _, _ ->
                prefs.edit().putString(key, "allow").apply()
                requestMediaAtOsLevel(request, resources)
            }
            .setNegativeButton("Block") { _, _ ->
                prefs.edit().putString(key, "deny").apply()
                request.deny()
            }
            .setOnCancelListener { request.deny() }
            .show()
    }

    private fun requestMediaAtOsLevel(request: PermissionRequest, resources: Array<String>) {
        val required = mutableListOf<String>()
        if (resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) required.add(Manifest.permission.CAMERA)
        if (resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) required.add(Manifest.permission.RECORD_AUDIO)
        val missing = required.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            runCatching { request.grant(resources) }.onFailure { request.deny() }
            return
        }
        pendingMediaRequest?.deny()
        pendingMediaRequest = request
        pendingMediaResources = resources
        requestPermissions(missing.toTypedArray(), REQUEST_MEDIA_PERMISSIONS)
    }

    private fun handleGeolocationRequest(origin: String, callback: GeolocationPermissions.Callback) {
        val host = hostKey(origin)
        if (host.isBlank()) { callback.invoke(origin, false, false); return }
        when (prefs.getString("site_geo_" + host, "ask")) {
            "deny" -> { callback.invoke(origin, false, false); return }
            "allow" -> { requestLocationAtOsLevel(origin, callback); return }
        }
        AlertDialog.Builder(this)
            .setTitle("VEIL location permission")
            .setMessage("$host wants to access your device location.")
            .setPositiveButton("Allow for this site") { _, _ ->
                prefs.edit().putString("site_geo_" + host, "allow").apply()
                requestLocationAtOsLevel(origin, callback)
            }
            .setNegativeButton("Block") { _, _ ->
                prefs.edit().putString("site_geo_" + host, "deny").apply()
                callback.invoke(origin, false, false)
            }
            .setOnCancelListener { callback.invoke(origin, false, false) }
            .show()
    }

    private fun requestLocationAtOsLevel(origin: String, callback: GeolocationPermissions.Callback) {
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fine || coarse) {
            callback.invoke(origin, true, false)
            return
        }
        pendingGeoCallback = callback
        pendingGeoOrigin = origin
        requestPermissions(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            REQUEST_LOCATION_PERMISSIONS
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MEDIA_PERMISSIONS) {
            val request = pendingMediaRequest
            val granted = permissions.isNotEmpty() && grantResults.isNotEmpty() &&
                permissions.indices.all { grantResults[it] == PackageManager.PERMISSION_GRANTED }
            if (request != null) {
                if (granted) runCatching { request.grant(pendingMediaResources) }.onFailure { request.deny() }
                else request.deny()
            }
            pendingMediaRequest = null
            pendingMediaResources = emptyArray()
        } else if (requestCode == REQUEST_LOCATION_PERMISSIONS) {
            val callback = pendingGeoCallback
            val granted = permissions.indices.any {
                (permissions[it] == Manifest.permission.ACCESS_FINE_LOCATION ||
                    permissions[it] == Manifest.permission.ACCESS_COARSE_LOCATION) &&
                    grantResults.getOrNull(it) == PackageManager.PERMISSION_GRANTED
            }
            callback?.invoke(pendingGeoOrigin, granted, false)
            pendingGeoCallback = null
            pendingGeoOrigin = ""
        }
    }

    private fun applySiteJavaScript(host: String) {
        if (!::web.isInitialized || host.isBlank()) return
        web.settings.javaScriptEnabled = prefs.getString("site_js_" + host.lowercase(Locale.ROOT), "default") != "block"
    }

    private fun sitePermissions() {
        val host = runCatching { Uri.parse(web.url ?: "").host ?: "" }.getOrDefault("")
        if (host.isBlank()) { Toast.makeText(this, "Open a website first", Toast.LENGTH_SHORT).show(); return }
        val jsKey = "site_js_" + host
        val geoKey = "site_geo_" + host
        val mediaKey = "site_media_" + host
        val choices = arrayOf(
            "JavaScript: allow", "JavaScript: block",
            "Location: ask", "Location: allow", "Location: block",
            "Camera & microphone: ask", "Camera & microphone: allow", "Camera & microphone: block",
            "Notifications: unavailable in Android WebView", "Reset site settings"
        )
        AlertDialog.Builder(this).setTitle("Site permissions • " + host).setItems(choices) { _, index ->
            when (index) {
                0 -> prefs.edit().putString(jsKey, "default").apply()
                1 -> prefs.edit().putString(jsKey, "block").apply()
                2 -> prefs.edit().remove(geoKey).apply()
                3 -> prefs.edit().putString(geoKey, "allow").apply()
                4 -> prefs.edit().putString(geoKey, "deny").apply()
                5 -> prefs.edit().remove(mediaKey).apply()
                6 -> prefs.edit().putString(mediaKey, "allow").apply()
                7 -> prefs.edit().putString(mediaKey, "deny").apply()
                8 -> Toast.makeText(this, "Web notifications are not supported by this WebView implementation.", Toast.LENGTH_LONG).show()
                9 -> prefs.edit().remove(jsKey).remove(geoKey).remove(mediaKey).apply()
            }
            applySiteJavaScript(host)
            Toast.makeText(this, "Site preference saved. Reload the page to apply changes.", Toast.LENGTH_SHORT).show()
        }.show()
    }

    private fun recordHistory(url: String) {
        val old = prefs.getString("history", "") ?: ""
        val updated = (listOf(url) + old.lines().filter { it.isNotBlank() && it != url }).take(500)
        prefs.edit().putString("history", updated.joinToString("\n")).apply()
    }

    private fun showHistory() {
        val history = (prefs.getString("history", "") ?: "").lines().filter { it.startsWith("http") }
        if (history.isEmpty()) Toast.makeText(this, "No browsing history", Toast.LENGTH_SHORT).show()
        else AlertDialog.Builder(this).setTitle(tr("Browsing history", "Riwayat penjelajahan"))
            .setItems(history.toTypedArray()) { _, index -> navigate(history[index]) }
            .setNeutralButton(tr("Clear history", "Hapus riwayat")) { _, _ -> prefs.edit().remove("history").apply() }
            .setNegativeButton(tr("Close", "Tutup"), null).show()
    }

    private fun saveBookmark() {
        val url = web.url ?: return
        val old = prefs.getString("bookmarks","") ?: ""
        prefs.edit().putString("bookmarks", (old + url + "\n").takeLast(12000)).apply()
        Toast.makeText(this,"Bookmark saved locally",Toast.LENGTH_SHORT).show()
    }
    private fun tabsDialog() {
        val options = arrayOf("New tab", "Switch tab (" + tabs.size + ")", "Close current tab", "New isolated private session", "Show saved bookmarks")
        AlertDialog.Builder(this).setTitle(tr("Tabs & library","Tab & pustaka")).setItems(options) { _, i ->
            when (i) {
                0 -> {
                    val next = createBrowserWebView()
                    tabs.add(next); activeTab = tabs.lastIndex
                    webHost.addView(next, FrameLayout.LayoutParams(-1, -1))
                    showActiveTab()
                    home()
                }
                1 -> showTabPicker()
                2 -> closeCurrentTab()
                3 -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) startActivity(Intent(this, PrivateActivity::class.java))
                    else Toast.makeText(this, "Isolated private mode requires Android 9 or newer.", Toast.LENGTH_LONG).show()
                }
                4 -> showBookmarks()
            }
        }.show()
    }

    private fun showActiveTab() {
        tabs.forEachIndexed { index, tab -> tab.visibility = if (index == activeTab) android.view.View.VISIBLE else android.view.View.GONE }
        web = tabs[activeTab]
        address.setText(web.url ?: "")
    }

    private fun showTabPicker() {
        if (tabs.isEmpty()) return
        val names = tabs.mapIndexed { index, tab -> (index + 1).toString() + ". " + (tab.title?.take(45) ?: tab.url ?: "New tab") }
        AlertDialog.Builder(this).setTitle("Open tab").setItems(names.toTypedArray()) { _, index ->
            activeTab = index
            showActiveTab()
        }.setNegativeButton("Close", null).show()
    }

    private fun closeCurrentTab() {
        if (tabs.size <= 1) {
            web.loadUrl("about:blank")
            home()
            return
        }
        val closing = tabs.removeAt(activeTab)
        webHost.removeView(closing)
        closing.stopLoading()
        closing.destroy()
        activeTab = (activeTab - 1).coerceAtLeast(0)
        showActiveTab()
    }
    private fun showBookmarks() {
        val list = (prefs.getString("bookmarks","") ?: "").lines().filter { it.startsWith("http") }.distinct()
        if (list.isEmpty()) Toast.makeText(this,"No bookmarks saved",Toast.LENGTH_SHORT).show()
        else AlertDialog.Builder(this).setTitle("Bookmarks").setItems(list.toTypedArray()) { _, i -> navigate(list[i]) }.setNegativeButton("Close",null).show()
    }
    private fun clearData() {
        web.clearHistory(); web.clearCache(true); web.clearFormData()
        CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush()
        prefs.edit().remove("bookmarks").remove("history").apply()
        Toast.makeText(this,"Local browser data cleared",Toast.LENGTH_SHORT).show()
    }
    override fun onResume() {
        super.onResume()
        if (::web.isInitialized) web.onResume()
    }

    override fun onPause() {
        if (::web.isInitialized) {
            web.onPause()
            web.url?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let {
                prefs.edit().putString("last_page", it).apply()
            }
        }
        super.onPause()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() { if (::web.isInitialized && web.canGoBack()) web.goBack() else home() }
    override fun onDestroy() {
        fullscreenView?.let { runCatching { (window.decorView as? FrameLayout)?.removeView(it) } }
        fullscreenView = null
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
        pendingMediaRequest?.deny()
        pendingMediaRequest = null
        pendingGeoCallback?.invoke(pendingGeoOrigin, false, false)
        pendingGeoCallback = null
        if (::web.isInitialized) { tabs.forEach { it.stopLoading(); it.destroy() }; tabs.clear() }
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_MEDIA_PERMISSIONS = 810
        private const val REQUEST_LOCATION_PERMISSIONS = 811
    }
}
