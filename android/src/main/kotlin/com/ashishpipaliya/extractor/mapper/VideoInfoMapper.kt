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
 *
 * Playlist / multi-video posts may emit multiple JSON objects (NDJSON) or a
 * single playlist object with `entries[]` — both are expanded to a list.
 */
class VideoInfoMapper {

    private val objectMapper = ObjectMapper()

    fun mapFromDumpJson(json: String): VideoInfo {
        return mapFromJson(objectMapper.readTree(json))
    }

    /** Parse one or more dump-json objects from yt-dlp stdout. */
    fun mapAllFromDumpOutput(out: String): List<VideoInfo> {
        val roots = parseJsonRoots(out)
        if (roots.isEmpty()) {
            throw IllegalStateException("yt-dlp --dump-json produced no JSON object")
        }
        val results = ArrayList<VideoInfo>()
        for (root in roots) {
            results.addAll(expandRoot(root))
        }
        return results.filter { hasPlayableMedia(it) }
    }

    private fun expandRoot(root: JsonNode): List<VideoInfo> {
        val type = textOrNull(root, "_type")?.lowercase()
        val entries = root.get("entries")
        if ((type == "playlist" || type == "multi_video") &&
            entries != null && entries.isArray
        ) {
            val out = ArrayList<VideoInfo>()
            for (entry in entries) {
                if (entry == null || entry.isNull) continue
                // Nested playlist wrappers are rare; flatten one level.
                val nestedType = textOrNull(entry, "_type")?.lowercase()
                val nestedEntries = entry.get("entries")
                if ((nestedType == "playlist" || nestedType == "multi_video") &&
                    nestedEntries != null && nestedEntries.isArray
                ) {
                    for (child in nestedEntries) {
                        if (child == null || child.isNull) continue
                        out.add(mapFromJson(child))
                    }
                } else {
                    out.add(mapFromJson(entry))
                }
            }
            return out
        }
        return listOf(mapFromJson(root))
    }

    private fun hasPlayableMedia(info: VideoInfo): Boolean {
        if (!info.url.isNullOrBlank()) return true
        val formats = info.formats ?: return false
        return formats.any { f -> f != null && !f.url.isNullOrBlank() }
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
            // yt-dlp keeps session cookies under `cookies`, not http_headers.Cookie
            // (GHSA-v8mc-9377-rwjj). Merge so Flutter proxy/player can send them.
            httpHeaders = mergeCookiesIntoHeaders(
                readHttpHeaders(root.get("http_headers")),
                textOrNull(root, "cookies"),
            ),
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
            httpHeaders = mergeCookiesIntoHeaders(
                readHttpHeaders(node.get("http_headers")),
                textOrNull(node, "cookies"),
            ),
        )
    }

    /**
     * Turn yt-dlp's `cookies` field into a Cookie request header and merge into
     * [headers]. The dump format may include Domain=/Path=/Secure attributes;
     * those are stripped so only `name=value` pairs remain.
     */
    private fun mergeCookiesIntoHeaders(
        headers: Map<String?, String?>?,
        cookiesField: String?,
    ): Map<String?, String?>? {
        val cookieHeader = cookieRequestHeader(cookiesField)
        if (cookieHeader.isNullOrBlank()) return headers

        val out = linkedMapOf<String?, String?>()
        if (headers != null) out.putAll(headers)
        val hasCookie = out.keys.any { it != null && it.equals("Cookie", ignoreCase = true) }
        if (!hasCookie) {
            out["Cookie"] = cookieHeader
        }
        return out
    }

    /** `name=value; …` suitable for an HTTP Cookie request header. */
    private fun cookieRequestHeader(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val pairs = raw.split(';')
            .map { it.trim() }
            .filter { part ->
                if (part.isEmpty()) return@filter false
                val name = part.substringBefore('=', missingDelimiterValue = part).trim()
                // Skip Set-Cookie-style attributes yt-dlp embeds in infojson.
                val lower = name.lowercase()
                lower != "domain" &&
                    lower != "path" &&
                    lower != "secure" &&
                    lower != "expires" &&
                    lower != "version" &&
                    lower != "httponly" &&
                    lower != "max-age" &&
                    lower != "samesite" &&
                    part.contains('=')
            }
        return pairs.takeIf { it.isNotEmpty() }?.joinToString("; ")
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

    /** Extract top-level `{…}` objects from yt-dlp stdout (may include warnings). */
    private fun parseJsonRoots(out: String): List<JsonNode> {
        val roots = ArrayList<JsonNode>()
        var i = 0
        val n = out.length
        while (i < n) {
            val start = out.indexOf('{', i)
            if (start < 0) break
            var depth = 0
            var inString = false
            var escape = false
            var end = -1
            for (j in start until n) {
                val c = out[j]
                if (inString) {
                    when {
                        escape -> escape = false
                        c == '\\' -> escape = true
                        c == '"' -> inString = false
                    }
                    continue
                }
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            end = j
                            break
                        }
                    }
                }
            }
            if (end < 0) break
            val slice = out.substring(start, end + 1)
            try {
                roots.add(objectMapper.readTree(slice))
            } catch (_: Exception) {
                // Skip malformed blobs (e.g. truncated warning text).
            }
            i = end + 1
        }
        return roots
    }
}
