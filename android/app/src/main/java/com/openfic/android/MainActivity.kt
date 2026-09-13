package com.openfic.android

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import com.openfic.android.storage.AppPreferencesStore
import com.openfic.android.storage.BackendInstance
import com.openfic.android.storage.UiSource
import com.openfic.android.storage.localizedContext
import com.openfic.android.update.UpdateCheckResult
import com.openfic.android.update.UpdateChecker
import com.openfic.android.update.UpdateInfo
import com.openfic.android.databinding.ActivityMainBinding
import com.openfic.android.web.AndroidHostBridge
import com.openfic.android.web.BackendProbe
import com.openfic.android.web.OpenFicAssetHandler
import com.openfic.android.web.ProbeResult
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Hosts the OpenFic SPA and points it at a backend the user configures.
 *
 * Deliberately mirrors what the Electron shell does in its "remote instance" mode: the
 * UI ships inside the app, and the backend address is handed to the SPA through
 * `/runtime-config.json` rather than baked in at build time.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var preferences: AppPreferencesStore
    private lateinit var assetLoader: WebViewAssetLoader

    private var webView: WebView? = null

    /**
     * Identity of what the WebView currently has loaded. Compared as a whole rather than by
     * instance id alone: editing an instance's URL or interface source keeps the id but must
     * still trigger a reload, and re-entering the manager without changing anything must not.
     */
    private var loadedSignature: String? = null

    /** Instance signature whose "use the server interface instead" prompt was dismissed. */
    private var authWarningDismissedFor: String? = null

    /** The launch-time update check runs once per activity instance. */
    private var updateCheckStarted = false

    /** Document-start registration of the host shim; only used in server-interface mode. */
    private var hostShimHandle: ScriptHandler? = null

    private var pendingFileChooser: android.webkit.ValueCallback<Array<Uri>>? = null

    /** Held so [leaveApp] can step aside for one dispatch and let the system finish us. */
    private var backCallback: OnBackPressedCallback? = null

    /** Completed by the reset page once the origin's storage has been cleared. */
    private var pendingOriginReset: CancellableContinuation<Unit>? = null

    private val instancesLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        // Not forced: loadApp compares the full instance signature, so switching instances or
        // editing the active one's URL/interface source reloads, while simply opening the
        // manager and backing out does not.
        if (preferences.read().isConfigured) {
            loadApp()
        } else {
            finish()
        }
    }

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val callback = pendingFileChooser
        pendingFileChooser = null
        if (callback == null) return@registerForActivityResult
        val data = result.data
        callback.onReceiveValue(
            if (result.resultCode == RESULT_OK && data != null) {
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, data)
            } else {
                null
            },
        )
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(localizedContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferences = AppPreferencesStore(this)
        assetLoader = WebViewAssetLoader.Builder()
            .setDomain(OpenFicAssetHandler.DOMAIN)
            .addPathHandler("/", OpenFicAssetHandler(this, preferences))
            .build()

        applyWindowInsets()
        applyTheme(isDark = isSystemInDarkMode())
        registerBackHandling()

        // Lets `adb forward` + chrome://inspect attach to the WebView in debug builds, which
        // is the only way to see the SPA's own console and network activity on-device.
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        binding.retryButton.setOnClickListener { loadApp(force = true) }
        binding.errorSettingsButton.setOnClickListener { openInstanceManager() }

        if (preferences.read().isConfigured) {
            loadApp(force = true)
        } else {
            instancesLauncher.launch(Intent(this, InstancesActivity::class.java))
        }
    }

    // region layout & theme

    /**
     * Insets are applied as padding rather than left to the page's `env(safe-area-inset-*)`.
     * The frontend only reads those variables in one place and its viewport meta omits
     * `viewport-fit=cover`, so padding the WebView keeps the entire UI inside the safe area
     * on notched phones and gesture navigation without touching any of its CSS.
     */
    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime(),
            )
            view.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right,
                bottom = bars.bottom,
            )
            insets
        }
    }

    private fun isSystemInDarkMode(): Boolean =
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    /**
     * Called from the web app through the `openficAndroidHost` bridge, so the strip behind
     * the system bars matches the theme the SPA actually rendered rather than the system one.
     */
    private fun applyTheme(isDark: Boolean) {
        val background = if (isDark) COLOR_DARK_BACKGROUND else COLOR_LIGHT_BACKGROUND
        binding.root.setBackgroundColor(background)
        window.setBackgroundDrawable(ColorDrawable(background))
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }
    }

    /**
     * Back asks the web app first, then falls back to web history, then to leaving.
     *
     * The SPA is the only side that knows about overlays: the sidebar drawer, the settings
     * dialog and the assistant panel are not routes, so the WebView's history cannot see
     * them and the old "go back or exit" logic closed the whole app while one was open.
     * Route changes do push real history entries, so the middle step still walks pages.
     */
    private fun registerBackHandling() {
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val view = webView
                if (view == null) {
                    leaveApp()
                    return
                }
                val queried = runCatching {
                    view.evaluateJavascript(BACK_HANDLER_JS) { result ->
                        // The result arrives JSON-encoded, so a boolean true is the string "true".
                        if (result != "true") goBackOrLeave(view)
                    }
                }.isSuccess
                if (!queried) goBackOrLeave(view)
            }
        }
        backCallback = callback
        onBackPressedDispatcher.addCallback(this, callback)
    }

    private fun goBackOrLeave(view: WebView) {
        if (isFinishing || isDestroyed) return
        if (view.canGoBack()) view.goBack() else leaveApp()
    }

    /** Disables our callback for one dispatch so the system's default (finish) runs. */
    private fun leaveApp() {
        backCallback?.isEnabled = false
        onBackPressedDispatcher.onBackPressed()
    }

    // endregion

    // region loading

    private fun loadApp(force: Boolean = false) {
        val instance = preferences.read().activeInstance
        if (instance == null) {
            showError(getString(R.string.error_no_server))
            return
        }

        val signature = "${instance.id}|${instance.url}|${instance.uiSource}"
        if (!force && signature == loadedSignature) {
            return
        }

        // Every instance is served from the same page origin, so the previous backend's
        // cached projects, open tabs and unsaved writing buffers have to go before this one
        // loads — they are keyed by project id and belong to the other server.
        val isSwitch = loadedSignature != null && loadedSignature != signature
        loadedSignature = signature

        showLoading()

        lifecycleScope.launch {
            if (isSwitch) clearOriginStorage()
            val reachable = BackendProbe.probe(instance.url) is ProbeResult.Reachable
            if (isFinishing || isDestroyed) return@launch
            if (reachable) {
                if (instance.uiSource == UiSource.BUNDLED && authWarningDismissedFor != signature) {
                    val auth = BackendProbe.probeAuth(instance.url)
                    if (isFinishing || isDestroyed) return@launch
                    if (auth != null && auth.enabled && !auth.authenticated) {
                        offerServerInterface(instance, signature)
                        return@launch
                    }
                }
                showWebView()
                val view = ensureWebView()
                installHostShim(view, instance)
                val target = when (instance.uiSource) {
                    // Same-origin: the backend serves the identical SPA from `/`, so
                    // runtime-config is not needed and cookies behave like a normal browser.
                    UiSource.SERVER -> instance.url
                    UiSource.BUNDLED -> OpenFicAssetHandler.BASE_URL
                }
                view.loadUrl(target)
            } else {
                showError(getString(R.string.error_unreachable, instance.url))
            }
        }
    }

    /**
     * Makes sure the page gets `window.openficAndroidHost`.
     *
     * In bundled mode the asset handler already splices the shim into `index.html`. In server
     * mode the page comes from the backend and never passes through the asset handler, so it
     * is registered as a document-start script instead — which also runs before the bundle,
     * matching how the frontend feature-detects the host.
     *
     * The registration is scoped to the backend's own origin and replaced whenever the active
     * instance changes.
     */
    private fun installHostShim(view: WebView, instance: BackendInstance) {
        hostShimHandle?.remove()
        hostShimHandle = null
        if (instance.uiSource != UiSource.SERVER) return

        val parsed = Uri.parse(instance.url)
        val origin = "${parsed.scheme}://${parsed.authority}"
        if (parsed.scheme.isNullOrBlank() || parsed.authority.isNullOrBlank()) return

        hostShimHandle = runCatching {
            WebViewCompat.addDocumentStartJavaScript(
                view,
                AndroidHostBridge.HOST_SHIM_JS,
                setOf(origin),
            )
        }.onFailure { Log.w(TAG, "could not install host shim for $origin: ${it.message}") }
            .getOrNull()
    }

    /**
     * A password-protected backend cannot be used from the bundled interface: the login
     * cookie would be cross-site there, and `SameSite=Lax` makes WebView drop it on every
     * subsequent request, so the login form fails no matter what is typed. The server
     * interface loads the SPA from the backend itself, which is same-origin and works.
     *
     * Offering the switch beats letting the user discover this by failing to log in.
     */
    private fun offerServerInterface(instance: BackendInstance, signature: String) {
        showLoading()
        AlertDialog.Builder(this)
            .setTitle(R.string.auth_required_title)
            .setMessage(getString(R.string.auth_required_message, instance.name))
            .setPositiveButton(R.string.auth_required_switch) { _, _ ->
                preferences.updateInstance(
                    instance.id,
                    instance.name,
                    instance.url,
                    UiSource.SERVER,
                )
                loadApp(force = true)
            }
            .setNegativeButton(R.string.auth_required_continue) { _, _ ->
                authWarningDismissedFor = signature
                loadApp(force = true)
            }
            .setCancelable(false)
            .show()
    }

    /**
     * Loads an internal page on the app's own origin that wipes IndexedDB and localStorage,
     * and waits for it to report back. Bounded so a page that never loads (no WebView
     * engine, script error) cannot wedge the switch.
     */
    private suspend fun clearOriginStorage() {
        val view = ensureWebView()
        val completed = withTimeoutOrNull(ORIGIN_RESET_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                pendingOriginReset = continuation
                continuation.invokeOnCancellation { pendingOriginReset = null }
                view.loadUrl(OpenFicAssetHandler.RESET_URL)
            }
        }
        if (completed == null) {
            Log.w(TAG, "origin storage reset timed out; loading the new instance anyway")
        }
    }

    // endregion

    // region views

    private fun showLoading() {
        binding.loadingView.visibility = View.VISIBLE
        binding.errorView.visibility = View.GONE
        binding.webViewContainer.visibility = View.INVISIBLE
    }

    private fun showWebView() {
        binding.loadingView.visibility = View.GONE
        binding.errorView.visibility = View.GONE
        binding.webViewContainer.visibility = View.VISIBLE
    }

    private fun showError(message: String) {
        binding.loadingView.visibility = View.GONE
        binding.webViewContainer.visibility = View.INVISIBLE
        binding.errorView.visibility = View.VISIBLE
        binding.errorMessage.text = message
    }

    /**
     * Looks for a newer release. Automatic checks stay silent unless there is something to
     * offer, and remember a declined version so the prompt does not reappear every launch;
     * a manual check always reports its outcome.
     */
    private fun checkForUpdates(manual: Boolean) {
        if (manual) {
            Toast.makeText(this, R.string.update_checking, Toast.LENGTH_SHORT).show()
        }
        lifecycleScope.launch {
            val result = UpdateChecker.check(BuildConfig.VERSION_NAME)
            if (isFinishing || isDestroyed) return@launch
            when (result) {
                is UpdateCheckResult.Available -> {
                    val alreadyDismissed =
                        preferences.read().dismissedUpdateVersion == result.info.version
                    if (manual || !alreadyDismissed) showUpdateDialog(result.info)
                }

                UpdateCheckResult.UpToDate ->
                    if (manual) Toast.makeText(this@MainActivity, R.string.update_up_to_date, Toast.LENGTH_SHORT).show()

                is UpdateCheckResult.Failed -> {
                    Log.d(TAG, "update check failed: ${result.reason}")
                    if (manual) Toast.makeText(this@MainActivity, R.string.update_check_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showUpdateDialog(info: UpdateInfo) {
        AlertDialog.Builder(this)
            .setTitle(R.string.update_available_title)
            .setMessage(getString(R.string.update_available_message, BuildConfig.VERSION_NAME, info.version))
            .setPositiveButton(R.string.update_action_download) { _, _ -> startUpdateDownload(info) }
            .setNegativeButton(R.string.update_action_later) { _, _ ->
                preferences.saveDismissedUpdateVersion(info.version)
            }
            .show()
    }

    /**
     * Downloads in-app first, with the browser as the fallback rather than the default.
     *
     * DownloadManager only reports failure after its own retries, and on a network that
     * blocks `github.com` it can sit idle producing no visible signal at all. So the state is
     * sampled for a short window: real progress means the download is healthy and the
     * completion notification takes over; a failure — or nothing moving at all — hands off to
     * the browser, which can use whatever proxy the device already has.
     */
    private fun startUpdateDownload(info: UpdateInfo) {
        val downloadId = UpdateChecker.enqueueDownload(this, info)
        if (downloadId == null) {
            offerBrowserDownload(info)
            return
        }
        Toast.makeText(this, R.string.update_download_started, Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            var sawProgress = false
            repeat(UPDATE_WATCH_ATTEMPTS) {
                delay(UPDATE_WATCH_INTERVAL_MS)
                if (isFinishing || isDestroyed) return@launch
                val snapshot = UpdateChecker.downloadSnapshot(this@MainActivity, downloadId)
                    ?: return@launch
                if (snapshot.bytesDownloaded > 0) sawProgress = true

                when {
                    snapshot.state == DownloadManager.STATUS_SUCCESSFUL -> {
                        Toast.makeText(this@MainActivity, R.string.update_download_done, Toast.LENGTH_LONG).show()
                        return@launch
                    }

                    snapshot.state == DownloadManager.STATUS_FAILED -> {
                        offerBrowserDownload(info)
                        return@launch
                    }

                    // Moving data: stop polling and let the notification announce it.
                    sawProgress -> return@launch
                }
            }
            if (!sawProgress) offerBrowserDownload(info)
        }
    }

    private fun offerBrowserDownload(info: UpdateInfo) {
        AlertDialog.Builder(this)
            .setTitle(R.string.update_download_stalled_title)
            .setMessage(R.string.update_download_stalled_message)
            .setPositiveButton(R.string.update_action_open_browser) { _, _ -> openInBrowser(info) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun openInBrowser(info: UpdateInfo) {
        val opened = runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.apkUrl)))
        }.isSuccess
        if (!opened) {
            Toast.makeText(this, R.string.update_open_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun openInstanceManager() {
        Log.d(TAG, "launching InstancesActivity")
        instancesLauncher.launch(Intent(this, InstancesActivity::class.java))
    }

    /**
     * Persisted so the native screens can resolve their strings in the app's language; they
     * are recreated (or already gone) by the time the user next opens one.
     */
    private fun onLanguageChanged(languageTag: String) {
        preferences.saveLanguage(languageTag)
    }

    private fun onOriginResetCompleted() {
        Log.d(TAG, "origin storage cleared")
        pendingOriginReset?.let { continuation ->
            pendingOriginReset = null
            if (continuation.isActive) continuation.resume(Unit)
        }
    }

    // endregion

    // region WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView(): WebView {
        webView?.let { return it }

        val view = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            settings.apply {
                javaScriptEnabled = true
                // The SPA keeps projects, preferences and the font cache in local storage.
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                // Backends on a LAN are plain HTTP while the bundled bundle is served from a
                // secure origin, so every API and Socket.IO call is mixed content.
                @Suppress("DEPRECATION")
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                useWideViewPort = true
                loadWithOverviewMode = false
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                allowFileAccess = false
                allowContentAccess = false
                cacheMode = WebSettings.LOAD_DEFAULT
            }
            // Lets the backend tell the app apart from a desktop browser without affecting
            // how the SPA renders. `userAgentString` can be null before anything sets it.
            val baseUserAgent = settings.userAgentString
                ?: WebSettings.getDefaultUserAgent(this@MainActivity)
            settings.userAgentString = "$baseUserAgent $UA_SUFFIX"

            CookieManager.getInstance().setAcceptCookie(true)
            // The bundled bundle is cross-origin to the backend; without this any cookie the
            // backend sets (including the optional auth cookie) would be dropped.
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            addJavascriptInterface(
                AndroidHostBridge(
                    onAppearanceChanged = ::applyTheme,
                    onLanguageChanged = ::onLanguageChanged,
                    onOpenInstanceManagerRequested = ::openInstanceManager,
                    onUpdateCheckRequested = { checkForUpdates(manual = true) },
                    onOriginResetCompleted = ::onOriginResetCompleted,
                ),
                AndroidHostBridge.INTERFACE_NAME,
            )

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    val url = request.url
                    if (url.host == OpenFicAssetHandler.DOMAIN) return false
                    // Navigating within the active backend stays in the app.
                    val backend = preferences.read().activeInstance?.url
                    if (backend != null && url.toString().startsWith(backend)) return false
                    if (url.scheme == "http" || url.scheme == "https") {
                        // Anything else is a link the user tapped; hand it to the browser
                        // rather than navigating the app away from the editor.
                        startActivity(Intent(Intent.ACTION_VIEW, url))
                        return true
                    }
                    return false
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    if (!request.isForMainFrame) return
                    showError(getString(R.string.error_load_failed, error.description.toString()))
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    webView: WebView,
                    filePathCallback: android.webkit.ValueCallback<Array<Uri>>,
                    fileChooserParams: FileChooserParams,
                ): Boolean {
                    pendingFileChooser?.onReceiveValue(null)
                    pendingFileChooser = filePathCallback
                    return try {
                        fileChooserLauncher.launch(fileChooserParams.createIntent())
                        true
                    } catch (_: Exception) {
                        pendingFileChooser = null
                        false
                    }
                }
            }

            setDownloadListener { url, _, contentDisposition, mimeType, _ ->
                enqueueDownload(url, contentDisposition, mimeType)
            }
        }

        binding.webViewContainer.addView(view)
        webView = view
        return view
    }

    private fun enqueueDownload(url: String, contentDisposition: String, mimeType: String) {
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setMimeType(mimeType)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            // Exports live behind the same origin as the API when the backend serves the SPA,
            // so the download has to carry whatever session cookie is in play. Request has no
            // setCookie(); the cookie goes in as a plain header.
            CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", "$it;") }
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            setDestinationInExternalPublicDir(
                android.os.Environment.DIRECTORY_DOWNLOADS,
                fileName,
            )
            setTitle(fileName)
        }

        val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        runCatching { manager.enqueue(request) }
    }

    // endregion

    override fun onResume() {
        super.onResume()
        webView?.onResume()
        webView?.resumeTimers()
        if (!updateCheckStarted) {
            updateCheckStarted = true
            checkForUpdates(manual = false)
        }
        // The instance manager may have been opened from the web UI and changed the address.
        if (preferences.read().isConfigured && webView != null) {
            loadApp(force = false)
        }
    }

    override fun onPause() {
        webView?.onPause()
        webView?.pauseTimers()
        super.onPause()
    }

    override fun onDestroy() {
        hostShimHandle?.remove()
        hostShimHandle = null
        binding.webViewContainer.removeAllViews()
        webView?.apply {
            stopLoading()
            destroy()
        }
        webView = null
        super.onDestroy()
    }

    private companion object {
        const val TAG = "OpenFicMain"
        const val UA_SUFFIX = "OpenFicAndroid/1.0"
        const val ORIGIN_RESET_TIMEOUT_MS = 5_000L

        /**
         * Returns whether the SPA consumed the back press. Guarded on the function existing
         * so a page that predates it (or failed to load) still gets the native fallback.
         */
        const val BACK_HANDLER_JS =
            "window.__openficHandleBack ? window.__openficHandleBack() : false"

        /** How long to watch an update download before deciding it is stalled. */
        const val UPDATE_WATCH_ATTEMPTS = 8
        const val UPDATE_WATCH_INTERVAL_MS = 2_000L

        // Matches the SPA's own surfaces so the strip behind the system bars blends in.
        // `theme_color` in frontend/public/manifest.webmanifest is #18181b.
        val COLOR_DARK_BACKGROUND = Color.parseColor("#18181b")
        val COLOR_LIGHT_BACKGROUND = Color.parseColor("#ffffff")
    }
}
