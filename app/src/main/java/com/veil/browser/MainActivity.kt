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
    private var pendingMediaRequest: PermissionRequest? = null
    private var pendingMediaResources: Array<String> = emptyArray()
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null
    private var pendingGeoOrigin: String = ""

    private fun filterEngine(): FilterEngine {
        val stamp = prefs.getLong("filters_updated_at", 0L)
        if (cachedFilterEngine == null || cachedFilterStamp != stamp) {
            val rules = (prefs.getString("downloaded_filter_rules", "") ?: "").lines().filter { it.isNotBlank() }
            val legacy = (prefs.getString("downloaded_block_domains", "") ?: "").lines().filter { it.isNotBlank() }.map { "||" + it + "^" }
            cachedFilterEngine = FilterEngine(rules + legacy)
            cachedFilterStamp = stamp
        }
        return cachedFilterEngine!!
    }

    // VEIL v1 starts with 50 language choices. Translation coverage is tracked separately.
    private val languageCodes = listOf(
        "en","id","ms","zh","zh-TW","ja","ko","hi","bn","ur","ar","fa","he","th","vi","tl","ta","te","ml","kn","mr","gu","pa","ne","si","my","km","lo","fr","de","es","pt","it","nl","sv","no","da","fi","pl","cs","sk","hu","ro","bg","el","ru","uk","tr","sw","am"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("veil_local_settings", MODE_PRIVATE)
        if (prefs.getString("language", null) == null) chooseLanguage(firstRun = true) else { applyAppLocale(); buildBrowser() }
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
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(black) }
        webHost = FrameLayout(this)
        tabs.forEach { old -> runCatching { old.stopLoading(); old.destroy() } }
        tabs.clear()
        web = createBrowserWebView()
        tabs.add(web)
        activeTab = 0
        webHost.addView(web, FrameLayout.LayoutParams(-1, -1))
        root.addView(webHost, LinearLayout.LayoutParams(-1, 0, 1f))

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(black)
        }
        val addressPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
            setBackgroundColor(panel)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(panel); cornerRadius = dp(16).toFloat()
            }
        }
        address = EditText(this).apply {
            isSingleLine = true
            textSize = 14f
            setTextColor(foreground)
            setHintTextColor(Color.GRAY)
            hint = tr("Search or enter address", "Cari atau masukkan alamat")
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setBackgroundColor(Color.TRANSPARENT)
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSelectAllOnFocus(true)
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_GO || id == EditorInfo.IME_ACTION_SEARCH || id == EditorInfo.IME_ACTION_DONE) {
                    navigate(text.toString()); true
                } else false
            }
        }
        addressPill.addView(address, LinearLayout.LayoutParams(0, dp(46), 1f))
        addressPill.addView(action("↵") { navigate(address.text.toString()) }, LinearLayout.LayoutParams(dp(36), dp(40)))
        toolbar.addView(addressPill, LinearLayout.LayoutParams(0, dp(54), 1f))
        val tabButton = action("1") { tabsDialog() }.apply { textSize = 16f }
        toolbar.addView(tabButton, LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(6) })
        val menuButton = action("⋮") { }.apply { textSize = 25f; setOnClickListener { showBrowserMenu(this) } }
        toolbar.addView(menuButton, LinearLayout.LayoutParams(dp(42), dp(48)))
        root.addView(toolbar)
        status = TextView(this).apply { text = "Private by design"; visibility = android.view.View.GONE }
        setContentView(root)
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
        tabs.add(next)
        activeTab = tabs.lastIndex
        webHost.addView(next, FrameLayout.LayoutParams(-1, -1))
        showActiveTab()
        home()
    }

    private fun createBrowserWebView(): WebView {
        return WebView(this).apply {
            setBackgroundColor(black)
            settings.javaScriptEnabled = true
            settings.setGeolocationEnabled(true)
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
                    if (uri.scheme == "veilsearch") {
                        val query = uri.getQueryParameter("q").orEmpty()
                        navigate(query)
                        return true
                    }
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
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    if (view === web) url?.let { address.setText(it) }
                    if (url != null && url.startsWith("http")) {
                        val filterLines = (prefs.getString("downloaded_filter_rules", "") ?: "").lines()
                        val scriptlets = ScriptletEngine(filterLines).scriptFor(url)
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
        val downloaded = (prefs.getString("downloaded_block_domains", "") ?: "").split('\n').filter { it.isNotBlank() }
        return (rules + downloaded).any { host == it || host.endsWith(".$it") }
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
            while (bounds.outWidth / sample > 1280 || bounds.outHeight / sample > 1280) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
            contentResolver.openInputStream(uri)?.use { input ->
                val bitmap = BitmapFactory.decodeStream(input, null, options) ?: return@use null
                val output = java.io.ByteArrayOutputStream()
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 72, output)
                bitmap.recycle()
                "data:image/jpeg;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
            }
        }.getOrNull()
        val backgroundCss = if (photoData != null) "background-image:linear-gradient(rgba(12,12,16,.52),rgba(12,12,16,.72)),url('$photoData');background-size:cover;background-position:center;" else "background:$bg;"
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
        <path d="M-30 690 Q160 590 420 700"/><path d="M-30 720 Q160 620 420 730"/><path d="M-30 750 Q160 650 420 760"/><path d="M-30 780 Q160 680 420 790"/><path d="M-30 810 Q160 710 420 820"/><path d="M-30 840 Q160 740 420 850"/>
        </g></svg>
        <a class="gear" href="veilaction://settings">⚙</a>
        <main class="center"><div class="logo"><span class="mark">V</span><span>veil</span></div>
        <div class="search"><input id="q" autocomplete="off" placeholder="Search the web privately..." onkeydown="if(event.key==='Enter')go()"><button class="go" onclick="go()">⌕</button></div>
        <div class="suggest">Private search. Clean browsing.<br>Fast, focused, and yours.</div></main>
        <script>function go(){var v=document.getElementById('q').value.trim();if(v)location.href='veilsearch://search?q='+encodeURIComponent(v)}</script>
        </body></html>"""
        web.loadDataWithBaseURL("https://veil.local/", html, "text/html", "UTF-8", null)
        address.setText("")
        address.hint = "Search or enter address"
    }

    private fun tabsDialog() {
        val options = arrayOf("New tab", "Switch tab • ${tabs.size}", "Close current tab", "New isolated private session", "Show saved bookmarks")
        AlertDialog.Builder(this).setTitle("Tabs").setItems(options) { _, i ->
            when (i) {
                0 -> newTab()
                1 -> showTabPicker()
                2 -> closeCurrentTab()
                3 -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) startActivity(Intent(this, PrivateActivity::class.java))
                    else Toast.makeText(this, "Private mode requires Android 9+", Toast.LENGTH_LONG).show()
                }
                4 -> showBookmarks()
            }
        }.setNegativeButton("Close", null).show()
    }


