package com.openfic.android.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Outcome of a reachability probe against a candidate backend. */
sealed interface ProbeResult {
    data class Reachable(val serverVersion: String?) : ProbeResult
    data class Unreachable(val reason: String) : ProbeResult
}

/** Whether a backend enforces its shared password, and whether this session has it. */
data class AuthStatus(
    val enabled: Boolean,
    val authenticated: Boolean,
)

/**
 * `GET /api/v1/health` is unauthenticated on the backend (see `backend/app/auth.py`), so a
 * successful probe proves the address is right even when the deployment has a password —
 * which matters because the SPA's own error screen cannot distinguish "wrong address" from
 * "needs login".
 */
object BackendProbe {

    private const val HEALTH_PATH = "/api/v1/health"
    private const val AUTH_STATUS_PATH = "/api/v1/auth/status"
    private const val TIMEOUT_MS = 8_000

    suspend fun probe(serverUrl: String): ProbeResult = withContext(Dispatchers.IO) {
        val connection = try {
            (URL("$serverUrl$HEALTH_PATH").openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
                instanceFollowRedirects = true
            }
        } catch (error: Exception) {
            return@withContext ProbeResult.Unreachable(error.message ?: "invalid address")
        }

        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                return@withContext ProbeResult.Unreachable("HTTP $code")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            ProbeResult.Reachable(extractVersion(body))
        } catch (error: IOException) {
            ProbeResult.Unreachable(error.message ?: "connection failed")
        } finally {
            connection.disconnect()
        }
    }

    /** The health payload is `{"status": ..., "version": ...}`; a miss here is not fatal. */
    private fun extractVersion(body: String): String? =
        Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)

    /**
     * Reads `/api/v1/auth/status`, which is one of the few endpoints the backend leaves open.
     * The app needs it before loading the UI: a password-protected backend cannot be used
     * from the bundled interface at all, because the login cookie is cross-site there and
     * `SameSite=Lax` stops WebView from ever attaching it. Knowing this up front lets the app
     * offer the working alternative instead of dropping the user on a login form that will
     * always fail.
     *
     * Returns null when the endpoint is unavailable; callers then assume it does not apply.
     */
    suspend fun probeAuth(serverUrl: String): AuthStatus? = withContext(Dispatchers.IO) {
        val connection = try {
            (URL("$serverUrl$AUTH_STATUS_PATH").openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
                instanceFollowRedirects = true
            }
        } catch (_: Exception) {
            return@withContext null
        }

        try {
            if (connection.responseCode !in 200..299) return@withContext null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val enabled = Regex("\"enabled\"\\s*:\\s*(true|false)").find(body)
                ?.groupValues?.get(1) == "true"
            val authenticated = Regex("\"authenticated\"\\s*:\\s*(true|false)").find(body)
                ?.groupValues?.get(1) == "true"
            AuthStatus(enabled = enabled, authenticated = authenticated)
        } catch (_: IOException) {
            null
        } finally {
            connection.disconnect()
        }
    }
}
