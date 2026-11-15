package com.aether.browser

import android.net.Uri
import android.webkit.WebResourceRequest
import java.util.concurrent.atomic.AtomicInteger

enum class Kind { NONE, AD_TRACKER, SCRIPT }

/** Live per-page counters shown on the shield badge. */
class PageStats {
    val ads = AtomicInteger(); val scripts = AtomicInteger(); val popups = AtomicInteger()
    fun total() = ads.get() + scripts.get() + popups.get()
    fun reset() { ads.set(0); scripts.set(0); popups.set(0) }
    fun add(k: Kind) { when (k) { Kind.AD_TRACKER -> ads.incrementAndGet(); Kind.SCRIPT -> scripts.incrementAndGet(); else -> 0 } }
}

/**
 * Brave-style shields. Decides, per request and per site, whether to block.
 *  Standard:   ad/tracker host + path blocklist.
 *  Aggressive: also third-party scripts (common CDNs allowed so sites keep working).
 * A site can have shields turned off (e.g. a streaming service that misbehaves).
 */
class Shields(private val blocker: AdBlocker, private val store: Store) {

    fun isUp(site: String) = site !in store.shieldsDown

    fun upgradable(url: String): Boolean {
        val h = Uri.parse(url).host ?: return false
        return h != "localhost" && !h.endsWith(".local") && !h.contains(':') && !h.matches(Regex("[0-9.]+"))
    }

    fun decide(req: WebResourceRequest, pageUrl: String?): Kind {
        val page = siteKey(pageUrl)
        if (page.isEmpty() || !isUp(page)) return Kind.NONE
        val uri = req.url
        if (blocker.shouldBlock(uri)) return Kind.AD_TRACKER
        if (!store.aggressive) return Kind.NONE
        val host = uri.host?.lowercase() ?: return Kind.NONE
        if (registrable(host) == registrable(page)) return Kind.NONE
        val path = uri.encodedPath?.lowercase().orEmpty()
        if ((path.endsWith(".js") || path.endsWith(".mjs")) && CDN.none { host == it || host.endsWith(".$it") }) return Kind.SCRIPT
        return Kind.NONE
    }

    companion object {
        fun siteKey(url: String?): String = Uri.parse(url ?: "").host?.lowercase()?.removePrefix("www.") ?: ""

        private val TWO = setOf("co.uk", "org.uk", "ac.uk", "gov.uk", "com.au", "net.au", "org.au", "co.jp",
            "co.in", "co.nz", "com.br", "com.cn", "com.mx", "co.za", "com.tr", "com.sg", "com.hk", "co.kr")

        fun registrable(h: String): String {
            val p = h.split('.')
            if (p.size <= 2) return h
            return if (p.takeLast(2).joinToString(".") in TWO) p.takeLast(3).joinToString(".") else p.takeLast(2).joinToString(".")
        }

        private val CDN = listOf("cdnjs.cloudflare.com", "cloudflare.com", "jsdelivr.net", "unpkg.com", "ajax.googleapis.com",
            "fonts.googleapis.com", "gstatic.com", "code.jquery.com", "bootstrapcdn.com")
    }
}
