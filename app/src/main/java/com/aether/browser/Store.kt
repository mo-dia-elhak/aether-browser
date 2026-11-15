package com.aether.browser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Fav(val title: String, val url: String)
data class HistoryItem(val title: String, val url: String, val time: Long)

/** All persistence is local SharedPreferences. Nothing is ever uploaded. */
class Store(ctx: Context) {
    private val p = ctx.getSharedPreferences("aether", Context.MODE_PRIVATE)

    var adBlock: Boolean
        get() = p.getBoolean("adblock", true)
        set(v) = p.edit().putBoolean("adblock", v).apply()

    var saveHistory: Boolean
        get() = p.getBoolean("history_on", true)
        set(v) = p.edit().putBoolean("history_on", v).apply()

    var engine: SearchEngine
        get() = SearchEngine.from(p.getString("engine", null))
        set(v) = p.edit().putString("engine", v.id).apply()

    var aggressive: Boolean
        get() = p.getBoolean("aggressive", false)
        set(v) = p.edit().putBoolean("aggressive", v).apply()

    var httpsUpgrade: Boolean
        get() = p.getBoolean("https_up", true)
        set(v) = p.edit().putBoolean("https_up", v).apply()

    var lifetimeBlocked: Long
        get() = p.getLong("lifetime", 0L)
        set(v) = p.edit().putLong("lifetime", v).apply()

    val shieldsDown: Set<String> get() = p.getStringSet("sh_down", emptySet<String>()) ?: emptySet()
    val scriptsOff: Set<String> get() = p.getStringSet("sc_off", emptySet<String>()) ?: emptySet()
    fun setShieldsUp(host: String, up: Boolean) = toggle("sh_down", host, !up)
    fun setScriptsOff(host: String, off: Boolean) = toggle("sc_off", host, off)
    private fun toggle(key: String, host: String, add: Boolean) {
        val s = HashSet(p.getStringSet(key, emptySet<String>()) ?: emptySet<String>())
        if (add) s.add(host) else s.remove(host)
        p.edit().putStringSet(key, s).apply()
    }

    // ---- favorites
    fun favorites(): MutableList<Fav> {
        val s = p.getString("favs", null) ?: return defaults()
        return try {
            val a = JSONArray(s)
            MutableList(a.length()) { Fav(a.getJSONObject(it).getString("t"), a.getJSONObject(it).getString("u")) }
        } catch (e: Exception) { defaults() }
    }

    fun saveFavorites(l: List<Fav>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("t", it.title).put("u", it.url)) }
        p.edit().putString("favs", a.toString()).apply()
    }

    // ---- history (local only, capped)
    fun history(): MutableList<HistoryItem> {
        val s = p.getString("history", null) ?: return mutableListOf()
        return try {
            val a = JSONArray(s)
            MutableList(a.length()) {
                val o = a.getJSONObject(it); HistoryItem(o.getString("t"), o.getString("u"), o.getLong("d"))
            }
        } catch (e: Exception) { mutableListOf() }
    }

    fun addHistory(title: String, url: String) {
        val h = history()
        if (h.firstOrNull()?.url == url) return
        h.add(0, HistoryItem(title, url, System.currentTimeMillis()))
        while (h.size > 300) h.removeAt(h.size - 1)
        val a = JSONArray()
        h.forEach { a.put(JSONObject().put("t", it.title).put("u", it.url).put("d", it.time)) }
        p.edit().putString("history", a.toString()).apply()
    }

    fun clearHistory() = p.edit().remove("history").apply()

    // ---- open tabs
    fun saveTabs(urls: List<String>, index: Int) {
        p.edit().putString("tabs", JSONArray(urls).toString()).putInt("tabIndex", index).apply()
    }

    fun loadTabs(): Pair<List<String>, Int> {
        val s = p.getString("tabs", null) ?: return Pair(emptyList(), 0)
        return try {
            val a = JSONArray(s)
            Pair(List(a.length()) { a.getString(it) }, p.getInt("tabIndex", 0))
        } catch (e: Exception) { Pair(emptyList(), 0) }
    }

    fun clearTabs() = p.edit().remove("tabs").remove("tabIndex").apply()

    private fun defaults() = mutableListOf(
        Fav("Wikipedia", "https://wikipedia.org"),
        Fav("GitHub", "https://github.com"),
        Fav("YouTube", "https://youtube.com"),
        Fav("Reddit", "https://reddit.com"),
        Fav("Maps", "https://openstreetmap.org"),
        Fav("News", "https://news.ycombinator.com"),
        Fav("Weather", "https://wttr.in"),
        Fav("Archive", "https://archive.org")
    )
}
