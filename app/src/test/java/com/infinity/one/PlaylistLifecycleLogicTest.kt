package com.infinity.one

import org.junit.Assert.*
import org.junit.Test

class PlaylistLifecycleLogicTest {

    @Test
    fun safeStoreRoundTrip() {
        val testData = """[{"name":"Main","url":"stalker:http://livebox.pro#mac=00:1A:79:AF:A4:CA"}]"""
        val obfuscated = SafeStore.obfuscate(testData)
        assertNotNull(obfuscated)
        assertNotEquals(testData, obfuscated)

        val restored = SafeStore.deobfuscate(obfuscated)
        assertEquals(testData, restored)
    }

    @Test
    fun safeStoreEdgeCases() {
        assertNull(SafeStore.deobfuscate(null))
        assertEquals("", SafeStore.deobfuscate(""))
        assertNull(SafeStore.deobfuscate("not_valid_base64!@#$"))

        val empty = ""
        val obfEmpty = SafeStore.obfuscate(empty)
        assertEquals("", SafeStore.deobfuscate(obfEmpty))
    }

    @Test
    fun stalkerSourceParsingAndEncoding() {
        val portal = "http://livebox.pro"
        val mac = "00:1A:79:AF:A4:CA"
        val src = StalkerLogic.source(portal, mac)
        assertNotNull(src)
        assertEquals("http://livebox.pro", src!!.portal)
        assertEquals("00:1A:79:AF:A4:CA", src.mac)

        val encoded = src.encode()
        assertEquals("stalker:http://livebox.pro#mac=00:1A:79:AF:A4:CA", encoded)

        val parsed = StalkerLogic.parse(encoded)
        assertNotNull(parsed)
        assertEquals(src.portal, parsed!!.portal)
        assertEquals(src.mac, parsed.mac)
    }

    @Test
    fun stalkerApiResetAndCancelAll() {
        // Ensure reset and cancelAll execute cleanly without exceptions
        StalkerApi.reset()
        StalkerApi.cancelAll()
        StalkerApi.reset(12345)
    }
}
