package com.bilipai.desktop.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlaybackStreamHeadersTest {
    @Test fun explicitHeadersSurviveAnImmutableNativeOwnershipSnapshotAndCaseReplacement() {
        val headers = linkedMapOf("X-Plugin-Key" to "first-value", "x-plugin-key" to "second-value", "Cookie" to "plugin=fixture")
        MpvPlayer().use { player ->
            val owner = player.loadVersioned(PlaybackSource("https://fixture.invalid/video?sign=not-public", streamHeaders = headers))
            headers.clear()
            val snapshot = assertNotNull(player.currentSourceSnapshot())
            assertEquals(owner, snapshot.sourceVersion)
            assertEquals(mapOf("x-plugin-key" to "second-value", "Cookie" to "plugin=fixture"), snapshot.source.streamHeaders)
            assertFailsWith<UnsupportedOperationException> { (snapshot.source.streamHeaders as MutableMap<String, String>).clear() }
            val text = snapshot.source.toString() + snapshot.toString()
            listOf("fixture.invalid", "not-public", "second-value", "plugin=fixture").forEach { assertFalse(it in text) }
        }
    }

    @Test fun anInvalidMutatedReplacementCannotAcquireOrAlterAnExistingOwner() {
        MpvPlayer().use { player ->
            val owner = player.loadVersioned(PlaybackSource("file:///C:/first.avi", title = "first"))
            val mutableHeaders = linkedMapOf("X-Plugin-Key" to "valid")
            val malformed = PlaybackSource("file:///C:/replacement.avi", title = "replacement", streamHeaders = mutableHeaders)
            mutableHeaders["X-Plugin-Key"] = "bad\r\nCookie: injected"
            assertFailsWith<IllegalArgumentException> { player.loadVersioned(malformed) }
            assertEquals(owner, player.currentSourceVersion)
            assertEquals("first", player.state.value.sourceTitle)
            assertFailsWith<IllegalArgumentException> { player.recoverSource(owner, replacement = malformed, forceSoftwareDecoding = true) }
            assertEquals(owner, player.currentSourceVersion)
            assertEquals("first", player.state.value.sourceTitle)
            assertFalse(player.state.value.softwareDecodingRequested)
            assertTrue(player.stopIfSourceVersion(owner))
        }
    }

    @Test fun aRecoveryAndNewSourceKeepOnlyTheirOwnExplicitHeaderMaps() {
        MpvPlayer().use { player ->
            val first = player.loadVersioned(PlaybackSource("https://fixture.invalid/first", streamHeaders = mapOf("Cookie" to "first=fixture")))
            assertTrue(player.recoverSource(first, replacement = PlaybackSource("https://fixture.invalid/backup", streamHeaders = mapOf("X-Plugin-Key" to "backup-key")), positionSeconds = 3.5, paused = true))
            assertEquals(first, player.currentSourceVersion)
            assertEquals(mapOf("X-Plugin-Key" to "backup-key"), assertNotNull(player.currentSourceSnapshot()).source.streamHeaders)
            player.replay()
            assertEquals(first, player.currentSourceVersion)
            assertEquals(mapOf("X-Plugin-Key" to "backup-key"), assertNotNull(player.currentSourceSnapshot()).source.streamHeaders)
            val next = player.loadVersioned(PlaybackSource("file:///C:/new.avi"))
            assertTrue(next > first)
            assertTrue(assertNotNull(player.currentSourceSnapshot()).source.streamHeaders.isEmpty())
            assertFalse(player.recoverSource(first))
        }
    }

    @Test fun explicitCookieAndRequestMetadataOverrideTheLegacyJarWithoutMergingIt() {
        val source = PlaybackSource("https://fixture.invalid/video", cookieHeader = "SESSDATA=account-fixture",
            referer = "https://www.bilibili.com/", userAgent = "old-UA", streamHeaders = linkedMapOf(
                "cOoKiE" to "plugin=explicit-fixture", "Referer" to "https://plugin.invalid/source", "user-agent" to "Plugin-Fixture-UA",
                "Authorization" to "Bearer fixture-token", "X-Route" to "a,b\\c"))
        val options = source.mpvFileOptions()
        assertEquals("", options["referrer"])
        assertEquals("", options["user-agent"])
        val fields = assertNotNull(options["http-header-fields"])
        assertFalse("account-fixture" in fields)
        assertTrue("cOoKiE: plugin=explicit-fixture" in fields)
        assertTrue("Referer: https://plugin.invalid/source" in fields)
        assertTrue("user-agent: Plugin-Fixture-UA" in fields)
        assertTrue("Authorization: Bearer fixture-token" in fields)
        assertTrue("X-Route: a\\,b\\c" in fields)
        assertFalse("account-fixture" in source.copy(streamHeaders = mapOf("Cookie" to "")).mpvFileOptions()["http-header-fields"].orEmpty())
    }

    @Test fun loadingAnEmptyHeaderSourceExplicitlyClearsNativeHeaderFields() {
        val source = PlaybackSource("https://fixture.invalid/video", referer = "", userAgent = "fixture-UA", cookieHeader = "")
        assertEquals("", source.mpvFileOptions()["http-header-fields"])
        assertEquals("", source.mpvFileOptions()["referrer"])
        assertEquals("Cookie: legacy=fixture", source.copy(cookieHeader = "legacy=fixture").mpvFileOptions()["http-header-fields"])
    }

    @Test fun headerInjectionAndResourceLimitsFailBeforeCredentialsReachNativeCode() {
        listOf("Name:evil", " Name", "Name\r\nCookie", "名", "").forEach { name ->
            assertFailsWith<IllegalArgumentException> { PlaybackSource("file:///C:/fixture.avi", streamHeaders = mapOf(name to "value")) }
        }
        listOf("line\nnext", "line\rnext", "nul\u0000", "del\u007f", "control\u001f").forEach { value ->
            assertFailsWith<IllegalArgumentException> { PlaybackSource("file:///C:/fixture.avi", streamHeaders = mapOf("X-Fixture" to value)) }
        }
        assertFailsWith<IllegalArgumentException> { copyPlaybackStreamHeaders((0..64).associate { "X-Fixture-$it" to "value" }) }
        assertFailsWith<IllegalArgumentException> { copyPlaybackStreamHeaders(mapOf("X-Fixture" to "界".repeat(3000))) }
        assertFailsWith<IllegalArgumentException> { copyPlaybackStreamHeaders((0..4).associate { "X-Fixture-$it" to "x".repeat(8192) }) }
    }

    @Test fun arbitraryHeaderEchoesAndBareValuesAreRedactedWhileTypedHttpStatusSurvives() {
        val diagnostics = PlayerDiagnostics()
        diagnostics.reset(PlaybackSource("https://fixture.invalid/video", cookieHeader = "SESSDATA=account-fixture",
            streamHeaders = mapOf("X-Custom-API-Key" to "custom-fixture-key", "Authorization" to "Bearer fixture-token", "Cookie" to "plugin=explicit-fixture")))
        diagnostics.append("http", "HTTP error 403; X-Custom-API-Key: different-native-echo")
        diagnostics.append("native", "bare custom-fixture-key fixture-token explicit-fixture account-fixture")
        diagnostics.append("http", "x-custom-api-key = second-native-echo\nAuthorization: Basic unrecognized-credential")
        diagnostics.append("transport", "rtsp://fixture-user:fixture-password@media.example/path?access_token=signed-fixture")
        val failure = diagnostics.failure(-13, "Loading failed", 9, 12)
        assertEquals(403, failure.httpStatus)
        assertEquals(PlayerFailureKind.NETWORK, failure.kind)
        val public = failure.toString()
        listOf("custom-fixture-key", "different-native-echo", "second-native-echo", "fixture-token", "explicit-fixture", "account-fixture", "unrecognized-credential", "fixture-user", "fixture-password", "signed-fixture").forEach {
            assertFalse(it in public, "Fixture credential survived redaction.")
        }
        assertTrue("media.example" in public)
    }
}
