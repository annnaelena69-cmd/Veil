package com.veil.browser

import android.net.Uri
import java.util.Locale

/** ABP subset for WebView. Unsupported advanced rules are skipped safely. */
class FilterEngine(lines: Collection<String>) {
    private data class NetworkRule(
        val regex: Regex, val exception: Boolean,
        val include: Set<String>, val exclude: Set<String>,
        val types: Set<String>, val notTypes: Set<String>,
        val thirdParty: Boolean?, val pageOptions: Set<String>, val generic: Boolean, val important: Boolean
    )
    private data class CosmeticRule(val include: Set<String>, val exclude: Set<String>, val selector: String, val generic: Boolean)

    private val activeLines: List<String> = run {
        fun canonical(raw: String): String {
            val s = raw.trim()
            val prefix = if (s.startsWith("@@")) "@@" else ""
            val parts = s.removePrefix(prefix).split('$', limit = 2)
            val opts = parts.getOrNull(1)?.split(',')?.filter { it != "badfilter" }?.joinToString(",")
            return prefix + parts[0] + if (opts.isNullOrEmpty()) "" else "$" + opts
        }
        val disabled = lines.filter { it.substringAfter('$', "").split(',').any { o -> o.trim() == "badfilter" } }.map(::canonical).toSet()
        lines.filter { it.substringAfter('$', "").split(',').none { o -> o.trim() == "badfilter" } && canonical(it) !in disabled }
    }
    private val networkRules = activeLines.mapNotNull(::parseNetwork)
    private val cosmeticRules = mutableListOf<CosmeticRule>()
    private val cosmeticExceptions = mutableListOf<CosmeticRule>()

    init {
        activeLines.forEach { raw ->
            val s = raw.trim()
            if (s.isEmpty() || s.startsWith("!") || s.startsWith("[")) return@forEach
            val marker = when {
                s.contains("#@#") -> "#@#"
                s.contains("##") -> "##"
                else -> return@forEach
            }
            val p = s.split(marker, limit = 2)
            if (p.size != 2) return@forEach
            val selector = p[1].trim()
            // Extended CSS and scriptlets are handled separately or skipped.
            if (selector.isEmpty() || selector.startsWith("+js(") || selector.contains(":-abp-") ||
                selector.contains(":has-text(") || selector.contains(":xpath(") ||
                selector.length > 500 || selector.contains("</style", true)) return@forEach
            val (inc, exc) = domains(p[0].split(','))
            val rule = CosmeticRule(inc, exc, selector, inc.isEmpty() && exc.isEmpty())
            if (marker == "#@#") cosmeticExceptions.add(rule) else cosmeticRules.add(rule)
        }
    }

