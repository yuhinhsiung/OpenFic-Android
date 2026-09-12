package com.openfic.android.update

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A published release newer than the installed build. */
data class UpdateInfo(
    val version: String,
    val apkUrl: String,
    val releaseUrl: String,
)

/**
 * Outcome of a check. Kept apart from a bare nullable so a manual check can tell "already
 * current" from "could not reach GitHub" — the two need different messages.
 */
sealed interface UpdateCheckResult {
    data class Available(val info: UpdateInfo) : UpdateCheckResult
    data object UpToDate : UpdateCheckResult
    data class Failed(val reason: String) : UpdateCheckResult
}

/**
 * Checks this fork's GitHub releases for a newer build.
 *
 * The release stream is independent of upstream, so the tag carries an `android-v` prefix to
 * stay clear of upstream's `v*` tags. Version comparison is numeric rather than lexical, so
 * 0.11.10 correctly ranks above 0.11.9.
 */
object UpdateChecker {

    private const val LATEST_RELEASE_API =
        "https://api.github.com/repos/yuhinhsiung/OpenFic-Android/releases/latest"
    private const val TIMEOUT_MS = 10_000

    suspend fun check(installedVersion: String): UpdateCheckResult = withContext(Dispatchers.IO) {
        val connection = try {
            (URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "OpenFicAndroid")
            }
        } catch (error: Exception) {
            return@withContext UpdateCheckResult.Failed(error.message ?: "unreachable")
        }

        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                return@withContext UpdateCheckResult.Failed("HTTP $code")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parse(body, installedVersion)
        } catch (error: IOException) {
            UpdateCheckResult.Failed(error.message ?: "connection failed")
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(body: String, installedVersion: String): UpdateCheckResult {
        val release = runCatching { JSONObject(body) }.getOrNull()
            ?: return UpdateCheckResult.Failed("malformed release payload")
        val tag = release.optString("tag_name").takeIf { it.isNotBlank() }
            ?: return UpdateCheckResult.Failed("release has no tag")

        if (!isNewer(parseVersion(tag), parseVersion(installedVersion))) {
            return UpdateCheckResult.UpToDate
        }

        val assets = release.optJSONArray("assets")
        val apk = (0 until (assets?.length() ?: 0))
            .mapNotNull { assets?.optJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
            ?.optString("browser_download_url")
            ?.takeIf { it.isNotBlank() }
            ?: return UpdateCheckResult.Failed("release has no APK asset")

        return UpdateCheckResult.Available(
            UpdateInfo(
                version = tag.removePrefix("android-v").removePrefix("android-"),
                apkUrl = apk,
                releaseUrl = release.optString("html_url"),
            ),
        )
    }

    private fun parseVersion(raw: String): List<Int> =
        Regex("\\d+").findAll(raw).map { it.value.toInt() }.toList()

    private fun isNewer(latest: List<Int>, installed: List<Int>): Boolean {
        for (index in 0 until maxOf(latest.size, installed.size)) {
            val candidate = latest.getOrElse(index) { 0 }
            val current = installed.getOrElse(index) { 0 }
            if (candidate != current) return candidate > current
        }
        return false
    }

    /** Snapshot of an in-flight download, used to tell progress from a stalled one. */
    data class DownloadSnapshot(val state: Int, val bytesDownloaded: Long)

    /**
     * Queues the APK with the system download manager, which keeps the app out of the
     * "install unknown apps" permission flow: tapping the finished notification runs the
     * installer. Returns the download id, or null if it could not be queued.
     */
    fun enqueueDownload(context: Context, info: UpdateInfo): Long? {
        val fileName = info.apkUrl.substringAfterLast('/').ifBlank { "OpenFic-Android.apk" }
        val request = DownloadManager.Request(Uri.parse(info.apkUrl)).apply {
            setMimeType("application/vnd.android.package-archive")
            setTitle(fileName)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        }
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return try {
            manager.enqueue(request)
        } catch (error: Exception) {
            Log.w(TAG, "could not queue update download: ${error.message}", error)
            null
        }
    }

    /** Null when the id is unknown — for instance after the app was restarted. */
    fun downloadSnapshot(context: Context, downloadId: Long): DownloadSnapshot? {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return try {
            manager.query(DownloadManager.Query().setFilterById(downloadId))?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                DownloadSnapshot(
                    state = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                    bytesDownloaded = cursor.getLong(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                    ),
                )
            }
        } catch (error: Exception) {
            Log.w(TAG, "could not read update download state: ${error.message}", error)
            null
        }
    }

    private const val TAG = "OpenFicUpdate"
}
