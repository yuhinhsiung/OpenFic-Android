package com.openfic.android.web

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import org.json.JSONObject

/**
 * Native side of the `openficAndroidHost` bridge the SPA sees.
 *
 * `@JavascriptInterface` only marshals primitives reliably — a JS object arrives as an
 * opaque handle — so every method here takes a JSON string. The shim injected into
 * `index.html` (see [HOST_SHIM_SCRIPT]) does the stringifying so the frontend keeps
 * passing plain objects, matching how it already talks to the Electron host.
 *
 * Calls arrive on a WebView worker thread; anything touching UI is bounced to the main
 * thread by the listener implementations.
 */
class AndroidHostBridge(
    private val onAppearanceChanged: (isDark: Boolean) -> Unit,
    private val onLanguageChanged: (languageTag: String) -> Unit,
    private val onOpenInstanceManagerRequested: () -> Unit,
    private val onUpdateCheckRequested: () -> Unit,
    private val onOriginResetCompleted: () -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun publishAppearance(payloadJson: String) {
        val isDark = runCatching {
            JSONObject(payloadJson).optString("appearance") == "dark"
        }.getOrDefault(false)
        mainHandler.post { onAppearanceChanged(isDark) }
    }

    /**
     * The SPA owns the language setting and reports it at startup and on every change; the
     * native screens read it back so they do not stay stuck on the device locale.
     */
    @JavascriptInterface
    fun publishLanguage(language: String) {
        val tag = language.trim().takeIf { it.isNotEmpty() } ?: return
        mainHandler.post { onLanguageChanged(tag) }
    }

    @JavascriptInterface
    fun publishSocketDiagnostic(payloadJson: String) {
        Log.d(TAG, "socket: $payloadJson")
    }

    /**
     * Takes an ignored string: the injected shim funnels every call through one helper that
     * always passes a payload, and Android's JS bridge rejects a no-arg method invoked with
     * an argument — the shim swallows that error, so the call would vanish silently.
     */
    @JavascriptInterface
    fun openInstanceManager(@Suppress("UNUSED_PARAMETER") payload: String) {
        Log.d(TAG, "openInstanceManager requested by the page")
        mainHandler.post(onOpenInstanceManagerRequested)
    }

    /** Sent by the internal reset page once this origin's storage has been cleared. */
    @JavascriptInterface
    fun notifyOriginResetComplete() {
        mainHandler.post(onOriginResetCompleted)
    }

    /** Manual "check for updates" from the app's settings, where the user expects feedback. */
    @JavascriptInterface
    fun checkForUpdates(@Suppress("UNUSED_PARAMETER") payload: String) {
        Log.d(TAG, "checkForUpdates requested by the page")
        mainHandler.post(onUpdateCheckRequested)
    }

    companion object {
        private const val TAG = "OpenFicHost"

        /** Name the shim forwards to; registered via `addJavascriptInterface`. */
        const val INTERFACE_NAME = "__openficAndroidHost"

        /**
         * The shim itself, without the surrounding `<script>` tag.
         *
         * Two injection paths use it: the asset handler splices [HOST_SHIM_SCRIPT] into the
         * bundled `index.html`, and server-interface mode registers this as a document-start
         * script (`WebViewCompat.addDocumentStartJavaScript`) because that page comes from the
         * backend and never passes through the asset handler.
         */
        val HOST_SHIM_JS: String = """
            (function () {
              var native = window.$INTERFACE_NAME;
              if (!native || window.openficAndroidHost) return;
              function call(name, payload) {
                // Strings go through verbatim; everything else is JSON-encoded, so each
                // native method receives exactly one predictable argument.
                var encoded = typeof payload === "string"
                  ? payload
                  : (payload == null ? "" : JSON.stringify(payload));
                try { native[name](encoded); }
                catch (error) { /* host disappeared mid-flight; nothing to recover */ }
              }
              window.openficAndroidHost = {
                platform: "android",
                publishAppearance: function (payload) { call("publishAppearance", payload); },
                publishLanguage: function (language) { call("publishLanguage", language); },
                publishSocketDiagnostic: function (payload) { call("publishSocketDiagnostic", payload); },
                openInstanceManager: function () { call("openInstanceManager"); },
                checkForUpdates: function () { call("checkForUpdates"); }
              };
            })();
        """.trimIndent()

        /** [HOST_SHIM_JS] wrapped for splicing into HTML. */
        val HOST_SHIM_SCRIPT: String = "<script>\n$HOST_SHIM_JS\n</script>"
    }
}
