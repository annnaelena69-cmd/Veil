package com.veil.browser

import android.net.Uri
import java.util.Locale

/**
 * ABP-compatible subset for Android WebView.
 * Unknown options and advanced filter families are ignored safely.
 * This is not a complete ABP implementation.
 */
class FilterEngine(lines: Collection<String>) {
    private data class Rule(
        val pattern: Regex,
        val exception: Boolean,
        val includeDomains: Set<String>,
        val excludeDomains: Set<String>,
        val resourceTypes: Set<String>,
        val excludedTypes: Set<String>,
        val thirdParty: Boolean?,
        val matchCase: Boolean,
        val exceptionOptions: Set<String>,
        val generic: Boolean
    )

    private data class CosmeticRule(
        val includeDomains: Set<String>,
        val excludeDomains: Set<String>,
        val selector: String,
        val generic: Boolean
    )

    private val rules = lines.mapNotNull(::parseNetworkRule)
    private val cosmeticRules = mutableListOf<CosmeticRule>()
    private val cosmeticExceptions = mutableListOf<CosmeticRule>()

    init {
        lines.forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("!") || line.startsWith("[")) return@forEach
            val marker = when {
                line.contains("#@#") -> "#@#"
                line.contains("##") -> "##"
                else -> null
            } ?: return@forEach
            val parts = line.split(marker, limit = 2)
            if (parts.size != 2) return@forEach
            val selector = parts[1].trim()
            if (selector.isEmpty() || selector.startsWith("+js(") || selector.contains(":-abp-") ||
                selector.contains(":has-text(") || selector.contains(":xpath(")) return@forEach
            if (selector.length > 500 || selector.contains("</style", true)) return@forEach
            val (include, exclude) = parseDomainList(parts[0].split(','))
            val rule = CosmeticRule(include, exclude, selector, include.isEmpty() && exclude.isEmpty())
            if (marker == "#@#") cosmeticExceptions.add(rule) else cosmeticRules.add(rule)
        }
    }

    fun shouldBlock(url: String, pageUrl: String?, isMainFrame: Boolean, accept: String?): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        val pageHost = runCatching {
            Uri.parse(if (isMainFrame) url else pageUrl ?: "").host?.lowercase(Locale.ROOT)
        }.getOrNull().orEmpty()
        if (pageHost.isNotEmpty() && hasPageException(pageHost, "document")) return false
        val thirdParty = pageHost.isNotEmpty() && !sameSite(host, pageHost)
        val type = resourceType(uri.toString(), accept, isMainFrame)
        val matching = rules.filter { rule ->
            domainApplies(pageHost, rule.includeDomains, rule.excludeDomains) &&
                (rule.resourceTypes.isEmpty() || type in rule.resourceTypes) &&
                type !in rule.excludedTypes &&
                (rule.thirdParty == null || rule.thirdParty == thirdParty) &&
                rule.pattern.containsMatchIn(url)
        }
        if (matching.any { it.exception }) return false
        val genericBlockDisabled = pageHost.isNotEmpty() && hasPageException(pageHost, "genericblock")
        return matching.any { !it.exception && !(genericBlockDisabled && it.generic) }
    }

    fun cosmeticCss(pageUrl: String?): String {
        val host = runCatching { Uri.parse(pageUrl ?: "").host?.lowercase(Locale.ROOT) }.getOrNull() ?: return ""
        if (hasPageException(host, "elemhide")) return ""
        val genericHidden = !hasPageException(host, "generichide")
        val exceptions = cosmeticExceptions.filter { domainApplies(host, it.includeDomains, it.excludeDomains) }
            .map { it.selector }.toSet()
        return cosmeticRules.asSequence()
            .filter { domainApplies(host, it.includeDomains, it.excludeDomains) }
            .filter { genericHidden || !it.generic }
            .filter { it.selector !in exceptions }
            .map { it.selector }.distinct().joinToString(",") { it }.take(12000)
    }

    private fun hasPageException(host: String, option: String): Boolean =
        rules.any { it.exception && option in it.exceptionOptions &&
            domainApplies(host, it.includeDomains, it.excludeDomains) &&
            it.pattern.containsMatchIn("https://$host/") }

    private fun parseNetworkRule(raw: String): Rule? {
        var line = raw.trim()
        if (line.isEmpty() || line.startsWith("!") || line.startsWith("[") ||
            line.contains("##") || line.contains("#@#") || line.contains("#?#") || line.contains("#$#")) return null
        val exception = line.startsWith("@@")
        if (exception) line = line.drop(2)
        val split = line.split('$', limit = 2)
        val patternText = split[0]
        val options = split.getOrNull(1)?.split(',')?.map { it.trim() } ?: emptyList()
        val include = mutableSetOf<String>()
        val exclude = mutableSetOf<String>()
        val types = mutableSetOf<String>()
        val excludedTypes = mutableSetOf<String>()
        val exceptionOptions = mutableSetOf<String>()
        var thirdParty: Boolean? = null
        var matchCase = false
        options.forEach { opt ->
            when {
                opt.startsWith("domain=") -> {
                    val (inc, exc) = parseDomainList(opt.removePrefix("domain=").split('|'))
                    include.addAll(inc); exclude.addAll(exc)
                }
                opt == "third-party" -> thirdParty = true
                opt == "~third-party" -> thirdParty = false
                opt == "match-case" -> matchCase = true
                opt in exceptionOnlyOptions && exception -> exceptionOptions.add(opt)
                opt.startsWith("~") && opt.drop(1) in knownTypes -> excludedTypes.add(opt.drop(1))
                opt in knownTypes -> types.add(opt)
                opt.isNotEmpty() && opt !in ignoredSafeOptions -> return null
            }
        }
        if (patternText.isBlank()) return null
        val generic = include.isEmpty() && exclude.isEmpty()
        val regexText = toRegex(patternText) ?: return null
        return runCatching {
            Rule(Regex(regexText, if (matchCase) setOf() else setOf(RegexOption.IGNORE_CASE)),
                exception, include, exclude, types, excludedTypes, thirdParty, matchCase, exceptionOptions, generic)
        }.getOrNull()
    }

    private fun toRegex(source: String): String? {
        var text = source
        val startAnchor = text.startsWith("|")
        val endAnchor = text.endsWith("|")
        if (text.startsWith("||")) {
            text = text.drop(2)
            val host = text.substringBeforeAny('/', '^', '*', '|', '?')
            if (host.isBlank()) return null
            val tail = text.removePrefix(host)
            return "^[a-zA-Z][a-zA-Z0-9+.-]*://([^/]+\\.)?${Regex.escape(host)}(?::[0-9]+)?${wildcard(tail)}"
        }
        if (startAnchor) text = text.drop(1)
        if (endAnchor) text = text.dropLast(1)
        return (if (startAnchor) "^" else "") + wildcard(text) + (if (endAnchor) "$" else "")
    }

    private fun wildcard(text: String): String = buildString {
        text.forEach { ch ->
            when (ch) {
                '*' -> append(".*")
                '^' -> append("(?:[^a-zA-Z0-9_.%-]|$)")
                '|' -> append("\\|")
                else -> append(Regex.escape(ch.toString()))
            }
        }
    }

    private fun resourceType(url: String, accept: String?, main: Boolean): String {
        if (main) return "document"
        val a = accept.orEmpty().lowercase(Locale.ROOT)
        val path = runCatching { Uri.parse(url).path.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
        return when {
            a.contains("text/css") || path.endsWith(".css") -> "stylesheet"
            a.contains("image/") || path.matches(Regex(".*\\.(png|jpe?g|gif|webp|svg|ico|avif)(\\?.*)?$")) -> "image"
            a.contains("font/") || path.matches(Regex(".*\\.(woff2?|ttf|otf|eot)(\\?.*)?$")) -> "font"
            a.contains("audio/") || a.contains("video/") || path.matches(Regex(".*\\.(mp3|mp4|webm|ogg|m3u8)(\\?.*)?$")) -> "media"
            path.endsWith(".js") || path.contains(".js?") -> "script"
            a.contains("text/html") -> "subdocument"
            else -> "other"
        }
    }

    private fun parseDomainList(values: List<String>): Pair<Set<String>, Set<String>> {
        val include = mutableSetOf<String>()
        val exclude = mutableSetOf<String>()
        values.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.forEach {
            if (it.startsWith("~")) exclude.add(it.drop(1)) else include.add(it)
        }
        return include to exclude
    }

    private fun domainApplies(host: String, include: Set<String>, exclude: Set<String>): Boolean {
        if (host.isBlank()) return include.isEmpty() && exclude.isEmpty()
        if (exclude.any { hostMatches(host, it) }) return false
        return include.isEmpty() || include.any { hostMatches(host, it) }
    }

    private fun sameSite(a: String, b: String): Boolean = hostMatches(a, b) || hostMatches(b, a)
    private fun hostMatches(host: String, domain: String): Boolean =
        domain == "*" || host == domain || host.endsWith(".$domain")

    private fun String.substringBeforeAny(vararg chars: Char): String {
        val i = indexOfAny(chars)
        return if (i < 0) this else substring(0, i)
    }

    companion object {
        private val knownTypes = setOf(
            "script", "image", "stylesheet", "font", "media", "xmlhttprequest",
            "subdocument", "document", "other", "object", "ping", "websocket",
            "webrtc", "popup", "webbundle"
        )
        private val exceptionOnlyOptions = setOf("document", "elemhide", "generichide", "genericblock")
        private val ignoredSafeOptions = setOf("important", "badfilter", "collapse", "sitekey")
    }
}
