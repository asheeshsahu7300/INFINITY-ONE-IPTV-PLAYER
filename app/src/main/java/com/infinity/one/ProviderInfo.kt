package com.infinity.one

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Film and series information straight from the provider (Xtream player_api.php): description, cover, backdrop, rating,
 * genres, running time and trailer. Works without any API key. Only used when TMDB is not set up or does not know the title.
 */
object ProviderInfo {
    private val client = Net.base
    private const val DAY = 24L * 3600 * 1000

    private val epCache = ConcurrentHashMap<String, Map<Int, List<EpMeta>>>()

    fun getEpisodes(seriesId: String, season: Int): List<EpMeta>? =
        epCache[seriesId]?.get(season)

    fun putEpisodes(seriesId: String, map: Map<Int, List<EpMeta>>) {
        epCache[seriesId] = map
    }

    fun cached(i: Item): Meta? {
        val seriesLazyUrl = StalkerLogic.lazyUrls(i).firstOrNull()?.first
        val u = when {
            i.url.startsWith("stalker://") -> i.url
            seriesLazyUrl != null -> seriesLazyUrl
            i.episodes.firstOrNull()?.url?.startsWith("stalker://") == true -> i.episodes.first().url
            else -> null
        } ?: return null
        val ref = StalkerLogic.seriesRef(u)
        val itemRef = StalkerLogic.itemRef(u)
        val streamId = itemRef?.let { StalkerApi.extractStreamId(it.cmd) }
        val normClean = MatchLogic.norm(StalkerApi.cleanSearchQuery(i.title))
        val srcKey = ref?.key ?: itemRef?.key
        return StalkerApi.StalkerMetaCache.get(ref?.id ?: streamId, itemRef?.cmd, normClean, srcKey)
    }

    private fun get(url: String): okhttp3.Response? = try {
        client.newCall(Request.Builder().url(url).build()).execute()
    } catch (e: Exception) {
        null
    }

    private fun toMeta(i: ProviderLogic.Info, tv: Boolean, item: Item): Meta = Meta(
        tmdbId = 0, isTv = tv,
        poster = i.cover ?: item.logo,
        backdrop = i.backdrop,
        overview = i.plot,
        rating = i.rating,
        year = i.year ?: item.year?.toString(),
        genres = i.genres.ifEmpty { if (item.group.isNotBlank()) listOf(item.group) else emptyList() },
        runtime = i.runtime,
        trailerKey = i.trailer,
        extended = true,
        uiLang = Lang.code(),
        checked = true,
        actors = i.actors,
        director = i.director
    )

