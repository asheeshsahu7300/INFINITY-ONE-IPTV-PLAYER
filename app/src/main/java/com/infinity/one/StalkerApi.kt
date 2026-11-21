package com.infinity.one

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.Writer
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Connection to a Stalker/Ministra portal (see [StalkerLogic]): login with the MAC address, channels, movies and
 * series as a list, play address at start. Series appear as placeholders in the list; [episodeList] fetches their episodes
 * only when a series is opened.
 */
object StalkerApi {
    private val client: OkHttpClient = Net.base.newBuilder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).addInterceptor(TlsFallback.interceptor()).build()

    /** Logged-in session: API and token, reused for a few minutes. */
    private class Session(val src: StalkerLogic.Source, val endpoint: String, val token: String, val at: Long)
    private val sessions = HashMap<Int, Session>()
    /** API address that answered last time, per source: tried first, so a new login costs only a few requests. */
    private val knownEndpoint = HashMap<Int, String>()
    private const val SESSION_MS = 10 * 60_000L
    private const val MAX_CHANNELS = 30000
    /** Pages per step when a category is opened or scrolled further (~14 titles per page), see [loadChunk]. */
    private const val LOAD_CHUNK = 4
    /** Short pause between two pages so the portal does not block (one request per category while loading). */
    private const val PAGE_PAUSE_MS = 120L
    /** This many categories in a row without an answer: wait [COOLDOWN_MS], at most [MAX_COOLDOWNS] times. */
    private const val GIVE_UP_AFTER = 3
    private const val MAX_COOLDOWNS = 2
    private const val COOLDOWN_MS = 30_000L

    /** Last response of the portal in short form (only code and kind, never address or MAC) for understandable error texts. */
    @Volatile private var note = ""

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun req(src: StalkerLogic.Source, url: String, token: String?): Request {
        val b = Request.Builder().url(url)
            .header("User-Agent", StalkerLogic.UA)
            .header("X-User-Agent", StalkerLogic.X_UA)
            .header("Accept", "*/*")
            .header("Referer", src.portal)
            .header("Cookie", StalkerLogic.cookie(src.mac))
        if (token != null) b.header("Authorization", "Bearer $token")
        return b.build()
    }

    /** JSON from a response; portals sometimes send a BOM or text before the JSON. */
    private fun jsonOf(body: String): JSONObject? {
        val trimmed = body.trim()
        val firstBrace = trimmed.indexOf('{')
        val firstBracket = trimmed.indexOf('[')
        if ((firstBracket in 0 until firstBrace) || (firstBracket >= 0 && firstBrace < 0)) {
            val lastBracket = trimmed.lastIndexOf(']')
            if (lastBracket > firstBracket) {
                val arrayStr = trimmed.substring(firstBracket, lastBracket + 1)
                return runCatching {
                    JSONObject().put("js", JSONArray(arrayStr))
                }.getOrNull()
            }
        }
        if (firstBrace < 0) return null
        val lastBrace = trimmed.lastIndexOf('}')
        if (lastBrace > firstBrace) {
            return runCatching { JSONObject(trimmed.substring(firstBrace, lastBrace + 1)) }.getOrNull()
        }
        return runCatching { JSONObject(trimmed.substring(firstBrace)) }.getOrNull()
    }

    /**
     * T-026: number of user-facing requests running now (play address, episode list). While it is above 0, list loading
     * ([pageRange], [paged]) waits before its next page, so playback and Live TV do not hang behind a reload.
     */
    private val urgentCount = java.util.concurrent.atomic.AtomicInteger(0)
    private const val URGENT_POLL_MS = 150L
    /** Longest time list loading waits for user requests at once, so a stuck request cannot stop it for good. */
    private const val URGENT_MAX_WAIT_MS = 20_000L

    private inline fun <T> urgent(block: () -> T): T {
        urgentCount.incrementAndGet()
        try { return block() } finally { urgentCount.decrementAndGet() }
    }

    /** List loading: wait while a user request runs ([urgent]); [check] throws on abort. */
    private fun yieldToUrgent(check: () -> Unit) {
        var waited = 0L
        while (urgentCount.get() > 0 && waited < URGENT_MAX_WAIT_MS) {
            check()
            Thread.sleep(URGENT_POLL_MS)
            waited += URGENT_POLL_MS
        }
    }

    /**
     * One request at a time per app, as before T-026: some portals reject a second request on the same session while one
     * is still running ("Authorization failed", seen on a stalker_portal portal during a 12 MB channel list download).
     * Fair, so a waiting play or episode request gets the next turn; list loading also pauses for it ([yieldToUrgent]).
     */
    private val callLock = java.util.concurrent.locks.ReentrantLock(true)
    @Volatile private var lastCallTime = 0L
    private const val MIN_CALL_INTERVAL_MS = 250L

    @Volatile private var activeSearchCall: okhttp3.Call? = null
    private val searchCache = object : java.util.LinkedHashMap<String, List<Item>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Item>>?): Boolean = size > 60
    }

    /** Cancel any currently executing remote search request immediately. */
    fun cancelSearch() {
        try {
            activeSearchCall?.cancel()
            activeSearchCall = null
        } catch (_: Exception) {}
    }

    /**
     * One request: HTTP code and body. On "429 Too Many Requests" waits as the portal asks (at most a few seconds)
     * and tries once more – portals that limit requests otherwise lock the box out for a while. null = no connection.
     */
    private fun call(src: StalkerLogic.Source, url: String, token: String?, isSearch: Boolean = false): Pair<Int, String>? {
        val maxRounds = if (isSearch) 1 else 3
        for (round in 0 until maxRounds) {
            // The request runs under the lock (one at a time, see [callLock]); a play request waits only for the request
            // that is running, because list loading pauses before its next page ([yieldToUrgent]).
            callLock.lock()
            val okCall = client.newCall(req(src, url, token))
            if (isSearch) activeSearchCall = okCall
            val res = try {
                val waitMs = lastCallTime + MIN_CALL_INTERVAL_MS - System.currentTimeMillis()
                if (waitMs > 0) Thread.sleep(waitMs)
                lastCallTime = System.currentTimeMillis()
                okCall.execute().use { r ->
                    val wait = if (r.code == 429) (r.header("Retry-After")?.trim()?.toLongOrNull() ?: 2L).coerceIn(1L, 6L) else 0L
                    Triple(r.code, r.body?.string().orEmpty(), wait)
                }
            } catch (e: Exception) {
                if (isSearch && (okCall.isCanceled() || (e is java.io.IOException && e.message?.contains("Canceled", ignoreCase = true) == true))) {
                    return null
                }
                android.util.Log.w("StalkerHttp", "call exception: ${e.message}, url=$url")
                null
            } finally {
                if (isSearch && activeSearchCall === okCall) activeSearchCall = null
                callLock.unlock()
            } ?: return null

            if (res.first == 429 && round < 2) {
                Thread.sleep(res.third * 1000)
                continue
            }
            if (res.first == 503) {
                android.util.Log.w("StalkerHttp", "Server returned 503 Service Unavailable for $url. Halting retries.")
                return res.first to res.second
            }
            if (res.first == 200 && res.second.isEmpty() && round < 2) {
                Thread.sleep(600L * (round + 1))
                continue
            }
            android.util.Log.i("StalkerHttp", "call: code=${res.first}, len=${res.second.length}, body=${res.second.take(200)}, url=$url")
            return res.first to res.second
        }
        return null
    }

    /** The portal no longer accepts the session (token taken over by another box, expired or locked). */
    private fun authFailed(body: String) = body.contains("Authorization failed", ignoreCase = true)

    /** Response as the "js" part, null on error (the reason is then in [note]). */
    private fun js(src: StalkerLogic.Source, url: String, token: String?, isSearch: Boolean = false): Any? {
        val (code, body) = call(src, url, token, isSearch) ?: run { note = "no connection"; return null }
        if (code !in 200..299) { note = "HTTP $code"; return null }
        if (authFailed(body)) { note = "Authorization failed"; return null }
        val o = jsonOf(body) ?: run { note = "invalid response"; return null }
        note = ""
        return o.opt("js")
    }

    /** Log in: find the API, get the token, fetch the profile (some portals only unlock with it). */
    private fun login(src: StalkerLogic.Source, force: Boolean = false): Session = synchronized(sessions) {
        val cachedS = sessions[src.key]
        if (!force && cachedS != null && System.currentTimeMillis() - cachedS.at < SESSION_MS) return@synchronized cachedS
        var seen = ""
        val known = knownEndpoint[src.key]
        val eps = StalkerLogic.endpoints(src.portal).let { l -> if (known != null) listOf(known) + (l - known) else l }
        for (ep in eps) {
            // First with, then without "prehash" (old portals do not know it)
            var hs: JSONObject? = null
            for (extra in listOf("&token=&prehash=0", "&token=")) {
                hs = js(src, StalkerLogic.api(ep, "stb", "handshake", extra), null) as? JSONObject
                if (hs?.optString("token").isNullOrBlank()) hs = null else break
                if (note.isNotEmpty()) seen = note
                if (note == "HTTP 429" || note.contains("503")) break
            }
            // The portal is there but limits requests: the other addresses would only make it worse
            if (hs == null && (seen == "HTTP 429" || seen.contains("503"))) break
            val token = hs?.optString("token")?.takeIf { it.isNotBlank() } ?: continue
            js(src, StalkerLogic.api(ep, "stb", "get_profile", StalkerLogic.profileExtra(src)), token)
            val s = Session(src, ep, token, System.currentTimeMillis())
            sessions[src.key] = s
            knownEndpoint[src.key] = ep
            return@synchronized s
        }
        throw PlaylistError(
            if (seen.isNotEmpty()) "Das Portal antwortet nicht richtig ($seen). Adresse und MAC-Adresse prüfen."
            else "Das Portal hat die Anmeldung abgelehnt. Adresse und MAC-Adresse prüfen."
        )
    }

    private fun categories(s: Session, type: String, action: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val a = js(s.src, StalkerLogic.api(s.endpoint, type, action), s.token) as? JSONArray ?: return out
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val id = o.opt("id")?.toString().orEmpty()
            val t = o.optString("title").ifBlank { o.optString("name") }
            if (id.isNotEmpty() && t.isNotBlank()) out[id] = t.trim()
        }
        return out
    }

    private fun fields(o: JSONObject): Map<String, String> {
        val f = HashMap<String, String>()
        for (k in o.keys()) {
            val v = o.opt(k)
            if (v is String || v is Number || v is Boolean) {
                f[k] = v.toString()
            } else if (v is JSONArray) {
                val list = (0 until v.length()).mapNotNull { idx ->
                    val elem = v.opt(idx)
                    when (elem) {
                        is String -> elem
                        is JSONObject -> elem.optString("name").takeIf { it.isNotBlank() } ?: elem.optString("title")
                        else -> elem?.toString()
                    }
                }.filter { it.isNotBlank() }
                if (list.isNotEmpty()) f[k] = list.joinToString(", ")
            }
        }
        return f
    }

    /**
     * Fetch all pages of a list ([extra] = parameters), [each] per entry. Stops at [maxItems], an empty page or
     * a repeating page. true = at least one page arrived.
     */
    private fun paged(
        s: Session, type: String, extra: String, maxItems: Int, check: () -> Unit, bulk: Boolean = false, each: (JSONObject) -> Unit
    ): Boolean {
        var page = 1
        var pages = 1
        var got = 0
        var ok = false
        var lastFirst: String? = null
        while (page <= pages && got < maxItems) {
            check()
            // T-026: only list loading ([bulk]) pauses for user requests. The episode list is itself a user request:
            // pausing there made it wait for itself until the 20 s limit, before every page.
            if (bulk) yieldToUrgent(check)
            val o = js(s.src, StalkerLogic.api(s.endpoint, type, "get_ordered_list", "$extra&p=$page"), s.token) as? JSONObject ?: break
            ok = true
            val a = o.optJSONArray("data") ?: break
            if (a.length() == 0) break
            val first = a.optJSONObject(0)?.let { it.opt("id")?.toString() ?: it.opt("name")?.toString() }
            if (first != null && first == lastFirst) break
            lastFirst = first
            val total = o.optInt("total_items", 0)
            val per = o.optInt("max_page_items", 14).coerceAtLeast(1)
            // Without a total count just keep paging until a page is empty
            pages = if (total > 0) ((total + per - 1) / per).coerceIn(1, 800) else (page + 1).coerceAtMost(800)
            for (i in 0 until a.length()) { a.optJSONObject(i)?.let { each(it); got++ } }
            page++
        }
        return ok
    }

    /**
     * Write the list to [out] (M3U): all channels, plus only the first page (newest titles) of every movie and series
     * category, fast like IPTVnator. Categories with more titles go to [onCats]; their further pages come when the
     * category is opened or scrolled ([loadChunk], [StalkerMore]). [onPhase] = progress, [check] throws on abort.
     * Return value: number of entries.
     * [freshLogin] false (T-026, background refresh while a list is shown): reuse a running session, so a playback that
     * uses its token is not cut off; a new login happens only if the portal no longer accepts it.
     */
    fun write(
        src: StalkerLogic.Source, out: Writer, onPhase: (String) -> Unit, check: () -> Unit,
        onCats: (List<StalkerLogic.CatRef>) -> Unit = {}, freshLogin: Boolean = true
    ): Int {
        onPhase("Zugang wird geprüft …")
        var s = login(src, force = freshLogin)
        var n = 0
        out.write("#EXTM3U\n")

        // ---- Channels
        onPhase("Sender werden geladen …")
        var genres = categories(s, "itv", "get_genres")
        if (!freshLogin && note == "Authorization failed") {
            s = login(src, force = true)
            genres = categories(s, "itv", "get_genres")
        }
        var live = 0
        val all = js(src, StalkerLogic.api(s.endpoint, "itv", "get_all_channels"), s.token)
        val data = (all as? JSONObject)?.optJSONArray("data") ?: (all as? JSONArray)
        if (data != null) for (i in 0 until data.length()) {
            val o = data.optJSONObject(i) ?: continue
            StalkerLogic.liveEntry(src, fields(o), genres)?.let { out.write(it); out.write("\n"); n++; live++ }
            if (i % 1000 == 0) check()
        }
        if (live == 0) {
            // Fallback: many portals block "all channels at once" and deliver only page by page
            paged(s, "itv", "&genre=*&force_ch_link_check=&fav=0&sortby=number&hd=0", MAX_CHANNELS, check, bulk = true) { o ->
                StalkerLogic.liveEntry(src, fields(o), genres)?.let { out.write(it); out.write("\n"); n++; live++ }
                if (live % 400 == 0) onPhase("Sender werden geladen ($live) …")
            }
        }

        // ---- Movies and series: only the first page (newest titles) per category, the rest on opening ([loadChunk])
        onPhase("Kategorien werden geladen …")
        val em = Emitter(src) { out.write(it); out.write("\n"); n++ }
        val cats = categories(s, "vod", "get_categories").filterKeys { it != "*" }.map { "vod" to it } +
            categories(s, "series", "get_categories").filterKeys { it != "*" }.map { "series" to it }
        val more = ArrayList<StalkerLogic.CatRef>()
        val skipped = ArrayList<Pair<String, Map.Entry<String, String>>>()
        var failedInRow = 0
        var cooldowns = 0
        fun wait() { var w = 0L; while (w < COOLDOWN_MS) { check(); Thread.sleep(1000); w += 1000 } }
        fun load(type: String, cid: String, cname: String): Paged {
            val r = firstPage(src, type, cid, cname, check, em)
            if (r.more && r.last > 0) more.add(StalkerLogic.CatRef(type, cid, StalkerLogic.groupName(cname), r.total, r.last))
            return r
        }
        for ((idx, c) in cats.withIndex()) {
            val (type, cat) = c
            // Portal stops answering several times in a row: pause briefly, then continue
            if (failedInRow >= GIVE_UP_AFTER && cooldowns < MAX_COOLDOWNS) {
                cooldowns++; failedInRow = 0
                onPhase("Portal antwortet nicht, neuer Versuch in ${COOLDOWN_MS / 1000} s …")
                wait()
            }
            if (failedInRow >= GIVE_UP_AFTER) { skipped.add(c); continue }
            val r = load(type, cat.key, cat.value)
            failedInRow = if (r.ok) 0 else failedInRow + 1
            // Without a single title the category would not appear at all: try again at the end
            if (!r.ok && r.last == 0) skipped.add(c)
            if (idx % 10 == 0) onPhase("Kategorien werden geladen (${idx + 1}/${cats.size}) …")
        }
        if (skipped.isNotEmpty()) {
            onPhase("Kategorien werden erneut versucht …")
            wait()
            var inRow = 0
            for ((type, cat) in skipped) {
                if (inRow >= GIVE_UP_AFTER) break
                inRow = if (load(type, cat.key, cat.value).ok) 0 else inRow + 1
            }
        }
        onCats(more)
        out.flush()
        if (n == 0) {
            val why = if (note.isNotEmpty()) " ($note)" else ""
            throw PlaylistError("Das Portal hat keine Sender geliefert$why. MAC-Adresse und Abo beim Anbieter prüfen.")
        }
        return n
    }

    /** Result of a run over pages: [total] entries according to the portal (-1 = unknown), [last] = last page loaded. */
    private class Paged(val ok: Boolean, val total: Int, val last: Int, val more: Boolean)

    private fun isStalkerPortal(src: StalkerLogic.Source): Boolean =
        src.portal.contains("stalker_portal", ignoreCase = true)

    /**
     * Pages [from] .. [from]+[max]-1 of a list to [each]. Stops at an empty or repeating page. [Paged.more] = the portal
     * has further pages; [Paged.ok] false = a page did not come even after logging in again.
     */
    private fun pageRange(
        src: StalkerLogic.Source, type: String, extra: String, from: Int, max: Int, check: () -> Unit, each: (JSONObject) -> Unit
    ): Paged {
        var page = from
        var pages = from
        var total = -1
        var lastFp: String? = null
        val isStalker = isStalkerPortal(src)
        // T-026: once the list only answers without "sortby", the following pages of this run are asked the same way.
        // Otherwise the next page came back empty and the category counted as complete (now stored, see StalkerMore).
        var ex = extra
        while (page <= pages && page < from + max) {
            check()
            yieldToUrgent(check)
            var o: JSONObject? = null
            for (attempt in 0 until 2) {
                val s = runCatching { login(src, force = attempt > 0) }.getOrNull() ?: break
                if (isStalker) {
                    val res = js(src, StalkerLogic.api(s.endpoint, type, "get_ordered_list", "$ex&p=$page"), s.token)
                    o = when (res) {
                        is JSONObject -> res
                        is JSONArray -> JSONObject().put("data", res)
                        else -> null
                    }
                    if (page == from && (o?.optJSONArray("data")?.length() ?: 0) == 0 && ex.contains("&sortby=")) {
                        val fallbackExtra = ex.replace(Regex("""&sortby=[^&]*"""), "")
                        val altRes = js(src, StalkerLogic.api(s.endpoint, type, "get_ordered_list", "$fallbackExtra&p=$page"), s.token)
                        val altObj = when (altRes) {
                            is JSONObject -> altRes
                            is JSONArray -> JSONObject().put("data", altRes)
                            else -> null
                        }
                        if (altObj != null && (altObj.optJSONArray("data")?.length() ?: 0) > 0) {
                            o = altObj
                            ex = fallbackExtra
                        }
                    }
                } else {
                    o = js(src, StalkerLogic.api(s.endpoint, type, "get_ordered_list", "$extra&p=$page"), s.token) as? JSONObject
                }
                if (o != null) break
            }
            if (o == null) return Paged(false, total, page - 1, true)
            val a = o.optJSONArray("data") ?: return Paged(true, total, page - 1, false)
            if (a.length() == 0) return Paged(true, total, page - 1, false)
            val fp = a.optJSONObject(0)?.opt("id")?.toString() + "|" + a.optJSONObject(a.length() - 1)?.opt("id")?.toString() + "|" + a.length()
            if (fp == lastFp) return Paged(true, total, page - 1, false)
            lastFp = fp
            total = o.optInt("total_items", -1).takeIf { it > 0 } ?: total
            val per = o.optInt("max_page_items", a.length()).coerceAtLeast(1)
            pages = StalkerLogic.pageCount(total, per, page + 1).coerceAtLeast(page)
            for (i in 0 until a.length()) a.optJSONObject(i)?.let(each)
            page++
            if (page < from + max && page <= pages) Thread.sleep(PAGE_PAUSE_MS)
        }
        return Paged(true, total, page - 1, page <= pages)
    }

    /** Writes movies and series as M3U lines (each movie ID and series name only once). */
    private class Emitter(val src: StalkerLogic.Source, val put: (String) -> Unit) {
        private val movies = HashSet<String>()
        private val series = HashSet<String>()

        private fun isSeries(o: JSONObject, type: String, cname: String): Boolean {
            // Explicit movie indicators from portals that send single media with movie=1 / movies=1 / is_movie=true
            val isMovie = o.optBoolean("is_movie", false) ||
                o.optString("is_movie") == "1" ||
                o.optString("movie") == "1" ||
                o.optInt("movie", 0) == 1 ||
                o.optString("movies") == "1" ||
                o.optInt("movies", 0) == 1

            // Explicit series indicators
            val isSeries = o.optString("is_series") == "1" ||
                o.optInt("is_series", 0) == 1 ||
                o.optBoolean("is_series", false) ||
                o.optString("series") == "1" ||
                o.optInt("series", 0) == 1 ||
                (o.opt("series") is Boolean && o.optBoolean("series")) ||
                (o.optJSONArray("series")?.length() ?: 0) > 0 ||
                o.optString("series_id").isNotBlank()

            if (isSeries && !isMovie) return true
            if (isMovie && !isSeries) return false
            if (isSeries) return true
            if (type == "series") return true

            // Category name hint when both movies and series come under single media (type=vod)
            val lower = cname.lowercase()
            return lower.contains("series") || lower.contains("tv show") || lower.contains("web series") || lower.contains("tv serial")
        }

        fun video(o: JSONObject, type: String, cid: String, cname: String) {
            val f = fields(o)
            if (isStalkerPortal(src)) {
                // jiotv / stalker_portal approach: single media with flags
                if (isSeries(o, type, cname)) {
                    val nm = (f["name"] ?: f["title"] ?: "").trim().lowercase()
                    if (nm.isNotEmpty() && series.add(nm)) {
                        StalkerLogic.seriesEntry(src, f, cid, cname, if (type == "series") "s" else "v")?.let(put)
                        cachePreload(f, true, cname)
                    }
                } else {
                    val movieKey = f["id"] ?: f["cmd"]
                    if (movieKey != null && movies.add(movieKey)) {
                        StalkerLogic.movieEntry(src, f, cname)?.let(put)
                        cachePreload(f, false, cname)
                    }
                }
            } else {
                // Normal approach (e.g. livebox.pro)
                if (type == "series" || o.optString("is_series") == "1") {
                    val nm = (f["name"] ?: f["title"] ?: "").trim().lowercase()
                    if (nm.isNotEmpty() && series.add(nm)) {
                        StalkerLogic.seriesEntry(src, f, cid, cname, if (type == "series") "s" else "v")?.let(put)
                        cachePreload(f, true, cname)
                    }
                } else if (movies.add(f["id"] ?: f["cmd"] ?: return)) {
                    StalkerLogic.movieEntry(src, f, cname)?.let(put)
                    cachePreload(f, false, cname)
                }
            }
        }

        private fun cachePreload(f: Map<String, String>, isTv: Boolean, cname: String) {
            runCatching {
                val rawTitle = f["name"] ?: f["title"] ?: ""
                val dummy = Item(
                    id = f["id"] ?: "", kind = if (isTv) Kind.SERIES else Kind.MOVIE,
                    title = rawTitle, year = null, url = "", logo = null, group = cname
                )
                val m = toStalkerMeta(src, f, isTv, dummy)
                if (!m.overview.isNullOrBlank() || m.actors.isNotEmpty() || !m.director.isNullOrBlank()) {
                    val sid = f["id"] ?: f["movie_id"] ?: f["series_id"]
                    val cmd = f["cmd"]
                    val cleanNm = cleanSearchQuery(rawTitle)
                    StalkerMetaCache.put(sid, cmd, MatchLogic.norm(cleanNm), m)
                }
            }
        }
    }

    private fun catExtra(src: StalkerLogic.Source, cid: String) =
        if (isStalkerPortal(src)) "&category=${enc(cid)}&genre=*&sortby=added"
        else "&category=${enc(cid)}&sortby=added"

    /** First page of a category. The portal sends the newest first; the app reads "new" from the end of the list: write oldest first. */
    private fun firstPage(src: StalkerLogic.Source, type: String, cid: String, cname: String, check: () -> Unit, em: Emitter): Paged {
        val rows = ArrayList<JSONObject>()
        val r = pageRange(src, type, catExtra(src, cid), 1, 1, check) { rows.add(it) }
        for (i in rows.indices.reversed()) em.video(rows[i], type, cid, cname)
        return r
    }

    /** One step loaded on demand: new M3U lines, new state, [done] = no further pages, [ok] false = portal does not answer. */
    class Chunk(val m3u: String, val ref: StalkerLogic.CatRef, val done: Boolean, val ok: Boolean)

    /** The next [LOAD_CHUNK] pages of a category after page [StalkerLogic.CatRef.pages] (on opening or scrolling). */
    fun loadChunk(src: StalkerLogic.Source, ref: StalkerLogic.CatRef, check: () -> Unit): Chunk {
        val sb = StringBuilder()
        val em = Emitter(src) { sb.append(it).append('\n') }
        val rows = ArrayList<JSONObject>()
        val r = pageRange(src, ref.type, catExtra(src, ref.id), ref.pages + 1, LOAD_CHUNK, check) { rows.add(it) }
        // Oldest first (see [firstPage]); these titles are older than everything loaded so far and go in front
        for (i in rows.indices.reversed()) em.video(rows[i], ref.type, ref.id, ref.name)
        val cur = ref.copy(pages = maxOf(ref.pages, r.last), total = if (r.total > 0) r.total else ref.total)
        return Chunk(sb.toString(), cur, r.ok && !r.more, r.ok)
    }

    private fun sourceOf(key: Int): StalkerLogic.Source? =
        PlaylistStore.lists.asSequence().mapNotNull { StalkerLogic.parse(it.url) }.firstOrNull { it.key == key }

    /** Returns true if there is at least one active or configured Stalker portal. */
    fun hasActiveSources(): Boolean =
        PlaylistStore.lists.any { StalkerLogic.isSource(it.url) } ||
        (PlaylistStore.shownUrl?.let { StalkerLogic.isSource(it) } == true)

    /** Returns all active Stalker sources (from shown playlists or active primary playlist). */
    fun activeSources(): List<StalkerLogic.Source> {
        val fromLists = PlaylistStore.lists.asSequence()
            .filterIndexed { i, _ -> PlaylistStore.isShown(i) }
            .mapNotNull { StalkerLogic.parse(it.url) }
        val fromPrimary = sequenceOf(PlaylistStore.shownUrl?.let { StalkerLogic.parse(it) }).filterNotNull()
        val all = (fromLists + fromPrimary).distinctBy { it.key }.toList()
        if (all.isNotEmpty()) return all
        return PlaylistStore.lists.asSequence().mapNotNull { StalkerLogic.parse(it.url) }.distinctBy { it.key }.toList()
    }

    /**
     * Remote search on active Stalker/MAG portals for VOD or Series.
     * Uses [StalkerLogic.searchUrl] (`type=$type&action=get_ordered_list&search=$q&p=1`).
     */
    suspend fun search(c: Context, q: String, type: String): List<Item> = withContext(Dispatchers.IO) {
        urgent {
            searchNow(c, q, type) {
                if (!isActive) throw CancellationException()
            }
        }
    }

    private fun searchNow(c: Context, q: String, type: String, check: () -> Unit): List<Item> {
        val cleanQ = q.trim()
        if (cleanQ.length < 3) return emptyList()
        val sources = activeSources()
        if (sources.isEmpty()) return emptyList()

        val out = ArrayList<Item>()
        for (src in sources) {
            check()
            yieldToUrgent(check)
            val cacheKey = "${src.key}:$type:${cleanQ.lowercase()}"
            val cached = synchronized(searchCache) { searchCache[cacheKey] }
            if (cached != null) {
                out.addAll(cached)
                continue
            }

            val s = runCatching { login(src) }.getOrNull() ?: continue
            val sb = StringBuilder()
            val em = Emitter(src) { sb.append(it).append('\n') }
            val isStalker = isStalkerPortal(src)
            val queryType = if (isStalker && type == "series") "vod" else type

            check()
            yieldToUrgent(check)
            // Query only page 1: immediate response with top matches in a single request
            val url = StalkerLogic.searchUrl(s.endpoint, queryType, cleanQ, 1)
            val res = js(src, url, s.token, isSearch = true)
            val data = when (res) {
                is JSONObject -> res.optJSONArray("data")
                is JSONArray -> res
                else -> null
            } ?: continue
            if (data.length() == 0) continue

            for (i in 0 until data.length()) {
                val obj = data.optJSONObject(i) ?: continue
                val cid = obj.optString("category_id", "")
                val cname = obj.optString("category_name", if (type == "series") "Serien" else "Filme")
                em.video(obj, queryType, cid, cname)
            }

            if (sb.isNotEmpty()) {
                val parsed = runCatching { M3uParser.parse("#EXTM3U\n$sb") }.getOrDefault(emptyList())
                if (parsed.isNotEmpty()) {
                    synchronized(searchCache) { searchCache[cacheKey] = parsed }
                    out.addAll(parsed)
                    runCatching { PlaylistStore.appendItems(c, src.encode(), parsed) }
                }
            }
        }
        return out
    }

    /**
     * Play command of the first file of a movie/episode with its own files ("/media/file_<file ID>.mpg"), as the MAG box
     * asks for it when the title is opened. [listType] "vod" or "series", [extra] = movie_id (+ season_id, episode_id).
     */
    private fun fileCmd(src: StalkerLogic.Source, s: Session, listType: String, extra: String): String? {
        val o = js(src, StalkerLogic.api(s.endpoint, listType, "get_ordered_list", "$extra&p=1"), s.token)
        val a = (o as? JSONObject)?.optJSONArray("data") ?: (o as? JSONArray) ?: return null
        val items = (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::fields) }
        val total = (o as? JSONObject)?.optInt("total_items", items.size) ?: items.size
        return StalkerLink.fileCmd(items, total)
    }

    /**
     * What the portal answered at the last failed start of a movie/episode, with MAC, tokens, server addresses and
     * account details hidden (see [StalkerLink.mask]). Shown under the error so the cause can be found. Empty = nothing.
     */
    @Volatile var lastDetail = ""

    /**
     * One create_link request: the "js" part (or null) and a short, masked description for [lastDetail].
     * [stop] = more requests now cannot help: no connection (each further form would wait for the same timeout), or still
     * "429 Too Many Requests" after the wait in [call] (each further form would make the limit worse).
     */
    private class LinkAnswer(val js: Any?, val what: String, val authFailed: Boolean, val stop: Boolean = false, val body: String = "")

    private fun linkCall(src: StalkerLogic.Source, url: String, token: String): LinkAnswer {
        val (code, body) = call(src, url, token) ?: return LinkAnswer(null, "no connection", false, stop = true)
        if (authFailed(body)) return LinkAnswer(null, "Authorization failed", true, body = body)
        if (code == 429 || code == 503) return LinkAnswer(null, "HTTP $code", false, stop = true, body = body)
        val o = if (code in 200..299) jsonOf(body) else null
        val what = if (code !in 200..299) "HTTP $code" else o?.opt("js")?.toString() ?: ("no JSON: " + body.take(80))
        return LinkAnswer(o?.opt("js"), StalkerLink.mask(what, src.mac, token), false, body = body)
    }

    /** Real play address for "stalker://…" (freshly generated by the portal). null = not possible. */
    suspend fun resolve(c: Context, url: String): String? = withContext(Dispatchers.IO) { urgent { resolveNow(c, url) } }

    private suspend fun resolveNow(c: Context, url: String): String? = withContext(Dispatchers.IO) {
        val trace = ArrayList<String>()
        fun fail(why: String): String? {
            trace.add(why)
            lastDetail = trace.joinToString(" | ").take(500)
            return null
        }
        lastDetail = ""
        val ref = StalkerLogic.itemRef(url) ?: return@withContext fail("bad item address")
        val src = sourceOf(ref.key) ?: return@withContext fail("portal login not found")
        val item = StalkerLink.Play.of(ref.type, ref.cmd, ref.series)

        var rejected = false
        var stopped = false
        fun ask(s: Session, cmd: String, series: String?, tag: String): String? {
            val cleanCmd = StalkerLink.cleanCmd(cmd)
            val reqs = LinkedHashSet<String>()
            // 1. Standard requests
            reqs.addAll(StalkerLink.requests(s.endpoint, item.linkType, cmd, series))
            // 1b. If cleanCmd is different, try cleanCmd
            if (cleanCmd.isNotEmpty() && cleanCmd != cmd) {
                reqs.addAll(StalkerLink.requests(s.endpoint, item.linkType, cleanCmd, series))
                reqs.add("${s.endpoint}?type=${item.linkType}&action=create_link&cmd=${StalkerLink.enc(cleanCmd)}&forced_storage=0&disable_ad=0&JsHttpRequest=1-xml" + (if (series != null) "&series=${StalkerLink.enc(series)}" else ""))
            }
            // 1c. Series fallback: some portals require type=series instead of type=vod for episodes
            if (series != null && item.linkType == "vod") {
                reqs.add("${s.endpoint}?type=series&action=create_link&cmd=${StalkerLink.enc(if (cleanCmd.isNotEmpty()) cleanCmd else cmd)}&series=${StalkerLink.enc(series)}&forced_storage=0&disable_ad=0&JsHttpRequest=1-xml")
            }
            // 1d. Channel path fallback: /ch/123_ -> /ch/123
            val chMatch = Regex("""(/ch/[a-zA-Z0-9_\-]+)""", RegexOption.IGNORE_CASE).find(if (cleanCmd.isNotEmpty()) cleanCmd else cmd)?.value
            if (chMatch != null && chMatch != cmd && chMatch != cleanCmd) {
                reqs.add("${s.endpoint}?type=${item.linkType}&action=create_link&cmd=${StalkerLink.enc(chMatch)}&forced_storage=0&disable_ad=0&JsHttpRequest=1-xml")
            }

            for ((i, link) in reqs.withIndex()) {
                val a = linkCall(src, link, s.token)
                trace.add("$tag.${i + 1}: ${a.what}")
                // Session no longer accepted: other request forms would fail the same way
                if (a.authFailed) { rejected = true; return null }
                if (a.stop) { stopped = true; return null }

                // 1. Extract link from raw body or JSON structure (portalApi.ts extractLink)
                val extracted = StalkerLink.extractLink(a.body)
                if (extracted.isNotEmpty()) {
                    val cleaned = StalkerLink.cleanStreamUrl(extracted, s.endpoint, src.mac, StalkerLink.extractStreamId(cmd))
                    if (StalkerLink.isPlayableStreamUrl(cleaned)) return cleaned
                    if (cleaned.startsWith("http://") || cleaned.startsWith("https://")) return cleaned
                }

                // 2. Also check fields / address builder (handles movie player token URLs & relative paths)
                val f = when (val r = a.js) {
                    is JSONObject -> fields(r)
                    is JSONArray -> r.optJSONObject(0)?.let(::fields) ?: r.optString(0).takeIf { it.isNotBlank() }?.let { mapOf("cmd" to it) } ?: emptyMap()
                    is String -> mapOf("cmd" to r)
                    else -> emptyMap()
                }
                if (f.isNotEmpty()) {
                    StalkerLink.address(s.endpoint, src.mac, f, item.linkType, cmd)?.let { return it }
                    // Stream limit of the account reached: every other form gets the same answer
                    if (StalkerLink.isFinal(StalkerLink.error(f))) { stopped = true; return null }
                }
            }
            return null
        }

        // Retry logic: If stream URL is not returned, force session token refresh (login force=true) and retry
        val live = ref.type == "itv"
        var finalUrl: String? = null
        var lastSession: Session? = null
        for (attempt in 0 until (if (live) 2 else 3)) {
            if (attempt > 0 && !live) delay(800)
            rejected = false
            stopped = false
            val s = runCatching { login(src, force = attempt > 0) }.getOrNull()
            if (s == null) {
                if (note.contains("503") || note.contains("429")) {
                    return@withContext fail("login failed ($note)")
                }
                if (attempt == 0) continue
                return@withContext fail("login failed ($note)")
            }
            lastSession = s
            val n = attempt + 1

            // 1. The command from the list
            if (item.cmd != null) {
                val out = ask(s, item.cmd, item.series, "$n")
                if (out != null) {
                    finalUrl = out
                    break
                }
            }

            // 2. Movie/episode with its own files (Ministra video club)
            if (item.lookup != null) {
                val fc = fileCmd(src, s, item.lookupType, item.lookup)
                trace.add("$n.file: ${fc ?: "none ($note)"}")
                if (fc != null) {
                    val out = ask(s, fc, null, "$n.F")
                    if (out != null) {
                        finalUrl = out
                        break
                    }
                }
            }

            // 3. Direct URL fallback: If cmd is already a playable HTTP/HTTPS stream URL (not internal command), use cleanCmd!
            val cleanCmd = item.cmd?.let { StalkerLink.cleanCmd(it) }.orEmpty()
            if (StalkerLink.isPlayableStreamUrl(cleanCmd)) {
                finalUrl = cleanCmd
                break
            }

            // If account reached stream limit (stopped), don't hammer with retries
            if (stopped) break

            // If stream URL not returned on attempt 0: Force session token refresh and retry
            if (attempt == 0) {
                trace.add("empty link -> retrying with refreshed session token")
                continue
            }
            break
        }

        if (finalUrl != null) {
            lastDetail = ""
            return@withContext finalUrl
        }

        // Fallbacks if create_link yielded nothing:
        val clean = StalkerLink.cleanCmd(ref.cmd)
        if (StalkerLink.isPlayableStreamUrl(clean)) {
            lastDetail = ""
            return@withContext clean
        }

        if (lastSession != null) {
            val fallbackProxy = StalkerLink.applySameHostStreamProxy("", lastSession.endpoint, clean)
            if (StalkerLink.isPlayableStreamUrl(fallbackProxy)) {
                lastDetail = ""
                return@withContext fallbackProxy
            }
        }

        // Some portals already deliver a directly playable address in the channel
        if (ref.type == "itv") StalkerLogic.stripCmd(ref.cmd)?.takeIf { !it.contains("localhost") }?.let { return@withContext it }
        fail("item cmd: " + StalkerLink.mask(ref.cmd.take(80), src.mac, ""))
    }

    /**
     * Seasons and episodes of a series ([url] = placeholder "stalker://…/series/…"). null = portal does not answer;
     * empty list = series without episodes. Two portal constructions are recognized: seasons with episode numbers ("series" list)
     * and seasons that deliver their episodes as separate entries.
     */
    fun episodeList(url: String): List<Episode>? = urgent { episodeListNow(url) }

    private fun episodeListNow(url: String): List<Episode>? {
        lastSeriesDetail = ""
        val trace = ArrayList<String>()
        fun done(l: List<Episode>?, why: String? = null): List<Episode>? {
            why?.let { trace.add(it) }
            if (l.isNullOrEmpty()) lastSeriesDetail = trace.joinToString(" | ").take(500)
            return l
        }
        val ref = StalkerLogic.seriesRef(url) ?: return done(null, "bad series address")
        val src = sourceOf(ref.key) ?: return done(null, "portal login not found")
        // Own series area first ("series"), the video club ("vod") as the second try – portals file series differently
        val types = if (ref.mode == "v") listOf("vod", "series") else listOf("series", "vod")
        for (attempt in 0 until 2) {
            val s = runCatching { login(src, force = attempt > 0) }.getOrNull()
                ?: return done(null, "login failed ($note)")
            var rejected = false
            for (type in types) {
                val l = episodesOf(s, src, ref, type, trace)
                if (!l.isNullOrEmpty()) return l
                if (note.contains("503") || note.contains("429")) return done(null, note)
                if (note == "Authorization failed") { rejected = true; break }
            }
            if (note.contains("503") || note.contains("429")) return done(null, note)
            // Session no longer accepted (e.g. taken over by another box): log in again once
            if (!rejected) break
        }
        return done(null)
    }

    /** Episodes of [ref] read as [type] ("series"/"vod"). null = no answer; [trace] gets a masked summary. */
    private fun episodesOf(s: Session, src: StalkerLogic.Source, ref: StalkerLogic.SeriesRef, type: String, trace: MutableList<String>): List<Episode>? {
        val seen = HashSet<Long>()
        val out = ArrayList<Episode>()

        /** [title] = the portal's own episode title (T-029); blank or null = "Episode N". */
        fun add(season: Int, num: Int, cmd: String, series: String?, seasonId: String = "", episodeId: String = "", title: String? = null) {
            // Without a command an episode can still be played via its file (needs season and episode ID)
            if (cmd.isBlank() && episodeId.isEmpty()) return
            if (!seen.add((season.toLong() shl 32) or num.toLong())) return
            // Single episode of a season: keep where it sits, so its file can be looked up at play time if needed
            val u = if (series == null && episodeId.isNotEmpty())
                StalkerLogic.itemUrl(src.key, "efile", StalkerLink.Play.episode(cmd, type, ref.id, seasonId, episodeId))
            else StalkerLogic.itemUrl(src.key, "movie", cmd, series)
            out.add(Episode(season, num, StalkerLogic.episodeTitle(title, num) ?: EpTitles.of(num), u))
        }

        /**
         * T-029 (after the maintainer's previous app): episode objects that a season row carries itself, in "series",
         * "episodes", "data" or "list", as a list or as a dictionary ({"1": {...}, "2": {...}}). Empty = none.
         */
        fun nestedEpisodes(o: JSONObject): List<JSONObject> {
            for (k in listOf("series", "episodes", "data", "list")) {
                val l = when (val v = o.opt(k)) {
                    is JSONArray -> (0 until v.length()).mapNotNull { v.optJSONObject(it) }
                    is JSONObject -> v.keys().asSequence().mapNotNull { v.optJSONObject(it) }.toList()
                    else -> emptyList()
                }
                if (l.isNotEmpty()) return l
            }
            return emptyList()
        }

        /** Dictionary answer {info, seasons, episodes: {"1": [...], …}}; true = episodes found (T-029: also from get_ordered_list). */
        fun readDict(o: JSONObject): Boolean {
            val epsMap = o.optJSONObject("episodes") ?: return false
            val before = out.size
            for (sNumStr in epsMap.keys()) {
                val sNum = sNumStr.toIntOrNull() ?: 1
                val epArr = epsMap.optJSONArray(sNumStr) ?: continue
                for (j in 0 until epArr.length()) {
                    val epObj = epArr.optJSONObject(j) ?: continue
                    val epNum = epObj.optInt("episode_num", j + 1)
                    val cmd = epObj.optString("cmd").trim().takeIf { it != "null" }.orEmpty()
                    // As before: without a command, the series ID plus episode number is sent to create_link
                    add(sNum, epNum, cmd.ifEmpty { ref.id }, epNum.toString(), title = epObj.optString("title"))
                }
            }
            return out.size > before
        }

        /** Entries of a season: episode numbers or single episodes. true = something found. */
        fun fill(o: JSONObject, season: Int, fallbackNum: Int, seasonId: String = ""): Boolean {
            val cmd = o.optString("cmd").trim().takeIf { it != "null" }.orEmpty()
            val nums = o.optJSONArray("series")
            if (nums != null && nums.length() > 0 && cmd.isNotEmpty()) {
                var any = false
                for (i in 0 until nums.length()) {
                    val n = nums.opt(i)?.toString()?.trim()?.toIntOrNull() ?: continue
                    add(season, n, cmd, n.toString()); any = true
                }
                if (any) return true
            }
            val sStr = o.optString("series").trim()
            if (sStr.isNotEmpty() && sStr != "null" && sStr != "[]" && cmd.isNotEmpty()) {
                val parts = sStr.removePrefix("[").removeSuffix("]").split(',')
                var any = false
                for (p in parts) {
                    val n = p.trim().trim('"', '\'').toIntOrNull() ?: continue
                    add(season, n, cmd, n.toString()); any = true
                }
                if (any) return true
            }
            // T-029: episode objects inside the season row (own cmd / id / title); asking for the season separately is
            // then not needed
            val nested = nestedEpisodes(o)
            if (nested.isNotEmpty()) {
                val sid = seasonId.ifEmpty { o.opt("id")?.toString()?.trim().orEmpty() }
                val before = out.size
                for ((i, e) in nested.withIndex()) {
                    val f = fields(e)
                    val num = StalkerLogic.episodeNumber(f, i + 1)
                    val title = f["title"] ?: f["name"]
                    val ecmd = e.optString("cmd").trim().takeIf { it != "null" }.orEmpty()
                    val eid = e.opt("id")?.toString()?.trim().orEmpty()
                    when {
                        ecmd.isNotEmpty() -> add(season, num, ecmd, null, title = title)
                        cmd.isNotEmpty() -> add(season, num, cmd, num.toString(), title = title)
                        eid.isNotEmpty() && sid.isNotEmpty() -> add(season, num, "", null, sid, eid, title)
                    }
                }
                if (out.size > before) return true
            }
            if (isSeason(o)) return false
            val eid = if (seasonId.isNotEmpty()) o.opt("id")?.toString()?.trim().orEmpty() else ""
            if (cmd.isNotEmpty() || eid.isNotEmpty()) {
                val before = out.size
                val f = fields(o)
                add(season, StalkerLogic.episodeNumber(f, fallbackNum), cmd, null, seasonId, eid, f["title"] ?: f["name"])
                return out.size > before
            }
            return false
        }

        val base = "&movie_id=${enc(ref.id)}&category=${enc(ref.cat)}&fav=0&sortby=added&hd=0&not_ended=0"
        val level1 = ArrayList<JSONObject>()
        var ok = paged(s, type, "$base&season_id=0&episode_id=0", 600, {}) { level1.add(it) }

        // Fallback 1: Direct clean movie_id query (portalApi.ts line 1365)
        if (!ok || level1.isEmpty()) {
            val cleanExtra = "&movie_id=${enc(ref.id)}"
            val o = js(src, StalkerLogic.api(s.endpoint, type, "get_ordered_list", cleanExtra), s.token)
            // T-029: some portals answer here already with the dictionary layout (episodes per season)
            if (o is JSONObject && readDict(o)) {
                trace.add("$type: ${out.size} episodes (dictionary)")
                out.sortWith(compareBy({ it.season }, { it.num }))
                return out
            }
            val arr = (o as? JSONObject)?.optJSONArray("data") ?: (o as? JSONArray)
            if (arr != null && arr.length() > 0) {
                ok = true
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { level1.add(it) }
                }
            }
        }

        // Fallback 2: get_series_info dictionary handling (portalApi.ts line 1375)
        if (!ok && (note.contains("503") || note.contains("429"))) return null
        if (!ok || level1.isEmpty()) {
            val infoExtra = "&series_id=${enc(ref.id)}"
            val o = js(src, StalkerLogic.api(s.endpoint, type, "get_series_info", infoExtra), s.token)
            if (o is JSONObject && readDict(o)) {
                out.sortWith(compareBy({ it.season }, { it.num }))
                return out
            }
        }

        trace.add("$type: " + if (!ok) "no answer ($note)" else "${level1.size} entries" + (level1.firstOrNull()?.let { " e.g. " + StalkerLink.mask(shape(it), src.mac, s.token) } ?: ""))
        if (!ok) return null

        // Cache series metadata from season rows (portalApi.ts: MAG returns credits on season rows)
        for (o in level1) {
            val f = fields(o)
            val desc = f["description"] ?: f["plot"] ?: f["storyline"] ?: f["desc"] ?: f["info"]
            if (!desc.isNullOrBlank() || !f["actors"].isNullOrBlank() || !f["director"].isNullOrBlank()) {
                val sm = toStalkerMeta(src, f, true, Item("stalker:${ref.key}:${ref.id}", Kind.SERIES, "", null, "", null, ""))
                StalkerMetaCache.put(ref.id, null, null, sm)
                break
            }
        }
        // T-029: the season level already lists the episodes themselves (is_episode, own id and season_id, no cmd).
        // Read them as episodes; otherwise every episode counted as a season and its id was asked for as a season ID.
        val rows = level1.map { fields(it) }
        if (rows.isNotEmpty() && rows.all { StalkerLogic.isEpisodeRow(it) }) {
            val seasons = StalkerLogic.seasonsById(rows.map { it["season_id"].orEmpty() })
            for ((i, f) in rows.withIndex()) {
                val sid = f["season_id"]?.trim().orEmpty()
                val eid = f["id"]?.trim().orEmpty()
                val cmd = f["cmd"]?.trim()?.takeIf { it != "null" }.orEmpty()
                add(StalkerLogic.episodeRowSeason(f, seasons[sid] ?: 1), StalkerLogic.episodeNumber(f, i + 1), cmd, null, sid, eid, f["name"])
            }
            trace.add("$type: ${out.size} episodes listed directly")
            out.sortWith(compareBy({ it.season }, { it.num }))
            return out
        }
        var idx = 0
        for (o in level1) {
            idx++
            val f = fields(o)
            val season = if (level1.size == 1 && !isSeason(o)) 1 else StalkerLogic.seasonNumber(f, idx)
            if (fill(o, season, idx)) continue
            // Season without own episodes: fetch its episodes individually
            val sid = o.opt("id")?.toString()?.trim().orEmpty()
            if (sid.isEmpty()) continue
            var k = 0
            var first: JSONObject? = null
            paged(s, type, "$base&season_id=${enc(sid)}&episode_id=0", 1000, {}) { e ->
                k++
                if (first == null) first = e
                fill(e, season, k, sid)
            }
            if (idx == 1) trace.add("season 1: $k entries" + (first?.let { " e.g. " + StalkerLink.mask(shape(it), src.mac, s.token) } ?: " ($note)"))
        }
        out.sortWith(compareBy({ it.season }, { it.num }))
        return out
    }

    /** Short form of a portal entry for [lastSeriesDetail]: the fields that decide how it is read. */
    private fun shape(o: JSONObject): String {
        val keys = listOf("id", "name", "cmd", "is_season", "is_episode", "is_file", "is_series", "has_files", "series", "season_id", "series_number")
        return keys.filter { o.has(it) }.joinToString(",", "{", "}") { k -> "$k=" + (o.opt(k)?.toString() ?: "").take(40) }
    }

    /** Why the episodes of a series could not be loaded (masked like [lastDetail]). Empty = nothing. */
    @Volatile var lastSeriesDetail = ""

    private fun isSeason(o: JSONObject): Boolean {
        val v = o.opt("is_season")
        if (v == true || v == 1 || v == "1" || v == "true") return true
        val nm = o.optString("name")
        return o.optJSONArray("series")?.length() == 0 && Regex("""(?i)(season|staffel|sezon|saison|temporada|stagione)""").containsMatchIn(nm)
    }

    object StalkerMetaCache {
        val byId = ConcurrentHashMap<String, Meta>()
        val byCmd = ConcurrentHashMap<String, Meta>()
        val byNormTitle = ConcurrentHashMap<String, Meta>()

        fun put(id: String?, cmd: String?, normTitle: String?, meta: Meta) {
            if (meta.overview.isNullOrBlank() && meta.actors.isEmpty() && meta.director.isNullOrBlank()) return
            if (!id.isNullOrBlank()) byId[id] = meta
            if (!cmd.isNullOrBlank()) byCmd[cmd] = meta
            if (!normTitle.isNullOrBlank()) byNormTitle[normTitle] = meta
        }

        fun get(id: String?, cmd: String?, normTitle: String?): Meta? {
            if (!id.isNullOrBlank()) {
                val m = byId[id]
                if (m != null) return m
            }
            if (!cmd.isNullOrBlank()) {
                val m = byCmd[cmd]
                if (m != null) return m
            }
            if (!normTitle.isNullOrBlank()) {
                val m = byNormTitle[normTitle]
                if (m != null) return m
            }
            return null
        }
    }

    fun extractStreamId(cmd: String): String? {
        val trimmed = cmd.trim()
        if (trimmed.all(Char::isDigit) && trimmed.isNotEmpty()) return trimmed
        if (trimmed.startsWith("ey")) {
            runCatching {
                val decoded = String(android.util.Base64.decode(trimmed, android.util.Base64.DEFAULT))
                val json = JSONObject(decoded)
                val sid = json.optString("stream_id").takeIf { it.isNotEmpty() }
                    ?: json.optString("id").takeIf { it.isNotEmpty() }
                    ?: json.optString("movie_id").takeIf { it.isNotEmpty() }
                if (sid != null) return sid
            }
        }
        val match = Regex("""/media/(?:file_)?(\d+)""").find(trimmed)
        if (match != null && match.groupValues.size > 1) {
            return match.groupValues[1]
        }
        return null
    }

    fun cleanSearchQuery(title: String): String {
        var t = MatchLogic.searchTitle(title)
        t = Regex("""\s*[\(\[].*""").replace(t, "")
        t = t.replace(Regex("""[^\p{L}\p{Nd}\s]"""), " ").trim()
        t = Regex("""\s+""").replace(t, " ")
        return t.ifBlank { MatchLogic.searchTitle(title).trim() }
    }

    private fun findMatchingObject(
        arr: JSONArray,
        targetId: String?,
        targetCmd: String?,
        rawTitle: String,
        cleanQ: String
    ): JSONObject? {
        if (arr.length() == 0) return null
        val objs = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }

        // 1. Exact ID match (highest priority)
        if (!targetId.isNullOrBlank()) {
            objs.firstOrNull { obj ->
                val f = fields(obj)
                val fid = f["id"] ?: f["movie_id"] ?: f["series_id"] ?: f["stream_id"]
                fid == targetId || fid?.startsWith("$targetId:") == true || fid?.startsWith("${targetId}_") == true ||
                extractStreamId(f["cmd"].orEmpty()) == targetId
            }?.let { return it }
        }

        // 2. Exact cmd match
        if (!targetCmd.isNullOrBlank()) {
            objs.firstOrNull { obj ->
                val f = fields(obj)
                f["cmd"] == targetCmd
            }?.let { return it }
        }

        // 3. Exact title match using MatchLogic.sameTitle
        objs.firstOrNull { obj ->
            val f = fields(obj)
            val n = f["name"] ?: f["title"] ?: ""
            MatchLogic.sameTitle(rawTitle, n)
        }?.let { return it }

        // 4. Normalized containment
        val normClean = MatchLogic.norm(cleanQ)
        if (normClean.isNotEmpty()) {
            objs.firstOrNull { obj ->
                val f = fields(obj)
                val n = f["name"] ?: f["title"] ?: ""
                val normN = MatchLogic.norm(n)
                normN == normClean || normN.startsWith(normClean) || normN.contains(normClean) || normClean.contains(normN)
            }?.let { return it }
        }

        // 5. If small result set, pick the first one with a valid description
        if (objs.size <= 3) {
            objs.firstOrNull { obj ->
                val f = fields(obj)
                listOf("description", "plot", "descr", "desc", "storyline", "info").any { !f[it].isNullOrBlank() }
            }?.let { return it }
        }

        return objs.firstOrNull()
    }

    /**
     * Fetches metadata (description, rating, year, genres, actors, director) directly from the Stalker portal.
     * Takes patterns and field names from portalApi.ts.
     */
    fun fetchInfo(item: Item): Meta? {
        val seriesLazyUrl = StalkerLogic.lazyUrls(item).firstOrNull()?.first
        val url = when {
            item.url.startsWith("stalker://") -> item.url
            seriesLazyUrl != null -> seriesLazyUrl
            item.episodes.firstOrNull()?.url?.startsWith("stalker://") == true -> item.episodes.first().url
            else -> return null
        }
        return runCatching {
            android.util.Log.i("StalkerMeta", "fetchInfo start: title='${item.title}', kind=${item.kind}, url=$url")
            val cleanQ = cleanSearchQuery(item.title)
            val normClean = MatchLogic.norm(cleanQ)

            if (item.kind == Kind.SERIES) {
                val ref = StalkerLogic.seriesRef(url) ?: return null
                val src = sourceOf(ref.key) ?: return null

                // 1. Fast memory cache
                StalkerMetaCache.get(ref.id, null, normClean)?.let { return it }

                val s = login(src)
                val type = if (ref.mode == "v") "vod" else "series"
                var matchObj: JSONObject? = null

                // Strategy A: Directly query series seasons/info by movie_id (standard MAG approach, lines 1363-1450 in portalApi.ts)
                if (ref.id.isNotBlank()) {
                    val extra = "&movie_id=${enc(ref.id)}"
                    val o = js(src, StalkerLogic.api(s.endpoint, type, "get_ordered_list", extra), s.token)
                    val arr = (o as? JSONObject)?.optJSONArray("data") ?: (o as? JSONArray)
                    if (arr != null && arr.length() > 0) {
                        for (i in 0 until arr.length()) {
                            val obj = arr.optJSONObject(i) ?: continue
                            val f = fields(obj)
                            val desc = f["description"] ?: f["plot"] ?: f["storyline"] ?: f["desc"] ?: f["info"]
                            if (!desc.isNullOrBlank() || !f["actors"].isNullOrBlank() || !f["director"].isNullOrBlank()) {
                                matchObj = obj
                                break
                            }
                        }
                        if (matchObj == null) {
                            matchObj = arr.optJSONObject(0)
                        }
                    }
                    if (matchObj == null) {
                        val infoExtra = "&series_id=${enc(ref.id)}"
                        val infoO = js(src, StalkerLogic.api(s.endpoint, type, "get_series_info", infoExtra), s.token)
                        if (infoO is JSONObject) {
                            matchObj = infoO.optJSONObject("info") ?: infoO
                        }
                    }
                }

                // Strategy B: Search within category if valid
                if (matchObj == null && cleanQ.isNotEmpty() && ref.cat.isNotBlank() && ref.cat != "*") {
                    val extra = "&category=${enc(ref.cat)}&search=${enc(cleanQ)}&p=1"
                    val o = js(src, StalkerLogic.api(s.endpoint, type, "get_ordered_list", extra), s.token) as? JSONObject
                    val arr = o?.optJSONArray("data")
                    if (arr != null && arr.length() > 0) {
                        matchObj = findMatchingObject(arr, ref.id, null, item.title, cleanQ)
                    }
                }

                // Strategy C: Search globally
                if (matchObj == null && cleanQ.isNotEmpty()) {
                    val extra = "&search=${enc(cleanQ)}&p=1"
                    val o = js(src, StalkerLogic.api(s.endpoint, type, "get_ordered_list", extra), s.token) as? JSONObject
                    val arr = o?.optJSONArray("data")
                    if (arr != null && arr.length() > 0) {
                        matchObj = findMatchingObject(arr, ref.id, null, item.title, cleanQ)
                    }
                }

                val matched = matchObj ?: return null
                val meta = toStalkerMeta(src, fields(matched), true, item)
                StalkerMetaCache.put(ref.id, null, normClean, meta)
                meta
            } else if (item.kind == Kind.MOVIE) {
                val ref = StalkerLogic.itemRef(url) ?: return null
                val src = sourceOf(ref.key) ?: return null
                val streamId = extractStreamId(ref.cmd)

                // 1. Fast memory cache
                StalkerMetaCache.get(streamId, ref.cmd, normClean)?.let { return it }

                android.util.Log.i("StalkerMeta", "Movie streamId=$streamId, cleanQ='$cleanQ'")
                val s = login(src)
                var matchObj: JSONObject? = null

                // Strategy A: Search by clean title
                if (cleanQ.isNotEmpty()) {
                    val extra = "&search=${enc(cleanQ)}&p=1"
                    val o = js(src, StalkerLogic.api(s.endpoint, "vod", "get_ordered_list", extra), s.token) as? JSONObject
                    val arr = o?.optJSONArray("data")
                    android.util.Log.i("StalkerMeta", "Strategy A arr length=${arr?.length() ?: 0}")
                    if (arr != null && arr.length() > 0) {
                        matchObj = findMatchingObject(arr, streamId, ref.cmd, item.title, cleanQ)
                    }
                }

                // Strategy B: If streamId is known, try movie_id query
                if (matchObj == null && streamId != null) {
                    val extra = "&movie_id=${enc(streamId)}&p=1"
                    val o = js(src, StalkerLogic.api(s.endpoint, "vod", "get_ordered_list", extra), s.token) as? JSONObject
                    val arr = o?.optJSONArray("data")
                    android.util.Log.i("StalkerMeta", "Strategy B arr length=${arr?.length() ?: 0}")
                    if (arr != null && arr.length() > 0) {
                        matchObj = findMatchingObject(arr, streamId, ref.cmd, item.title, cleanQ)
                    }
                    if (matchObj == null) {
                        val infoExtra = "&movie_id=${enc(streamId)}"
                        val infoO = js(src, StalkerLogic.api(s.endpoint, "vod", "get_info", infoExtra), s.token)
                        android.util.Log.i("StalkerMeta", "Strategy B2 get_info=${infoO != null}")
                        if (infoO is JSONObject) {
                            matchObj = infoO.optJSONObject("info") ?: infoO
                        }
                    }
                }

                // Strategy C: Search by raw searchTitle or first word
                if (matchObj == null) {
                    val qRaw = MatchLogic.searchTitle(item.title).trim()
                    if (qRaw.isNotEmpty() && qRaw != cleanQ) {
                        val extra = "&search=${enc(qRaw)}&p=1"
                        val o = js(src, StalkerLogic.api(s.endpoint, "vod", "get_ordered_list", extra), s.token) as? JSONObject
                        val arr = o?.optJSONArray("data")
                        android.util.Log.i("StalkerMeta", "Strategy C arr length=${arr?.length() ?: 0}")
                        if (arr != null && arr.length() > 0) {
                            matchObj = findMatchingObject(arr, streamId, ref.cmd, item.title, qRaw)
                        }
                    }
                }

                // Strategy D: Search by first 1-2 words of title if multi-word
                if (matchObj == null) {
                    val words = cleanQ.split(' ').filter { it.isNotBlank() }
                    if (words.size > 1) {
                        val firstWord = words.take(2).joinToString(" ")
                        val extra = "&search=${enc(firstWord)}&p=1"
                        val o = js(src, StalkerLogic.api(s.endpoint, "vod", "get_ordered_list", extra), s.token) as? JSONObject
                        val arr = o?.optJSONArray("data")
                        android.util.Log.i("StalkerMeta", "Strategy D ('$firstWord') arr length=${arr?.length() ?: 0}")
                        if (arr != null && arr.length() > 0) {
                            matchObj = findMatchingObject(arr, streamId, ref.cmd, item.title, cleanQ)
                        }
                    }
                }

                val matched = matchObj ?: return null
                val meta = toStalkerMeta(src, fields(matched), false, item)
                StalkerMetaCache.put(streamId, ref.cmd, normClean, meta)
                meta
            } else null
        }.getOrNull()
    }

    private fun toStalkerMeta(src: StalkerLogic.Source, f: Map<String, String>, tv: Boolean, item: Item): Meta {
        // Matches pickDescription and pickMeta from portalApi.ts
        val plot = listOf(
            "description", "plot", "descr", "desc", "info", "storyline",
            "short_description", "comments", "o_name", "overview"
        ).firstNotNullOfOrNull { k ->
            f[k]?.trim()?.takeIf {
                it.isNotEmpty() && !it.equals("null", true) && !it.equals("undefined", true)
            }
        }
        val rating = listOf(
            "rating_imdb", "imdb_rating", "rating", "rating_kinopoisk", "kinopoisk_rating", "vote_average"
        ).firstNotNullOfOrNull { k -> f[k]?.trim()?.toDoubleOrNull()?.takeIf { it > 0 } }

        val yr = listOf(
            "year", "production_year", "release_year", "released", "release_date"
        ).firstNotNullOfOrNull { k ->
            f[k]?.trim()?.takeIf { it.length >= 4 }?.substring(0, 4)?.takeIf { it.all(Char::isDigit) }
        }
        val cover = StalkerLogic.cover(src, f) ?: item.logo
        val genres = listOf("genres_str", "genre_str", "genre", "category_name")
            .firstNotNullOfOrNull { k -> f[k]?.trim()?.takeIf { it.isNotEmpty() } }
            ?.split(',', '/', '|')?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: (if (item.group.isNotBlank()) listOf(item.group) else emptyList())

        val actors = listOf("actors", "actor", "cast")
            .firstNotNullOfOrNull { k -> f[k]?.trim()?.takeIf {
                it.isNotEmpty() && !it.equals("null", true) && !it.equals("undefined", true) && !it.equals("n/a", true) && !it.equals("n, a", true)
            } }
            ?.split(',', ';', '/', '|')?.map { it.trim() }?.filter {
                it.isNotEmpty() && !it.equals("null", true) && !it.equals("n/a", true) && !it.equals("n, a", true) && it != "-"
            }
            ?: emptyList()

        val director = listOf("director", "directors")
            .firstNotNullOfOrNull { k -> f[k]?.trim()?.takeIf {
                it.isNotEmpty() && !it.equals("null", true) && !it.equals("undefined", true) && !it.equals("n/a", true) && !it.equals("n, a", true) && it != "-"
            } }

        return Meta(
            tmdbId = 0, isTv = tv, poster = cover, backdrop = null,
            overview = plot, rating = rating, year = yr ?: item.year?.toString(),
            genres = genres, runtime = null, trailerKey = null, extended = true,
            uiLang = Lang.code(), checked = true,
            actors = actors, director = director
        )
    }
}
