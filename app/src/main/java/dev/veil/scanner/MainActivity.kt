package dev.veil.scanner

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val scanning = AtomicBoolean(false)
    private lateinit var status: TextView
    private lateinit var progress: TextView
    private lateinit var results: TextView
    private val prefs by lazy { getSharedPreferences("scan_cache_v2", MODE_PRIVATE) }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQUEST_CODE) refreshStatus(
            if (grantResult == PackageManager.PERMISSION_GRANTED) "Permission granted." else "Permission denied. Open Shizuku and grant access."
        )
    }
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread { refreshStatus("Shizuku service connected.") }
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        runOnUiThread { refreshStatus("Shizuku service disconnected. Reopen Shizuku, then reconnect.") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        if (::status.isInitialized) refreshStatus()
    }

    private fun refreshStatus(note: String? = null) {
        if (!::status.isInitialized) return
        val message = when {
            !runCatching { Shizuku.pingBinder() }.getOrDefault(false) ->
                "Shizuku: disconnected / not running. Start Shizuku, then return here."
            runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false) ->
                "Shizuku: connected · permission granted · ready"
            else -> "Shizuku: connected · permission not granted"
        }
        status.text = if (note.isNullOrBlank()) message else "$message\n$note"
    }

    private fun requestShizuku() {
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            refreshStatus("Start the Shizuku service first. Wireless debugging/ADB setup may be required.")
            return
        }
        try {
            when (Shizuku.checkSelfPermission()) {
                PackageManager.PERMISSION_GRANTED -> refreshStatus("Access is already granted.")
                else -> if (Shizuku.shouldShowRequestPermissionRationale()) {
                    refreshStatus("Open Shizuku Manager and approve Veil, then tap Check again.")
                    Shizuku.requestPermission(REQUEST_CODE)
                } else {
                    Shizuku.requestPermission(REQUEST_CODE)
                }
            }
        } catch (e: Throwable) {
            refreshStatus("Permission check failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun showCachedResults() {
        val cached = prefs.getStringSet(KEY_CACHE, emptySet()).orEmpty().toList().sorted()
        if (cached.isNotEmpty()) {
            results.text = cached.joinToString("\n")
            progress.text = "Cached results: ${cached.size}. Starting a fresh scan updates this list."
        } else {
            progress.text = "No cached scan yet."
        }
    }

    private fun scanCandidates() {
        if (scanning.get()) {
            progress.text = "A scan is already running."
            return
        }
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false) ||
            runCatching { Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED }.getOrDefault(true)) {
            refreshStatus("Scanning blocked: connect Shizuku and grant Veil permission.")
            return
        }
        scanning.set(true)
        progress.text = "Scanning in stages… cached results remain visible until new results arrive."
        worker.execute {
            val found = linkedSetOf<String>()
            val failures = mutableListOf<String>()
            var completedRoots = 0
            try {
                val roots = listOf(
                    "/sdcard/Android/data", "/sdcard/Android/obb", "/sdcard/Android/media",
                    "/sdcard/Download", "/sdcard/Documents", "/sdcard/Games", "/sdcard/Saves"
                )
                for (root in roots) {
                    if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
                        failures += "Shizuku disconnected during scan."
                        break
                    }
                    val script = """
                        root='$root'
                        if [ -d "$root" ] && [ -r "$root" ]; then
                          find "$root" -type f \(
                            -iname '*.sav' -o -iname '*.save' -o -iname '*.dat' -o
                            -iname '*.db' -o -iname '*.sqlite' -o -iname '*.json' -o
                            -iname '*.bin' -o -iname '*.bak' -o -iname '*.backup' -o
                            -iname '*.profile' -o -iname '*.cfg' -o -iname '*.xml' -o
                            -iname 'save*' -o -iname 'profile*'
                          \) -print 2>/dev/null
                        else
                          echo "__VEIL_ROOT_DENIED__:$root"
                        fi
                    """.trimIndent()
                    try {
                        val process = Shizuku.newProcess(arrayOf("sh", "-c", script), null, null)
                        BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.startsWith("__VEIL_ROOT_DENIED__:")) {
                                    failures += "No access: ${line.substringAfter(':')}"
                                } else if (line.isNotBlank()) {
                                    found += line
                                    if (found.size % BATCH_SIZE == 0) {
                                        val snapshot = found.toList()
                                        runOnUiThread {
                                            progress.text = "Scanning… $completedRoots/${roots.size} locations · ${snapshot.size} candidates"
                                            results.text = snapshot.joinToString("\n")
                                        }
                                    }
                                    if (found.size >= MAX_RESULTS) break
                                }
                            }
                        }
                        val exitCode = process.waitFor()
                        if (exitCode != 0) failures += "Search command at $root returned exit code $exitCode."
                    } catch (e: Throwable) {
                        failures += "$root: ${e.message ?: e.javaClass.simpleName}"
                    }
                    completedRoots++
                    val snapshot = found.toList()
                    runOnUiThread {
                        progress.text = "Scanned $completedRoots/${roots.size} locations · ${snapshot.size} candidates"
                        results.text = snapshot.joinToString("\n")
                    }
                    if (found.size >= MAX_RESULTS) break
                }
                prefs.edit().putStringSet(KEY_CACHE, HashSet(found)).putLong(KEY_CACHE_TIME, System.currentTimeMillis()).apply()
                val summary = buildString {
                    append("Finished: ${found.size} candidate files. ")
                    append(if (found.size >= MAX_RESULTS) "Result cap reached. " else "")
                    append("Accessible roots: $completedRoots.")
                    if (failures.isNotEmpty()) append("\nWarnings / recovery details:\n" + failures.distinct().joinToString("\n"))
                    if (found.isEmpty()) append("\nNo matches found. Try granting Shizuku permission or use the Android folder picker in a future access mode.")
                }
                runOnUiThread {
                    progress.text = summary
                    results.text = found.joinToString("\n").ifBlank { "No matching save candidates." }
                    refreshStatus()
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    progress.text = "Scan error: ${e.message ?: e.javaClass.simpleName}. Cached results were preserved."
                    refreshStatus()
                }
            } finally {
                scanning.set(false)
            }
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        worker.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_CODE = 4107
        private const val MAX_RESULTS = 3000
        private const val BATCH_SIZE = 40
        private const val KEY_CACHE = "paths"
        private const val KEY_CACHE_TIME = "updated_at"
    }
}
