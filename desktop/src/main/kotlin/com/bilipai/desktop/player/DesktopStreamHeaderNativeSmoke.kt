package com.bilipai.desktop.player

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/** Actual loopback decoding/header handoff. All credentials here are synthetic fixture strings. */
internal object DesktopStreamHeaderNativeSmoke {
    fun run(player: MpvPlayer, video: File): List<String> {
        val passed = mutableListOf<String>()
        val observations = CopyOnWriteArrayList<Pair<String, Map<String, List<String>>>>()
        DesktopHttpMediaFixture(video) { target, headers ->
            val stage = target.substringAfter("fixture_case=", "").substringBefore('&')
            observations += stage to headers
        }.use { http ->
            fun source(stage: String, headers: Map<String, String> = emptyMap(), legacyCookie: String = "") = PlaybackSource(
                http.playableUrl + "&fixture_case=$stage", cookieHeader = legacyCookie, referer = "", userAgent = "Fixture-Base-UA",
                title = "Synthetic HTTP stream headers", startPositionSeconds = 2.0, startPaused = true, streamHeaders = headers)
            fun observed(stage: String): List<Map<String, List<String>>> = observations.filter { it.first == stage }.map { it.second }.also {
                check(it.isNotEmpty()) { "Native HTTP header fixture received no request for stage $stage." }
            }
            fun decoded(stage: String) {
                waitFor(player, "decoded stream-header stage $stage") {
                    !it.loading && it.firstVideoFrameReady && it.nativePaused == true && it.videoCodec != null && abs(it.positionSeconds - 2.0) < 0.15
                }
            }

            player.loadVersioned(source("legacy", legacyCookie = "SESSDATA=account-fixture-cookie"))
            decoded("legacy")
            check(observed("legacy").all { it["cookie"] == listOf("SESSDATA=account-fixture-cookie") }) { "Native legacy cookie fixture was not sent exactly once." }
            passed += "legacy header baseline"

            val explicit = linkedMapOf("Cookie" to "plugin=external-fixture-cookie", "Authorization" to "Bearer external-fixture-token",
                "X-Plugin-Key" to "external-fixture-key", "Referer" to "https://plugin.invalid/external", "User-Agent" to "Fixture-External-UA",
                "X-Comma-Route" to "left,right\\route")
            val owner = player.loadVersioned(source("external", explicit))
            // Mutating the original plugin map after handoff cannot change the native request.
            explicit.clear()
            decoded("external")
            check(observed("external").all {
                it["cookie"] == listOf("plugin=external-fixture-cookie") && it["authorization"] == listOf("Bearer external-fixture-token") &&
                    it["x-plugin-key"] == listOf("external-fixture-key") && it["referer"] == listOf("https://plugin.invalid/external") &&
                    it["user-agent"] == listOf("Fixture-External-UA") && it["x-comma-route"] == listOf("left,right\\route") &&
                    it.values.flatten().none { value -> value.contains("account-fixture-cookie") }
            }) { "Native explicit HTTP stream request headers were missing, duplicated or inherited account credentials." }
            check(player.currentSourceVersion == owner)
            passed += "immutable explicit headers and commas"

            check(player.recoverSource(owner, source("recovered", mapOf("Authorization" to "Bearer recovered-fixture-token", "X-Plugin-Key" to "recovered-fixture-key")),
                positionSeconds = 2.0, paused = true))
            decoded("recovered")
            check(player.currentSourceVersion == owner) { "HTTP stream recovery transferred media ownership." }
            check(observed("recovered").all {
                it["authorization"] == listOf("Bearer recovered-fixture-token") && it["x-plugin-key"] == listOf("recovered-fixture-key") &&
                    "cookie" !in it && "x-comma-route" !in it && "referer" !in it && it["user-agent"] == listOf("Fixture-Base-UA")
            }) { "Native recovery retained headers from the previous stream." }
            passed += "same-owner recovery replaces headers"

            val nextOwner = player.loadVersioned(source("empty"))
            decoded("empty")
            check(nextOwner > owner && player.currentSourceVersion == nextOwner)
            check(observed("empty").all {
                listOf("cookie", "authorization", "x-plugin-key", "x-comma-route", "referer").none(it::containsKey) &&
                    it["user-agent"] == listOf("Fixture-Base-UA")
            }) { "An empty stream inherited sensitive HTTP headers from an earlier owner." }
            passed += "empty stream clears previous headers"

            player.loadVersioned(source("empty-cookie", mapOf("Cookie" to ""), legacyCookie = "SESSDATA=blocked-account-fixture"))
            decoded("empty-cookie")
            check(observed("empty-cookie").all {
                it["cookie"].orEmpty().all(String::isEmpty) && it.values.flatten().none { value -> value.contains("blocked-account-fixture") }
            }) { "An explicitly empty Cookie was replaced with the app account cookie." }
            passed += "explicit empty Cookie blocks legacy fallback"

            val deniedOwner = player.loadVersioned(source("denied", mapOf("Cookie" to "plugin=denied-fixture-cookie", "X-Plugin-Key" to "denied-fixture-key",
                "Authorization" to "Bearer denied-fixture-token")).copy(videoUrl = http.deniedUrl + "&fixture_case=denied"))
            waitFor(player, "typed stream-header HTTP failure", allowError = true) { it.failure?.httpStatus == 403 }
            val failure = requireNotNull(player.state.value.failure)
            check(failure.sourceVersion == deniedOwner && failure.kind == PlayerFailureKind.NETWORK)
            val published = failure.toString()
            listOf("denied-fixture-cookie", "denied-fixture-key", "denied-fixture-token", "fixture-signature", "/denied").forEach {
                check(it !in published) { "Native arbitrary stream-header failure exposed fixture credentials." }
            }
            check(observed("denied").all { it["x-plugin-key"] == listOf("denied-fixture-key") })
            passed += "real 403 typed failure preserves safe diagnostics"
        }
        player.load(PlaybackSource(video.absolutePath, referer = "", title = "Native stream headers complete", startPositionSeconds = 2.0, startPaused = true))
        waitFor(player, "restored local source after stream-header fixture") { !it.loading && it.firstVideoFrameReady && it.nativePaused == true }
        return passed
    }

    private fun waitFor(player: MpvPlayer, operation: String, allowError: Boolean = false, condition: (PlayerState) -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) {
            val state = player.state.value
            if (condition(state)) return
            check(allowError || state.error == null) { "$operation failed: ${state.failure?.kind}" }
            Thread.sleep(25)
        }
        error("Timed out waiting for $operation.")
    }
}
