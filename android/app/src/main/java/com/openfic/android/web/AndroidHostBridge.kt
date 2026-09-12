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
    private val onOpenServerSettingsRequested: () -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun publishAppearance(payloadJson: String) {
        val isDark = runCatching {
            JSONObject(payloadJson).optString("appearance") == "dark"
        }.getOrDefault(false)
        mainHandler.post { onAppearanceChanged(isDark) }
    }

    @JavascriptInterface
    fun publishLanguage(language: String) = Unit

    @JavascriptInterface
    fun publishSocketDiagnostic(payloadJson: String) {
        Log.d(TAG, "socket: $payloadJson")
    }

    @JavascriptInterface
    fun openServerSettings() {
        mainHandler.post(onOpenServerSettingsRequested)
    }

    companion object {
        private const val TAG = "OpenFicHost"

        /** Name the shim forwards to; registered via `addJavascriptInterface`. */
        const val INTERFACE_NAME = "__openficAndroidHost"

        /**
         * Installed ahead of the app bundle so `register-sw.ts` and the appearance bridge
         * can feature-detect the host during their first synchronous run.
         */
        val HOST_SHIM_SCRIPT: String = """
            <script>
            (function () {
              var native = window.$INTERFACE_NAME;
              if (!native || window.openficAndroidHost) return;
              function call(name, payload) {
                try { native[name](payload == null ? "" : JSON.stringify(payload)); }
                catch (error) { /* host disappeared mid-flight; nothing to recover */ }
              }
              window.openficAndroidHost = {
                platform: "android",
                publishAppearance: function (payload) { call("publishAppearance", payload); },
                publishLanguage: function (language) { call("publishLanguage", language); },
                publishSocketDiagnostic: function (payload) { call("publishSocketDiagnostic", payload); },
                openServerSettings: function () { call("openServerSettings"); }
              };
            })();
            </script>
        """.trimIndent()
    }
}
