package com.veil.browser

import android.net.Uri
import java.util.Locale

/**
 * Small, deterministic ABP subset for Android WebView.
 * Supports domain anchors, wildcards, exceptions, domain scopes and common resource options.
 * Unsupported advanced ABP syntax is ignored rather than interpreted unsafely.
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
        val matchCase: Boolean
    )

    private val rules: List<Rule> = lines.mapNotNull { parse(it) }
    private val cosmetic = mutableMapOf<String, MutableList<String>>()
    private val genericCosmetic = mutableListOf<String>()

    init {
        lines.forEach { raw ->
            val line = raw.trim()
            if (line.startsWith("!") || line.startsWith("[") || line.isEmpty()) return@forEach
            val marker = when {
                line.contains("#@#") -> "#@#"
                line.contains("##") -> "##"
                else -> null
            } ?: return@forEach
            val parts = line.split(marker, limit = 2)
            if (parts.size != 2) return@forEach
            val domains = parts[0].split(',').map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotBlank() }
            val selector = parts[1].trim()
            if (selector.isBlank() || selector.startsWith("+js(")) return@forEach
            if (marker == "##") {
                if (domains.isEmpty()) genericCosmetic.add(selector)
                else domains.forEach { cosmetic.getOrPut(it) { mutableListOf() }.add(selector) }
            }
        }
    }

    fun shouldBlock(url: String, pageUrl: String?, isMainFrame: Boolean, accept: String?): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        val pageHost = runCatching { Uri.parse(pageUrl ?: "").host?.lowercase(Locale.ROOT) }.getOrNull()
        val thirdParty = pageHost != null && !hostMatches(host, pageHost) && !hostMatches(pageHost, host)
        val type = when {
            isMainFrame -> "document"
            accept?.contains("image", true) == true -> "image"
            accept?.contains("font", true) == true -> "font"
            accept?.contains("text/css", true) == true -> "stylesheet"
            else -> "other"
        }
        val matching = rules.filter { rule ->
            (rule.includeDomains.isEmpty() || rule.includeDomains.any { hostMatches(pageHost ?: "", it) }) &&
            rule.excludeDomains.none { hostMatches(pageHost ?: "", it) } &&
            (rule.resourceTypes.isEmpty() || type in rule.resourceTypes) &&
            type !in rule.excludedTypes &&
            (rule.thirdParty == null || rule.thirdParty == thirdParty) &&
            rule.pattern.containsMatchIn(url)
        }
        if (matching.any { it.exception }) return false
        return matching.any { !it.exception }
    }

    fun cosmeticCss(pageUrl: String?): String {
        val host = runCatching { Uri.parse(pageUrl ?: "").host?.lowercase(Locale.ROOT) }.getOrNull() ?: return ""
        val selectors = (genericCosmetic + cosmetic.filterKeys { hostMatches(host, it) }.values.flatten()).distinct()
        return selectors.filter { it.length < 500 && !it.contains("</style", true) }
            .joinToString(",") { it }.take(12000)
    }

    private fun parse(raw: String): Rule? {
        var line = raw.trim()
        if (line.isEmpty() || line.startsWith("!") || line.startsWith("[") || line.contains("##") || line.contains("#@#")) return null
        val exception = line.startsWith("@@")
        if (exception) line = line.drop(2)
        val split = line.split('$', limit = 2)
        var patternText = split[0]
        val options = split.getOrNull(1)?.split(',') ?: emptyList()
        val include = mutableSetOf<String>()
        val exclude = mutableSetOf<String>()
        val types = mutableSetOf<String>()
        val excludedTypes = mutableSetOf<String>()
        var thirdParty: Boolean? = null
        var matchCase = false
        options.forEach { rawOpt ->
            val opt = rawOpt.trim()
            when {
                opt.startsWith("domain=") -> opt.removePrefix("domain=").split('|').forEach {
                    if (it.startsWith("~")) exclude.add(it.drop(1).lowercase(Locale.ROOT))
                    else include.add(it.lowercase(Locale.ROOT))
                }
                opt == "third-party" -> thirdParty = true
                opt == "~third-party" -> thirdParty = false
                opt == "match-case" -> matchCase = true
                opt.startsWith("~") && opt.drop(1) in knownTypes -> excludedTypes.add(opt.drop(1))
                opt in knownTypes -> types.add(opt)
            }
        }
        if (patternText.isBlank()) return null
        val anchoredStart = patternText.startsWith("|")
        val anchoredEnd = patternText.endsWith("|")
        if (patternText.startsWith("||")) {
            patternText = patternText.drop(2)
            val hostPart = patternText.substringBeforeAny('/', '^', '*', '|')
            if (hostPart.isBlank()) return null
            patternText = "^[a-zA-Z][a-zA-Z0-9+.-]*://([^/]+\\.)?" + Regex.escape(hostPart) + "(?::[0-9]+)?" + wildcardTail(patternText.removePrefix(hostPart))
        } else {
            if (anchoredStart) patternText = patternText.drop(1)
            if (anchoredEnd) patternText = patternText.dropLast(1)
            patternText = (if (anchoredStart) "^" else "") + wildcard(patternText) + (if (anchoredEnd) "$" else "")
        }
        return runCatching {
            Rule(if (matchCase) Regex(patternText) else Regex(patternText, RegexOption.IGNORE_CASE), exception, include, exclude, types, excludedTypes, thirdParty, matchCase)
        }.getOrNull()
    }

    private fun wildcard(text: String): String = buildString {
        var i = 0
        while (i < text.length) {
            when (text[i]) {
                '*' -> append(".*")
                '^' -> append("(?:[^a-zA-Z0-9_.%-]|$)")
                '|' -> append("\\|")
                else -> append(Regex.escape(text[i].toString()))
            }
            i++
        }
    }

    private fun wildcardTail(text: String): String = wildcard(text)
    private fun String.substringBeforeAny(vararg chars: Char): String {
        val i = indexOfAny(chars)
        return if (i < 0) this else substring(0, i)
    }
    private fun hostMatches(host: String, domain: String): Boolean =
        host == domain || host.endsWith(".$domain")

    companion object {
        private val knownTypes = setOf("script", "image", "stylesheet", "font", "media", "xmlhttprequest", "subdocument", "document", "other")
    }
}