    /** Info about a title from the provider; null = unreachable or nothing available. */
    suspend fun fetch(ctx: Context, i: Item): Meta? = withContext(Dispatchers.IO) {
        try {
            val isStalker = i.url.startsWith("stalker://") ||
                StalkerLogic.isLazy(i) ||
                i.episodes.any { it.url.startsWith("stalker://") }
            if (isStalker) {
                if (StalkerLogic.isLazy(i)) return@withContext null
                StalkerApi.fetchInfo(i)
            } else {
                when (i.kind) {
                    Kind.MOVIE -> movie(i)
                    Kind.SERIES -> series(ctx, i)
                    else -> null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun movie(i: Item): Meta? {
        val (acct, id) = ProviderLogic.movieRef(i.url) ?: ProviderLogic.movieRefWithPlaylist(i) ?: return null
        val body = get(ProviderLogic.apiUrl(acct, "get_vod_info", "&vod_id=$id"))?.use { r ->
            if (r.isSuccessful) r.body?.string() else null
        } ?: return null
        val info = JSONObject(body).optJSONObject("info") ?: return null
        val f = HashMap<String, String>()
        for (k in info.keys()) {
            val v = info.opt(k)
            if (v is String || v is Number) f[k] = v.toString()
        }
        val back = info.optJSONArray("backdrop_path")?.let { a -> (0 until a.length()).map { a.optString(it) }.firstOrNull { it.startsWith("http") } }
            ?: info.optString("backdrop_path").takeIf { it.startsWith("http") }
            ?: info.optString("backdrop").takeIf { it.startsWith("http") }
            ?: info.optString("fanart").takeIf { it.startsWith("http") }
        val r = ProviderLogic.infoOf(f, back, listOf("cover", "cover_big", "movie_image", "stream_icon", "poster", "screenshot_uri", "logo", "pic"))
        return if (r.isEmpty()) null else toMeta(r, false, i)
    }

    private fun series(ctx: Context, i: Item): Meta? {
        val acct = ProviderLogic.seriesAcct(i) ?: ProviderLogic.accountFromPlaylists(i) ?: return null

        // Try direct get_series_info first (much faster and returns full series details + episode stills)
        val sid = XtreamLogic.parseLazy(i.url)?.second
            ?: Regex("""/series/[^/]+/[^/]+/(\d+)""").find(i.episodes.firstOrNull()?.url ?: i.url)?.groupValues?.get(1)
            ?: i.id.takeIf { it.all(Char::isDigit) }
        if (sid != null) {
            val body = get(ProviderLogic.apiUrl(acct, "get_series_info", "&series_id=$sid"))?.use { r ->
                if (r.isSuccessful) r.body?.string() else null
            }
            if (body != null) {
                try {
                    val root = JSONObject(body)
                    val sInfo = root.optJSONObject("info")
                    if (sInfo != null) {
                        val f = HashMap<String, String>()
                        for (k in sInfo.keys()) {
                            val v = sInfo.opt(k)
                            if (v is String || v is Number) f[k] = v.toString()
                        }
                        val back = sInfo.optJSONArray("backdrop_path")?.let { a ->
                            (0 until a.length()).map { a.optString(it) }.firstOrNull { it.startsWith("http") }
                        } ?: sInfo.optString("backdrop_path").takeIf { it.startsWith("http") }
                            ?: sInfo.optString("backdrop").takeIf { it.startsWith("http") }
                            ?: sInfo.optString("fanart").takeIf { it.startsWith("http") }
                        val r = ProviderLogic.infoOf(f, back, listOf("cover", "cover_big", "movie_image", "stream_icon", "poster", "screenshot_uri", "logo"))

                        // Cache episode stills and info from "episodes" object if present
                        val epsObj = root.optJSONObject("episodes")
                        if (epsObj != null) {
                            val sMap = HashMap<Int, MutableList<EpMeta>>()
                            for (sKey in epsObj.keys()) {
                                val sNum = sKey.trim().toIntOrNull() ?: continue
                                val epArr = epsObj.optJSONArray(sKey) ?: continue
                                val list = sMap.getOrPut(sNum) { ArrayList() }
                                for (eIdx in 0 until epArr.length()) {
                                    val epJson = epArr.optJSONObject(eIdx) ?: continue
                                    val epInfo = epJson.optJSONObject("info")
                                    val epNum = epJson.optInt("episode_num", eIdx + 1)
                                    val epTitle = epJson.optString("title").ifBlank { epInfo?.optString("name") }
                                    val still = epInfo?.optString("movie_image")?.takeIf { it.startsWith("http") }
                                        ?: epInfo?.optString("screenshot_uri")?.takeIf { it.startsWith("http") }
                                        ?: epJson.optString("movie_image").takeIf { it.startsWith("http") }
                                    val epPlot = epInfo?.optString("plot")?.ifBlank { null }
                                    val epRating = epInfo?.optString("rating")?.toDoubleOrNull()
                                    val epDur = epInfo?.optInt("duration_secs")?.let { if (it > 0) it / 60 else null }
                                    list.add(EpMeta(epNum, epTitle, epPlot, still, epRating, null, epDur, null))
                                }
                            }
                            if (sMap.isNotEmpty()) {
                                putEpisodes(i.id, sMap)
                            }
                        }

                        if (!r.isEmpty()) return toMeta(r, true, i)
                    }
                } catch (e: Exception) {
                }
            }
        }

        // Fallback: search in get_series cache
        val file = File(ctx.cacheDir, "prov_series_" + (acct.host + acct.user).hashCode() + ".json")
        if (!file.exists() || System.currentTimeMillis() - file.lastModified() > DAY) {
            val tmp = File(file.path + ".tmp")
            val ok = get(ProviderLogic.apiUrl(acct, "get_series"))?.use { r ->
                val b = r.body
                if (!r.isSuccessful || b == null) false
                else { tmp.outputStream().use { o -> b.byteStream().copyTo(o) }; true }
            } ?: false
            if (ok) { file.delete(); tmp.renameTo(file) } else { tmp.delete(); if (!file.exists()) return null }
        }
        val info = findSeries(file, i.title) ?: return null
        return if (info.isEmpty()) null else toMeta(info, true, i)
    }

    /** Reads the (large) series list entry by entry without loading it fully into memory. */
    private fun findSeries(file: File, title: String): ProviderLogic.Info? {
        JsonReader(file.bufferedReader()).use { r ->
            r.beginArray()
            while (r.hasNext()) {
                val f = HashMap<String, String>()
                var back: String? = null
                r.beginObject()
                while (r.hasNext()) {
                    val n = r.nextName()
                    when (r.peek()) {
                        JsonToken.STRING, JsonToken.NUMBER -> f[n] = r.nextString()
                        JsonToken.BEGIN_ARRAY -> {
                            if (n == "backdrop_path") {
                                r.beginArray()
                                while (r.hasNext()) {
                                    if (r.peek() == JsonToken.STRING) { val v = r.nextString(); if (back == null && v.startsWith("http")) back = v }
                                    else r.skipValue()
                                }
                                r.endArray()
                            } else r.skipValue()
                        }
                        else -> r.skipValue()
                    }
                }
                r.endObject()
                if (ProviderLogic.sameName(title, f["name"])) return ProviderLogic.infoOf(f, back, listOf("cover", "cover_big", "stream_icon", "poster", "screenshot_uri"))
            }
        }
        return null
    }
}
