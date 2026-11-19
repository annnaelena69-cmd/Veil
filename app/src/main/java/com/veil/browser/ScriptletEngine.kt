package com.veil.browser

import android.net.Uri
import java.util.Locale

/** Conservative ABP-style scriptlet support. Only allowlisted literal values are accepted. */
class ScriptletEngine(private val lines: Collection<String>) {
    private data class Entry(val domains: List<String>, val path: String, val value: String)

    fun scriptFor(pageUrl: String?): String {
        val host = runCatching { Uri.parse(pageUrl ?: "").host?.lowercase(Locale.ROOT) }.getOrNull() ?: return ""
        val entries = lines.mapNotNull { parse(it) }.filter { entry ->
            entry.domains.isEmpty() || entry.domains.any { host == it || host.endsWith(".$it") }
        }.distinctBy { it.path }
        return entries.joinToString("") { entry ->
            val path = entry.path.split(".").joinToString(",") { "'" + it + "'" }
            "(function(){try{var p=window;var a=[$path];for(var i=0;i<a.length-1;i++){if(!p[a[i]]||typeof p[a[i]]!=='object')return;p=p[a[i]];}var k=a[a.length-1];Object.defineProperty(p,k,{configurable:true,get:function(){return ${entry.value};},set:function(){}});}catch(e){}})();"
        }.take(12000)
    }

    private fun parse(raw: String): Entry? {
        val line = raw.trim()
        if (line.startsWith("!") || line.startsWith("@@")) return null
        val marker = line.indexOf("##+js(")
        if (marker < 0 || !line.endsWith(")")) return null
        val domains = line.substring(0, marker).split(',').map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotBlank() }
        val args = line.substring(marker + 6, line.length - 1).split(',').map { it.trim() }
        if (args.size !in 3..4 || args[0] != "set-constant") return null
        val path = args[1]
        if (path.isBlank() || path.length > 160 || !path.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*"))) return null
        val value = when (args[2].lowercase(Locale.ROOT)) {
            "true" -> "true"
            "false" -> "false"
            "null" -> "null"
            "undefined" -> "undefined"
            "empty-string" -> "''"
            "noop" -> "(function(){})"
            else -> return null
        }
        return Entry(domains, path, value)
    }
}
