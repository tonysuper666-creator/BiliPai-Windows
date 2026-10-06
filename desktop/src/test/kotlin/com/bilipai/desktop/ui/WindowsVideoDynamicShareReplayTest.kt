package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.DynamicRepostContentItem
import com.android.purebilibili.data.model.response.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import kotlin.test.*

class WindowsVideoDynamicShareReplayTest {
    private val original = DynamicCreateFeedRequest(DynamicCreateFeedReq(
        content = DynamicCreateFeedContent(listOf(DynamicRepostContentItem(WindowsVideoDynamicShareReplay.DRAFT, 1, ""))),
        scene = 5, upload_id = "0_1700000000_1234"),
        DynamicVideoRepostSource(DynamicVideoRepostResource(170001L, 8)))
    private fun request(payload: DynamicCreateFeedRequest = original, url: String =
        "https://api.bilibili.com/x/dynamic/feed/create/dyn?csrf=LOCAL-COMPOSER-NOT-A-REAL-CSRF") =
        Request.Builder().url(url).post(Json.encodeToString(payload).toRequestBody("application/json".toMediaType())).build()

    @Test fun realOriginalModelsSerializeIntoOneFailureAndOneExplicitRetry() {
        val replay = WindowsVideoDynamicShareReplay(170001L)
        replay.respond(request()) { true }!!.use { assertTrue(it.body!!.string().contains(WindowsVideoDynamicShareReplay.RETRY_ERROR)) }
        replay.respond(request()) { true }!!.use { assertTrue(it.body!!.string().contains("990000027")) }
        val payloads = replay.receipt().getValue("payloads").jsonArray
        assertEquals(listOf(-1, 0), payloads.map { it.jsonObject.getValue("responseCode").jsonPrimitive.int })
        assertTrue(payloads.all { it.jsonObject.getValue("rid").jsonPrimitive.long == 170001L })
        assertFails { replay.respond(request()) { true } }
    }
    @Test fun wrongOriginPortQueryOrMethodDoesNotConsumeAttempt() {
        for (url in listOf("https://foreign.invalid/x/dynamic/feed/create/dyn?csrf=LOCAL-COMPOSER-NOT-A-REAL-CSRF",
            "https://api.bilibili.com:444/x/dynamic/feed/create/dyn?csrf=LOCAL-COMPOSER-NOT-A-REAL-CSRF",
            "https://api.bilibili.com/x/dynamic/feed/create/dyn?csrf=foreign",
            "https://api.bilibili.com/x/dynamic/feed/create/dyn?csrf=LOCAL-COMPOSER-NOT-A-REAL-CSRF&csrf=LOCAL-COMPOSER-NOT-A-REAL-CSRF")) {
            val replay = WindowsVideoDynamicShareReplay(170001L)
            assertFails { replay.respond(request(url = url)) { true } }; assertEquals(0, replay.count())
        }
        assertFails { WindowsVideoDynamicShareReplay(170001L).respond(request().newBuilder().get().build()) { true } }
    }
    @Test fun wrongSceneAidTypeOrTextCannotPublishSyntheticSuccess() {
        for (payload in listOf(original.copy(dyn_req = original.dyn_req.copy(scene = 1)),
            original.copy(web_repost_src = DynamicVideoRepostSource(DynamicVideoRepostResource(99, 8))),
            original.copy(web_repost_src = DynamicVideoRepostSource(DynamicVideoRepostResource(170001, 1))),
            original.copy(dyn_req = original.dyn_req.copy(content = DynamicCreateFeedContent(listOf(DynamicRepostContentItem("foreign", 1, ""))))))) {
            val replay = WindowsVideoDynamicShareReplay(170001L)
            assertFails { replay.respond(request(payload)) { true } }; assertEquals(0, replay.count())
        }
    }
    @Test fun unknownSchemaAndRetiredOwnerDoNotAdvance() {
        val replay = WindowsVideoDynamicShareReplay(170001L)
        val unknown = Json.encodeToString(original).dropLast(1) + ",\"foreign\":1}"
        assertFails { replay.respond(request().newBuilder().post(unknown.toRequestBody("application/json".toMediaType())).build()) { true } }
        assertFails { replay.respond(request()) { false } }
        var calls = 0
        assertFails { replay.respond(request()) { ++calls == 1 } }
        assertEquals(0, replay.count())
    }
    @Test fun otherMutationsStillFallThroughToTheUnchangedComposerRejector() {
        val replay = WindowsVideoDynamicShareReplay(170001L)
        for (path in listOf("/x/relation/modify", "/x/web-interface/archive/like", "/x/v2/reply/add", "/x/dynamic/feed/create/dyn/submit")) {
            val candidate = request(url = "https://api.bilibili.com$path")
            assertNull(replay.respond(candidate) { true })
            assertFails { WindowsCommentComposerReplay.requireReadOnly(candidate.method, candidate.url.host, path) }
        }
        assertFails { replay.receipt() }
    }
}
