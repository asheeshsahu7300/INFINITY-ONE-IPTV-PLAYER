package com.infinity.one

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StalkerLinkTest {
    private val ep = "http://p:8080/portal.php"
    private val mac = "00:1A:79:AB:CD:EF"

    @Test fun channelsKeepOneRequest() {
        val r = StalkerLink.requests(ep, "itv", "ffrt http://localhost/ch/1_")
        assertEquals(1, r.size)
        assertTrue(r[0].contains("forced_storage=undefined"))
    }

    @Test fun moviesTrySeveralForms() {
        val r = StalkerLink.requests(ep, "vod", "eyJ0eXBlIjoibW92aWUifQ==")
        assertEquals(3, r.size)
        assertTrue(r[0].contains("forced_storage=undefined"))
        assertTrue(r[1].contains("forced_storage=&"))
        assertEquals("$ep?type=vod&action=create_link&cmd=eyJ0eXBlIjoibW92aWUifQ%3D%3D&JsHttpRequest=1-xml", r[2])
        // Episode number goes along in every form
        assertTrue(StalkerLink.requests(ep, "vod", "/media/5.mpg", "3").all { it.contains("&series=3") })
        // Bare file id: also as the video club's own form
        assertTrue(StalkerLink.requests(ep, "vod", "123").last().contains("cmd=%2Fmedia%2Ffile_123.mpg"))
    }

    @Test fun readsTheAddress() {
        assertEquals("http://s/movie/u/p/1.mkv", StalkerLink.address(ep, mac, mapOf("cmd" to "ffmpeg http://s/movie/u/p/1.mkv"), "vod"))
        assertEquals("https://s/a.mp4", StalkerLink.address(ep, mac, mapOf("url" to "https://s/a.mp4", "cmd" to ""), "vod"))
        assertEquals("http://p:8080/play/movie.php?stream=1.mkv&play_token=t",
            StalkerLink.address(ep, mac, mapOf("cmd" to "ffmpeg /play/movie.php?stream=1.mkv&play_token=t"), "vod"))
        // Only id + play token
        assertEquals("http://p:8080/play/movie.php?mac=00%3A1A%3A79%3AAB%3ACD%3AEF&stream=55.mp4&play_token=abc&type=movie",
            StalkerLink.address("http://p:8080/stalker_portal/c/", mac, mapOf("id" to "55", "play_token" to "abc", "cmd" to ""), "vod"))
    }

    @Test fun nothingPlayable() {
        // Portal only echoes the command it was sent
        assertNull(StalkerLink.address(ep, mac, mapOf("cmd" to "/media/5.mpg"), "vod", "/media/5.mpg"))
        assertNull(StalkerLink.address(ep, mac, mapOf("cmd" to "ffmpeg ", "error" to "nothing_to_play"), "vod"))
        assertNull(StalkerLink.address(ep, mac, mapOf("id" to "55", "play_token" to "abc"), "itv"))
        assertNull(StalkerLink.address(ep, mac, emptyMap(), "vod"))
    }

    @Test fun relativeAnswerUsesThePortalInstallation() {
        val lp = "http://p/stalker_portal/server/load.php"
        assertEquals("http://p/stalker_portal", StalkerLink.installation(lp))
        assertEquals("http://p:8080", StalkerLink.installation(ep))
        assertEquals("http://p/stalker_portal/media/file_9.mpg?x=1",
            StalkerLink.address(lp, mac, mapOf("cmd" to "ffmpeg /media/file_9.mpg?x=1"), "vod", "/media/file_9.mpg"))
    }

    @Test fun moviesWithOwnFilesAreLookedUpAtPlayTime() {
        val f = mapOf("id" to "77", "has_files" to "1", "cmd" to "/media/77.mpg")
        assertTrue(StalkerLogic.needsFileLookup(f, "/media/77.mpg"))
        assertTrue(!StalkerLogic.needsFileLookup(f - "has_files", "/media/77.mpg"))
        assertTrue(!StalkerLogic.needsFileLookup(f, "/media/file_5.mpg"))
        assertTrue(!StalkerLogic.needsFileLookup(f, "eyJ0eXBlIjoibW92aWUifQ=="))
        val src = StalkerLogic.source("http://p/stalker_portal/c/", mac)!!
        val entry = StalkerLogic.movieEntry(src, f + ("name" to "Film"), "Cat")!!
        val r = StalkerLogic.itemRef(entry.lines()[1])!!
        assertEquals("mfile", r.type); assertEquals("77", r.cmd)
        assertTrue(StalkerLogic.isItem(entry.lines()[1]))
    }

    @Test fun maskHidesPrivateDetails() {
        val m = StalkerLink.mask(
            """{"cmd":"ffmpeg http:\/\/real.host:8080\/movie\/bob\/secret\/1.mkv?play_token=abc&mac=00:1A:79:AB:CD:EF","token":"T0KEN123"}""",
            mac, "T0KEN123")
        for (bad in listOf("real.host", "bob", "secret", "abc", "00:1A:79", "T0KEN123")) assertTrue(m, !m.contains(bad))
        assertTrue(m, m.contains("http://SERVER/movie/USER/PASS/1.mkv"))
    }

    @Test fun movieFileFallback() {
        assertEquals("1012490", StalkerLink.movieIdOf("/media/1012490.mpg"))
        assertEquals("5", StalkerLink.movieIdOf("auto /media/5.mpg"))
        assertNull(StalkerLink.movieIdOf("/media/file_5.mpg"))
        assertNull(StalkerLink.movieIdOf("eyJ0eXBlIjoibW92aWUifQ=="))
        // File entry with its own command
        assertEquals("/media/file_88.mpg", StalkerLink.fileCmd(listOf(mapOf("id" to "88", "cmd" to "/media/file_88.mpg")), 1))
        // Only an ID, short list: the file
        assertEquals("/media/file_88.mpg", StalkerLink.fileCmd(listOf(mapOf("id" to "88")), 1))
        // Portal ignored movie_id and sent a whole category: no guessing
        assertNull(StalkerLink.fileCmd(listOf(mapOf("id" to "3", "cmd" to "/media/3.mpg")), 4200))
        assertNull(StalkerLink.fileCmd(emptyList(), 0))
        // ...also when the category's entries carry their own "/media/file_…" (another title would start)
        assertNull(StalkerLink.fileCmd(listOf(mapOf("id" to "3", "cmd" to "/media/file_3.mpg")), 4200))
        assertNull(StalkerLink.fileCmd(List(21) { mapOf("id" to "$it", "cmd" to "/media/file_$it.mpg") }, 21))
        // A short list with a file entry further down: that file
        assertEquals("/media/file_9.mpg", StalkerLink.fileCmd(listOf(mapOf("id" to "1"), mapOf("id" to "9", "cmd" to "auto /media/file_9.mpg")), 2))
    }

    @Test fun portalErrorMeansNoAddress() {
        val lp = "http://p/stalker_portal/server/load.php"
        // Another video club command with an error: not playable, so the file lookup still runs
        assertNull(StalkerLink.address(lp, mac, mapOf("cmd" to "/media/file_123.mpg", "error" to "nothing_to_play"), "vod", "/media/77.mpg"))
        // Relative path or play token with an error: no address
        assertNull(StalkerLink.address(lp, mac, mapOf("cmd" to "ffmpeg /play/movie.php?stream=1.mkv", "error" to "limit"), "vod"))
        assertNull(StalkerLink.address(ep, mac, mapOf("id" to "55", "play_token" to "abc", "cmd" to "", "error" to "nothing_to_play"), "vod"))
        // A full address still counts, as before this PR
        assertEquals("http://s/a.mkv", StalkerLink.address(ep, mac, mapOf("cmd" to "ffmpeg http://s/a.mkv", "error" to "link_fault"), "vod"))
        // Spellings of "no error"
        assertEquals("http://p/stalker_portal/play/movie.php?stream=1.mkv",
            StalkerLink.address(lp, mac, mapOf("cmd" to "ffmpeg /play/movie.php?stream=1.mkv", "error" to "0"), "vod"))
        assertNull(StalkerLink.error(mapOf("error" to "")))
        assertNull(StalkerLink.error(mapOf("error" to "null")))
        assertNull(StalkerLink.error(emptyMap()))
        assertEquals("nothing_to_play", StalkerLink.error(mapOf("error" to " nothing_to_play ")))
    }

    @Test fun onlyTheStreamLimitStopsTheForms() {
        assertTrue(StalkerLink.isFinal("limit"))
        assertTrue(StalkerLink.isFinal("LIMIT"))
        assertTrue(!StalkerLink.isFinal("nothing_to_play"))
        assertTrue(!StalkerLink.isFinal(null))
    }

    @Test fun bareVideoClubCommandIsNoAddress() {
        val lp = "http://p/stalker_portal/server/load.php"
        // Form 4 sends "/media/file_123.mpg" for the bare id "123": its echo is not an address
        assertNull(StalkerLink.address(lp, mac, mapOf("cmd" to "/media/file_123.mpg"), "vod", "123"))
        // Answer with a different video club command than the one sent
        assertNull(StalkerLink.address(lp, mac, mapOf("cmd" to "ffmpeg /media/77.mpg"), "vod", "/media/file_5.mpg"))
        // Other relative paths still count
        assertEquals("http://p/stalker_portal/play/movie.php?stream=1.mkv",
            StalkerLink.address(lp, mac, mapOf("url" to "/play/movie.php?stream=1.mkv"), "vod", "/media/77.mpg"))
    }

    @Test fun whatToAskFor() {
        // Plain movie "/media/<id>.mpg": its command first, its files as the fallback
        val m = StalkerLink.Play.of("vod", "/media/1012490.mpg", null)
        assertEquals("/media/1012490.mpg", m.cmd); assertEquals("&movie_id=1012490", m.lookup)
        // Xtream-like command: no lookup
        assertNull(StalkerLink.Play.of("vod", "eyJ0eXBlIjoibW92aWUifQ==", null).lookup)
        // Season with episode numbers: no lookup
        assertNull(StalkerLink.Play.of("vod", "/media/7.mpg", "3").lookup)
        // Known movie with files: straight to the file
        val f = StalkerLink.Play.of("mfile", "77", null)
        assertNull(f.cmd); assertEquals("&movie_id=77", f.lookup)
        // Single episode: own command, then movie/season/episode files
        val stored = StalkerLink.Play.episode("/media/9.mpg", "series", "5", "6", "9")
        val e = StalkerLink.Play.of("efile", stored, null)
        assertEquals("/media/9.mpg", e.cmd); assertEquals("series", e.lookupType)
        assertEquals("&movie_id=5&season_id=6&episode_id=9", e.lookup)
        // Survives the item address
        val r = StalkerLogic.itemRef(StalkerLogic.itemUrl(1, "efile", stored))!!
        assertEquals("efile", r.type); assertEquals(stored, r.cmd)
    }

    @Test fun cleanCmdStripsPrefixes() {
        assertEquals("http://test.com/stream.m3u8", StalkerLink.cleanCmd("ffmpeg http://test.com/stream.m3u8"))
        assertEquals("http://test.com/live.ts", StalkerLink.cleanCmd("auto http://test.com/live.ts"))
        assertEquals("/ch/123", StalkerLink.cleanCmd("ffrt12 /ch/123"))
        assertEquals("http://test.com/video.mp4", StalkerLink.cleanCmd("-i http://test.com/video.mp4"))
        assertEquals("http://test.com/video.mp4", StalkerLink.cleanCmd("vlc http://test.com/video.mp4"))
    }

    @Test fun isPlayableStreamUrlValidatesCorrectly() {
        assertTrue(StalkerLink.isPlayableStreamUrl("http://portal.com/live/stream.m3u8"))
        assertTrue(StalkerLink.isPlayableStreamUrl("https://portal.com/live/123.ts"))
        assertTrue(StalkerLink.isPlayableStreamUrl("rtsp://media.host/ch1"))
        assertTrue(!StalkerLink.isPlayableStreamUrl(null))
        assertTrue(!StalkerLink.isPlayableStreamUrl(""))
        assertTrue(!StalkerLink.isPlayableStreamUrl("http://localhost:8080/live/1"))
        assertTrue(!StalkerLink.isPlayableStreamUrl("http://127.0.0.1/live/1"))
        assertTrue(!StalkerLink.isPlayableStreamUrl("ffmpeg http://portal.com/live/1"))
        assertTrue(!StalkerLink.isPlayableStreamUrl("http://portal.com/ch/123_"))
        assertTrue(!StalkerLink.isPlayableStreamUrl("/ch/123_"))
    }

    @Test fun extractStreamIdFindsId() {
        assertEquals("456", StalkerLink.extractStreamId("stream=456&token=abc"))
        assertEquals("789", StalkerLink.extractStreamId("789"))
        assertEquals("101", StalkerLink.extractStreamId("channel/101"))
        assertEquals("202", StalkerLink.extractStreamId("/ch/202"))
        assertEquals("303", StalkerLink.extractStreamId("/media/303.mpg"))
    }

    @Test fun cleanStreamUrlRewritesHostAndFixesParams() {
        val endpoint = "http://myportal.com:8080/portal.php"
        val out1 = StalkerLink.cleanStreamUrl("http://localhost:8080/live/stream?mac=%mac%&stream=&token=t", endpoint, "00:1A:79:12:34:56", "999")
        assertEquals("http://myportal.com:8080/live/stream?mac=00:1A:79:12:34:56&stream=999&token=t", out1)

        val out2 = StalkerLink.cleanStreamUrl("http://webhop.live:8000/live/abc.ts", endpoint, "00:1A:79:12:34:56")
        assertEquals("http://myportal.com:8000/live/abc.ts", out2)

        val out3 = StalkerLink.cleanStreamUrl("/live/stream.m3u8", endpoint, "00:1A:79:12:34:56")
        assertEquals("http://myportal.com:8080/live/stream.m3u8", out3)
    }

    @Test fun extractLinkParsesVariousResponses() {
        // js as string
        assertEquals("http://server.com/live.m3u8", StalkerLink.extractLink("""{"js": "http://server.com/live.m3u8"}"""))
        // js with cmd
        assertEquals("http://server.com/vod.mp4", StalkerLink.extractLink("""{"js": {"cmd": "http://server.com/vod.mp4"}}"""))
        // js with url
        assertEquals("http://server.com/stream", StalkerLink.extractLink("""{"js": {"url": "http://server.com/stream"}}"""))
        // js with link
        assertEquals("http://server.com/link", StalkerLink.extractLink("""{"js": {"link": "http://server.com/link"}}"""))
        // js as array
        assertEquals("http://server.com/array", StalkerLink.extractLink("""{"js": ["http://server.com/array"]}"""))
        // raw URL
        assertEquals("http://server.com/raw.ts", StalkerLink.extractLink("http://server.com/raw.ts"))
    }

    @Test fun applySameHostStreamProxyBuildsFallback() {
        val endpoint = "http://portal.com:8080/portal.php"
        assertEquals("http://portal.com:8080/media/movie.mpg", StalkerLink.applySameHostStreamProxy("", endpoint, "ffmpeg /media/movie.mpg"))
        assertEquals("http://direct.com/live.m3u8", StalkerLink.applySameHostStreamProxy("", endpoint, "http://direct.com/live.m3u8"))
    }
}
