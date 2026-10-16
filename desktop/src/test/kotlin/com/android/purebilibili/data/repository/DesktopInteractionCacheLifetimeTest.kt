package com.android.purebilibili.data.repository

import kotlin.test.*

class DesktopInteractionCacheLifetimeTest {
    @Test fun defaultKnownValueSurvivesOneMinuteAndExpiresAtThirtyMinutes() {
        var now = 0L
        val cache = VideoInteractionStatusCache(nowMs = { now })
        val version = cache.read("account", "follow:41").second
        assertTrue(cache.publish("account", "follow:41", true, version, query = true))
        now = 60_000L
        assertEquals(true, cache.read("account", "follow:41").first)
        now = 1_799_999L
        assertEquals(true, cache.read("account", "follow:41").first)
        now = 1_800_000L
        val expired = cache.read("account", "follow:41")
        assertNull(expired.first)
        assertTrue(expired.second > version)
    }

    @Test fun defaultPendingOperationExpiresBeforeTheLongKnownValueLifetime() {
        var now = 0L
        val cache = VideoInteractionStatusCache(nowMs = { now })
        val operation = cache.beginOperation("account", "follow:41")
        now = 59_999L
        assertNull(cache.queryVersion("account", "follow:41"))
        now = 60_000L
        val query = assertNotNull(cache.queryVersion("account", "follow:41"))
        assertTrue(query > operation)
        assertFalse(cache.publish("account", "follow:41", true, operation))
        assertNull(cache.read("account", "follow:41").first)
    }
}
