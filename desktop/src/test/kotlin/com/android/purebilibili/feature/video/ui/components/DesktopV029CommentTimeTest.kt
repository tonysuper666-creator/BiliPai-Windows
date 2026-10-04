package com.android.purebilibili.feature.video.ui.components

import com.android.purebilibili.core.store.DesktopOriginalReplySettings
import com.android.purebilibili.data.model.response.ReplyItem
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.test.*

class DesktopV029CommentTimeTest {
    private fun absolute(seconds: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(seconds * 1000))

    @Test fun previewDefaultRemainsRelativeAndDetailedModePreservesSeconds() {
        val item = ReplyItem(rpid = 42, ctime = System.currentTimeMillis() / 1000 - 60)
        val relative = requireNotNull(resolveReplyPreviewTextContent(item).commentContext).timeText
        val precise = requireNotNull(resolveReplyPreviewTextContent(item, detailedTimeEnabled = true).commentContext).timeText
        assertNotEquals(absolute(item.ctime), relative)
        assertTrue(relative.isNotBlank())
        assertEquals(absolute(item.ctime), precise)
        assertNotEquals(relative, precise)
    }

    @Test fun actualDetailedTimeSettingSurvivesColdStoreAndCanBeDisabledWithoutTouchingOtherPreferences(): Unit = runBlocking {
        val directory = Files.createTempDirectory("bp-v029-comment-time-")
        val store = DesktopPluginStore(directory)
        store.update("settings", mapOf("unrelated" to JsonPrimitive("preserved")))
        val context = DesktopPluginContext(store)
        assertFalse(DesktopOriginalReplySettings.getDetailedCommentTimeEnabled(context).first())
        DesktopOriginalReplySettings.setDetailedCommentTimeEnabled(context, true)
        val cold = DesktopPluginContext(DesktopPluginStore(directory))
        assertTrue(DesktopOriginalReplySettings.getDetailedCommentTimeEnabled(cold).first())
        val item = ReplyItem(rpid = 42, ctime = System.currentTimeMillis() / 1000 - 60)
        val precise = resolveReplyPreviewTextContent(item, detailedTimeEnabled = DesktopOriginalReplySettings.getDetailedCommentTimeEnabled(cold).first())
        assertEquals(absolute(item.ctime), requireNotNull(precise.commentContext).timeText)
        DesktopOriginalReplySettings.setDetailedCommentTimeEnabled(cold, false)
        val nextCold = DesktopPluginContext(DesktopPluginStore(directory))
        assertFalse(DesktopOriginalReplySettings.getDetailedCommentTimeEnabled(nextCold).first())
        val relative = resolveReplyPreviewTextContent(item, detailedTimeEnabled = DesktopOriginalReplySettings.getDetailedCommentTimeEnabled(nextCold).first())
        assertNotEquals(absolute(item.ctime), requireNotNull(relative.commentContext).timeText)
        assertEquals(JsonPrimitive("preserved"), nextCold.store.preferences("settings")["unrelated"])
    }

    @Test fun savedCommentImageAlwaysKeepsTheFullTimestampRegardlessOfDisplayMode() {
        val item = ReplyItem(rpid = 42, ctime = System.currentTimeMillis() / 1000 - 60)
        val relative = requireNotNull(resolveReplyPreviewTextContent(item, detailedTimeEnabled = false).commentContext).timeText
        val saved = buildReplyCommentImageSpec(item, generatedAtMillis = 0)
        assertTrue(saved.metadataText.startsWith(absolute(item.ctime)))
        assertNotEquals(relative, saved.metadataText)
        assertEquals(requireNotNull(resolveReplyPreviewTextContent(item, detailedTimeEnabled = true).commentContext).timeText, saved.metadataText)
    }
}
