package com.openfic.android.web

import android.content.Context
import android.util.Log
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import com.openfic.android.storage.AppPreferencesStore
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Serves the bundled SPA from `assets/www` and answers `/runtime-config.json` with the
 * user's backend address.
 *
 * The runtime-config response is the whole reason this port needs so little frontend work:
 * `frontend/src/lib/runtime-config.ts` already fetches that path at startup and treats
 * `backendBaseUrl` as the single source of truth for every axios request and Socket.IO
 * connection. The Electron shell answers the same path from its `app://` protocol handler,
 * so this mirrors the desktop "remote instance" mode rather than inventing a new channel.
 *
 * The path is claimed before any static lookup, exactly as `desktop/src/main/protocol.ts`
 * does, so no real `runtime-config.json` needs to ship in the bundle.
 */
class OpenFicAssetHandler(
    context: Context,
    private val preferences: AppPreferencesStore,
) : WebViewAssetLoader.PathHandler {

    private val assets = context.applicationContext.assets

    override fun handle(path: String): WebResourceResponse? {
        // WebViewAssetLoader strips the registered prefix before calling the handler, so a
        // request for "/runtime-config.json" against a handler registered at "/" arrives
        // here as "runtime-config.json". Normalising the leading slash away makes the match
        // work regardless of how the prefix was registered.
        val relative = path.trimStart('/')

        if (relative == RUNTIME_CONFIG_FILE) {
            Log.d(TAG, "serving runtime-config for $path")
            return runtimeConfigResponse()
        }

        if (relative.contains("..")) {
            return null
        }

        if (relative.isEmpty() || relative == INDEX_HTML) {
            return serveIndexHtml()
        }

        openAsset("$ASSET_ROOT/$relative")?.let { return it }

        // A deep link such as /projects/abc has no file extension: hand back the SPA shell
        // and let the client router resolve it. Paths that do look like files are genuine
        // misses and must stay misses, or a typo'd asset would be served HTML.
        if (!relative.substringAfterLast('/').contains('.')) {
            return serveIndexHtml()
        }
        return null
    }

    /**
     * The host shim has to be in the document before the bundle's first module runs —
     * `register-sw.ts` and the appearance bridge both feature-detect the host
     * synchronously at import time. `onPageStarted` is too late to guarantee that, so the
     * script is spliced into the HTML as it is served.
     */
    private fun serveIndexHtml(): WebResourceResponse? {
        val html = try {
            assets.open("$ASSET_ROOT/$INDEX_HTML").use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (_: IOException) {
            return null
        }

        val headTag = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(html)
        val injected = if (headTag != null) {
            html.substring(0, headTag.range.last + 1) +
                "\n" + AndroidHostBridge.HOST_SHIM_SCRIPT +
                html.substring(headTag.range.last + 1)
        } else {
            AndroidHostBridge.HOST_SHIM_SCRIPT + html
        }

        return WebResourceResponse(
            "text/html",
            "UTF-8",
            200,
            "OK",
            mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream(injected.toByteArray(Charsets.UTF_8)),
        )
    }

    private fun runtimeConfigResponse(): WebResourceResponse {
        val serverUrl = preferences.read().serverUrl
        val payload = JSONObject().apply {
            if (!serverUrl.isNullOrBlank()) {
                put("backendBaseUrl", serverUrl)
            }
        }

        val body = payload.toString().toByteArray(Charsets.UTF_8)
        return WebResourceResponse(
            "application/json",
            "UTF-8",
            200,
            "OK",
            // No CORS header here on purpose: this is a same-origin request, and WebView
            // rejects a same-origin response that carries `Access-Control-Allow-Origin`.
            mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream(body),
        )
    }

    private fun openAsset(assetPath: String): WebResourceResponse? {
        val stream: InputStream = try {
            assets.open(assetPath)
        } catch (_: IOException) {
            return null
        }

        val headers = mutableMapOf<String, String>()
        if (assetPath.endsWith(INDEX_HTML)) {
            // The shell must never be served stale: it is what points at the current
            // hashed asset filenames.
            headers["Cache-Control"] = "no-store"
        }

        return WebResourceResponse(
            mimeTypeFor(assetPath),
            null,
            200,
            "OK",
            headers,
            stream,
        )
    }

    /**
     * WebView refuses to decode a font served as `application/octet-stream`, and Android's
     * built-in `MimeTypeMap` has no entry for woff2 — which is the only font format the
     * frontend build keeps. Map the extensions the bundle actually ships.
     */
    private fun mimeTypeFor(assetPath: String): String = when (assetPath.substringAfterLast('.', "").lowercase()) {
        "html" -> "text/html"
        "js", "mjs" -> "text/javascript"
        "css" -> "text/css"
        "json", "webmanifest" -> "application/json"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "ico" -> "image/x-icon"
        "woff2" -> "font/woff2"
        "woff" -> "font/woff"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "wasm" -> "application/wasm"
        "map" -> "application/json"
        "txt" -> "text/plain"
        else -> "application/octet-stream"
    }

    companion object {
        private const val TAG = "OpenFicAssets"

        /** Origin the bundled bundle is served from. HTTPS so the origin counts as secure. */
        const val DOMAIN = "appassets.androidplatform.net"

        /** Where `copyFrontendDist` unpacks `frontend/dist` inside the APK. */
        const val ASSET_ROOT = "www"

        const val INDEX_HTML = "index.html"

        /** Request path the SPA fetches its backend address from, without a leading slash. */
        const val RUNTIME_CONFIG_FILE = "runtime-config.json"

        const val RUNTIME_CONFIG_PATH = "/runtime-config.json"

        /**
         * Entry point for the bundled SPA. Deliberately the directory root, not
         * `/index.html`: the client router matches on `location.pathname`, and
         * `/index.html` matches no route, which renders an empty app.
         */
        val BASE_URL = "https://$DOMAIN/"
    }
}
