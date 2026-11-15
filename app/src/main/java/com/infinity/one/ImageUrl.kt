package com.infinity.one

/**
 * Normalizes, resolves, and rewrites provider image / logo / poster / cover / still URLs.
 * Port of TypeScript `buildImageUrl` from portalApi.ts.
 */
object ImageUrl {

    private val STALE_HOSTS = Regex(
        """(?:webhop\.live|starshare\.live|corelink\.|corelink\.blog|stalker\.|mag\.local|iptv\.local|localhost|127\.0\.0\.1)""",
        RegexOption.IGNORE_CASE
    )

    private val URL_ORIGIN_PATH = Regex(
        """^(https?://[^/]+)(/.*)?$""",
        RegexOption.IGNORE_CASE
    )

    private val BASE64_ALPHABET = Regex("""^[A-Za-z0-9+/=\s]+$""")

    private fun decodeBase64Bytes(s: String): ByteArray? {
        val clean = s.replace(Regex("""\s+"""), "")
        return try {
            android.util.Base64.decode(clean, android.util.Base64.DEFAULT)
        } catch (_: Throwable) {
            try {
                java.util.Base64.getDecoder().decode(clean)
            } catch (_: Throwable) {
                null
            }
        }
    }

    fun cleanServerRoot(base: String): String {
        var b = base.trim()
        if (b.startsWith("stalker:", ignoreCase = true)) {
            b = b.substring(8).trim()
        }
        val match = Regex("""^(https?://[^/?#]+)""", RegexOption.IGNORE_CASE).find(b)
        if (match != null) {
            return match.groupValues[1].trimEnd('/')
        }
        val s = b.trimEnd('/')
            .replace(Regex("""/portal\.php.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""/c/.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""/c$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""/get\.php.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""/player_api\.php.*$""", RegexOption.IGNORE_CASE), "")
        val root = s.split('?')[0].split('#')[0].trimEnd('/')
        return if (root.isNotEmpty() && !root.startsWith("http://", ignoreCase = true) && !root.startsWith("https://", ignoreCase = true)) {
            "http://$root"
        } else root
    }

    fun activeServerRoot(): String {
        val u = runCatching { PlaylistStore.shownUrl }.getOrNull()
            ?: runCatching { PlaylistStore.lists.getOrNull(PlaylistStore.active)?.url }.getOrNull()
            ?: runCatching { PlaylistStore.channels.firstOrNull()?.url }.getOrNull()
            ?: ""
        val cleaned = cleanServerRoot(u)
        return if (cleaned.isNotBlank()) cleaned else "http://livebox.pro"
    }

    fun rewriteStale(urlStr: String, base: String = ""): String {
        var effectiveRoot = cleanServerRoot(base.ifBlank { activeServerRoot() })
        if (effectiveRoot.isBlank()) {
            effectiveRoot = "http://livebox.pro"
        }

        // Special handling for webhop.live (stale host in livebox.pro and Stalker playlists)
        if (urlStr.contains("webhop.live", ignoreCase = true)) {
            val targetHost = Regex("""https?://([^:/]+)""", RegexOption.IGNORE_CASE)
                .find(effectiveRoot)?.groupValues?.get(1) ?: "livebox.pro"

            val targetPort = Regex("""https?://[^:/]+:(\d+)""", RegexOption.IGNORE_CASE)
                .find(effectiveRoot)?.groupValues?.get(1)

            return if (targetPort != null) {
                urlStr.replace(Regex("""https?://[^/]*webhop\.live(?::\d+)?(/.*)?$""", RegexOption.IGNORE_CASE)) { m ->
                    val path = m.groupValues.getOrNull(1) ?: ""
                    "$effectiveRoot$path"
                }
            } else {
                // If effectiveRoot has no explicit port, preserve any port already present on webhop.live (e.g. :8080)
                urlStr.replace(Regex("""(?:[a-zA-Z0-9.-]+\.)?webhop\.live""", RegexOption.IGNORE_CASE), targetHost)
            }
        }

        // General rewrite for other stale / local origins (starshare.live, corelink.blog, localhost, stalker., mag.local, etc.)
        val urlMatch = URL_ORIGIN_PATH.find(urlStr)
        if (urlMatch != null) {
            val origin = urlMatch.groupValues[1]
            val path = urlMatch.groupValues.getOrNull(2) ?: ""
            if (STALE_HOSTS.containsMatchIn(origin)) {
                return if (path.isNotEmpty()) {
                    if (path.startsWith("/")) "$effectiveRoot$path" else "$effectiveRoot/$path"
                } else effectiveRoot
            }
        }
        return urlStr
    }

    fun rewriteUrl(urlStr: String): String = rewriteStale(urlStr, "")

