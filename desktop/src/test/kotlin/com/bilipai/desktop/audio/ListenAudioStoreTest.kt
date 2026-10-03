package com.bilipai.desktop.audio

import com.android.purebilibili.feature.video.player.PlaylistItem
import com.bilipai.desktop.data.DesktopCommunityRepository
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.data.DesktopSessionStore
import com.bilipai.desktop.player.MpvPlayer
import com.bilipai.desktop.player.PlaybackSource
import com.bilipai.desktop.player.PlayerPreferences
import java.nio.file.Files
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ListenAudioStoreTest {
    private fun item(id: String, cid: Long = 1) = PlaylistItem(id, cid, "标题 $id", "", "作者", duration = 80)

    @Test fun `saved queue favorites recent selected item and resume position survive reopening`() {
        val directory = Files.createTempDirectory("bilipai-listen-state-test-")
        try {
            val file = directory.resolve("listen.json")
            val queue = listOf(item("BVfirst"), item("BVsecond", 2))
            val snapshot = ListenAudioSaved(queue, 1, queue.reversed(), listOf(queue.first()), 27.25)
            ListenAudioStore(file).save(snapshot)
            assertEquals(snapshot, ListenAudioStore(file).read())
            assertEquals(listOf("listen.json"), Files.list(directory).use { it.map { path -> path.fileName.toString() }.toList() })
            val anotherAccount = directory.resolve("other-account.json")
            assertEquals(ListenAudioSaved(), ListenAudioStore(anotherAccount).read())
            ListenAudioStore(anotherAccount).save(ListenAudioSaved(listOf(item("BVthird")), 0, positionSeconds = 3.0))
            assertEquals(snapshot, ListenAudioStore(file).read())
        } finally { deleteDirectory(directory) }
    }

    @Test fun `damaged documents and invalid queue bounds recover into usable cold state`() {
        val directory = Files.createTempDirectory("bilipai-listen-state-test-")
        try {
            val file = directory.resolve("listen.json")
            Files.writeString(file, "{truncated")
            assertEquals(ListenAudioSaved(), ListenAudioStore(file).read())
            ListenAudioStore(file).save(ListenAudioSaved(listOf(item(""), item("BVfirst"), item("BVfirst", 2)), 100,
                recent = List(350) { item("recent-$it") }, positionSeconds = Double.NaN))
            val restored = ListenAudioStore(file).read()
            assertEquals(listOf(item("BVfirst")), restored.queue)
            assertEquals(0, restored.currentIndex)
            assertEquals(300, restored.recent.size)
            assertEquals(0.0, restored.positionSeconds)
        } finally { deleteDirectory(directory) }
    }

    @Test fun `inactive audio account destruction preserves its resume and cannot release another source`() {
        val directory = Files.createTempDirectory("bilipai-listen-account-test-")
        val player = MpvPlayer()
        var session: ListenAudioSession? = null
        try {
            val store = ListenAudioStore(directory.resolve("listen.json"))
            val saved = ListenAudioSaved(listOf(item("BVfirst")), 0, positionSeconds = 27.25)
            store.save(saved)
            val repository = DesktopRepository(DesktopSessionStore(directory.resolve("account.json"), persistent = false))
            val foreignVersion = player.loadVersioned(PlaybackSource("file:///C:/other-account.mp4", title = "Other account"))
            SwingUtilities.invokeAndWait {
                session = ListenAudioSession(repository, DesktopCommunityRepository(repository), player, store = store)
                assertFalse(requireNotNull(session).state.value.active)
                requireNotNull(session).close()
            }
            assertEquals(foreignVersion, player.currentSourceVersion)
            assertEquals("Other account", player.state.value.sourceTitle)
            assertEquals(saved, store.read())
            assertTrue(player.stopIfSourceVersion(foreignVersion))
        } finally { session?.close(); player.close(); deleteDirectory(directory) }
    }

    @Test fun `listening forces audio output without changing ordinary video preferences`() {
        val directory = Files.createTempDirectory("bilipai-listen-preferences-test-")
        val player = MpvPlayer()
        var session: ListenAudioSession? = null
        try {
            val repository = DesktopRepository(DesktopSessionStore(directory.resolve("account.json"), persistent = false))
            val normal = PlayerPreferences(audioOnly = false, volume = 32.0, speed = 1.5, muted = true)
            SwingUtilities.invokeAndWait {
                session = ListenAudioSession(repository, DesktopCommunityRepository(repository), player,
                    initialPreferences = normal, store = ListenAudioStore(directory.resolve("listen.json")))
                requireNotNull(session).updatePreferences(normal)
                assertTrue(player.state.value.audioOnly)
                assertEquals(32.0, player.state.value.volume)
                assertEquals(1.5, player.state.value.speed)
                assertTrue(player.state.value.muted)
                assertFalse(normal.audioOnly)
                requireNotNull(session).enqueue(listOf(item("BVfirst"), item("BVsecond")))
                requireNotNull(session).moveQueueItem(0, 1)
                assertEquals(listOf("BVsecond", "BVfirst"), requireNotNull(session).state.value.queue.map { it.bvid })
                requireNotNull(session).close()
            }
            assertFalse(normal.audioOnly)
            assertEquals(listOf("BVsecond", "BVfirst"), ListenAudioStore(directory.resolve("listen.json")).read().queue.map { it.bvid })
        } finally { session?.close(); player.close(); deleteDirectory(directory) }
    }

    @Test fun `fresh separate audio actor is configured in constructor before identical window update`() {
        val directory = Files.createTempDirectory("bilipai-listen-initial-actor-")
        val player = MpvPlayer()
        var session: ListenAudioSession? = null
        try {
            val repository = DesktopRepository(DesktopSessionStore(directory.resolve("account.json"), persistent = false))
            val initial = PlayerPreferences(volume = 32.0, speed = 1.5, muted = true, audioOnly = false,
                playbackMode = com.bilipai.desktop.player.PlaybackMode.REPEAT_ONE)
            SwingUtilities.invokeAndWait {
                session = ListenAudioSession(repository, DesktopCommunityRepository(repository), player,
                    initialPreferences = initial, store = ListenAudioStore(directory.resolve("listen.json")))
                assertTrue(player.state.value.audioOnly)
                assertEquals(32.0, player.state.value.volume)
                assertEquals(1.5, player.state.value.speed)
                assertTrue(player.state.value.muted)
                assertTrue(player.state.value.looping)
                assertEquals(0L, player.currentSourceVersion)
                kotlin.test.assertNull(player.currentSourceSnapshot())
                requireNotNull(session).updatePreferences(initial)
                assertTrue(player.state.value.audioOnly)
                assertEquals(1.5, player.state.value.speed)
                assertFalse(initial.audioOnly)
            }
        } finally { session?.close(); player.close(); deleteDirectory(directory) }
    }

    @Test fun `new audio session cannot initialize over an existing foreign source or its local controls`() {
        val directory = Files.createTempDirectory("bilipai-listen-foreign-initial-")
        val player = MpvPlayer()
        var session: ListenAudioSession? = null
        try {
            val repository = DesktopRepository(DesktopSessionStore(directory.resolve("account.json"), persistent = false))
            player.applyPreferences(PlayerPreferences(volume = 67.0, speed = 2.0, muted = false, audioOnly = false,
                hardwareDecodeEnabled = false, playbackMode = com.bilipai.desktop.player.PlaybackMode.REPEAT_ONE))
            val version = player.loadVersioned(PlaybackSource("file:///C:/private-not-decoded.mp4", title = "Existing foreign source"))
            val source = requireNotNull(player.currentSourceSnapshot())
            SwingUtilities.invokeAndWait {
                session = ListenAudioSession(repository, DesktopCommunityRepository(repository), player,
                    initialPreferences = PlayerPreferences(volume = 32.0, speed = 1.5, muted = true),
                    store = ListenAudioStore(directory.resolve("listen.json")))
                assertTrue(player.ownsSourceSnapshot(source))
                assertEquals(version, player.currentSourceVersion)
                assertEquals(67.0, player.state.value.volume)
                assertEquals(2.0, player.state.value.speed)
                assertFalse(player.state.value.audioOnly)
                assertFalse(player.state.value.muted)
                assertFalse(player.state.value.hardwareDecodeEnabled)
                assertTrue(player.state.value.looping)
                requireNotNull(session).close()
                assertTrue(player.ownsSourceSnapshot(source))
            }
        } finally { session?.close(); player.close(); deleteDirectory(directory) }
    }

    @Test fun `later audio window volume delta preserves newer source local speed loop and view choices`() {
        val directory = Files.createTempDirectory("bilipai-listen-later-delta-")
        val player = MpvPlayer()
        var session: ListenAudioSession? = null
        try {
            val repository = DesktopRepository(DesktopSessionStore(directory.resolve("account.json"), persistent = false))
            val initial = PlayerPreferences(volume = 32.0, speed = 1.5, muted = true, audioOnly = false)
            SwingUtilities.invokeAndWait {
                session = ListenAudioSession(repository, DesktopCommunityRepository(repository), player,
                    initialPreferences = initial, store = ListenAudioStore(directory.resolve("listen.json")))
                val version = player.loadVersioned(PlaybackSource("file:///C:/private-later-control.mp4", title = "Local control source"))
                player.setSpeed(2.0); player.setAudioOnly(false); player.setLoop(true); player.setMuted(false)
                requireNotNull(session).updatePreferences(initial.copy(volume = 41.0))
                assertEquals(version, player.currentSourceVersion)
                assertEquals(41.0, player.state.value.volume)
                assertEquals(2.0, player.state.value.speed)
                assertFalse(player.state.value.audioOnly)
                assertFalse(player.state.value.muted)
                assertTrue(player.state.value.looping)
            }
        } finally { session?.close(); player.close(); deleteDirectory(directory) }
    }

    private fun deleteDirectory(directory: java.nio.file.Path) {
        Files.list(directory).use { files -> files.forEach { Files.deleteIfExists(it) } }
        Files.deleteIfExists(directory)
    }
}
