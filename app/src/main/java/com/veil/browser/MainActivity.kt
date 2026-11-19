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
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        header.addView(label("VEIL", 20f))
        address = EditText(this).apply {
            isSingleLine = true; textSize = 14f; setTextColor(this@MainActivity.foreground); setHintTextColor(Color.GRAY)
            hint = tr("Search or enter website", "Cari atau masukkan situs"); setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(panel); imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_GO) { navigate(text.toString()); true } else false }
        }
        header.addView(address, LinearLayout.LayoutParams(0, dp(44), 1f))
        header.addView(action("GO") { navigate(address.text.toString()) })
        root.addView(header)

        webHost = FrameLayout(this)
        tabs.forEach { old -> runCatching { old.stopLoading(); old.loadUrl("about:blank"); old.destroy() } }
        tabs.clear()
        web = createBrowserWebView()
        tabs.add(web)
        activeTab = 0
        webHost.addView(web, FrameLayout.LayoutParams(-1, -1))
        root.addView(webHost, LinearLayout.LayoutParams(-1, 0, 1f))
        status = label("Private by design", 11f).apply { gravity = Gravity.START }
        root.addView(status)
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(panel) }
        val homeLabel = tr("Home", "Beranda")
        val backLabel = tr("Back", "Kembali")
        val tabsLabel = tr("Tabs", "Tab")
        val settingsLabel = tr("Settings", "Pengaturan")
        listOf(homeLabel, backLabel, tabsLabel, "★", settingsLabel).forEach { item ->
            nav.addView(action(item) {
                when (item) {
                    homeLabel -> home()
                    backLabel -> if (web.canGoBack()) web.goBack() else home()
                    tabsLabel -> tabsDialog()
                    "★" -> saveBookmark()
                    else -> settings()
                }
            }, LinearLayout.LayoutParams(0, dp(50), 1f))
        }
        root.addView(nav)
        setContentView(root)
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
                    if (view === web) applySiteJavaScript(uri.host ?: "")
                    if (uri.scheme == "https" || uri.scheme == "http") { if (view === web) address.setText(uri.toString()); return false }
                    return true
                }
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val requestUri = request?.url ?: return null
                    val host = requestUri.host?.lowercase(Locale.ROOT) ?: return null
                    // Local VEIL UI must not be evaluated by remote filter lists.
                    if (host == "veil.local" || host == "veil-private.local") return null
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
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (url != null && url.startsWith("http")) recordHistory(url)
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
        if (!::web.isInitialized || !::status.isInitialized) return
        val photo = runCatching {
            val raw = prefs.getString("home_photo", null) ?: return@runCatching null
            val uri = Uri.parse(raw)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val maxSide = 1440
            var sample = 1
            while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
            contentResolver.openInputStream(uri)?.use { input ->
                val bitmap = BitmapFactory.decodeStream(input, null, options) ?: return@use null
                val out = java.io.ByteArrayOutputStream()
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 76, out)
                bitmap.recycle()
                "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            }
        }.getOrNull()
        val bg = when (prefs.getString("theme", "Obsidian Full Black")) {
            "Midnight Dark" -> "#111827"
            "Minimal Grey" -> "#25252b"
            "Light" -> "#eef0f5"
            "Ocean Blue" -> "#071b2d"
            "Forest" -> "#10251e"
            "Sunset" -> "#2a171b"
            else -> "#050608"
        }
        val fg = if (prefs.getString("theme", "Obsidian Full Black") == "Light") "#20232b" else "#f4f5f8"
        val accent = String.format("#%06X", 0xFFFFFF and accentColor)
        val filterCount = (prefs.getString("downloaded_filter_rules", "") ?: "").lineSequence().count { it.isNotBlank() }
        val wallpaper = if (photo != null) "background-image:linear-gradient(180deg,rgba(3,5,9,.55),rgba(3,5,9,.88)),url('$photo');background-size:cover;background-position:center;background-attachment:fixed;" else "background:$bg;"
        val html = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1"><meta charset="utf-8"><style>
        *{box-sizing:border-box}body{margin:0;min-height:100vh;padding:24px 18px 34px;color:$fg;font-family:Arial,sans-serif;$wallpaper}
        .brand{font-size:30px;font-weight:800;letter-spacing:7px;text-align:center;margin:28px 0 5px}.tag{text-align:center;opacity:.72;font-size:13px;margin:0 0 26px}
        .card{background:rgba(120,125,135,.18);border:1px solid rgba(255,255,255,.12);border-radius:20px;padding:18px;margin:14px 0;backdrop-filter:blur(16px)}
        .row{display:flex;justify-content:space-between;align-items:center;gap:10px}.muted{opacity:.72;font-size:12px}.big{font-size:27px;font-weight:700;color:$accent;margin:12px 0 2px}
        .grid{display:grid;grid-template-columns:repeat(3,1fr);gap:10px;margin-top:14px}.tile{display:block;text-decoration:none;color:$fg;text-align:center;background:rgba(120,125,135,.2);border:1px solid rgba(255,255,255,.10);border-radius:16px;padding:16px 5px;font-size:12px}
        .ico{font-size:23px;display:block;margin-bottom:8px}.section{font-weight:700;font-size:15px;margin-bottom:4px}.pill{display:inline-block;border-radius:99px;padding:7px 11px;background:rgba(120,125,135,.22);font-size:11px}
        </style></head><body>
        <div class="brand">VEIL</div><p class="tag">Your browser. Your rules.</p>
        <section class="card"><div class="row"><b>🛡 Status privasi</b><span class="pill">● PROTEKSI AKTIF</span></div>
        <div class="big">$filterCount</div><div class="muted">Aturan filter tersimpan • EasyList / EasyPrivacy</div>
        <div class="row" style="margin-top:16px"><span class="muted">Tracker protection</span><b>${if (prefs.getBoolean("tracker_protection", true)) "ON" else "OFF"}</b></div>
        <div class="row" style="margin-top:8px"><span class="muted">Blokir cookie pihak ketiga</span><b>${if (prefs.getBoolean("block_third_party_cookies", true)) "ON" else "OFF"}</b></div></section>
        <section class="card"><div class="section">Akses cepat</div><div class="muted">Buka situs favorit</div>
        <div class="grid">
        <a class="tile" href="https://www.youtube.com/"><span class="ico">▶</span>YouTube</a>
        <a class="tile" href="https://www.wikipedia.org/"><span class="ico">◈</span>Wikipedia</a>
        <a class="tile" href="https://www.reddit.com/"><span class="ico">◎</span>Reddit</a>
        <a class="tile" href="https://mail.google.com/"><span class="ico">✉</span>Gmail</a>
        <a class="tile" href="https://github.com/"><span class="ico">⌘</span>GitHub</a>
        <a class="tile" href="https://duckduckgo.com/"><span class="ico">⌕</span>Search</a>
        </div></section>
        <section class="card"><div class="section">Privasi & personalisasi</div><div class="muted">Tanpa akun • Pengaturan lokal • Tema dapat diubah</div>
        <p style="margin:14px 0 0"><span class="pill">Tema: ${prefs.getString("theme", "Obsidian Full Black")}</span> <span class="pill">DNS Android</span></p></section>
        </body></html>"""
        runCatching { web.loadDataWithBaseURL("https://veil.local/", html, "text/html", "UTF-8", null) }
            .onFailure { web.loadUrl("about:blank") }
        status.text = if (photo != null) "Custom wallpaper • Private by design" else "Private by design"
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
        web.loadUrl(q)
    }

    private fun settings() {
        val items = arrayOf(
            "AdBlock level • Standard / Strong / Super",
            "Update EasyList + EasyPrivacy",
            "Filter engine status",
            "Tracker protection: " + if (prefs.getBoolean("tracker_protection", true)) "ON" else "OFF",
            "Block third-party cookies: " + if (prefs.getBoolean("block_third_party_cookies", true)) "ON" else "OFF",
            "HTTPS-only mode: " + if (prefs.getBoolean("https_only", true)) "ON" else "OFF",
            "Private DNS / DNS settings",
            "Site permissions",
            "App language • 50 choices",
            "Homepage wallpaper",
            "Theme",
            "Accent color",
            "Search engine",
            "Browsing history",
            "Clear browsing data",
            "About VEIL & privacy"
        )
        AlertDialog.Builder(this).setTitle(tr("VEIL Settings","Pengaturan VEIL")).setItems(items) { _, which ->
            when (which) {
                0 -> chooseBlock()
                1 -> refreshFilters(force = true)
                2 -> showFilterStatus()
                3 -> {
                    val enabled = !prefs.getBoolean("tracker_protection", true)
                    prefs.edit().putBoolean("tracker_protection", enabled).apply()
                    settings()
                    Toast.makeText(this, "Tracker protection " + if (enabled) "enabled" else "disabled", Toast.LENGTH_SHORT).show()
                }
                4 -> {
                    val enabled = !prefs.getBoolean("block_third_party_cookies", true)
                    prefs.edit().putBoolean("block_third_party_cookies", enabled).apply()
                    tabs.forEach { CookieManager.getInstance().setAcceptThirdPartyCookies(it, !enabled) }
                    settings()
                }
                5 -> {
                    val enabled = !prefs.getBoolean("https_only", true)
                    prefs.edit().putBoolean("https_only", enabled).apply()
                    tabs.forEach { tab ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            tab.settings.mixedContentMode = if (enabled) android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW else android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        }
                    }
                    settings()
                }
                6 -> dnsSettings()
                7 -> sitePermissions()
                8 -> chooseLanguage()
                9 -> pickPhoto()
                10 -> chooseTheme()
                11 -> chooseAccent()
                12 -> chooseSearch()
                13 -> showHistory()
                14 -> clearData()
                15 -> AlertDialog.Builder(this).setTitle("VEIL privacy").setMessage("VEIL stores settings locally and includes no account or analytics SDK. EasyList and EasyPrivacy support is still a partial ABP implementation, not full uBlock Origin or Brave-level protection. HTTPS-only mode upgrades typed HTTP URLs and blocks mixed content; some sites may not work. Private DNS is configured in Android system settings and applies device-wide.").setPositiveButton("OK", null).show()
            }
        }.show()
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
    @Suppress("DEPRECATION")
    override fun onBackPressed() { if (::web.isInitialized && web.canGoBack()) web.goBack() else home() }
    override fun onDestroy() {
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
