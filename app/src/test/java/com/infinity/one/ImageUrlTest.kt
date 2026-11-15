package com.infinity.one

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ImageUrlTest {

    private val basePortal = "http://myportal.net:8080/c/portal.php"
    private val expectedRoot = "http://myportal.net:8080"

    @Test
    fun testEmptyAndInvalidStrings() {
        assertEquals("", buildImageUrl(basePortal, null))
        assertEquals("", buildImageUrl(basePortal, ""))
        assertEquals("", buildImageUrl(basePortal, "   "))
        assertEquals("", buildImageUrl(basePortal, "null"))
        assertEquals("", buildImageUrl(basePortal, "NULL"))
        assertEquals("", buildImageUrl(basePortal, "undefined"))
        assertEquals("", buildImageUrl(basePortal, "N/A"))
        assertEquals("", buildImageUrl(basePortal, "none"))
    }

    @Test
    fun testServerRelativeUrls() {
        assertEquals(
            "$expectedRoot/stalker_portal/screenshots/123.jpg",
            buildImageUrl(basePortal, "/stalker_portal/screenshots/123.jpg")
        )
        assertEquals(
            "$expectedRoot/misc/logos/320/ch1.png",
            buildImageUrl(basePortal, "misc/logos/320/ch1.png")
        )
    }

    @Test
    fun testStaleOriginRewriting() {
        assertEquals(
            "$expectedRoot/stalker_portal/screenshots/abc.jpg",
            buildImageUrl(basePortal, "http://webhop.live/stalker_portal/screenshots/abc.jpg")
        )
        assertEquals(
            "$expectedRoot/images/logo.png",
            buildImageUrl(basePortal, "http://127.0.0.1/images/logo.png")
        )
        assertEquals(
            "$expectedRoot/corelink/cover.jpg",
            buildImageUrl(basePortal, "http://corelink.blog/corelink/cover.jpg")
        )
        assertEquals(
            "$expectedRoot/media/pic.png",
            buildImageUrl(basePortal, "http://starshare.live/media/pic.png")
        )
        // Normal external link shouldn't be rewritten
        assertEquals(
            "https://image.tmdb.org/t/p/w500/xyz.jpg",
            buildImageUrl(basePortal, "https://image.tmdb.org/t/p/w500/xyz.jpg")
        )
    }

    @Test
    fun testBase64EncodedUrl() {
        val target = "http://webhop.live/images/chan1.png"
        val b64 = Base64.getEncoder().encodeToString(target.toByteArray())
        assertEquals(
            "$expectedRoot/images/chan1.png",
            buildImageUrl(basePortal, b64)
        )
    }

    @Test
    fun testDataUri() {
        // Truncated (< 350 chars) should be dropped
        val truncated = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUg=="
        assertEquals("", buildImageUrl(basePortal, truncated))

        // Valid long data URI
        val longData = "data:image/png;base64," + "A".repeat(400)
        assertEquals(longData, buildImageUrl(basePortal, longData))
    }

    @Test
    fun testLiveboxProWebhopRewrite() {
        val liveboxBaseNoPort = "http://livebox.pro"
        assertEquals(
            "http://livebox.pro:8080/images/3faed9fb85410b54107f282fab2c5d6e.jpg",
            buildImageUrl(liveboxBaseNoPort, "http://webhop.live:8080/images/3faed9fb85410b54107f282fab2c5d6e.jpg")
        )
        assertEquals(
            "http://livebox.pro/images/test.jpg",
            buildImageUrl(liveboxBaseNoPort, "http://webhop.live/images/test.jpg")
        )

        val liveboxBaseWithPort = "http://livebox.pro:8000"
        assertEquals(
            "http://livebox.pro:8000/images/abc.jpg",
            buildImageUrl(liveboxBaseWithPort, "http://webhop.live:8080/images/abc.jpg")
        )

        val stalkerLivebox = "stalker:http://livebox.pro#mac=00:1A:79:B4:7C:52"
        assertEquals(
            "http://livebox.pro:8080/images/cover.jpg",
            buildImageUrl(stalkerLivebox, "http://webhop.live:8080/images/cover.jpg")
        )
    }

    @Test
    fun testRawBase64ImagePayloads() {
        // JPEG prefix
        val rawJpeg = "/9j/" + "A".repeat(350)
        assertEquals("data:image/jpeg;base64,$rawJpeg", buildImageUrl(basePortal, rawJpeg))

        // PNG prefix
        val rawPng = "iVBORw0KGgo" + "A".repeat(350)
        assertEquals("data:image/png;base64,$rawPng", buildImageUrl(basePortal, rawPng))

        // WebP prefix
        val rawWebp = "UklGR" + "A".repeat(350)
        assertEquals("data:image/webp;base64,$rawWebp", buildImageUrl(basePortal, rawWebp))
    }
}
