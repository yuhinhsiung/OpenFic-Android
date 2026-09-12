package com.openfic.android

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
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
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import com.openfic.android.storage.AppPreferencesStore
import com.openfic.android.storage.UiSource
import com.openfic.android.databinding.ActivityMainBinding
import com.openfic.android.web.AndroidHostBridge
import com.openfic.android.web.BackendProbe
import com.openfic.android.web.OpenFicAssetHandler
import com.openfic.android.web.ProbeResult
import kotlinx.coroutines.launch

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
    private var loadedServerUrl: String? = null
    private var loadedUiSource: UiSource? = null
    private var pendingFileChooser: android.webkit.ValueCallback<Array<Uri>>? = null

    private val serverSetupLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val current = preferences.read()
        when {
            current.isConfigured -> loadApp(force = true)
            else -> finish()
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
        binding.errorSettingsButton.setOnClickListener { openServerSettings() }

        if (preferences.read().isConfigured) {
            loadApp(force = true)
        } else {
            serverSetupLauncher.launch(Intent(this, ServerSetupActivity::class.java))
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

    private fun registerBackHandling() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val view = webView
                    if (view != null && view.canGoBack()) {
                        view.goBack()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            },
        )
    }

    // endregion

    // region loading

    private fun loadApp(force: Boolean = false) {
        val current = preferences.read()
        val serverUrl = current.serverUrl
        if (!current.isConfigured || serverUrl == null) {
            showError(getString(R.string.error_no_server))
            return
        }

        if (!force && serverUrl == loadedServerUrl && current.uiSource == loadedUiSource) {
            return
        }
        loadedServerUrl = serverUrl
        loadedUiSource = current.uiSource

        showLoading()

        lifecycleScope.launch {
            val reachable = BackendProbe.probe(serverUrl) is ProbeResult.Reachable
            if (isFinishing || isDestroyed) return@launch
            if (reachable) {
                showWebView()
                val view = ensureWebView()
                val target = when (current.uiSource) {
                    // Same-origin: the backend serves the identical SPA from `/`, so
                    // runtime-config is not needed and cookies behave like a normal browser.
                    UiSource.SERVER -> serverUrl
                    UiSource.BUNDLED -> OpenFicAssetHandler.BASE_URL
                }
                view.loadUrl(target)
            } else {
                showError(getString(R.string.error_unreachable, serverUrl))
            }
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

    private fun openServerSettings() {
        serverSetupLauncher.launch(Intent(this, ServerSetupActivity::class.java))
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
                    onOpenServerSettingsRequested = ::openServerSettings,
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
                    val backend = loadedServerUrl
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
        // The setup screen may have been opened from the web UI and changed the address.
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
        binding.webViewContainer.removeAllViews()
        webView?.apply {
            stopLoading()
            destroy()
        }
        webView = null
        super.onDestroy()
    }

    private companion object {
        const val UA_SUFFIX = "OpenFicAndroid/1.0"

        // Matches the SPA's own surfaces so the strip behind the system bars blends in.
        // `theme_color` in frontend/public/manifest.webmanifest is #18181b.
        val COLOR_DARK_BACKGROUND = Color.parseColor("#18181b")
        val COLOR_LIGHT_BACKGROUND = Color.parseColor("#ffffff")
    }
}
