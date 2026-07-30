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
 */
class InfoService(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.Main)
    private val mapper = VideoInfoMapper()
    private val executor = Executors.newSingleThreadExecutor()

    fun getVideoInfo(url: String, callback: (Result<VideoInfo>) -> Unit) {
        executor.execute {
            try {
                val videoInfo = fetchDumpJson(url, emptyMap())
                scope.launch {
                    callback(Result.success(videoInfo))
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
        callback: (Result<VideoInfo>) -> Unit
    ) {
        executor.execute {
            try {
                val videoInfo = fetchDumpJson(url, options)
                scope.launch {
                    callback(Result.success(videoInfo))
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
    ): VideoInfo {
        val request = YoutubeDLRequest(url)
        request.addOption("--dump-json")
        request.addOption("--no-playlist")
        request.addOption("--no-update")

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
        // yt-dlp may print warnings before the JSON object; take the last `{…}` blob.
        val json = extractJsonObject(out)
            ?: throw IllegalStateException("yt-dlp --dump-json produced no JSON object")
        return mapper.mapFromDumpJson(json)
    }

    private fun extractJsonObject(out: String): String? {
        val start = out.indexOf('{')
        val end = out.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return out.substring(start, end + 1)
    }
}
