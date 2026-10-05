package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.grpc.ProtoWire
import com.android.purebilibili.data.repository.DesktopDynamicCommentGrpc
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.video.ui.components.resolveChargedReplyLabel
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlin.test.*

class DesktopChargedCommentProtocolTest {
    @Test fun originalJsonChargedDescAndServerFirstResolverAreConsumed(): Unit {
        val reply=Json.decodeFromString(ReplyItem.serializer(),"""{"rpid":3,"reply_control":{"charged_desc":"充电专属"},"card_label":[{"text_content":"另一充电标签"}]}""")
        assertEquals("充电专属",reply.replyControl?.chargedDesc)
        assertEquals("充电专属",resolveChargedReplyLabel(reply))
    }
    @Test fun originalCardLabelFallbackIsNotGuessedFromVipOrFollowing(): Unit {
        assertNull(resolveChargedReplyLabel(ReplyItem()))
        assertNull(resolveChargedReplyLabel(ReplyItem(cardLabels=listOf(ReplyCardLabel(textContent="粉丝")))))
        assertEquals("充电评论",resolveChargedReplyLabel(ReplyItem(replyControl=ReplyControl(chargedDesc=" "),
            cardLabels=listOf(ReplyCardLabel(textContent="充电评论")))))
    }
    @Test fun actualGrpcRequestField31ReachesOriginalReplyAndResolver(): Unit = runBlocking {
        var calls=0;var owned=true
        val control=ProtoWire.message(ProtoWire.string(31,"服务端充电"),ProtoWire.string(11,"测试属地"))
        val reply=ProtoWire.message(ProtoWire.int64(2,3),ProtoWire.int64(3,170001),ProtoWire.bytes(14,control))
        val bytes=ProtoWire.message(ProtoWire.bytes(2,reply))
        val grpc=DesktopDynamicCommentGrpc({ path,message ->
            assertTrue(path.endsWith("/MainList"));assertTrue(message.isNotEmpty());calls++;bytes
        },{if(!owned)throw CancellationException("retired")})
        val item=grpc.getMainList(170001,1,2,null).getOrThrow().replies!!.single()
        assertEquals(1,calls);assertEquals("服务端充电",resolveChargedReplyLabel(item))
        owned=false
        assertFailsWith<CancellationException>{grpc.getMainList(170001,1,2,null)}
        assertEquals(1,calls)
    }
    @Test fun lateGrpcTransportAfterRetirementCannotPublishChargedReply(): Unit = runBlocking {
        val deferred=CompletableDeferred<ByteArray>();var owned=true
        val grpc=DesktopDynamicCommentGrpc({_,_->deferred.await()},{if(!owned)throw CancellationException("retired")})
        val operation=async {grpc.getMainList(170001,1,2,null)}
        yield();owned=false;deferred.complete(ProtoWire.message(ProtoWire.bytes(2,
            ProtoWire.message(ProtoWire.int64(2,3),ProtoWire.bytes(14,ProtoWire.message(ProtoWire.string(31,"充电")))))))
        assertFailsWith<CancellationException>{operation.await()}
    }
}
