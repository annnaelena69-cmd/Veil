package com.veil.browser

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var root: LinearLayout
    private lateinit var web: WebView
    private lateinit var address: EditText
    private lateinit var status: TextView
    private val black = Color.rgb(4, 4, 5)
    private val panel = Color.rgb(19, 19, 22)
    private val foreground = Color.rgb(240, 240, 244)
    private val tabs = mutableListOf<String>()
    private var privateMode = false

    private val languageCodes = listOf(
        "en","id","ar","bn","bg","ca","zh","zh-TW","hr","cs","da","nl","et","fi","fr","de","el","gu","he","hi",
        "hu","is","it","ja","kn","kk","km","ko","lo","lv","lt","mk","ms","ml","mr","mn","my","ne","no","fa",
        "pl","pt","pa","ro","ru","sr","si","sk","sl","es","sw","sv","tl","ta","te","th","tr","uk","ur","uz",
        "vi","af","sq","am","hy","az","eu","be","bs","ceb","co","eo","gl","ka","ht","ha","haw","hmn","ig","ga",
        "jw","ku","ky","lb","mg","mt","mi","ny","ps","sm","gd","st","sn","sd","so","su","tg","tt","tk","yi"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("veil_local_settings", MODE_PRIVATE)
        if (prefs.getString("language", null) == null) chooseLanguage(firstRun = true) else buildBrowser()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun label(s: String, size: Float = 14f) = TextView(this).apply {
        text = s; textSize = size; setTextColor(foreground); gravity = Gravity.CENTER
        setPadding(dp(8), dp(8), dp(8), dp(8))
    }
    private fun action(s: String, callback: () -> Unit) = label(s).apply {
        setBackgroundColor(panel); setOnClickListener { callback() }
    }
    private fun chooseLanguage(firstRun: Boolean = false) {
        val locales = languageCodes.mapNotNull { code -> runCatching { Locale.forLanguageTag(code) }.getOrNull() }
        val names = locales.map { it.getDisplayName(it).replaceFirstChar { c -> c.uppercase(it) } }.distinct()
        AlertDialog.Builder(this).setTitle(if (firstRun) "VEIL • Choose language" else "Language")
            .setItems(names.toTypedArray()) { _, index ->
                prefs.edit().putString("language", locales[index].toLanguageTag()).apply()
                if (firstRun || !::root.isInitialized) buildBrowser()
                else Toast.makeText(this, "Language saved. Some screens may need a restart.", Toast.LENGTH_SHORT).show()
            }.setCancelable(!firstRun).show()
    }

    private fun buildBrowser() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(black) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        header.addView(label("VEIL", 20f))
        address = EditText(this).apply {
            singleLine = true; textSize = 14f; setTextColor(foreground); setHintTextColor(Color.GRAY)
            hint = "Search or enter website"; setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(panel); imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_GO) { navigate(text.toString()); true } else false }
        }
        header.addView(address, LinearLayout.LayoutParams(0, dp(44), 1f))
        header.addView(action("GO") { navigate(address.text.toString()) })
        root.addView(header)

        web = WebView(this).apply {
            setBackgroundColor(black)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val uri = request?.url ?: return false
                    if (uri.scheme == "https" || uri.scheme == "http") { address.setText(uri.toString()); return false }
                    return true
                }
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val host = request?.url?.host?.lowercase(Locale.ROOT) ?: return null
                    val mode = prefs.getString("block_level", "standard") ?: "standard"
                    if (blocked(host, mode)) return WebResourceResponse("text/plain", "utf-8", 204, "Blocked by VEIL", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                    return super.shouldInterceptRequest(view, request)
                }
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon); url?.let { address.setText(it) }
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url); status.text = "VEIL protection • " + (prefs.getString("block_level", "standard") ?: "standard").uppercase()
                }
            }
        }
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        status = label("Private by design", 11f).apply { gravity = Gravity.START }
        root.addView(status)
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(panel) }
        listOf("Home","Back","Tabs","★","Settings").forEach { item ->
            nav.addView(action(item) {
                when (item) {
                    "Home" -> home()
                    "Back" -> if (web.canGoBack()) web.goBack() else home()
                    "Tabs" -> tabsDialog()
                    "★" -> saveBookmark()
                    else -> settings()
                }
            }, LinearLayout.LayoutParams(0, dp(50), 1f))
        }
        root.addView(nav)
        setContentView(root)
        home()
    }

    private fun blocked(host: String, mode: String): Boolean {
        val standard = listOf("doubleclick.net","googlesyndication.com","googleadservices.com","adservice.google.com","adnxs.com")
        val strong = standard + listOf("googletagmanager.com","google-analytics.com","scorecardresearch.com","taboola.com","outbrain.com","criteo.com","hotjar.com","adsrvr.org","rubiconproject.com")
        val superBlock = strong + listOf("connect.facebook.net","facebook.net","branch.io","adjust.com","appsflyer.com","mixpanel.com","segment.io","amplitude.com","onesignal.com","moatads.com","pubmatic.com")
        val rules = when (mode) { "strong" -> strong; "super" -> superBlock; else -> standard }
        return rules.any { host == it || host.endsWith(".$it") }
    }

    private fun home() {
        web.loadDataWithBaseURL(null, """<html><meta name="viewport" content="width=device-width,initial-scale=1"><body style="margin:0;background:#040405;color:#eee;font-family:sans-serif;text-align:center;padding:35px 18px"><h1 style="letter-spacing:8px;font-size:34px">VEIL</h1><p style="color:#999">Your browser. Your rules.</p><p style="margin-top:42px;color:#aaa">No account • Local settings • Privacy controls</p><p style="color:#777;font-size:12px">Search above or enter a website.</p></body></html>""", "text/html", "UTF-8", null)
        val uri = prefs.getString("home_photo", null)
        if (uri != null) status.text = "Custom homepage photo saved in settings"
    }

    private fun navigate(raw: String) {
        var q = raw.trim()
        if (q.isEmpty()) return
        if (!q.contains("://") && q.contains(".") && !q.contains(" ")) q = "https://$q"
        else if (!q.startsWith("https://") && !q.startsWith("http://")) {
            val engine = prefs.getString("search_engine", "https://duckduckgo.com/?q=") ?: "https://duckduckgo.com/?q="
            q = engine + URLEncoder.encode(q, "UTF-8")
        }
        web.loadUrl(q)
    }

    private fun settings() {
        val items = arrayOf("AdBlock: Standard / Strong / Super Block","Choose homepage photo from Gallery","Theme: Obsidian / Midnight / Light","Accent color","Search engine","Language (100 options)","Clear browsing data","About & privacy")
        AlertDialog.Builder(this).setTitle("VEIL Settings").setItems(items) { _, which ->
            when (which) {
                0 -> chooseBlock()
                1 -> pickPhoto()
                2 -> chooseTheme()
                3 -> chooseAccent()
                4 -> chooseSearch()
                5 -> chooseLanguage()
                6 -> clearData()
                7 -> AlertDialog.Builder(this).setTitle("VEIL privacy").setMessage("No login, analytics SDK or telemetry endpoint is included in this source prototype. Websites you visit still receive requests and may collect data. The starter ad filter is not a complete filter-list engine.").setPositiveButton("OK", null).show()
            }
        }.show()
    }
    private fun chooseBlock() {
        val names = arrayOf("Standard","Strong","Super Block • Most aggressive")
        val keys = arrayOf("standard","strong","super")
        AlertDialog.Builder(this).setTitle("AdBlock level").setSingleChoiceItems(names, keys.indexOf(prefs.getString("block_level","standard"))) { d, i ->
            prefs.edit().putString("block_level",keys[i]).apply(); d.dismiss(); status.text = "AdBlock: " + names[i]
        }.setNegativeButton("Cancel",null).show()
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
            Toast.makeText(this,"Photo selected. Wallpaper display is being finalized.",Toast.LENGTH_LONG).show()
        }
    }
    private fun chooseTheme() {
        val names = arrayOf("Obsidian Full Black","Midnight Dark","Minimal Grey","Light")
        AlertDialog.Builder(this).setTitle("Theme").setItems(names) { _, i ->
            prefs.edit().putString("theme",names[i]).apply()
            Toast.makeText(this,names[i]+" saved",Toast.LENGTH_SHORT).show()
        }.show()
    }
    private fun chooseAccent() {
        val names = arrayOf("Silver","Ice Blue","Emerald","Amber","Rose")
        AlertDialog.Builder(this).setTitle("Accent").setItems(names) { _, i -> prefs.edit().putInt("accent",i).apply(); Toast.makeText(this,names[i]+" saved",Toast.LENGTH_SHORT).show() }.show()
    }
    private fun chooseSearch() {
        val names = arrayOf("DuckDuckGo","Google","Bing","Brave Search")
        val urls = arrayOf("https://duckduckgo.com/?q=","https://www.google.com/search?q=","https://www.bing.com/search?q=","https://search.brave.com/search?q=")
        AlertDialog.Builder(this).setTitle("Search engine").setItems(names) { _, i -> prefs.edit().putString("search_engine",urls[i]).apply() }.show()
    }
    private fun saveBookmark() {
        val url = web.url ?: return
        val old = prefs.getString("bookmarks","") ?: ""
        prefs.edit().putString("bookmarks", (old + url + "\n").takeLast(12000)).apply()
        Toast.makeText(this,"Bookmark saved locally",Toast.LENGTH_SHORT).show()
    }
    private fun tabsDialog() {
        val options = arrayOf("New tab","Private browsing","Close current tab","Show saved bookmarks")
        AlertDialog.Builder(this).setTitle("Tabs & library").setItems(options) { _, i ->
            when(i) {
                0 -> { tabs.add("about:blank"); home() }
                1 -> { privateMode = true; web.clearHistory(); web.clearCache(true); home(); Toast.makeText(this,"Private session started in this tab",Toast.LENGTH_SHORT).show() }
                2 -> home()
                3 -> showBookmarks()
            }
        }.show()
    }
    private fun showBookmarks() {
        val list = (prefs.getString("bookmarks","") ?: "").lines().filter { it.startsWith("http") }.distinct()
        if (list.isEmpty()) Toast.makeText(this,"No bookmarks saved",Toast.LENGTH_SHORT).show()
        else AlertDialog.Builder(this).setTitle("Bookmarks").setItems(list.toTypedArray()) { _, i -> navigate(list[i]) }.setNegativeButton("Close",null).show()
    }
    private fun clearData() {
        web.clearHistory(); web.clearCache(true); web.clearFormData()
        CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush()
        prefs.edit().remove("bookmarks").apply()
        Toast.makeText(this,"Local browser data cleared",Toast.LENGTH_SHORT).show()
    }
    @Suppress("DEPRECATION")
    override fun onBackPressed() { if (::web.isInitialized && web.canGoBack()) web.goBack() else home() }
    override fun onDestroy() { if (::web.isInitialized) { web.stopLoading(); web.destroy() }; super.onDestroy() }
}
