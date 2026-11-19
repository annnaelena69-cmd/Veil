package com.veil.browser

import android.net.Uri
import java.util.Locale

/** Conservative ABP-style set-constant scriptlet subset. */
class ScriptletEngine(private val lines: Collection<String>) {
    private data class Entry(
        val includeDomains: Set<String>,
        val excludeDomains: Set<String>,
        val path: String,
        val value: String,
        val exception: Boolean
    ) {
        fun applies(host: String): Boolean {
            if (excludeDomains.any { host == it || host.endsWith(".$it") }) return false
            return includeDomains.isEmpty() || includeDomains.any { host == it || host.endsWith(".$it") }
        }
        fun signature() = "$path=$value"
    }

    fun scriptFor(pageUrl: String?): String {
        val host = runCatching { Uri.parse(pageUrl ?: "").host?.lowercase(Locale.ROOT) }.getOrNull() ?: return ""
        val parsed = lines.mapNotNull(::parse).filter { it.applies(host) }
        val exceptions = parsed.filter { it.exception }.map { it.signature() }.toSet()
        val entries = parsed.filter { !it.exception && it.signature() !in exceptions }.distinctBy { it.path }
        return entries.joinToString("") { entry ->
            val path = entry.path.split(".").joinToString(",") { "'" + it + "'" }
            "(function(){try{var p=window;var a=[$path];for(var i=0;i<a.length-1;i++){if(!p[a[i]]||typeof p[a[i]]!=='object')return;p=p[a[i]];}var k=a[a.length-1];Object.defineProperty(p,k,{configurable:true,get:function(){return ${entry.value};},set:function(){}});}catch(e){}})();"
        }.take(12000)
    }

    private fun parse(raw: String): Entry? {
        val line = raw.trim()
        val exceptionMarker = "#@#+js("
        val blockMarker = "##+js("
        val exception = line.contains(exceptionMarker)
        val marker = if (exception) exceptionMarker else blockMarker
        val index = line.indexOf(marker)
        if (index < 0 || !line.endsWith(")")) return null
        val domains = line.substring(0, index).split(',').map { it.trim().lowercase(Locale.ROOT) }
        val include = domains.filter { it.isNotBlank() && !it.startsWith("~") }.toSet()
        val exclude = domains.filter { it.startsWith("~") }.map { it.drop(1) }.filter { it.isNotBlank() }.toSet()
        val args = line.substring(index + marker.length, line.length - 1).split(',').map { it.trim() }
        if (args.size !in 3..4 || args[0] != "set-constant") return null
        val path = args[1]
        if (path.isBlank() || path.length > 160 ||
            !path.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*"))) return null
        val value = when (args[2].lowercase(Locale.ROOT)) {
            "true" -> "true"
            "false" -> "false"
            "null" -> "null"
            "undefined" -> "undefined"
            "empty-string" -> "''"
            "noop" -> "(function(){})"
            else -> return null
        }
        return Entry(include, exclude, path, value, exception)
    }
}
