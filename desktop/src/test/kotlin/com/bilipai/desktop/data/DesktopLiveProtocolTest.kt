package com.bilipai.desktop.data

import com.android.purebilibili.core.network.socket.DanmakuProtocol
import com.android.purebilibili.data.repository.parseLiveDanmakuPermission
import com.android.purebilibili.data.repository.parseLiveDanmakuHistoryItems
import com.android.purebilibili.feature.live.LiveRealtimeAction
import com.android.purebilibili.feature.live.resolveLiveRealtimeAction
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopLiveProtocolTest {
    @Test fun `compressed socket frame keeps chat identity style and server deletion`(): Unit = runBlocking {
        val chat = """{"cmd":"DANMU_MSG","info":[[0,5,25,65280],"实时消息",[42,"名字",1],[3,"勋章",0,0,255],[9],0,0,2]}"""
        val deletion = """{"cmd":"SUPER_CHAT_MESSAGE_DELETE","data":{"ids":[99,100]}}"""
        val packets = listOf(chat, deletion).map { text -> DanmakuProtocol.encode(DanmakuProtocol.Packet(
            DanmakuProtocol.PROTO_VER_JSON, DanmakuProtocol.OP_MESSAGE, body = text.toByteArray())) }
        val compressed = ByteArrayOutputStream().also { output -> DeflaterOutputStream(output).use { it.write(packets[0] + packets[1]) } }.toByteArray()
        val frame = DanmakuProtocol.encode(DanmakuProtocol.Packet(DanmakuProtocol.PROTO_VER_ZLIB, DanmakuProtocol.OP_MESSAGE, body = compressed))
        val decoded = DanmakuProtocol.decode(frame)
        assertEquals(2, decoded.size)
        val action = resolveLiveRealtimeAction(Json.parseToJsonElement(String(decoded[0].body)).jsonObject, 42)
        assertTrue(action is LiveRealtimeAction.EmitChat)
        assertEquals("实时消息", action.item.text); assertEquals(5, action.item.mode); assertEquals(65280, action.item.color)
        assertTrue(action.item.isSelf); assertTrue(action.item.isAdmin); assertEquals("勋章", action.item.medalName)
        val removed = resolveLiveRealtimeAction(Json.parseToJsonElement(String(decoded[1].body)).jsonObject)
        assertTrue(removed is LiveRealtimeAction.RemoveSuperChats); assertEquals(listOf(99L, 100L), removed.ids)
        assertTrue(DanmakuProtocol.decode(frame.copyOf(10)).isEmpty())
    }

    @Test fun `original send permissions exclude unavailable styles and reject malformed config`() {
        val permission = parseLiveDanmakuPermission("""{"code":0,"data":{"group":[{"color":[{"color":"16777215","name":"白","color_hex":"#ffffff","status":1},{"color":"255","name":"蓝","status":0}]}],"mode":[{"mode":1,"name":"滚动","status":1},{"mode":5,"name":"顶部","status":0}]}}""")
        assertTrue(permission.canSend); assertEquals(40, permission.maxLength)
        assertEquals(listOf(16777215), permission.availableColors.map { it.color })
        assertEquals(listOf(1), permission.availableModes.map { it.mode })
        assertFalse(parseLiveDanmakuPermission("invalid").canSend)
        assertFalse(parseLiveDanmakuPermission("""{"code":-101,"message":"需要登录"}""").canSend)
    }

    @Test fun `original history parser retains reply and report identity`() {
        val items = parseLiveDanmakuHistoryItems("""{"code":0,"data":{"room":[{"text":"历史","id_str":"stable-id","user":{"uid":42,"base":{"name":"名字"}},"reply":{"reply_uname":"对方"},"check_info":{"ts":123,"ct":"signature"}}]}}""").getOrThrow()
        assertEquals(1, items.size); assertEquals(42L, items.single().uid)
        assertEquals("对方", items.single().replyToName); assertEquals("stable-id", items.single().idStr)
        assertEquals(123L, items.single().reportTs); assertEquals("signature", items.single().reportSign)
    }
}
