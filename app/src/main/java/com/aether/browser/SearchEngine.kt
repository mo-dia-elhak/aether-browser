package com.aether.browser

import android.net.Uri

/** Search engines. Add one by adding a line here; the UI builds itself from this list. */
enum class SearchEngine(val id: String, val label: String, val short: String, private val template: String) {
    DUCKDUCKGO("ddg", "DuckDuckGo", "DDG", "https://duckduckgo.com/?q="),
    GOOGLE("google", "Google", "G", "https://www.google.com/search?q="),
    BING("bing", "Bing", "Bing", "https://www.bing.com/search?q="),
    ECOSIA("ecosia", "Ecosia", "Eco", "https://www.ecosia.org/search?q="),
    STARTPAGE("startpage", "Startpage", "SP", "https://www.startpage.com/do/search?q="),
    BRAVE("brave", "Brave Search", "Brave", "https://search.brave.com/search?q=");

    fun urlFor(query: String) = template + Uri.encode(query)

    companion object {
        fun from(id: String?) = values().firstOrNull { it.id == id } ?: DUCKDUCKGO
    }
}
