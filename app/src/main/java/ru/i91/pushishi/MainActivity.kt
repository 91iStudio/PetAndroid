package ru.i91.pushishi

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

/**
 * The game itself: the same pet.91i.ru, inside the app (one app instead of a site icon and an app with the same name).
 * Only the game's own address opens here; any other link goes to the browser. The game can open the widget settings,
 * and tells the app its key so the widgets link by themselves — the key stays on this phone.
 */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var store: Store

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        store = Store(this)
        web = WebView(this).apply {
            setBackgroundColor(Color.rgb(0xF3, 0xD9, 0xB1))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.textZoom = 100 // the game lays itself out; the system's big font would break the room
            settings.userAgentString = settings.userAgentString + " PushishiApp/" + version()
            // the page is light only: no automatic darkening (some phones, e.g. Black Shark, turned its pink buttons white)
            if (Build.VERSION.SDK_INT >= 33) settings.isAlgorithmicDarkeningAllowed = false
            else if (Build.VERSION.SDK_INT >= 29) noForceDark(settings)
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val u = request.url
                    if (u.scheme == "https" && u.host == HOST) return false
                    try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) { }
                    return true
                }
            }
            addJavascriptInterface(Bridge(), "PushishiApp")
        }
        // Android 15 draws apps under the status bar: keep the game below it
        setContentView(FrameLayout(this).apply {
            fitsSystemWindows = true
            setBackgroundColor(Color.rgb(0xF3, 0xD9, 0xB1))
            addView(web)
        })
        if (state != null) web.restoreState(state)
        if (state == null || web.url == null) web.loadUrl(address(intent))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.data != null) web.loadUrl(address(intent))
    }

    /** pushishi://move/123456 — the site in the phone's browser hands the pets over to the app with a one-time code. */
    private fun address(intent: Intent?): String {
        val d: Uri? = intent?.data
        val code = d?.takeIf { it.scheme == "pushishi" && it.host == "move" }?.lastPathSegment?.filter { it.isDigit() }
        return if (code != null && code.length == 6) "${Api.SITE}/#move=$code" else "${Api.SITE}/"
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        web.saveState(out)
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        web.resumeTimers()
    }

    override fun onPause() {
        super.onPause()
        web.onPause()
        web.pauseTimers()
        // back on the home screen: the widgets catch up with what happened in the game
        SyncJob.schedule(this)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // «back» closes the game's open window first; with nothing open the app goes to the background
        web.evaluateJavascript("window.appBack ? window.appBack() : false") { handled ->
            if (handled != "true") { if (web.canGoBack()) web.goBack() else super@MainActivity.onBackPressed() }
        }
    }

    @Suppress("DEPRECATION")
    @android.annotation.TargetApi(29)
    private fun noForceDark(s: WebSettings) { s.forceDark = WebSettings.FORCE_DARK_OFF }

    private fun version() = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "dev" } catch (_: Exception) { "dev" }

    /** What the game may ask of the app. Nothing else is reachable from the page. */
    inner class Bridge {
        @JavascriptInterface
        fun widgets() = runOnUiThread { startActivity(Intent(this@MainActivity, WidgetsActivity::class.java)) }

        @JavascriptInterface
        fun version(): String = this@MainActivity.version()

        /**
         * The game's key on this phone and whether it has pets yet. The widgets are linked to these pets by themselves
         * (once — and again only if the game here switches to other pets).
         */
        @JavascriptInterface
        fun account(key: String, hasPets: Boolean) {
            if (!hasPets || key.length !in 20..100) return
            val mark = Store.fingerprint(key)
            if (store.linkedWith == mark && store.token != null) return
            Thread { PetWidget.safe(applicationContext) { link(key, mark) } }.start()
        }
    }

    @Synchronized
    private fun link(key: String, mark: String) {
        if (store.linkedWith == mark && store.token != null) return
        val nonce = Api.start(this).json?.optString("nonce")?.takeIf { it.isNotEmpty() } ?: return
        if (!Api.claim(this, key, nonce).ok) return
        val token = Api.poll(this, nonce).json?.optString("token")?.takeIf { it.isNotEmpty() } ?: return
        store.token = token
        store.linkedWith = mark
        Api.journal(this, "виджеты привязаны к питомцам из игры")
        Sync.run(this)
        PetWidget.refresh(this)
    }

    private companion object { const val HOST = "pet.91i.ru" }
}
