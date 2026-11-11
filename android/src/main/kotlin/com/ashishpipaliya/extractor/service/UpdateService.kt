package com.ashishpipaliya.extractor.service

import android.content.Context
import android.util.Log
import com.ashishpipaliya.extractor.generated.OperationStatus
import com.ashishpipaliya.extractor.generated.UpdateChannel
import com.ashishpipaliya.extractor.generated.UpdateResult
import com.fasterxml.jackson.databind.JsonNode
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Updates the on-device yt-dlp binary.
 *
 * Intentionally bypasses [YoutubeDL.updateYoutubeDL]: that helper compares GitHub
 * tags only to SharedPreferences (`dlpVersion`), uses a 10s read timeout, and has
 * no channel wiring from our pigeon API. Phone transfer restores those prefs but
 * never `noBackupFilesDir`, so the stock updater can no-op forever.
 */
class UpdateService(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.Main)
    private val executor = Executors.newSingleThreadExecutor()

    fun updateYoutubeDL(channel: UpdateChannel, callback: (Result<UpdateResult>) -> Unit) {
        executor.execute {
            try {
                reconcileInstalledVersion()

                val release = fetchLatestRelease(channel)
                val remoteTag = release.tag
                val installed = currentBinaryVersion()

                if (installed != null && installed == remoteTag) {
                    writeVersionPrefs(remoteTag, release.name)
                    Log.i(TAG, "yt-dlp already up to date: $installed (channel=$channel)")
                    scope.launch {
                        callback(
                            Result.success(
                                UpdateResult(
                                    status = OperationStatus.ALREADY_UP_TO_DATE,
                                    version = installed,
                                    errorMessage = null
                                )
                            )
                        )
                    }
                    return@execute
                }

                Log.i(
                    TAG,
                    "yt-dlp update: installed=${installed ?: "none"} → $remoteTag (channel=$channel)"
                )
                val temp = downloadBinary(release.downloadUrl)
                try {
                    installBinary(temp)
                } finally {
                    temp.delete()
                }

                writeVersionPrefs(remoteTag, release.name)
                val version = currentBinaryVersion() ?: remoteTag
                Log.i(TAG, "yt-dlp updated to $version")

                scope.launch {
                    callback(
                        Result.success(
                            UpdateResult(
                                status = OperationStatus.SUCCESS,
                                version = version,
                                errorMessage = null
                            )
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "yt-dlp update failed", e)
                scope.launch {
                    callback(
                        Result.success(
                            UpdateResult(
                                status = OperationStatus.ERROR,
                                version = currentBinaryVersion(),
                                errorMessage = e.message ?: "Unknown update error"
                            )
                        )
                    )
                }
            }
        }
    }

    /**
     * Align youtubedl-android's version prefs with the binary that actually exists.
     * Must [commit] before any later prefs read in this same flow.
     */
    private fun reconcileInstalledVersion() {
        val prefs = context.getSharedPreferences(YTDLP_PREFS, Context.MODE_PRIVATE)
        val prefsVersion = prefs.getString(DLP_VERSION_KEY, null)
        val installedVersion = currentBinaryVersion()

        when {
            installedVersion == null -> {
                if (prefsVersion != null) {
                    Log.w(
                        TAG,
                        "yt-dlp binary missing/unusable but prefs claim $prefsVersion; clearing prefs"
                    )
                }
                prefs.edit()
                    .remove(DLP_VERSION_KEY)
                    .remove(DLP_VERSION_NAME_KEY)
                    .commit()
            }
            prefsVersion != null && prefsVersion != installedVersion -> {
                Log.w(
                    TAG,
                    "yt-dlp prefs claim $prefsVersion but binary is $installedVersion; syncing prefs"
                )
                writeVersionPrefs(installedVersion, "yt-dlp $installedVersion")
            }
        }
    }

    private fun fetchLatestRelease(channel: UpdateChannel): ReleaseInfo {
        val apiUrl = when (channel) {
            UpdateChannel.NIGHTLY -> NIGHTLY_API
            else -> STABLE_API
        }
        val connection = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "videofile-extractor")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("GitHub releases HTTP $code for $apiUrl")
            }
            val json = YoutubeDL.objectMapper.readTree(connection.inputStream)
            val tag = json.path("tag_name").asText(null)
                ?: throw IllegalStateException("release missing tag_name")
            val name = json.path("name").asText(tag)
            val downloadUrl = findYtDlpAssetUrl(json)
                ?: throw IllegalStateException("release missing yt-dlp asset")
            return ReleaseInfo(tag = tag, name = name, downloadUrl = downloadUrl)
        } finally {
            connection.disconnect()
        }
    }

    private fun findYtDlpAssetUrl(json: JsonNode): String? {
        val assets = json.path("assets")
        if (!assets.isArray) return null
        for (asset in assets) {
            if (asset.path("name").asText() == YTDLP_BIN) {
                val url = asset.path("browser_download_url").asText(null)
                if (!url.isNullOrBlank()) return url
            }
        }
        return null
    }

    private fun downloadBinary(url: String): File {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "videofile-extractor")
        }
        val dest = File.createTempFile("yt-dlp-", ".bin", context.cacheDir)
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("yt-dlp download HTTP $code")
            }
            connection.inputStream.use { input ->
                FileOutputStream(dest).use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            }
            if (dest.length() <= 0L) {
                throw IllegalStateException("yt-dlp download empty")
            }
            return dest
        } catch (e: Exception) {
            dest.delete()
            throw e
        } finally {
            connection.disconnect()
        }
    }

    private fun installBinary(downloaded: File) {
        val ytdlpDir = File(File(context.noBackupFilesDir, YoutubeDL.baseName), YoutubeDL.ytdlpDirName)
        val binary = File(ytdlpDir, YTDLP_BIN)
        try {
            if (ytdlpDir.exists()) ytdlpDir.deleteRecursively()
            ytdlpDir.mkdirs()
            downloaded.copyTo(binary, overwrite = true)
        } catch (e: Exception) {
            ytdlpDir.deleteRecursively()
            YoutubeDL.getInstance().init_ytdlp(context, ytdlpDir)
            throw e
        }
    }

    private fun writeVersionPrefs(tag: String, name: String) {
        context.getSharedPreferences(YTDLP_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(DLP_VERSION_KEY, tag)
            .putString(DLP_VERSION_NAME_KEY, name)
            .commit()
    }

    private fun currentBinaryVersion(): String? {
        return try {
            val request = YoutubeDLRequest(emptyList())
            request.addOption("--version")
            YoutubeDL.getInstance().execute(request).out.trim().takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    private data class ReleaseInfo(
        val tag: String,
        val name: String,
        val downloadUrl: String,
    )

    companion object {
        private const val TAG = "ExtractorUpdate"
        private const val YTDLP_PREFS = "youtubedl-android"
        private const val DLP_VERSION_KEY = "dlpVersion"
        private const val DLP_VERSION_NAME_KEY = "dlpVersionName"
        private const val YTDLP_BIN = "yt-dlp"
        private const val STABLE_API = "https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest"
        private const val NIGHTLY_API =
            "https://api.github.com/repos/yt-dlp/yt-dlp-nightly-builds/releases/latest"
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 120_000
    }
}
