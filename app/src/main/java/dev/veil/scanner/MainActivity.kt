package dev.veil.scanner

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var results: TextView

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQUEST_CODE) {
            status.text = if (grantResult == PackageManager.PERMISSION_GRANTED) {
                "Shizuku permission granted"
            } else {
                "Shizuku permission denied"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(permissionListener)

        status = TextView(this).apply { textSize = 16f; text = currentStatus() }
        results = TextView(this).apply { textSize = 13f; setTextIsSelectable(true) }

        val connect = Button(this).apply {
            text = "Connect / grant Shizuku"
            setOnClickListener { requestShizuku() }
        }
        val scan = Button(this).apply {
            text = "Scan game-save candidates"
            setOnClickListener { scanCandidates() }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            addView(status)
            addView(connect)
            addView(scan)
            addView(results)
        }
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    private fun currentStatus(): String = when {
        !Shizuku.pingBinder() -> "Shizuku is not running. Start it in the Shizuku app."
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> "Shizuku connected · permission granted"
        else -> "Shizuku connected · permission not granted"
    }

    private fun requestShizuku() {
        if (!Shizuku.pingBinder()) {
            status.text = "Start Shizuku first, then return here."
            return
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            status.text = currentStatus()
        } else {
            Shizuku.requestPermission(REQUEST_CODE)
        }
    }

    private fun scanCandidates() {
        if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            status.text = "Connect Shizuku and grant permission before scanning."
            return
        }
        status.text = "Scanning shared-storage locations…"
        results.text = ""
        worker.execute {
            val report = runCatching {
                val script = """
                    for root in /sdcard/Android/data /sdcard/Android/obb /sdcard/Android/media /sdcard/Download; do
                      [ -d \"\$root\" ] || continue
                      find \"\$root\" -type f \\( -iname '*.sav' -o -iname '*.dat' -o -iname '*.db' -o -iname '*.json' -o -iname '*.bin' -o -iname '*.bak' \\) -print 2>/dev/null
                    done
                """.trimIndent()
                val process = Shizuku.newProcess(arrayOf("sh", "-c", script), null, null)
                val found = BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    buildList {
                        while (size < MAX_RESULTS) {
                            val line = reader.readLine() ?: break
                            add(line)
                        }
                    }
                }
                process.destroy()
                if (found.isEmpty()) "No matching files found in the scanned locations."
                else found.joinToString("\n")
            }.getOrElse { "Scan failed: ${it.message ?: it.javaClass.simpleName}" }

            runOnUiThread {
                status.text = currentStatus()
                results.text = report
            }
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        worker.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_CODE = 4107
        private const val MAX_RESULTS = 500
    }
}
