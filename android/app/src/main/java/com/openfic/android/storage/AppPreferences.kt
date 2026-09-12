package com.openfic.android.storage

import android.content.Context
import androidx.core.content.edit

/**
 * Where the WebView gets its HTML/JS from.
 *
 * [BUNDLED] ships the React bundle inside the APK, so the app paints immediately and keeps
 * working when the backend is built without its static frontend. The page origin is then
 * `appassets.androidplatform.net`, which is cross-origin to the backend — fine for the
 * default password-less backend, but a backend that enables `OPENFIC_AUTH_PASSWORD` sets a
 * `SameSite=Lax` cookie that WebView will not attach to those cross-site requests.
 *
 * [SERVER] points the WebView straight at the backend, which serves the same SPA from `/`.
 * Same-origin, so authentication cookies behave normally, at the cost of a network round
 * trip before first paint.
 */
enum class UiSource {
    BUNDLED,
    SERVER,
}

data class AppPreferences(
    val serverUrl: String?,
    val uiSource: UiSource,
) {
    val isConfigured: Boolean
        get() = !serverUrl.isNullOrBlank()
}

class AppPreferencesStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun read(): AppPreferences = AppPreferences(
        serverUrl = prefs.getString(KEY_SERVER_URL, null)?.takeIf { it.isNotBlank() },
        uiSource = when (prefs.getString(KEY_UI_SOURCE, null)) {
            UiSource.SERVER.name -> UiSource.SERVER
            else -> UiSource.BUNDLED
        },
    )

    fun saveServerUrl(url: String) {
        prefs.edit { putString(KEY_SERVER_URL, normalizeServerUrl(url)) }
    }

    fun saveUiSource(source: UiSource) {
        prefs.edit { putString(KEY_UI_SOURCE, source.name) }
    }

    fun clear() {
        prefs.edit { clear() }
    }

    private companion object {
        const val PREFS_NAME = "openfic_android_prefs"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_UI_SOURCE = "ui_source"
    }
}

/**
 * Accepts what a user actually types — `192.168.1.10:8000`, `nas.local`, a URL with a
 * trailing slash — and turns it into the `scheme://host[:port]` form the frontend expects
 * as `backendBaseUrl`. A bare host defaults to `http://` because LAN backends are plain
 * HTTP unless the operator put a TLS proxy in front of them.
 */
fun normalizeServerUrl(raw: String): String {
    var value = raw.trim()
    if (value.isEmpty()) return value
    if (!value.startsWith("http://", ignoreCase = true) && !value.startsWith("https://", ignoreCase = true)) {
        value = "http://$value"
    }
    return value.trimEnd('/')
}
