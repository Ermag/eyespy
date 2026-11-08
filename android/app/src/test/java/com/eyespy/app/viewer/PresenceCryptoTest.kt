package com.eyespy.app.viewer

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PresenceCryptoTest {
    @Test
    fun encryptsWithFreshNonceAndWritesVectorForServerCheck() {
        val a = Presence.encrypt("testtoken-1234567890", "alice-phone", "away", 1794146873595)
        val b = Presence.encrypt("testtoken-1234567890", "alice-phone", "away", 1794146873595)
        assertTrue(a.startsWith("v1."))
        assertNotEquals("nonce must be random", a, b)
        assertTrue("plaintext must not leak", !a.contains("alice-phone") && !a.contains("away"))
        // consumed by the cross-language check against server/eyespy/relay.py
        File(System.getProperty("java.io.tmpdir"), "eyespy-presence-vector.txt").writeText(a)
    }
}
