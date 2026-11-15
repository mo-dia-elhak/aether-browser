package com.aether.browser

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aether.browser.databinding.ActivityMainBinding
import com.aether.browser.databinding.ItemFavoriteBinding
import com.aether.browser.databinding.ItemTabBinding
import com.aether.browser.databinding.SheetSettingsBinding
import com.aether.browser.databinding.SheetShieldsBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.min

/** A tab. Its WebView is created lazily, so background/restored tabs cost no RAM until opened. */
class BrowserTab(private val factory: () -> WebView) {
    private var wv: WebView? = null
    val webView: WebView get() = wv ?: factory().also { wv = it }
    val hasWebView: Boolean get() = wv != null
    @Volatile var url: String? = null          // null/empty = start page
    var pending: String? = null      // restored URL, loaded when the tab is first selected
    var title: String = "Start Page"
    var thumb: Bitmap? = null
    var desktop = false
    val stats = PageStats()
    var upgradedFrom: String? = null

    fun destroy() {
        wv?.let { it.stopLoading(); (it.parent as? ViewGroup)?.removeView(it); it.destroy() }
        wv = null
    }
}

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private lateinit var store: Store
    private lateinit var adBlocker: AdBlocker
    private lateinit var shields: Shields
    private var flushed = 0
    private val tabs = mutableListOf<BrowserTab>()
    private var current: BrowserTab? = null
    private val blocked = AtomicInteger(0)
    private var adBlockOn = true
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val tabAdapter = TabAdapter()
    private val favAdapter = FavAdapter()

    private val mobileUa by lazy {
        WebSettings.getDefaultUserAgent(this).replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+ "), "")
    }

    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        fileCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        store = Store(this)
        adBlocker = AdBlocker(this)
        shields = Shields(adBlocker, store)
        adBlockOn = store.adBlock
        setupUi()
        restoreTabs()
        if (savedInstanceState == null) handleIntent(intent)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = onBack()
        })
    }

    // ================================================================ UI setup
    private fun setupUi() {
        b.favGrid.layoutManager = GridLayoutManager(this, 4)
        b.favGrid.adapter = favAdapter
        favAdapter.items = store.favorites()
        b.tabGrid.layoutManager = GridLayoutManager(this, 2)
        b.tabGrid.adapter = tabAdapter
        updateEngineUi()

        b.urlField.setOnEditorActionListener { v, id, ev ->
            if (id == EditorInfo.IME_ACTION_GO || (ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN)) {
                navigate(v.text.toString()); hideKeyboard(); v.clearFocus(); true
            } else false
        }
        // Safari-style: show only the host when idle, the full address when editing.
        b.urlField.setOnFocusChangeListener { v, focused ->
            val et = v as EditText
            val u = current?.url.orEmpty()
            if (focused) { et.setText(u); et.post { et.selectAll() } } else et.setText(display(u))
        }

        b.btnEngine.setOnClickListener { showEngineMenu(it) }
        b.btnShield.setOnClickListener { showShields() }
        b.btnBack.setOnClickListener { goBack() }
        b.btnForward.setOnClickListener { current?.takeIf { it.hasWebView }?.webView?.goForward() }
        b.btnReload.setOnClickListener { current?.takeIf { !it.url.isNullOrEmpty() }?.webView?.reload() }
        b.btnShare.setOnClickListener { share() }
        b.btnTabs.setOnClickListener { openSwitcher() }
        b.btnMenu.setOnClickListener { showMenu(it) }
        b.btnNewTab.setOnClickListener { select(newTab(false)); hideSwitcher() }
        b.btnDone.setOnClickListener { hideSwitcher() }
    }

    private fun updateEngineUi() {
        val e = store.engine
        b.btnEngine.text = e.short
        b.urlField.hint = "Search ${e.label} or enter website"
    }

    // ================================================================ Tabs
    private fun newTab(select: Boolean = true): BrowserTab {
        lateinit var tab: BrowserTab
        tab = BrowserTab { buildWebView(tab) }
        tabs.add(tab)
        if (select) select(tab)
        return tab
    }

    private fun select(tab: BrowserTab) {
        if (current !== tab) current?.takeIf { it.hasWebView }?.webView?.onPause()
        current = tab
        tab.pending?.let { tab.pending = null; applyJs(tab.webView, it); tab.webView.loadUrl(it) }
        if (tab.hasWebView) tab.webView.onResume()
        render()
    }

    private fun closeTab(tab: BrowserTab) {
        val i = tabs.indexOf(tab)
        tabs.remove(tab)
        tab.destroy()
        if (tab === current) current = null
        if (tabs.isEmpty()) select(newTab(false))
        else if (current == null) select(tabs[min(i, tabs.size - 1)])
        if (tabs.size == 1 && i == 0) tabAdapter.notifyDataSetChanged()
        else { tabAdapter.notifyItemRemoved(i); tabAdapter.notifyItemRangeChanged(0, tabs.size) }
        b.tabCount.text = tabs.size.toString()
    }

    private fun render() {
        val t = current ?: return
        val isStart = t.url.isNullOrEmpty()
        b.webContainer.removeAllViews()
        b.startPage.visibility = if (isStart) View.VISIBLE else View.GONE
        if (!isStart) {
            val wv = t.webView
            (wv.parent as? ViewGroup)?.removeView(wv)
            b.webContainer.addView(wv, FrameLayout.LayoutParams(-1, -1))
        }
        if (!b.urlField.hasFocus()) b.urlField.setText(display(t.url))
        b.progress.visibility = View.GONE
        b.tabCount.text = tabs.size.toString()
        b.statNumber.text = "%,d".format(store.lifetimeBlocked + blocked.get() - flushed)
        b.stats.text = "${blocked.get()} blocked this session\nNo telemetry. Everything stays on this device."
        updateNav()
        updateBadge()
    }

    private fun updateBadge() {
        val t = current
        val show = t != null && !t.url.isNullOrEmpty()
        b.btnShield.visibility = if (show) View.VISIBLE else View.GONE
        if (!show || t == null) return
        val up = adBlockOn && shields.isUp(Shields.siteKey(t.url))
        b.btnShield.text = if (up) t.stats.total().toString() else "off"
        b.btnShield.alpha = if (up) 1f else .5f
    }

    /** Per-site "block all scripts" (Brave-style). Applied before each navigation. */
    private fun applyJs(wv: WebView, url: String) {
        wv.settings.javaScriptEnabled = Shields.siteKey(url) !in store.scriptsOff
    }

    private fun updateNav() {
        val t = current
        val canBack = t != null && !t.url.isNullOrEmpty()
        val canFwd = t != null && t.hasWebView && t.webView.canGoForward()
        b.btnBack.alpha = if (canBack) 1f else .35f
        b.btnForward.alpha = if (canFwd) 1f else .35f
    }

    private fun goBack() {
        val t = current ?: return
        if (t.url.isNullOrEmpty()) return
        if (t.webView.canGoBack()) t.webView.goBack() else { t.url = null; render() }
    }

    private fun navigate(input: String) {
        val t = current ?: return
        var u = resolve(input)
        if (u.isEmpty()) return
        t.upgradedFrom = null
        if (store.httpsUpgrade && u.startsWith("http://") && shields.upgradable(u)) {
            t.upgradedFrom = u; u = "https://" + u.removePrefix("http://")
        }
        t.url = u
        render()
        applyJs(t.webView, u)
        t.webView.loadUrl(u)
    }

    /** URL, bare domain, or search query. Queries go to the engine the user picked. */
    private fun resolve(input: String): String {
        val s = input.trim()
        if (s.isEmpty()) return ""
        if (s.startsWith("http://") || s.startsWith("https://")) return s
        if (!s.contains(' ') && (s.contains('.') || s.startsWith("localhost"))) return "https://$s"
        return store.engine.urlFor(s)
    }

    private fun display(u: String?): String {
        if (u.isNullOrEmpty()) return ""
        return Uri.parse(u).host?.removePrefix("www.") ?: u
    }

    private fun handleIntent(i: Intent?) {
        val data = i?.data ?: return
        if (i.action != Intent.ACTION_VIEW) return
        val t = newTab(false)
        t.url = data.toString(); t.pending = t.url
        select(t)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIntent(intent) }

    // ================================================================ Tab switcher
    private fun openSwitcher() {
        hideKeyboard()
        current?.let { capture(it) }
        tabAdapter.notifyDataSetChanged()
        showSwitcher()
    }

    private fun showSwitcher() = b.tabSwitcher.run {
        alpha = 0f; scaleX = .94f; scaleY = .94f; visibility = View.VISIBLE
        animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    }

    private fun hideSwitcher() {
        b.tabSwitcher.animate().alpha(0f).scaleX(1.04f).scaleY(1.04f).setDuration(160)
            .withEndAction { b.tabSwitcher.visibility = View.GONE }.start()
    }

    private fun capture(tab: BrowserTab) {
        if (!tab.hasWebView || tab.url.isNullOrEmpty()) return
        val wv = tab.webView
        if (wv.width == 0 || wv.height == 0) return
        try {
            val bmp = Bitmap.createBitmap(wv.width / 2, wv.height / 2, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp); c.scale(0.5f, 0.5f); wv.draw(c)
            tab.thumb = bmp
        } catch (_: Throwable) { }
    }

    inner class TabAdapter : RecyclerView.Adapter<TabAdapter.VH>() {
        inner class VH(val v: ItemTabBinding) : RecyclerView.ViewHolder(v.root)
        override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(ItemTabBinding.inflate(layoutInflater, p, false))
        override fun getItemCount() = tabs.size
        override fun onBindViewHolder(h: VH, i: Int) {
            val t = tabs[i]
            h.v.title.text = if (t.url.isNullOrEmpty()) "Start Page" else t.title
            h.v.thumb.setImageBitmap(t.thumb)
            h.v.root.setBackgroundResource(if (t === current) R.drawable.bg_card_selected else R.drawable.bg_card)
            h.v.root.setOnClickListener { select(t); hideSwitcher() }
            h.v.close.setOnClickListener { closeTab(t) }
        }
    }

    // ================================================================ Favorites (icons generated locally)
    inner class FavAdapter : RecyclerView.Adapter<FavAdapter.VH>() {
        var items = mutableListOf<Fav>()
        inner class VH(val v: ItemFavoriteBinding) : RecyclerView.ViewHolder(v.root)
        override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(ItemFavoriteBinding.inflate(layoutInflater, p, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(h: VH, i: Int) {
            val f = items[i]
            h.v.label.text = f.title
            h.v.icon.text = f.title.take(1).uppercase()
            h.v.icon.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.HSVToColor(floatArrayOf((abs(f.title.hashCode()) % 360).toFloat(), 0.55f, 0.62f)))
            }
            h.v.root.setOnClickListener { navigate(f.url) }
            h.v.root.setOnLongClickListener {
                AlertDialog.Builder(this@MainActivity).setMessage("Remove ${f.title}?")
                    .setPositiveButton("Remove") { _, _ -> items.remove(f); store.saveFavorites(items); notifyDataSetChanged() }
                    .setNegativeButton("Cancel", null).show()
                true
            }
        }
    }

    // ================================================================ Menus, settings, history
    private fun showEngineMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            SearchEngine.values().forEachIndexed { i, e ->
                menu.add(0, i, i, e.label).apply { isCheckable = true; isChecked = e == store.engine }
            }
            menu.setGroupCheckable(0, true, true)
            setOnMenuItemClickListener { store.engine = SearchEngine.values()[it.itemId]; updateEngineUi(); true }
            show()
        }
    }

    private fun showShields() {
        val t = current ?: return
        if (t.url.isNullOrEmpty()) return
        val key = Shields.siteKey(t.url)
        val d = BottomSheetDialog(this)
        val s = SheetShieldsBinding.inflate(layoutInflater)
        var changed = false
        val st = t.stats
        s.shTitle.text = key
        s.shCount.text = st.total().toString()
        s.shBreakdown.text = "Ads & trackers ${st.ads.get()}  ·  Third-party scripts ${st.scripts.get()}  ·  Pop-ups ${st.popups.get()}"
        s.swSite.isChecked = shields.isUp(key)
        s.swSite.setOnCheckedChangeListener { _, v -> store.setShieldsUp(key, v); changed = true }
        s.swScripts.isChecked = key in store.scriptsOff
        s.swScripts.setOnCheckedChangeListener { _, v -> store.setScriptsOff(key, v); changed = true }
        s.swAggressive.isChecked = store.aggressive
        s.swAggressive.setOnCheckedChangeListener { _, v -> store.aggressive = v; changed = true }
        s.swHttps.isChecked = store.httpsUpgrade
        s.swHttps.setOnCheckedChangeListener { _, v -> store.httpsUpgrade = v }
        d.setOnDismissListener {
            updateBadge()
            if (changed && t.hasWebView) { applyJs(t.webView, t.url ?: ""); t.webView.reload() }
        }
        d.setContentView(s.root)
        d.show()
    }

    private fun showMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "New Tab")
            menu.add(0, 2, 1, "Add to Favorites")
            menu.add(0, 3, 2, "Find on Page")
            menu.add(0, 4, 3, "Request Desktop Site").apply { isCheckable = true; isChecked = current?.desktop == true }
            menu.add(0, 5, 4, "History")
            menu.add(0, 6, 5, "Settings")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> select(newTab(false))
                    2 -> addFavorite()
                    3 -> findInPage()
                    4 -> toggleDesktop()
                    5 -> showHistory()
                    6 -> showSettings()
                }
                true
            }
            show()
        }
    }

    private fun showSettings() {
        val d = BottomSheetDialog(this)
        val s = SheetSettingsBinding.inflate(layoutInflater)
        SearchEngine.values().forEach { e ->
            s.engineGroup.addView(RadioButton(this).apply {
                id = View.generateViewId(); text = e.label; tag = e; textSize = 16f
                isChecked = e == store.engine; setPadding(dp(8), dp(10), dp(8), dp(10))
            })
        }
        s.engineGroup.setOnCheckedChangeListener { g, id ->
            store.engine = g.findViewById<RadioButton>(id).tag as SearchEngine; updateEngineUi()
        }
        s.swAdblock.isChecked = adBlockOn
        s.swAdblock.setOnCheckedChangeListener { _, v -> adBlockOn = v; store.adBlock = v }
        s.swHistory.isChecked = store.saveHistory
        s.swHistory.setOnCheckedChangeListener { _, v -> store.saveHistory = v }
        s.btnHistory.setOnClickListener { d.dismiss(); showHistory() }
        s.btnClear.setOnClickListener {
            d.dismiss()
            AlertDialog.Builder(this).setTitle("Clear all browsing data?")
                .setMessage("Cookies, cache, site data, history and open tabs will be deleted from this device.")
                .setPositiveButton("Clear") { _, _ -> clearData() }
                .setNegativeButton("Cancel", null).show()
        }
        d.setContentView(s.root)
        d.show()
    }

    private fun showHistory() {
        val items = store.history().take(100)
        if (items.isEmpty()) return toast("History is empty")
        val d = BottomSheetDialog(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(16), dp(8), dp(8)) }
        root.addView(TextView(this).apply { text = "History"; textSize = 20f; setPadding(dp(16), 0, 0, dp(8)); setTypeface(typeface, android.graphics.Typeface.BOLD) })
        val lv = ListView(this).apply {
            isNestedScrollingEnabled = true
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1, items.map { "${it.title}\n${display(it.url)}" })
            setOnItemClickListener { _, _, i, _ -> d.dismiss(); navigate(items[i].url) }
        }
        root.addView(lv, LinearLayout.LayoutParams(-1, dp(440)))
        root.addView(TextView(this).apply {
            text = "Clear History"; textSize = 16f; gravity = android.view.Gravity.CENTER; setPadding(0, dp(14), 0, dp(14))
            setTextColor(getColor(R.color.aether_accent))
            setOnClickListener { store.clearHistory(); d.dismiss(); toast("History cleared") }
        })
        d.setContentView(root)
        d.show()
    }

    private fun findInPage() {
        val t = current?.takeIf { it.hasWebView && !it.url.isNullOrEmpty() } ?: return
        val input = EditText(this).apply { hint = "Find on page"; setSingleLine(); setPadding(dp(24), dp(16), dp(24), dp(16)) }
        AlertDialog.Builder(this).setTitle("Find on Page").setView(input)
            .setPositiveButton("Find") { _, _ -> t.webView.findAllAsync(input.text.toString()) }
            .setNegativeButton("Clear") { _, _ -> t.webView.clearMatches() }.show()
    }

    private fun toggleDesktop() {
        val t = current ?: return
        t.desktop = !t.desktop
        if (t.hasWebView) {
            t.webView.settings.userAgentString = if (t.desktop) DESKTOP_UA else mobileUa
            if (!t.url.isNullOrEmpty()) t.webView.reload()
        }
    }

    private fun addFavorite() {
        val t = current ?: return
        val u = t.url
        if (u.isNullOrEmpty()) return toast("Open a page first")
        val name = display(u).substringBefore('.').replaceFirstChar { it.uppercase() }
        favAdapter.items.add(Fav(name, u))
        store.saveFavorites(favAdapter.items); favAdapter.notifyDataSetChanged()
        toast("Added to Favorites")
    }

    private fun clearData() {
        tabs.forEach { if (it.hasWebView) it.webView.apply { clearCache(true); clearHistory() } }
        CookieManager.getInstance().apply { removeAllCookies(null); flush() }
        WebStorage.getInstance().deleteAllData()
        store.clearHistory(); store.clearTabs()
        toast("Browsing data cleared")
    }

    private fun share() {
        val u = current?.url ?: return
        if (u.isEmpty()) return
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, u), null))
    }

    private fun linkMenu(url: String) {
        AlertDialog.Builder(this).setTitle(display(url)).setItems(arrayOf("Open in New Tab", "Copy Link", "Share")) { _, i ->
            when (i) {
                0 -> { val t = newTab(false); t.url = url; t.pending = url; select(t) }
                1 -> { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("link", url)); toast("Link copied") }
                2 -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), null))
            }
        }.show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun hideKeyboard() =
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(b.urlField.windowToken, 0)

    // ================================================================ WebView factory
    private fun buildWebView(tab: BrowserTab): WebView = WebView(this).apply {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false   // pop-up blocking
            mediaPlaybackRequiresUserGesture = true
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(false)
            safeBrowsingEnabled = false                      // no URL hashes sent to Google
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true); builtInZoomControls = true; displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = if (tab.desktop) DESKTOP_UA else mobileUa   // Chrome-like UA for compatibility
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)

        setOnLongClickListener {
            val r = hitTestResult
            if ((r.type == WebView.HitTestResult.SRC_ANCHOR_TYPE || r.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) && r.extra != null) {
                linkMenu(r.extra!!); true
            } else false
        }

        setDownloadListener { url, ua, cd, mime, _ ->
            try {
                val req = DownloadManager.Request(Uri.parse(url)).apply {
                    setMimeType(mime); addRequestHeader("User-Agent", ua)
                    CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(url, cd, mime))
                }
                (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                toast("Downloading…")
            } catch (e: Exception) { toast("Download failed") }
        }

        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                if (!adBlockOn || req.isForMainFrame) return null
                val kind = shields.decide(req, tab.url)
                if (kind == Kind.NONE) return null
                tab.stats.add(kind)
                blocked.incrementAndGet()
                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }

            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                when (req.url.scheme) {
                    "http" -> {
                        val u = req.url.toString()
                        if (req.isForMainFrame && store.httpsUpgrade && shields.upgradable(u)) {
                            tab.upgradedFrom = u; applyJs(view, u)
                            view.loadUrl(u.replaceFirst("http://", "https://")); return true
                        }
                        if (req.isForMainFrame) applyJs(view, u)
                        return false
                    }
                    "https" -> { if (req.isForMainFrame) applyJs(view, req.url.toString()); return false }
                    "about", "data", "blob" -> return false
                    "tel", "mailto", "sms", "geo" ->
                        try { startActivity(Intent(Intent.ACTION_VIEW, req.url)) } catch (_: ActivityNotFoundException) { }
                }
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                tab.url = url
                tab.stats.reset()
                if (tab === current) {
                    b.progress.visibility = View.VISIBLE
                    if (b.startPage.visibility == View.VISIBLE) render()
                    if (!b.urlField.hasFocus()) b.urlField.setText(display(url))
                    updateNav()
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                tab.title = view.title?.takeIf { it.isNotBlank() } ?: display(url)
                if (adBlockOn) view.evaluateJavascript(AdBlocker.COSMETIC_JS, null)
                if (store.saveHistory && url.startsWith("http")) store.addHistory(tab.title, url)
                if (tab === current) { b.progress.visibility = View.GONE; updateNav(); updateBadge() }
            }

            // If an HTTPS upgrade fails, fall back to the original http:// address once.
            override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
                val back = tab.upgradedFrom
                if (req.isForMainFrame && back != null && req.url.scheme == "https") {
                    tab.upgradedFrom = null; tab.url = back; view.loadUrl(back)
                }
            }

            // A crashed/killed renderer must never take the whole browser down.
            override fun onRenderProcessGone(view: WebView, d: RenderProcessGoneDetail): Boolean {
                val u = tab.url
                b.root.post {
                    tab.destroy(); tab.pending = u
                    if (tab === current) select(tab)
                    toast("Page was reloaded to free memory")
                }
                return true
            }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, p: Int) {
                if (tab === current) { b.progress.progress = p; updateBadge(); if (p >= 100) b.progress.visibility = View.GONE }
            }

            override fun onReceivedTitle(view: WebView, title: String?) { if (!title.isNullOrBlank()) tab.title = title }

            // Pop-ups open only if triggered by a real user tap.
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, msg: Message): Boolean {
                if (!isUserGesture) { blocked.incrementAndGet(); tab.stats.popups.incrementAndGet(); return false }
                val t = newTab(false)
                t.url = "about:blank"
                (msg.obj as WebView.WebViewTransport).webView = t.webView
                msg.sendToTarget()
                select(t)
                return true
            }

            // <input type="file"> uploads via the system picker (no storage permission needed).
            override fun onShowFileChooser(wv: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = cb
                return try { filePicker.launch(params.createIntent()); true }
                catch (e: Exception) { fileCallback = null; false }
            }

            override fun onShowCustomView(view: View, cb: CustomViewCallback) {   // fullscreen video
                customView = view; customCallback = cb
                b.fullscreen.addView(view); b.fullscreen.visibility = View.VISIBLE
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
            }

            override fun onHideCustomView() = hideCustom()
        }
    }

    private fun hideCustom() {
        val v = customView ?: return
        b.fullscreen.removeView(v); b.fullscreen.visibility = View.GONE
        customView = null
        customCallback?.onCustomViewHidden(); customCallback = null
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
    }

    private fun onBack() {
        when {
            customView != null -> hideCustom()
            b.tabSwitcher.visibility == View.VISIBLE -> hideSwitcher()
            current?.url.isNullOrEmpty() -> finish()
            else -> goBack()
        }
    }

    // ================================================================ Lifecycle
    private fun restoreTabs() {
        val (urls, idx) = store.loadTabs()
        urls.forEach { u -> val t = newTab(false); if (u.isNotEmpty()) { t.url = u; t.pending = u } }
        if (tabs.isEmpty()) newTab(false)
        select(tabs[idx.coerceIn(0, tabs.size - 1)])
    }

    override fun onPause() { super.onPause(); current?.takeIf { it.hasWebView }?.webView?.onPause() }
    override fun onResume() { super.onResume(); current?.takeIf { it.hasWebView }?.webView?.onResume() }
    override fun onStop() {
        super.onStop()
        store.lifetimeBlocked += blocked.get() - flushed; flushed = blocked.get()
        store.saveTabs(tabs.map { it.url ?: "" }, tabs.indexOf(current)) }
    override fun onDestroy() { tabs.forEach { it.destroy() }; super.onDestroy() }

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
