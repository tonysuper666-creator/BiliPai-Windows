package com.bilipai.desktop.settings

import com.android.purebilibili.core.store.DesktopOriginalPlaybackSettingsPreferences as Playback
import com.android.purebilibili.core.store.DesktopOriginalReplySettings as Reply
import com.android.purebilibili.core.store.DesktopOriginalTabletAudioSettings as Tablet
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.*

/** Real generated preference owners over the same persisted Windows store. */
class DesktopCommentAvatarSettingsTest {
    private class Fixture {
        val directory = Files.createTempDirectory("bp-comment-avatar-settings-")
        val store = DesktopPluginStore(directory)
        val reply = DesktopPluginContext(store)
        var current = true
        val playback = DesktopOriginalPlayerSettingsContext(reply, { current }, { action ->
            if (current) { action(); true } else false
        }, largeScreenOrFoldableConfiguration = { false }, isDebugBuild = { false })
    }

    @Test fun `both original readers default off without creating a settings file`(): Unit = runBlocking {
        val f = Fixture()
        assertFalse(Reply.getCommentAvatarPushEnabled(f.reply).first())
        assertFalse(Playback.getCommentAvatarPushEnabled(f.playback).first())
        assertFalse(Files.exists(f.directory.resolve("plugin-settings.json")))
    }

    @Test fun `Windows setter and shared reply setter publish to each other and cold readers`(): Unit = runBlocking {
        val f = Fixture()
        f.store.update("settings", mapOf("foreign" to JsonPrimitive("keep")))
        Playback.setCommentAvatarPushEnabled(f.playback, true)
        assertTrue(Reply.getCommentAvatarPushEnabled(f.reply).first())
        assertTrue(Reply.getCommentAvatarPushEnabled(DesktopPluginContext(DesktopPluginStore(f.directory))).first())
        Reply.setCommentAvatarPushEnabled(f.reply, false)
        assertFalse(Playback.getCommentAvatarPushEnabled(f.playback).first())
        assertEquals(JsonPrimitive("keep"), f.store.preferences("settings")["foreign"])
        assertEquals(JsonPrimitive(false), f.store.preferences("settings")["comment_avatar_push_enabled"])
    }

    @Test fun `retired Windows settings page cannot publish to shared comments`(): Unit = runBlocking {
        val f = Fixture()
        Playback.setCommentAvatarPushEnabled(f.playback, true)
        val before = Files.readAllBytes(f.directory.resolve("plugin-settings.json"))
        f.current = false
        assertFailsWith<CancellationException> { Playback.setCommentAvatarPushEnabled(f.playback, false) }
        assertTrue(Reply.getCommentAvatarPushEnabled(f.reply).first())
        assertContentEquals(before, Files.readAllBytes(f.directory.resolve("plugin-settings.json")))
    }

    @Test fun `cancelled reply writer cannot create settings`(): Unit = runBlocking {
        val f = Fixture()
        val job = launch {
            cancel("Original caller has retired")
            Reply.setCommentAvatarPushEnabled(f.reply, true)
        }
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(Reply.getCommentAvatarPushEnabled(f.reply).first())
        assertFalse(Files.exists(f.directory.resolve("plugin-settings.json")))
    }

    @Test fun `backup imports the boolean for both owners and rejects string coercion`(): Unit = runBlocking {
        val f = Fixture()
        val service = DesktopSettingsShareService(f.playback)
        fun profile(value: String) = """{"schemaVersion":1,"app":"BiliPai","appVersion":"0.4.6","exportedAtIso":"2026-10-17T00:00:00Z","profileName":"avatar","sections":{"playback":{"comment_avatar_push_enabled":$value}}}"""
        val imported = service.applyImport(service.readImportSession(profile("true")))
        assertEquals(listOf("comment_avatar_push_enabled"), imported.appliedKeys)
        assertTrue(Reply.getCommentAvatarPushEnabled(f.reply).first())
        assertTrue(Playback.getCommentAvatarPushEnabled(f.playback).first())
        val before = Files.readAllBytes(f.directory.resolve("plugin-settings.json"))
        val invalid = service.applyImport(service.readImportSession(profile("\"false\"")))
        assertTrue(invalid.appliedKeys.isEmpty())
        assertEquals(listOf("comment_avatar_push_enabled"), invalid.skippedKeys)
        assertContentEquals(before, Files.readAllBytes(f.directory.resolve("plugin-settings.json")))
    }

    @Test fun `Bangumi sort setter shares the normalised live preference and synchronous mirror`(): Unit = runBlocking {
        val f = Fixture()
        for ((input, expected) in listOf(2 to 2, 3 to 3, -1 to 3)) {
            Reply.setCommentDefaultSortMode(f.reply, input)
            assertEquals(expected, Reply.getCommentDefaultSortMode(f.reply).first())
            assertEquals(expected, Reply.getCommentDefaultSortModeSync(f.reply))
            assertEquals(JsonPrimitive(expected), f.store.preferences("settings")["comment_default_sort_mode"])
        }
        Tablet.setCommentDefaultSortMode(f.playback, 2)
        assertEquals(2, Reply.getCommentDefaultSortModeSync(f.reply))
        assertEquals(2, Reply.getCommentDefaultSortMode(f.reply).first())
    }
}
