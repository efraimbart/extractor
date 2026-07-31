package com.ashishpipaliya.extractor.service

import android.content.Context
import android.util.Log
import com.ashishpipaliya.extractor.generated.VideoInfo
import com.ashishpipaliya.extractor.mapper.VideoInfoMapper
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.net.URI
import java.util.concurrent.Executors

/**
 * Service responsible for fetching video information via yt-dlp `--dump-json`.
 *
 * Multi-item posts (IG carousel, etc.) are returned as multiple [VideoInfo]s.
 * YouTube watch URLs keep `--no-playlist` so a shared video+list link does not
 * expand into the entire playlist.
 */
class InfoService(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.Main)
    private val mapper = VideoInfoMapper()
    private val executor = Executors.newSingleThreadExecutor()

    fun getVideoInfo(url: String, callback: (Result<List<VideoInfo>>) -> Unit) {
        executor.execute {
            try {
                val videos = fetchDumpJson(url, emptyMap())
                scope.launch {
                    callback(Result.success(videos))
                }
            } catch (e: Exception) {
                scope.launch {
                    callback(Result.failure(e))
                }
            }
        }
    }

    fun getVideoInfoWithOptions(
        url: String,
        options: Map<String?, String?>,
        callback: (Result<List<VideoInfo>>) -> Unit
    ) {
        executor.execute {
            try {
                val videos = fetchDumpJson(url, options)
                scope.launch {
                    callback(Result.success(videos))
                }
            } catch (e: Exception) {
                scope.launch {
                    callback(Result.failure(e))
                }
            }
        }
    }

    /**
     * Run yt-dlp `--dump-json` and map the raw JSON (same data path as the CLI).
     * Avoids youtubedl-android's typed POJO, which collapses missing codecs and drops fields.
     */
    private fun fetchDumpJson(
        url: String,
        options: Map<String?, String?>,
    ): List<VideoInfo> {
        val request = YoutubeDLRequest(url)
        request.addOption("--dump-json")
        request.addOption("--no-update")
        // Don't abort the whole carousel/playlist when one entry fails.
        request.addOption("--ignore-errors")
        // Cap expansion; callers may override via --playlist-end.
        val playlistEnd = options["--playlist-end"]?.takeIf { it.isNotBlank() } ?: "30"
        request.addOption("--playlist-end", playlistEnd)

        // Persist the in-process cookie jar so we can attach Cookie headers for
        // Flutter's proxy/player (dump-json often omits them from http_headers).
        val cookieFile = File(
            context.cacheDir,
            "ytdlp-cookies-${System.currentTimeMillis()}-${(0..9999).random()}.txt",
        )
        request.addOption("--cookies", cookieFile.absolutePath)

        if (shouldForceSingleVideo(url)) {
            request.addOption("--no-playlist")
        }

        options.forEach { (key, value) ->
            if (key == null) return@forEach
            // Caller options must not strip dump-json / our cookie dump / defaults.
            if (key == "--dump-json" || key == "-j") return@forEach
            if (key == "--cookies") return@forEach
            if (key == "--playlist-end") return@forEach
            if (key == "--ignore-errors") return@forEach
            if (value != null && value.isNotEmpty()) {
                request.addOption(key, value)
            } else {
                request.addOption(key)
            }
        }

        try {
            val response = YoutubeDL.getInstance().execute(request)
            val out = response.out?.trim().orEmpty()
            if (out.isEmpty()) {
                throw IllegalStateException("yt-dlp --dump-json returned empty output")
            }
            var videos = mapper.mapAllFromDumpOutput(out)
            if (videos.isEmpty()) {
                throw IllegalStateException("yt-dlp --dump-json produced no playable entries")
            }
            videos = enrichWithCookieJar(videos, cookieFile)
            return videos
        } finally {
            if (cookieFile.exists()) {
                cookieFile.delete()
            }
        }
    }

    /**
     * Merge Netscape jar cookies into each format/info httpHeaders when missing.
     * Prefer host-matched cookies for the stream URL.
     */
    private fun enrichWithCookieJar(
        videos: List<VideoInfo>,
        cookieFile: File,
    ): List<VideoInfo> {
        if (!cookieFile.exists() || cookieFile.length() == 0L) {
            Log.d(TAG, "cookie jar empty after dump-json")
            return videos
        }
        val jar = parseNetscapeCookies(cookieFile)
        if (jar.isEmpty()) {
            Log.d(TAG, "cookie jar parsed empty (${cookieFile.length()} bytes)")
            return videos
        }
        Log.d(TAG, "cookie jar entries=${jar.size}")

        return videos.map { info ->
            val infoHeaders = ensureCookieHeader(info.httpHeaders, info.url, jar)
            val formats = info.formats?.map { fmt ->
                if (fmt == null) return@map null
                val headers = ensureCookieHeader(fmt.httpHeaders ?: infoHeaders, fmt.url, jar)
                if (headers === fmt.httpHeaders) fmt
                else fmt.copy(httpHeaders = headers)
            }
            if (infoHeaders === info.httpHeaders && formats === info.formats) info
            else info.copy(httpHeaders = infoHeaders, formats = formats)
        }
    }

    private fun ensureCookieHeader(
        existing: Map<String?, String?>?,
        url: String?,
        jar: List<NetscapeCookie>,
    ): Map<String?, String?>? {
        if (existing?.keys?.any { it != null && it.equals("Cookie", ignoreCase = true) } == true) {
            return existing
        }
        val header = cookieHeaderForUrl(url, jar) ?: return existing
        val out = linkedMapOf<String?, String?>()
        if (existing != null) out.putAll(existing)
        out["Cookie"] = header
        return out
    }

    private fun cookieHeaderForUrl(url: String?, jar: List<NetscapeCookie>): String? {
        if (url.isNullOrBlank() || jar.isEmpty()) return null
        val host = try {
            URI(url).host?.lowercase() ?: return null
        } catch (_: Exception) {
            return null
        }
        val matched = jar.filter { cookie ->
            val domain = cookie.domain.lowercase().removePrefix(".")
            host == domain || host.endsWith(".$domain")
        }
        if (matched.isEmpty()) return null
        // Later jar entries win on duplicate names (yt-dlp refresh order).
        val byName = linkedMapOf<String, String>()
        for (c in matched) {
            byName[c.name] = c.value
        }
        return byName.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private data class NetscapeCookie(
        val domain: String,
        val name: String,
        val value: String,
    )

    private fun parseNetscapeCookies(file: File): List<NetscapeCookie> {
        val out = ArrayList<NetscapeCookie>()
        file.forEachLine { raw ->
            var line = raw.trim()
            if (line.isEmpty()) return@forEachLine
            if (line.startsWith("#HttpOnly_", ignoreCase = true)) {
                line = line.removePrefix("#HttpOnly_").removePrefix("#httponly_")
            } else if (line.startsWith("#")) {
                return@forEachLine
            }
            val parts = line.split('\t')
            if (parts.size < 7) return@forEachLine
            val domain = parts[0].trim()
            val name = parts[5].trim()
            val value = parts[6].trim()
            if (domain.isEmpty() || name.isEmpty()) return@forEachLine
            out.add(NetscapeCookie(domain = domain, name = name, value = value))
        }
        return out
    }

    /**
     * YouTube watch / youtu.be links often include `list=` — those should stay
     * single-video. Explicit `/playlist` URLs may expand (still capped).
     */
    private fun shouldForceSingleVideo(url: String): Boolean {
        val lower = url.lowercase()
        val isYouTube = lower.contains("youtube.com") || lower.contains("youtu.be")
        if (!isYouTube) return false
        if (lower.contains("/playlist")) return false
        return true
    }

    companion object {
        private const val TAG = "InfoService"
    }
}
