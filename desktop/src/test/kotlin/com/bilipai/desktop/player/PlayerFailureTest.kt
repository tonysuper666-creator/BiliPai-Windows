package com.bilipai.desktop.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerFailureTest {
    @Test fun signedUrlsAndHeadersAndBareCookiesNeverReachPublishedDiagnostics() {
        val diagnostics = PlayerDiagnostics()
        diagnostics.reset(PlaybackSource("https://media.example/video.m4s?sign=abc", cookieHeader = "SESSDATA=secret-session; bili_jct=secret-csrf"))
        diagnostics.append("ffmpeg", "HTTP error 403 https://user:password@media.example/video.m4s?sign=abc&token=xyz")
        diagnostics.append("http", "Cookie: SESSDATA=secret-session; bili_jct=secret-csrf")
        diagnostics.append("http", "Authorization: Bearer bearer-secret")
        diagnostics.append("demux", "bare secret-session and sign=sign-secret and ?expiry=secret-expiry&token=secret-token")
        val failure = diagnostics.failure(-13, "Loading failed", 42, 7)
        assertEquals(PlayerFailureKind.NETWORK, failure.kind)
        assertEquals(403, failure.httpStatus)
        assertEquals(42L, failure.sourceVersion)
        assertEquals(7L, failure.attemptId)
        val public = failure.toString()
        listOf("password", "secret-session", "secret-csrf", "bearer-secret", "sign-secret", "secret-expiry", "secret-token", "sign=abc", "token=xyz").forEach {
            assertFalse(it in public, "Secret survived: $it")
        }
        assertTrue("media.example" in public)
    }

    @Test fun genericMpvLoadingFailureDoesNotInventNetworkOrDecoderEvidence() {
        val diagnostics = PlayerDiagnostics()
        diagnostics.reset(PlaybackSource("https://media.example/invalid.mp4"))
        diagnostics.append("demux", "Failed to open input")
        assertEquals(PlayerFailureKind.UNKNOWN, diagnostics.failure(-13, "Loading failed", 1, 1).kind)
        diagnostics.append("vd_lavc", "Could not initialize decoder: unsupported codec")
        assertEquals(PlayerFailureKind.DECODER, diagnostics.failure(-13, "Loading failed", 1, 2).kind)
        assertEquals(PlayerFailureKind.AUDIO_OUTPUT, diagnostics.failure(-14, "audio failed", 1, 3).kind)
        assertEquals(PlayerFailureKind.VIDEO_OUTPUT, diagnostics.failure(-15, "video failed", 1, 4).kind)
    }

    @Test fun aNewAttemptClearsOldEvidenceAndKeepsBoundedLines() {
        val diagnostics = PlayerDiagnostics(capacity = 3, maximumCharacters = 100)
        diagnostics.reset(null)
        diagnostics.append("http", "Connection timed out")
        assertEquals(PlayerFailureKind.NETWORK, diagnostics.failure(-13, "Loading failed", 1, 1).kind)
        diagnostics.reset(null)
        repeat(100) { diagnostics.append("file", "No such file or directory number $it") }
        val failure = diagnostics.failure(-13, "Loading failed", 1, 2)
        assertEquals(PlayerFailureKind.FILE_IO, failure.kind)
        assertTrue(failure.diagnostics.size <= 3)
        assertTrue(failure.diagnostics.sumOf(String::length) <= 100)
        assertFalse(failure.diagnostics.any { "timed out" in it })
    }
}
