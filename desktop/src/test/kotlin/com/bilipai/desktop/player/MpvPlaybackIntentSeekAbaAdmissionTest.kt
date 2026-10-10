package com.bilipai.desktop.player

import kotlin.test.*

/** The existing actual Session actor against its memory-only ABI fixture.
 * Real Load/START_FILE/FILE_LOADED/codec readback establishes the seek command
 * gate. No DLL initialization, HWND, thread, media IO or seek ACK is fabricated.
 * This is the final native intent primitive used by PGC recapture, not a second
 * end-to-end PGC fixture. The short-action sentinel stands for consumer linking.
 */
class MpvPlaybackIntentSeekAbaAdmissionTest {
    @Test fun actualLoadedActorSeekOutAndBackRejectsOldIntentDespiteSameSourceSnapshot() {
        MpvPlayer().use { player ->
            val withoutSession = player.capturePlaybackIntentSerials()
            assertNull(player.seekToTracked(55.0))
            assertEquals(withoutSession, player.capturePlaybackIntentSerials())
            val version = player.loadVersioned(PlaybackSource(
                "https://fixture.invalid/video", "https://fixture.invalid/audio",
                title = "Memory ABI seek admission", startPositionSeconds = 27.75, startPaused = true))
            PremiumRecoveryActor(player).use { actor ->
                assertTrue(player.recoverSource(version))
                actor.drain() // Execute the real Load through mpv_command_node.
                assertEquals("loadfile", actor.native.loads.single().first())
                assertEquals("https://fixture.invalid/video", actor.native.loads.single()[1])
                actor.loaded() // Real START_FILE / FILE_LOADED ABI and refreshState.
                assertFalse(player.state.value.loading)
                assertNull(player.state.value.error)
                assertEquals("h264", player.state.value.videoCodec)
                assertEquals("flac", player.state.value.audioCodec)
                assertEquals(27.75, player.state.value.positionSeconds)
                // The helper intentionally does not run native initialization;
                // its source is loaded, its global DLL-ready flag stays false.
                assertFalse(player.state.value.ready)
                val source = assertNotNull(player.currentSourceSnapshot())
                val oldIntent = player.capturePlaybackIntentSerials()
                assertTrue(player.ownsSourceSnapshot(source))
                assertTrue(player.ownsPlaybackIntentSerials(oldIntent))

                val out = assertNotNull(player.seekToTracked(55.0))
                val back = assertNotNull(player.seekToTracked(27.75))
                assertTrue(out > oldIntent.second)
                assertTrue(back > out)
                val current = assertNotNull(player.currentSourceSnapshot())
                assertEquals(source.sourceVersion, current.sourceVersion)
                assertEquals(source.source, current.source)
                assertTrue(player.ownsSourceSnapshot(source))
                assertEquals(27.75, player.state.value.positionSeconds)
                assertEquals(0L, player.state.value.seekCompletedId)
                val freshIntent = player.capturePlaybackIntentSerials()
                assertEquals(oldIntent.first, freshIntent.first)
                assertEquals(back, freshIntent.second)
                assertFalse(player.ownsPlaybackIntentSerials(oldIntent))

                var consumerLinks = 0
                var staleAccepted = true
                assertTrue(player.admitSourceSnapshot(source) {
                    staleAccepted = player.admitPlaybackIntentSerials(oldIntent) { consumerLinks++ }
                })
                assertFalse(staleAccepted)
                assertEquals(0, consumerLinks)
                assertTrue(player.admitSourceSnapshot(source) {
                    assertTrue(player.admitPlaybackIntentSerials(freshIntent) { consumerLinks++ })
                })
                assertEquals(1, consumerLinks)
                // Do not drain these seeks: the existing ABI fake only handles
                // load/subtitle commands. Returning IDs proves actual admission;
                // no playback-restart/seek-completion or hardware claim follows.
            }
        }
    }
}
