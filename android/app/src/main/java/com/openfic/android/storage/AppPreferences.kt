package com.openfic.android.storage

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

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

/**
 * A backend the user has saved. Mirrors the desktop client's "instance" concept: the shell
 * keeps a list and points the frontend at whichever one is active.
 *
 * [uiSource] is per instance rather than global because it tracks a property of the backend
 * — whether it enforces a password — and two saved backends can differ on that.
 */
data class BackendInstance(
    val id: String,
    val name: String,
    val url: String,
    val uiSource: UiSource,
)

data class AppPreferences(
    val instances: List<BackendInstance>,
    val activeInstanceId: String?,
    /** BCP-47 tag the SPA reported, used to localise the native screens. Null until it does. */
    val language: String?,
    /** Update version the user already declined, so the prompt is not repeated. */
    val dismissedUpdateVersion: String?,
) {
    val activeInstance: BackendInstance?
        get() = instances.firstOrNull { it.id == activeInstanceId }

    val isConfigured: Boolean
        get() = activeInstance != null
}

class AppPreferencesStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun read(): AppPreferences = AppPreferences(
        instances = readInstances(),
        activeInstanceId = prefs.getString(KEY_ACTIVE_INSTANCE, null),
        language = prefs.getString(KEY_LANGUAGE, null)?.takeIf { it.isNotBlank() },
        dismissedUpdateVersion = prefs.getString(KEY_DISMISSED_UPDATE, null),
    )

    fun saveLanguage(language: String) {
        prefs.edit { putString(KEY_LANGUAGE, language) }
    }

    fun saveDismissedUpdateVersion(version: String) {
        prefs.edit { putString(KEY_DISMISSED_UPDATE, version) }
    }

    /** Adds a new instance and makes it active. Returns the new id. */
    fun addInstance(name: String, url: String, uiSource: UiSource): String {
        val instance = BackendInstance(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifEmpty { defaultNameFor(url) },
            url = normalizeServerUrl(url),
            uiSource = uiSource,
        )
        writeInstances(readInstances() + instance)
        setActiveInstance(instance.id)
        return instance.id
    }

    fun updateInstance(id: String, name: String, url: String, uiSource: UiSource) {
        val updated = readInstances().map { instance ->
            if (instance.id != id) {
                instance
            } else {
                instance.copy(
                    name = name.trim().ifEmpty { defaultNameFor(url) },
                    url = normalizeServerUrl(url),
                    uiSource = uiSource,
                )
            }
        }
        writeInstances(updated)
    }

    fun removeInstance(id: String) {
        val remaining = readInstances().filterNot { it.id == id }
        writeInstances(remaining)
        if (prefs.getString(KEY_ACTIVE_INSTANCE, null) == id) {
            setActiveInstance(remaining.firstOrNull()?.id)
        }
    }

    fun setActiveInstance(id: String?) {
        prefs.edit { putString(KEY_ACTIVE_INSTANCE, id) }
    }

    private fun readInstances(): List<BackendInstance> {
        val raw = prefs.getString(KEY_INSTANCES, null)
        val parsed = if (raw.isNullOrBlank()) {
            emptyList()
        } else {
            runCatching {
                val array = JSONArray(raw)
                (0 until array.length()).mapNotNull { index ->
                    val item = array.optJSONObject(index) ?: return@mapNotNull null
                    val id = item.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val url = item.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    BackendInstance(
                        id = id,
                        name = item.optString("name"),
                        url = url,
                        uiSource = if (item.optString("uiSource") == UiSource.SERVER.name) {
                            UiSource.SERVER
                        } else {
                            UiSource.BUNDLED
                        },
                    )
                }
            }.getOrDefault(emptyList())
        }

        // Upgrade path from the single-backend build, which stored only a bare URL.
        if (parsed.isNotEmpty()) return parsed
        val legacyUrl = prefs.getString(KEY_LEGACY_SERVER_URL, null)
        if (legacyUrl.isNullOrBlank()) return emptyList()

        val migrated = listOf(
            BackendInstance(
                id = LEGACY_INSTANCE_ID,
                name = defaultNameFor(legacyUrl),
                url = normalizeServerUrl(legacyUrl),
                uiSource = if (prefs.getString(KEY_LEGACY_UI_SOURCE, null) == UiSource.SERVER.name) {
                    UiSource.SERVER
                } else {
                    UiSource.BUNDLED
                },
            ),
        )
        writeInstances(migrated)
        prefs.edit { putString(KEY_ACTIVE_INSTANCE, LEGACY_INSTANCE_ID) }
        return migrated
    }

    private fun writeInstances(instances: List<BackendInstance>) {
        val array = JSONArray()
        instances.forEach { instance ->
            array.put(
                JSONObject().apply {
                    put("id", instance.id)
                    put("name", instance.name)
                    put("url", instance.url)
                    put("uiSource", instance.uiSource.name)
                },
            )
        }
        prefs.edit { putString(KEY_INSTANCES, array.toString()) }
    }

    private companion object {
        const val PREFS_NAME = "openfic_android_prefs"
        const val KEY_INSTANCES = "instances"
        const val KEY_ACTIVE_INSTANCE = "active_instance_id"
        const val KEY_LANGUAGE = "ui_language"
        const val KEY_DISMISSED_UPDATE = "dismissed_update_version"

        /** Written by builds that supported only one backend. */
        const val KEY_LEGACY_SERVER_URL = "server_url"
        const val KEY_LEGACY_UI_SOURCE = "ui_source"
        const val LEGACY_INSTANCE_ID = "migrated-default"
    }
}

/** A readable default label: the host, which is what distinguishes one backend from another. */
fun defaultNameFor(rawUrl: String): String {
    val normalized = normalizeServerUrl(rawUrl)
    val withoutScheme = normalized.substringAfter("://", normalized)
    return withoutScheme.substringBefore('/').ifBlank { normalized }
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