    fun build(base: String, raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val s = raw.trim()
        if (s.isEmpty() || s.equals("null", ignoreCase = true) || s.equals("undefined", ignoreCase = true) ||
            s.equals("N/A", ignoreCase = true) || s.equals("none", ignoreCase = true)
        ) return ""

        val serverRoot = cleanServerRoot(base.ifBlank { activeServerRoot() })

        // 1. Data URI: already has the data: scheme (e.g. data:image/png;base64,...)
        if (s.startsWith("data:", ignoreCase = true)) {
            // If it's an incomplete / truncated base64 image payload (e.g. < 350 chars), drop it to avoid Glide/Coil decode crashes
            if (s.length < 350) return ""
            return s
        }

        // 2. Direct HTTP / HTTPS link: rewrite stale provider hostnames if matched
        if (s.startsWith("http://", ignoreCase = true) || s.startsWith("https://", ignoreCase = true)) {
            if (s.contains("image.tmdb.org", ignoreCase = true)) {
                val path = s.substringAfter("image.tmdb.org/t/p/")
                val afterPrefix = path.substringAfter('/', "")
                if (afterPrefix.isBlank() || !afterPrefix.contains('.')) return ""
            }
            return rewriteStale(s, serverRoot)
        }

        // 3. Base64-encoded URL (e.g. aHR0cDov... -> http:// or aHR0cHM6... -> https://)
        if (s.startsWith("aHR0cDov") || s.startsWith("aHR0cHM6") || s.startsWith("aHR0cDox")) {
            try {
                val bytes = decodeBase64Bytes(s)
                if (bytes != null) {
                    val decoded = String(bytes, Charsets.UTF_8).trim()
                    if (decoded.startsWith("http://", ignoreCase = true) || decoded.startsWith("https://", ignoreCase = true)) {
                        return rewriteStale(decoded, serverRoot)
                    }
                }
            } catch (_: Throwable) {
                // not a base64 url
            }
        }

        // 4. Raw base64 image data without the data: prefix (must have sufficient length to be a complete image)
        if (s.length >= 350) {
            if (s.startsWith("/9j/")) {
                return "data:image/jpeg;base64,$s"
            }
            if (s.startsWith("iVBORw0KGgo")) {
                return "data:image/png;base64,$s"
            }
            if (s.startsWith("R0lGOD")) {
                return "data:image/gif;base64,$s"
            }
            if (s.startsWith("UklGR")) {
                return "data:image/webp;base64,$s"
            }
            if (s.startsWith("PHN2Zw") || s.startsWith("PD94bW")) {
                return "data:image/svg+xml;base64,$s"
            }
            if (s.startsWith("Qk")) {
                return "data:image/bmp;base64,$s"
            }

            // Long base64 string without URL slashes or dots (common raw base64 image payload)
            if (s.length > 500 && BASE64_ALPHABET.matches(s)) {
                try {
                    val sample = s.replace(Regex("""\s+"""), "").take(32)
                    val decoded = decodeBase64Bytes(sample)
                    if (decoded != null && decoded.size >= 4) {
                        // PNG magic: 0x89 'P' 'N' 'G'
                        if (decoded[0] == 0x89.toByte() && decoded[1] == 0x50.toByte() && decoded[2] == 0x4E.toByte() && decoded[3] == 0x47.toByte()) {
                            return "data:image/png;base64,$s"
                        }
                        // JPEG magic: 0xFF 0xD8
                        if (decoded[0] == 0xFF.toByte() && decoded[1] == 0xD8.toByte()) {
                            return "data:image/jpeg;base64,$s"
                        }
                        // GIF magic: 'G' 'I' 'F'
                        if (decoded[0] == 'G'.code.toByte() && decoded[1] == 'I'.code.toByte() && decoded[2] == 'F'.code.toByte()) {
                            return "data:image/gif;base64,$s"
                        }
                        // WebP / RIFF magic: 'R' 'I' 'F' 'F'
                        if (decoded[0] == 'R'.code.toByte() && decoded[1] == 'I'.code.toByte() && decoded[2] == 'F'.code.toByte() && decoded[3] == 'F'.code.toByte()) {
                            return "data:image/webp;base64,$s"
                        }
                    }
                } catch (_: Throwable) {
                    // not a raw base64 image
                }
            }
        }

        // 5. Server-relative path
        return if (s.startsWith("/")) {
            "$serverRoot$s"
        } else {
            "$serverRoot/$s"
        }
    }
}

/** Global convenience aliases */
fun buildImageUrl(base: String, raw: String?): String = ImageUrl.build(base, raw)
fun rewriteImageUrl(raw: String): String = ImageUrl.rewriteUrl(raw)