    fun shouldBlock(url: String, pageUrl: String?, mainFrame: Boolean, accept: String?): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        val pageHost = runCatching { Uri.parse(if (mainFrame) url else pageUrl ?: "").host?.lowercase(Locale.ROOT) }.getOrNull().orEmpty()
        if (pageHost.isNotEmpty() && pageException(pageHost, "document")) return false
        val third = pageHost.isNotEmpty() && !(matches(host, pageHost) || matches(pageHost, host))
        val type = requestType(url, accept, mainFrame)
        val noGeneric = pageHost.isNotEmpty() && pageException(pageHost, "genericblock")
        var hasException = false
        var hasBlock = false
        var hasImportantBlock = false
        for (rule in networkRules) {
            if (!applies(pageHost, rule.include, rule.exclude)) continue
            if (rule.types.isNotEmpty() && type !in rule.types) continue
            if (type in rule.notTypes) continue
            if (rule.thirdParty != null && rule.thirdParty != third) continue
            if (!rule.regex.containsMatchIn(url)) continue
            if (rule.exception) hasException = true
            else if (rule.important) hasImportantBlock = true
            else if (!(noGeneric && rule.generic)) hasBlock = true
            if (hasImportantBlock) return true
        }
        if (hasException) return false
        return hasBlock
    }

    fun cosmeticCss(pageUrl: String?): String {
        val host = runCatching { Uri.parse(pageUrl ?: "").host?.lowercase(Locale.ROOT) }.getOrNull() ?: return ""
        if (pageException(host, "elemhide")) return ""
        val genericEnabled = !pageException(host, "generichide")
        val exceptions = cosmeticExceptions.filter { applies(host, it.include, it.exclude) }.map { it.selector }.toSet()
        return cosmeticRules.asSequence()
            .filter { applies(host, it.include, it.exclude) }
            .filter { genericEnabled || !it.generic }
            .filter { it.selector !in exceptions }
            .map { it.selector }.distinct().joinToString(",") { it }.take(12000)
    }

    private fun pageException(host: String, option: String): Boolean =
        networkRules.any { it.exception && option in it.pageOptions &&
            applies(host, it.include, it.exclude) && it.regex.containsMatchIn("https://$host/") }

    private fun parseNetwork(raw: String): NetworkRule? {
        var s = raw.trim()
        if (s.isEmpty() || s.startsWith("!") || s.startsWith("[") ||
            s.contains("##") || s.contains("#@#") || s.contains("#?#") || s.contains("#$#")) return null
        val exception = s.startsWith("@@")
        if (exception) s = s.drop(2)
        val parts = s.split('$', limit = 2)
        val pattern = parts[0]
        val opts = parts.getOrNull(1)?.split(',')?.map { it.trim() }.orEmpty()
        val inc = mutableSetOf<String>(); val exc = mutableSetOf<String>()
        val types = mutableSetOf<String>(); val notTypes = mutableSetOf<String>()
        val pageOpts = mutableSetOf<String>()
        var third: Boolean? = null
        var important = false
        for (o in opts) {
            when {
                o.startsWith("domain=") -> { val d = domains(o.removePrefix("domain=").split('|')); inc.addAll(d.first); exc.addAll(d.second) }
                o == "third-party" -> third = true
                o == "~third-party" -> third = false
                o == "match-case" -> Unit
                o == "important" && !exception -> important = true
                exception && o in setOf("document", "elemhide", "generichide", "genericblock") -> pageOpts.add(o)
                o.startsWith("~") && o.drop(1) in resourceTypes -> notTypes.add(o.drop(1))
                o in resourceTypes -> types.add(o)
                o == "collapse" -> Unit
                o.isNotEmpty() -> return null
            }
        }
        if (pattern.isBlank() || (pattern.startsWith("/") && pattern.endsWith("/"))) return null
        val matchCase = "match-case" in opts
        val regex = toRegex(pattern) ?: return null
        return runCatching {
            NetworkRule(Regex(regex, if (matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE)),
                exception, inc, exc, types, notTypes, third, pageOpts, inc.isEmpty() && exc.isEmpty(), important)
        }.getOrNull()
    }

    private fun toRegex(source: String): String? {
        var s = source
        val start = s.startsWith("|"); val end = s.endsWith("|")
        if (s.startsWith("||")) {
            s = s.drop(2)
            val host = s.substringBeforeAny('/', '^', '*', '|', '?')
            if (host.isBlank()) return null
            return "^[a-zA-Z][a-zA-Z0-9+.-]*://([^/]+\\.)?${Regex.escape(host)}(?::[0-9]+)?${wildcard(s.removePrefix(host))}"
        }
        if (start) s = s.drop(1)
        if (end) s = s.dropLast(1)
        return (if (start) "^" else "") + wildcard(s) + if (end) "$" else ""
    }

    private fun wildcard(s: String) = buildString {
        s.forEach { ch -> when (ch) {
            '*' -> append(".*")
            '^' -> append("(?:[^a-zA-Z0-9_.%-]|$)")
            '|' -> append("\\|")
            else -> append(Regex.escape(ch.toString()))
        } }
    }

    private fun requestType(url: String, accept: String?, main: Boolean): String {
        if (main) return "document"
        val a = accept.orEmpty().lowercase(Locale.ROOT)
        val path = runCatching { Uri.parse(url).path.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
        return when {
            a.contains("text/css") || path.endsWith(".css") -> "stylesheet"
            a.contains("image/") || path.matches(Regex(".*\\.(png|jpe?g|gif|webp|svg|ico|avif)$")) -> "image"
            a.contains("font/") || path.matches(Regex(".*\\.(woff2?|ttf|otf|eot)$")) -> "font"
            a.contains("audio/") || a.contains("video/") || path.matches(Regex(".*\\.(mp3|mp4|webm|ogg|m3u8)$")) -> "media"
            path.endsWith(".js") -> "script"
            a.contains("text/html") -> "subdocument"
            else -> "other"
        }
    }

    private fun domains(values: List<String>): Pair<Set<String>, Set<String>> {
        val inc = mutableSetOf<String>(); val exc = mutableSetOf<String>()
        values.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.forEach {
            if (it.startsWith("~")) exc.add(it.drop(1)) else inc.add(it)
        }
        return inc to exc
    }
    private fun applies(host: String, inc: Set<String>, exc: Set<String>): Boolean {
        if (host.isBlank()) return inc.isEmpty() && exc.isEmpty()
        if (exc.any { matches(host, it) }) return false
        return inc.isEmpty() || inc.any { matches(host, it) }
    }
    private fun matches(host: String, domain: String) = domain == "*" || host == domain || host.endsWith(".$domain")
    private fun String.substringBeforeAny(vararg chars: Char): String {
        val i = indexOfAny(chars); return if (i < 0) this else substring(0, i)
    }

    companion object {
        private val resourceTypes = setOf("script", "image", "stylesheet", "font", "media", "xmlhttprequest",
            "subdocument", "document", "other", "object", "ping", "websocket", "webrtc", "popup", "webbundle")
    }
}
