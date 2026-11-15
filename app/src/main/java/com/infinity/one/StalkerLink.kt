package com.infinity.one

import java.net.URLEncoder

/**
 * Asking a Stalker portal for a play address ("create_link") and reading its answer. Without Android, testable.
 *
 * Portals differ here, mostly for movies and series:
 * - Some reject the parameters a MAG box sends ("forced_storage=undefined"), others need them. So for movies
 *   several request forms are tried in turn ([requests]).
 * - The address comes back in "cmd" ("ffmpeg http://…"), in "url", as a relative path, or only as a file id plus a
 *   play token that the app turns into ".../play/movie.php?..." itself ([address]).
 */
object StalkerLink {
    fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun api(endpoint: String, type: String, extra: String) =
        "$endpoint?type=$type&action=create_link$extra&JsHttpRequest=1-xml"

    /** Strips player prefixes like ffmpeg, auto, ffrt, -i, vlc from commands. */
    fun cleanCmd(cmd: String): String =
        cmd.replace(Regex("""^(ffmpeg|ffrt\d*|auto|-i|vlc)\s+""", RegexOption.IGNORE_CASE), "").trim()

    /**
     * Checks whether a stream URL is a real, network-playable media address,
     * rather than an internal unresolved STB command (like localhost, /ch/123_, ffmpeg).
     */
    fun isPlayableStreamUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val trimmed = url.trim()
        if (!Regex("""^(https?|rtsp|rtmp|mms)://""", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)) return false
        if (trimmed.startsWith("file:", ignoreCase = true) ||
            trimmed.contains("localhost", ignoreCase = true) ||
            trimmed.contains("127.0.0.1") ||
            Regex("""^(ffmpeg|ffrt\d*|auto|-i|vlc)\s+""", RegexOption.IGNORE_CASE).containsMatchIn(trimmed) ||
            Regex("""/ch/\d+_""", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)
        ) {
            return false
        }
        return true
    }

    /** Extracts stream ID from command or movieId for fixing stream= parameters. */
    fun extractStreamId(cmd: String): String? {
        val sm = Regex("""stream=([a-zA-Z0-9_\-]+)""", RegexOption.IGNORE_CASE).find(cmd)
        if (sm != null) return sm.groupValues[1]
        val trimmed = cleanCmd(cmd)
        if (trimmed.isNotEmpty() && trimmed.all(Char::isDigit)) return trimmed
        val cm = Regex("""(?:ch|channel|stream)/([a-zA-Z0-9_\-]+)""", RegexOption.IGNORE_CASE).find(cmd)
        if (cm != null) return cm.groupValues[1]
        return movieIdOf(cmd)
    }

    /**
     * create_link requests to try in this order. Channels ("itv") keep the single MAG form, so zapping stays fast.
     * [series] = episode number if the command belongs to a whole season.
     */
    fun requests(endpoint: String, type: String, cmd: String, series: String? = null): List<String> {
        val c = enc(cmd.trim())
        val s = series?.let { enc(it) } ?: ""
        val withSeries = if (series != null) "&series=$s" else ""
        val out = LinkedHashSet<String>()
        // 1. As a MAG box sends it
        out.add(api(endpoint, type, "&cmd=$c&series=$s&forced_storage=undefined&disable_ad=0&download=0"))
        if (type != "vod") return out.toList()
        // 2. Empty storage instead of "undefined" (Ministra video club)
        out.add(api(endpoint, type, "&cmd=$c&series=$s&forced_storage=&disable_ad=0&download=0&force_ch_link_check=0"))
        // 3. Only the command (many Xtream-based portals)
        out.add(api(endpoint, type, "&cmd=$c$withSeries"))
        // 4. A bare file id: the form the video club uses itself
        val id = cmd.trim()
        if (id.isNotEmpty() && id.all(Char::isDigit)) out.add(api(endpoint, type, "&cmd=" + enc("/media/file_$id.mpg") + withSeries))
        return out.toList()
    }

    /** "ffmpeg http://…" / "auto http://…" → the address; null if there is no http(s) address. */
    fun strip(cmd: String?): String? {
        val c = cmd?.trim() ?: return null
        val cleaned = c.replace(Regex("""^(ffmpeg|ffrt\d*|auto|-i|vlc)\s+""", RegexOption.IGNORE_CASE), "")
        val i = cleaned.indexOf("http://").let { if (it >= 0) it else cleaned.indexOf("https://") }
        if (i < 0) return null
        return cleaned.substring(i).trim().substringBefore(' ').takeIf { it.length > 8 }
    }

    /** Cleans up stream URLs: replaces %mac%, repairs localhost/127.0.0.1 loopback, fixes empty stream IDs. */
    fun cleanStreamUrl(rawUrl: String, endpoint: String, mac: String, streamId: String? = null): String {
        var u = rawUrl.trim()
        if (mac.isNotEmpty()) {
            u = u.replace("%mac%", mac, ignoreCase = true)
        }
        u = u.replace("\\", "").trim()
        u = u.replace(Regex("""^(ffmpeg|ffrt\d*|auto|-i|vlc)\s+""", RegexOption.IGNORE_CASE), "").trim()

        val candidates = u.split(Regex("""\s+|\|""")).map { it.trim() }.filter { it.isNotEmpty() }
        val candidateHttp = candidates.firstOrNull { Regex("""^(https?|rtmp|rtsp)://""", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        if (candidateHttp != null) {
            u = candidateHttp
        }

        val serverRoot = origin(endpoint) ?: endpoint.substringBefore("/portal.php").substringBefore("/c").trimEnd('/')

        if (u.startsWith("/") && !u.startsWith("//")) {
            u = "$serverRoot$u"
        }

        if (Regex("""(localhost|127\.0\.0\.1|webhop\.live|starshare\.live|corelink\.|corelink\.blog|stalker\.|mag\.local|iptv\.local)""", RegexOption.IGNORE_CASE).containsMatchIn(u)) {
            try {
                val portalUri = java.net.URI(serverRoot)
                val streamUri = java.net.URI(u)
                u = java.net.URI(
                    portalUri.scheme ?: streamUri.scheme,
                    streamUri.userInfo,
                    portalUri.host,
                    streamUri.port,
                    streamUri.path,
                    streamUri.query,
                    streamUri.fragment
                ).toString()
            } catch (_: Exception) {
                val host = origin(endpoint)
                if (host != null) {
                    u = u.replace(Regex("""https?://[^/]+""", RegexOption.IGNORE_CASE), host)
                }
            }
        }

        if (Regex("""stream=(&|$)""", RegexOption.IGNORE_CASE).containsMatchIn(u)) {
            val sid = streamId?.takeIf { it.isNotBlank() }
            if (sid != null) {
                u = u.replace(Regex("""stream=(&|$)""", RegexOption.IGNORE_CASE), "stream=$sid$1")
            }
        }

        return u
    }

    /**
     * Extracts a link string from the raw response body following portalApi.ts extractLink logic.
     */
    fun extractLink(body: String?): String {
        if (body.isNullOrBlank()) return ""
        val trimmed = body.trim()
        val parsed = try {
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                if (trimmed.startsWith("[")) org.json.JSONArray(trimmed) else org.json.JSONObject(trimmed)
            } else null
        } catch (_: Exception) { null }

        if (parsed != null) {
            val js = (parsed as? org.json.JSONObject)?.opt("js")
            val target = when (js) {
                is org.json.JSONArray -> js.opt(0)
                else -> js
            }
            if (target is String && target.isNotBlank() && !target.trim().startsWith("{")) {
                return target.trim()
            }
            if (target is org.json.JSONObject) {
                for (k in listOf("cmd", "url", "link", "playlist", "stream", "stream_url", "uri", "file", "path")) {
                    val v = target.optString(k, "").trim()
                    if (v.isNotEmpty() && v != "null" && v != "0") return v
                }
            }
            val dataTarget = when (parsed) {
                is org.json.JSONArray -> parsed.opt(0)
                is org.json.JSONObject -> parsed.opt("data") ?: parsed
                else -> null
            }
            val dt = when (dataTarget) {
                is org.json.JSONArray -> dataTarget.opt(0)
                else -> dataTarget
            }
            if (dt is String && dt.isNotBlank() && !dt.trim().startsWith("{")) {
                return dt.trim()
            }
            if (dt is org.json.JSONObject) {
                for (k in listOf("cmd", "url", "link", "stream", "stream_url", "uri", "file", "path")) {
                    val v = dt.optString(k, "").trim()
                    if (v.isNotEmpty() && v != "null" && v != "0") return v
                }
            }
        }

        if (Regex("""^(https?|rtmp|rtsp)://""", RegexOption.IGNORE_CASE).containsMatchIn(trimmed) ||
            (trimmed.startsWith("/") && !trimmed.startsWith("{") && !trimmed.startsWith("["))) {
            return trimmed
        }

        val m = Regex("""\"(?:cmd|url|link|stream|stream_url|uri)\"\s*:\s*\"([^\"]+)\"""", RegexOption.IGNORE_CASE).find(trimmed)
        if (m != null) {
            return m.groupValues[1].replace("\\", "").trim()
        }

        return ""
    }

    /**
     * Fallback to same host stream URL (from stbEnvironment / portalApi.ts).
     */
    fun applySameHostStreamProxy(streamUrl: String?, endpoint: String, cmd: String?): String {
        val serverRoot = origin(endpoint) ?: endpoint.substringBefore("/portal.php").substringBefore("/c").trimEnd('/')
        var target = streamUrl?.trim().orEmpty()
        if (target.isEmpty() && !cmd.isNullOrBlank()) {
            val clean = cleanCmd(cmd)
            if (clean.isNotEmpty()) {
                if (isPlayableStreamUrl(clean)) {
                    target = clean
                } else if ((clean.startsWith("/") || clean.contains(".mpg") || clean.contains(".m3u8") || clean.contains(".ts")) && !clean.contains("ch/")) {
                    target = "$serverRoot/${clean.trimStart('/')}"
                }
            }
        }
        return target
    }

    /** "http://host:port" of the portal. */
    fun origin(portal: String): String? = Regex("""^(https?://[^/?#]+)""", RegexOption.IGNORE_CASE).find(portal.trim())?.value

    /** The portal installation: [endpoint] without "/server/load.php" or "/portal.php" ("http://host/stalker_portal"). */
    fun installation(endpoint: String): String? {
        val m = Regex("""^(https?://[^/?#]+)([^?#]*)""", RegexOption.IGNORE_CASE).find(endpoint.trim()) ?: return null
        val path = m.groupValues[2].replace(Regex("""/(?:server/load\.php|[^/]*\.php)$""", RegexOption.IGNORE_CASE), "").trimEnd('/')
        return m.groupValues[1] + path
    }

    /** Error field of a create_link answer ("nothing_to_play", "limit", …); null = none ("", "0", "null", "false"). */
    fun error(f: Map<String, String>): String? =
        f["error"]?.trim()?.takeIf { it.isNotEmpty() && it != "0" && !it.equals("null", true) && !it.equals("false", true) }

    /**
     * true = no other request form and no file lookup can help, so stop asking: the account's stream limit is reached
     * ("limit", the value the MAG client reports as too many users). Other errors such as "nothing_to_play" can depend on
     * the request form, so the next form is still tried.
     */
    fun isFinal(error: String?): Boolean = error.equals("limit", ignoreCase = true)

    /** A bare video club command ("/media/<id>.mpg", "/media/file_<id>.mpg"): an identifier, not a path to play. */
    private val VIRTUAL_CMD = Regex("""^/media/(?:file_)?\d+\.mpg$""", RegexOption.IGNORE_CASE)

    /**
     * Play address from the "js" part of a create_link answer ([f] = its fields; a bare string counts as "cmd").
     * [endpoint] = the portal API that answered, [sent] = the command that was asked for. null = nothing playable.
     */
    fun address(endpoint: String, mac: String, f: Map<String, String>, type: String, sent: String = ""): String? {
        val base = installation(endpoint)
        // With an error set ("nothing_to_play", …) only a full address counts, as before: a path or an address built
        // from the answer would not play, and the next request form or the file lookup would never be tried
        val err = error(f)
        val streamId = f["id"] ?: extractStreamId(sent)
        for (k in listOf("cmd", "url", "link", "playlist", "stream", "stream_url", "uri", "file", "path")) {
            val v = f[k]?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: continue
            strip(v)?.let { return cleanStreamUrl(it, endpoint, mac, streamId) }
            if (err != null) continue
            // Path without a server ("/media/…?…", "/play/movie.php?…"): served by the portal installation.
            // Not when the portal only echoes the command it was sent, and not a bare video club command: both mean
            // no address.
            val p = v.substringAfterLast(' ')
            if (base != null && p.startsWith("/") && p != sent.trim().substringAfterLast(' ') && !VIRTUAL_CMD.matches(p)) return cleanStreamUrl(base + p, endpoint, mac, streamId)
        }
        if (err != null) return null
        // Only file id + play token: build the address of the portal's movie player
        val host = origin(endpoint)
        if (type == "vod" && host != null) {
            val token = f["play_token"]?.trim().orEmpty()
            var id = f["id"]?.trim().orEmpty()
            if (token.isNotEmpty() && id.isNotEmpty() && id != "null" && id != "0") {
                if (!id.contains('.')) id += ".mp4"
                return "$host/play/movie.php?mac=${enc(mac)}&stream=${enc(id)}&play_token=${enc(token)}&type=movie"
            }
        }
        return null
    }

    /**
     * Portal answer made safe to show on screen: MAC, token, server addresses, account names/passwords and
     * other secrets in addresses are replaced. Only the shape stays (which fields, which kind of path).
     */
    fun mask(text: String, mac: String, token: String): String {
        var t = text
        if (mac.isNotEmpty()) {
            t = t.replace(mac, "MAC", ignoreCase = true).replace(enc(mac), "MAC", ignoreCase = true)
        }
        if (token.length >= 4) t = t.replace(token, "TOKEN")
        t = t.replace("\\/", "/")
        t = Regex("""(?i)(https?://)[^/\s"'?]+""").replace(t, "$1SERVER")
        t = Regex("""(?i)/(movie|series|live|timeshift)/[^/\s"]+/[^/\s"]+/""").replace(t, "/$1/USER/PASS/")
        t = Regex("""(?i)\b(play_token|token|password|pass|passwd|username|user|login|mac|sn|key|sig|signature|auth)=([^&\s"']+)""").replace(t, "$1=…")
        t = Regex("""(?i)"(play_token|token|password|username|login|mac)"\s*:\s*"[^"]*"""").replace(t, "\"$1\":\"…\"")
        return t
    }

    private val MEDIA_ID = Regex("""^(?:\S+\s+)?/media/(\d+)\.mpg$""")

    /** Movie ID of a video club command "/media/<id>.mpg" (not "/media/file_<id>.mpg"); null otherwise. */
    fun movieIdOf(cmd: String): String? = MEDIA_ID.find(cmd.trim())?.groupValues?.get(1)

    /** More entries than this in a file list means the portal ignored movie_id and sent a whole category. */
    private const val MAX_FILES = 20

    /**
     * Play command of a movie's first file from the answer to get_ordered_list with movie_id ([items], [total] = total_items).
     * Only a short file list counts: a portal that ignores movie_id returns a whole category, and none of its entries
     * (not even one that carries "/media/file_…") may be played instead. In a short list an entry that carries
     * "/media/file_…" wins, otherwise the first entry's bare ID.
     */
    fun fileCmd(items: List<Map<String, String>>, total: Int): String? {
        if (items.isEmpty() || total > MAX_FILES || items.size > MAX_FILES) return null
        for (f in items) {
            val c = f["cmd"]?.trim().orEmpty()
            val i = c.indexOf("/media/file_")
            if (i >= 0) return c.substring(i).substringBefore(' ')
        }
        val id = items[0]["id"]?.trim()?.takeIf { it.isNotEmpty() && it != "null" && it != "0" } ?: return null
        return "/media/file_$id.mpg"
    }

    /**
     * What to ask the portal for one item. [cmd] = command for create_link first (null = go straight to the file),
     * [lookup] = get_ordered_list parameters that list the item's own files ([lookupType] "vod"/"series"), null = none.
     */
    class Play(val linkType: String, val cmd: String?, val series: String?, val lookupType: String, val lookup: String?) {
        companion object {
            private const val SEP = '\u0002'
            private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

            /** Stored command of a single episode: its own command plus list type, series, season and episode ID. */
            fun episode(cmd: String, listType: String, movieId: String, seasonId: String, episodeId: String): String =
                listOf(cmd, listType, movieId, seasonId, episodeId).joinToString(SEP.toString())

            fun of(type: String, cmd: String, series: String?): Play = when (type) {
                "mfile" -> Play("vod", null, null, "vod", "&movie_id=${enc(cmd)}")
                "efile" -> {
                    val p = cmd.split(SEP)
                    if (p.size != 5) Play("vod", p[0], series, "vod", null)
                    else Play("vod", p[0].ifBlank { null }, series, p[1],
                        "&movie_id=${enc(p[2])}&season_id=${enc(p[3])}&episode_id=${enc(p[4])}")
                }
                "vod" -> Play("vod", cmd, series, "vod", if (series == null) movieIdOf(cmd)?.let { "&movie_id=$it" } else null)
                else -> Play(type, cmd, series, type, null)
            }
        }
    }
}
