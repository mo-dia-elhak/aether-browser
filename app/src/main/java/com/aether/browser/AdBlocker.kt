package com.aether.browser

import android.content.Context
import android.net.Uri

/**
 * Native request-level blocker. Runs inside WebViewClient.shouldInterceptRequest,
 * so blocked requests never leave the device (saves data, speeds up loading).
 * Extend it by dropping a hosts-format list into assets/blocklist.txt.
 */
class AdBlocker(ctx: Context) {
    private val hosts = HashSet<String>(BUILT_IN)

    init {
        try {
            ctx.assets.open("blocklist.txt").bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val s = line.trim()
                    if (s.isEmpty() || s.startsWith("#")) return@forEach
                    val parts = s.split(Regex("\\s+"))
                    val h = (if (parts.size > 1) parts[1] else parts[0]).lowercase()
                    if (h.contains('.')) hosts.add(h)
                }
            }
        } catch (_: Exception) { }
    }

    fun shouldBlock(uri: Uri): Boolean {
        var h = uri.host?.lowercase() ?: return false
        while (h.contains('.')) {            // matches host and all parent domains
            if (h in hosts) return true
            h = h.substring(h.indexOf('.') + 1)
        }
        val path = uri.encodedPath?.lowercase() ?: return false
        return PATHS.any { path.contains(it) }
    }

    companion object {
        private val PATHS = listOf("/pagead/", "/adserver/", "/ads/banner", "/adframe")

        /** Hides leftover ad containers after load. */
        val COSMETIC_JS = """
            (function(){if(document.getElementById('__aether_css'))return;
            var s=document.createElement('style');s.id='__aether_css';
            s.textContent='ins.adsbygoogle,.adsbygoogle,[id^="google_ads"],[id^="div-gpt-ad"],[class*="ad-banner"],[class*="ad-slot"],[data-ad-slot],iframe[src*="doubleclick"],iframe[src*="googlesyndication"]{display:none!important}';
            (document.head||document.documentElement).appendChild(s);})();
        """.trimIndent()

        private val BUILT_IN = listOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com", "google-analytics.com",
            "googletagmanager.com", "googletagservices.com", "adservice.google.com", "adnxs.com",
            "adsrvr.org", "advertising.com", "taboola.com", "outbrain.com", "criteo.com", "criteo.net",
            "rubiconproject.com", "pubmatic.com", "openx.net", "casalemedia.com", "moatads.com",
            "scorecardresearch.com", "quantserve.com", "amazon-adsystem.com", "connect.facebook.net",
            "hotjar.com", "mixpanel.com", "cdn.segment.com", "amplitude.com", "appsflyer.com",
            "mopub.com", "applovin.com", "inmobi.com", "smaato.net", "yieldmo.com", "sharethrough.com",
            "teads.tv", "adform.net", "adroll.com", "bidswitch.net", "contextweb.com", "lijit.com",
            "33across.com", "zedo.com", "popads.net", "popcash.net", "propellerads.com", "exoclick.com",
            "juicyads.com", "trafficjunky.net", "clickadu.com", "adsterra.com", "hilltopads.net",
            "onclickads.net", "ads-twitter.com", "analytics.twitter.com", "analytics.tiktok.com",
            "bat.bing.com", "clarity.ms", "fullstory.com", "mouseflow.com", "crazyegg.com", "chartbeat.com"
        )
    }
}
