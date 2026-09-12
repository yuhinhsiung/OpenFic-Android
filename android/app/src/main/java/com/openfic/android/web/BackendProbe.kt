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

/**
 * `GET /api/v1/health` is unauthenticated on the backend (see `backend/app/auth.py`), so a
 * successful probe proves the address is right even when the deployment has a password —
 * which matters because the SPA's own error screen cannot distinguish "wrong address" from
 * "needs login".
 */
object BackendProbe {

    private const val HEALTH_PATH = "/api/v1/health"
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
}
