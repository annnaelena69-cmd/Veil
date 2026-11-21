package dev.veil.scanner

import android.os.RemoteException
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class ScannerUserService : IScannerService.Stub() {
    @Throws(RemoteException::class)
    override fun scan(root: String): String {
        val allowed = setOf(
            "/sdcard/Android/data", "/sdcard/Android/obb", "/sdcard/Android/media",
            "/sdcard/Download", "/sdcard/Documents", "/sdcard/Games",
            "/sdcard/Saves", "/sdcard/Android"
        )
        if (root !in allowed) return "VEIL_ERROR:Rejected unapproved scan path: $root"

        val quoted = "'" + root.replace("'", "'\\''") + "'"
        val command = "if [ ! -d $quoted ]; then echo VEIL_ERROR:Folder not found: $root; " +
            "elif [ ! -r $quoted ]; then echo VEIL_ERROR:Permission denied: $root; " +
            "else find $quoted -maxdepth 6 -type f \\( " +
            "-iname '*.sav' -o -iname '*.save' -o -iname '*.dat' -o -iname '*.db' -o " +
            "-iname '*.sqlite' -o -iname '*.json' -o -iname '*.bin' -o -iname '*.bak' -o " +
            "-iname '*.backup' -o -iname '*.profile' -o -iname '*.cfg' -o -iname '*.xml' -o " +
            "-iname 'save*' -o -iname 'profile*' \\) -print 2>&1 | head -n 3000; fi"

        return try {
            val process = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
            val output = StringBuilder()
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (output.length < 900_000) output.append(line).append('\n')
                }
            }
            if (!process.waitFor(45, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                "VEIL_ERROR:Timed out while scanning $root. Try a smaller folder or reconnect Shizuku."
            } else {
                val text = output.toString().trim()
                if (process.exitValue() != 0 && text.isBlank()) "VEIL_ERROR:Search failed in $root (exit ${process.exitValue()}). Check folder access."
                else text
            }
        } catch (e: Throwable) {
            "VEIL_ERROR:$root: ${e.message ?: e.javaClass.simpleName}. Retry after checking Shizuku service."
        }
    }

    override fun destroy() {
        System.exit(0)
    }
}
