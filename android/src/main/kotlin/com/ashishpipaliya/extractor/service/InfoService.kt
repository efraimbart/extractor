package com.ashishpipaliya.extractor.service

import android.content.Context
import com.ashishpipaliya.extractor.generated.VideoInfo
import com.ashishpipaliya.extractor.mapper.VideoInfoMapper
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
        // Cap carousel / playlist expansion; avoid accidental huge playlists.
        request.addOption("--playlist-end", "30")

        if (shouldForceSingleVideo(url)) {
            request.addOption("--no-playlist")
        }

        options.forEach { (key, value) ->
            if (key == null) return@forEach
            // Caller options must not strip dump-json.
            if (key == "--dump-json" || key == "-j") return@forEach
            if (value != null) {
                request.addOption(key, value)
            } else {
                request.addOption(key)
            }
        }

        val response = YoutubeDL.getInstance().execute(request)
        val out = response.out?.trim().orEmpty()
        if (out.isEmpty()) {
            throw IllegalStateException("yt-dlp --dump-json returned empty output")
        }
        val videos = mapper.mapAllFromDumpOutput(out)
        if (videos.isEmpty()) {
            throw IllegalStateException("yt-dlp --dump-json produced no playable entries")
        }
        return videos
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
}
