package com.bilipai.desktop.audio

import com.android.purebilibili.feature.audio.lyrics.*
import com.android.purebilibili.feature.video.player.PlaylistItem
import com.android.purebilibili.feature.video.subtitle.SubtitleCue
import com.android.purebilibili.feature.video.subtitle.SubtitleTrackMeta
import com.bilipai.desktop.data.DesktopCommunityRepository
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.data.DesktopSessionStore
import com.bilipai.desktop.player.MpvPlayer
import com.bilipai.desktop.player.PlaybackSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ListenAudioLifecycleTest {
    private fun item(id: String, cid: Long = 1) = PlaylistItem(id, cid, "标题 $id", "", "作者", duration = 80)
    private fun prepared(item: PlaylistItem) = PreparedListenAudio(item.copy(title = "已解析 ${item.title}"),
        PlaybackSource("file:///C:/listen-test.mp4", title = "已解析 ${item.title}"))

    @Test fun `favorite continuation keeps loaded next track and paused source and rejects retired owners`() {
        Fixture(FakeSource(::prepared)).use { fixture ->
            val owner = Any()
            SwingUtilities.invokeAndWait {
                assertTrue(fixture.session.playQueueForOwner(owner, listOf(item("BVfirst", 11), item("BVsecond", 22))))
            }
            fixture.await { it.active && !it.loading }
            SwingUtilities.invokeAndWait { fixture.session.next() }
            fixture.await { it.current?.bvid == "BVsecond" && !it.loading }
            val selected = fixture.session.state.value.current
            val sourceVersion = fixture.player.currentSourceVersion
            SwingUtilities.invokeAndWait {
                fixture.session.pause()
                assertTrue(fixture.session.ownsQueue(owner))
                assertTrue(fixture.session.appendQueueForOwner(owner,
                    listOf(item("BVsecond", 999), item("BVthird", 33), item("BVthird", 44))))
            }
            assertEquals(listOf("BVfirst", "BVsecond", "BVthird"), fixture.session.state.value.queue.map { it.bvid })
            assertEquals(selected, fixture.session.state.value.current)
            assertEquals(1, fixture.session.state.value.currentIndex)
            assertEquals(sourceVersion, fixture.player.currentSourceVersion)
            assertTrue(fixture.player.state.value.paused)
            SwingUtilities.invokeAndWait {
                fixture.session.play(listOf(item("BVnormal")))
                assertFalse(fixture.session.ownsQueue(owner))
                assertFalse(fixture.session.appendQueueForOwner(owner, listOf(item("BVstale"))))
            }
            assertEquals(listOf("BVnormal"), fixture.session.state.value.queue.map { it.bvid })
        }
    }

    @Test fun `pending favorite owner cannot append or replace a foreign native source`() {
        val started = CompletableDeferred<Unit>()
        val completion = CompletableDeferred<PreparedListenAudio>()
        Fixture(FakeSource { selected -> started.complete(Unit); completion.await().copy(item = selected) }).use { fixture ->
            val owner = Any()
            SwingUtilities.invokeAndWait { assertTrue(fixture.session.playQueueForOwner(owner, listOf(item("BVpending")))) }
            runBlocking { withTimeout(3_000) { started.await() } }
            val foreignVersion = fixture.player.loadVersioned(PlaybackSource("file:///C:/foreign-listen.mp4", title = "Foreign"))
            SwingUtilities.invokeAndWait {
                assertFalse(fixture.session.ownsQueue(owner))
                assertFalse(fixture.session.appendQueueForOwner(owner, listOf(item("BVstale"))))
            }
            completion.complete(prepared(item("BVpending")))
            fixture.await { !it.loading }
            assertEquals(foreignVersion, fixture.player.currentSourceVersion)
            assertEquals("Foreign", fixture.player.state.value.sourceTitle)
            assertEquals(listOf("BVpending"), fixture.session.state.value.queue.map { it.bvid })
        }
    }

    @Test fun `cold queue resumes the selected part and active session destruction releases its actual source`() {
        val saved = ListenAudioSaved(listOf(item("BVfirst"), item("BVsecond", 22)), 1, positionSeconds = 27.25)
        Fixture(FakeSource(::prepared), saved).use { fixture ->
            SwingUtilities.invokeAndWait { fixture.session.togglePause() }
            fixture.await { it.active && !it.loading }
            assertEquals("BVsecond", fixture.session.state.value.current?.bvid)
            assertEquals(22L, fixture.session.state.value.current?.cid)
            assertEquals(27.25, fixture.player.state.value.positionSeconds)
            assertTrue(fixture.player.state.value.audioOnly)
            val ownedVersion = fixture.player.currentSourceVersion
            SwingUtilities.invokeAndWait { fixture.session.close() }
            assertTrue(fixture.player.currentSourceVersion > ownedVersion)
            assertEquals("BiliPai", fixture.player.state.value.sourceTitle)
            assertEquals(27.25, fixture.store.read().positionSeconds)
            assertEquals(1, fixture.store.read().currentIndex)
        }
    }

    @Test fun `active old account closure retains its own resume without releasing a newer source`() {
        val saved = ListenAudioSaved(listOf(item("BVfirst")), 0, positionSeconds = 13.5)
        Fixture(FakeSource(::prepared), saved).use { fixture ->
            SwingUtilities.invokeAndWait { fixture.session.togglePause() }
            fixture.await { it.active && !it.loading }
            val foreignVersion = fixture.player.loadVersioned(PlaybackSource("file:///C:/new-account.mp4",
                title = "Other account", startPositionSeconds = 3.0))
            SwingUtilities.invokeAndWait { fixture.session.close() }
            assertEquals(foreignVersion, fixture.player.currentSourceVersion)
            assertEquals("Other account", fixture.player.state.value.sourceTitle)
            assertEquals(13.5, fixture.store.read().positionSeconds)
        }
    }

    @Test fun `pause cancels delayed preparation so completion cannot restart listening`() {
        val started = CompletableDeferred<Unit>()
        val result = CompletableDeferred<PreparedListenAudio>()
        Fixture(FakeSource { selected -> started.complete(Unit); result.await().copy(item = selected) }).use { fixture ->
            SwingUtilities.invokeAndWait { fixture.session.play(listOf(item("BVfirst"))) }
            runBlocking { withTimeout(3_000) { started.await() } }
            SwingUtilities.invokeAndWait { fixture.session.pause() }
            val versionAfterPause = fixture.player.currentSourceVersion
            result.complete(prepared(item("BVfirst")))
            SwingUtilities.invokeAndWait { }
            assertFalse(fixture.session.state.value.active)
            assertFalse(fixture.session.state.value.loading)
            assertEquals(versionAfterPause, fixture.player.currentSourceVersion)
            assertEquals("BiliPai", fixture.player.state.value.sourceTitle)
        }
    }

    @Test fun `clearing an old queue cannot stop a foreign source and clears obsolete resume`() {
        Fixture(FakeSource(::prepared), ListenAudioSaved(listOf(item("BVfirst")), 0, positionSeconds = 10.0)).use { fixture ->
            SwingUtilities.invokeAndWait { fixture.session.togglePause() }
            fixture.await { it.active && !it.loading }
            val foreignVersion = fixture.player.loadVersioned(PlaybackSource("file:///C:/new-account.mp4", title = "Other account"))
            SwingUtilities.invokeAndWait { fixture.session.clearQueue(); fixture.session.close() }
            assertEquals(foreignVersion, fixture.player.currentSourceVersion)
            assertEquals("Other account", fixture.player.state.value.sourceTitle)
            assertTrue(fixture.store.read().queue.isEmpty())
            assertEquals(0.0, fixture.store.read().positionSeconds)
        }
    }

    private class FakeSource(private val preparing: suspend (PlaylistItem) -> PreparedListenAudio) : ListenPlaybackDataSource {
        override val lyrics = LyricsRepository(emptyList(), object : LyricsCache {
            override suspend fun read(key: String): LyricDocument? = null
            override suspend fun write(key: String, document: LyricDocument) = Unit
        })
        override suspend fun prepare(item: PlaylistItem) = preparing(item)
        override suspend fun subtitleTracks(item: PlaylistItem): List<SubtitleTrackMeta> = emptyList()
        override suspend fun subtitleCues(track: SubtitleTrackMeta): List<SubtitleCue> = emptyList()
    }

    private class Fixture(source: ListenPlaybackDataSource, saved: ListenAudioSaved = ListenAudioSaved()) : AutoCloseable {
        val directory = Files.createTempDirectory("bilipai-listen-lifecycle-test-")
        val player = MpvPlayer()
        val store = ListenAudioStore(directory.resolve("listen.json"))
        val repository = DesktopRepository(DesktopSessionStore(directory.resolve("account.json"), persistent = false))
        lateinit var session: ListenAudioSession
        init {
            store.save(saved)
            SwingUtilities.invokeAndWait { session = ListenAudioSession(repository, DesktopCommunityRepository(repository), player,
                store = store, playbackDataSource = source) }
        }
        fun await(predicate: (ListenAudioState) -> Boolean) = runBlocking { withTimeout(3_000) { session.state.first { predicate(it) } } }
        override fun close() {
            SwingUtilities.invokeAndWait { session.close() }
            player.close()
            Files.list(directory).use { files -> files.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(directory)
        }
    }
}
