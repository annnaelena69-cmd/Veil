package dev.veil.scanner

import android.app.Activity
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val scanning = AtomicBoolean(false)
    private lateinit var status: TextView
    private lateinit var progress: TextView
    private lateinit var results: TextView
    private val prefs by lazy { getSharedPreferences("scan_cache_v3", MODE_PRIVATE) }
    @Volatile private var scanner: IScannerService? = null
    @Volatile private var binding = false
    private lateinit var serviceArgs: Shizuku.UserServiceArgs

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQUEST_CODE) {
            refreshStatus(if (grantResult == PackageManager.PERMISSION_GRANTED) "Permission granted." else "Permission denied. Open Shizuku Manager, approve Veil, then retry.")
            if (grantResult == PackageManager.PERMISSION_GRANTED) bindScannerService()
        }
    }
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread {
            refreshStatus("Shizuku binder received.")
            if (hasShizukuPermission()) bindScannerService()
        }
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        scanner = null
        binding = false
        runOnUiThread { refreshStatus("Shizuku stopped. Restart its service and reconnect; cached results remain available.") }
    }
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            scanner = IScannerService.Stub.asInterface(service)
            binding = false
            runOnUiThread {
                refreshStatus(if (scanner != null) "Scanner service connected and ready." else "Scanner service returned an invalid binder.")
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            scanner = null
            binding = false
            runOnUiThread { refreshStatus("Scanner service disconnected. Tap Connect / retry.") }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        serviceArgs = Shizuku.UserServiceArgs(ComponentName(this, ScannerUserService::class.java))
            .tag("veil-save-scanner")
            .version(1)
            .daemon(false)

        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)

        status = TextView(this).apply { textSize = 16f }
        progress = TextView(this).apply { textSize = 13f }
        results = TextView(this).apply { textSize = 13f; setTextIsSelectable(true) }

        val connect = Button(this).apply {
            text = "Check / connect Shizuku"
            setOnClickListener { requestShizuku() }
        }
        val scan = Button(this).apply {
            text = "Scan game saves"
            setOnClickListener { scanCandidates() }
        }
        val clear = Button(this).apply {
            text = "Clear scan cache"
            setOnClickListener {
                prefs.edit().clear().apply()
                progress.text = "Cache cleared."
            }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            addView(status)
            addView(connect)
            addView(scan)
            addView(clear)
            addView(progress)
            addView(results, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(ScrollView(this).apply { addView(layout) })
        refreshStatus()
        showCachedResults()
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) {
            refreshStatus()
            if (hasShizukuPermission()) bindScannerService()
        }
    }

    private fun hasShizukuPermission(): Boolean =
        runCatching { Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)

    private fun refreshStatus(note: String? = null) {
        if (!::status.isInitialized) return
        val message = when {
            !runCatching { Shizuku.pingBinder() }.getOrDefault(false) ->
                "Shizuku: disconnected / not running. Start Shizuku first."
            !hasShizukuPermission() -> "Shizuku: connected · permission not granted"
            scanner != null -> "Shizuku: connected · permission granted · scanner ready"
            binding -> "Shizuku: permission granted · connecting scanner service…"
            else -> "Shizuku: connected · permission granted · scanner not bound"
        }
        status.text = if (note.isNullOrBlank()) message else "$message\n$note"
    }

    private fun requestShizuku() {
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            refreshStatus("Recovery: start Shizuku using Wireless debugging or ADB, then return here.")
            return
        }
        try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                refreshStatus("Access already granted. Connecting scanner service…")
                bindScannerService()
            } else {
                Shizuku.requestPermission(REQUEST_CODE)
            }
        } catch (e: Throwable) {
            refreshStatus("Permission check failed: ${e.message ?: e.javaClass.simpleName}. Reopen Shizuku Manager and retry.")
        }
    }

    @Synchronized
    private fun bindScannerService() {
        if (scanner != null || binding || !hasShizukuPermission()) return
        try {
            binding = true
            Shizuku.bindUserService(serviceArgs, serviceConnection)
            refreshStatus("Binding Shizuku scanner service…")
        } catch (e: Throwable) {
            binding = false
            scanner = null
            refreshStatus("Service bind failed: ${e.message ?: e.javaClass.simpleName}. Restart Shizuku and retry.")
        }
    }

    private fun showCachedResults() {
        val cached = prefs.getStringSet(KEY_CACHE, emptySet()).orEmpty().toList().sorted()
        if (cached.isNotEmpty()) {
            results.text = cached.joinToString("\n")
            progress.text = "Cached results: ${cached.size}. Cache remains visible while a fresh scan runs."
        } else {
            progress.text = "No cached scan yet."
        }
    }

    private fun scanCandidates() {
        if (scanning.get()) {
            progress.text = "A scan is already running."
            return
        }
        if (!hasShizukuPermission()) {
            refreshStatus("Scan blocked. Connect Shizuku and grant permission first.")
            return
        }
        val remote = scanner
        if (remote == null) {
            bindScannerService()
            refreshStatus("Scanner is still connecting. Wait for 'scanner ready', then tap Scan again.")
            return
        }

        scanning.set(true)
        progress.text = "Scanning in stages… previous cache remains visible."
        worker.execute {
            val found = linkedSetOf<String>()
            val failures = mutableListOf<String>()
            val roots = listOf(
                "/sdcard/Android/data", "/sdcard/Android/obb", "/sdcard/Android/media",
                "/sdcard/Download", "/sdcard/Documents", "/sdcard/Games",
                "/sdcard/Saves", "/sdcard/Android"
            )
            try {
                for ((index, root) in roots.withIndex()) {
                    if (!hasShizukuPermission()) {
                        failures += "Shizuku disconnected or permission lost during scan. Reconnect and retry."
                        break
                    }
                    try {
                        val response = remote.scan(root)
                        if (response.startsWith("VEIL_ERROR:")) {
                            failures += response.removePrefix("VEIL_ERROR:")
                        } else {
                            response.lineSequence().filter { it.isNotBlank() }.forEach { found.add(it) }
                        }
                    } catch (e: Throwable) {
                        failures += "$root: ${e.message ?: e.javaClass.simpleName}. Reconnect Shizuku and rescan this location."
                    }
                    val snapshot = found.toList()
                    runOnUiThread {
                        progress.text = "Scanned ${index + 1}/${roots.size} locations · ${snapshot.size} candidates"
                        results.text = snapshot.joinToString("\n").ifBlank { "No matching files yet." }
                    }
                    if (found.size >= MAX_RESULTS) break
                }

                if (found.isNotEmpty()) {
                    prefs.edit()
                        .putStringSet(KEY_CACHE, HashSet(found.take(MAX_RESULTS)))
                        .putLong(KEY_CACHE_TIME, System.currentTimeMillis())
                        .apply()
                }
                val summary = buildString {
                    append("Finished: ${found.size} candidate files. ")
                    append("Locations checked: ${roots.size}.")
                    if (failures.isNotEmpty()) append("\nAccess / recovery report:\n" + failures.distinct().joinToString("\n"))
                    if (found.isEmpty()) append("\nNo matches. Check that the game has created save files and that Shizuku is running with permission.")
                }
                runOnUiThread {
                    progress.text = summary
                    results.text = found.joinToString("\n").ifBlank { "No matching save candidates." }
                    refreshStatus()
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    progress.text = "Scan failed: ${e.message ?: e.javaClass.simpleName}. Previous cache was preserved. Reconnect Shizuku and retry."
                    refreshStatus()
                }
            } finally {
                scanning.set(false)
            }
        }
    }

    override fun onDestroy() {
        runCatching { Shizuku.unbindUserService(serviceArgs, serviceConnection, true) }
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        worker.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_CODE = 4107
        private const val MAX_RESULTS = 3000
        private const val KEY_CACHE = "paths"
        private const val KEY_CACHE_TIME = "updated_at"
    }
}
