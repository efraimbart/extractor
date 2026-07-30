package com.ashishpipaliya.extractor.mapper

import com.ashishpipaliya.extractor.generated.VideoFormat
import com.ashishpipaliya.extractor.generated.VideoInfo
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Maps yt-dlp `--dump-json` output into Pigeon VideoInfo.
 *
 * Prefer raw dump-json over youtubedl-android's VideoInfo POJO so we keep
 * null vs `"none"` codec semantics, `urls[]`, and `resolution` intact.
 */
class VideoInfoMapper {

    private val objectMapper = ObjectMapper()

    fun mapFromDumpJson(json: String): VideoInfo {
        return mapFromJson(objectMapper.readTree(json))
    }

    fun mapFromJson(root: JsonNode): VideoInfo {
        val formatsNode = root.get("formats")
        val formats = if (formatsNode != null && formatsNode.isArray) {
            formatsNode.mapNotNull { node ->
                if (node == null || node.isNull) null else mapFormat(node)
            }
        } else {
            null
        }

        return VideoInfo(
            id = textOrNull(root, "id"),
            title = textOrNull(root, "title"),
            description = textOrNull(root, "description"),
            uploader = textOrNull(root, "uploader"),
            uploaderId = textOrNull(root, "uploader_id"),
            uploaderUrl = textOrNull(root, "uploader_url"),
            channelId = textOrNull(root, "channel_id"),
            channelUrl = textOrNull(root, "channel_url"),
            duration = resolveDurationSeconds(root, formatsNode),
            viewCount = numberAsLong(root, "view_count"),
            likeCount = numberAsLong(root, "like_count"),
            thumbnail = textOrNull(root, "thumbnail"),
            url = firstUrl(root),
            formats = formats,
            ext = textOrNull(root, "ext"),
            width = numberAsLong(root, "width"),
            height = numberAsLong(root, "height"),
            fps = numberAsLong(root, "fps"),
            vcodec = textOrNull(root, "vcodec"),
            acodec = textOrNull(root, "acodec"),
            httpHeaders = readHttpHeaders(root.get("http_headers"))
        )
    }

    private fun mapFormat(node: JsonNode): VideoFormat {
        val width = numberAsLong(node, "width")
        val height = numberAsLong(node, "height")
        val resolution = textOrNull(node, "resolution")
            ?: textOrNull(node, "format")

        return VideoFormat(
            formatId = textOrNull(node, "format_id"),
            formatNote = textOrNull(node, "format_note"),
            ext = textOrNull(node, "ext"),
            url = firstUrl(node),
            width = width,
            height = height,
            fps = numberAsLong(node, "fps"),
            filesize = numberAsLong(node, "filesize")
                ?: numberAsLong(node, "filesize_approx"),
            tbr = numberAsLong(node, "tbr"),
            // Preserve null vs "none" — callers must not treat missing as none.
            vcodec = textOrNull(node, "vcodec"),
            acodec = textOrNull(node, "acodec"),
            resolution = resolution,
            httpHeaders = readHttpHeaders(node.get("http_headers"))
        )
    }

    /** Prefer `url`, then first entry of `urls` (fragmented / multi-URL formats). */
    private fun firstUrl(node: JsonNode): String? {
        textOrNull(node, "url")?.let { return it }
        val urls = node.get("urls") ?: return null
        if (!urls.isArray) return null
        for (u in urls) {
            if (u != null && u.isTextual) {
                val s = u.asText()
                if (s.isNotBlank()) return s
            }
        }
        return null
    }

    private fun readHttpHeaders(node: JsonNode?): Map<String?, String?>? {
        if (node == null || !node.isObject || node.isEmpty) return null
        val out = linkedMapOf<String?, String?>()
        val fields = node.fields()
        while (fields.hasNext()) {
            val entry = fields.next()
            val value = entry.value
            out[entry.key] = when {
                value == null || value.isNull -> null
                value.isTextual -> value.asText()
                else -> value.toString()
            }
        }
        return out.ifEmpty { null }
    }

    private fun textOrNull(node: JsonNode, field: String): String? {
        val v = node.get(field) ?: return null
        if (v.isNull || !v.isValueNode) return null
        val s = v.asText()
        return s.ifBlank { null }
    }

    /**
     * yt-dlp `duration` is seconds (often a float). Prefer top-level; some
     * extractors only put it on formats — take the max in that case.
     */
    private fun resolveDurationSeconds(root: JsonNode, formatsNode: JsonNode?): Long? {
        numberAsLong(root, "duration")?.let { return it }
        if (formatsNode == null || !formatsNode.isArray) return null
        var max: Long? = null
        for (f in formatsNode) {
            if (f == null || f.isNull) continue
            val d = numberAsLong(f, "duration") ?: continue
            if (max == null || d > max) max = d
        }
        return max
    }

    private fun numberAsLong(node: JsonNode, field: String): Long? {
        val v = node.get(field) ?: return null
        if (v.isNull) return null
        val n = when {
            v.isNumber -> v.asDouble()
            v.isTextual -> v.asText().toDoubleOrNull() ?: return null
            else -> return null
        }
        // Unset numeric fields are commonly 0 in dump-json; treat as absent.
        if (n <= 0.0) return null
        return kotlin.math.round(n).toLong()
    }
}
