package com.bilipai.desktop.ui

import okhttp3.FormBody
import okhttp3.Request
import kotlin.test.*

class WindowsBrandFeedbackReplayTest {
    private fun request(action: Int, aid: Long = 170001L, csrf: String = "LOCAL-COMPOSER-NOT-A-REAL-CSRF") =
        Request.Builder().url("https://api.bilibili.com/x/web-interface/archive/like")
            .post(FormBody.Builder().add("aid", aid.toString()).add("like", action.toString()).add("csrf", csrf).build()).build()

    @Test fun exactOriginalFormSequenceGetsOnlyMemoryResponses() {
        val replay = WindowsBrandFeedbackReplay(170001L)
        for (action in listOf(1, 2, 1, 2, 1)) {
            val sent = request(action)
            replay.respond(sent) { true }!!.use {
                assertEquals(200, it.code); assertTrue(it.body!!.string().contains("\"code\":0"))
                assertSame(sent, it.request)
            }
        }
        assertTrue(replay.receipt().toString().contains("\"remoteMutationSent\":false"))
        assertFails { replay.respond(request(2)) { true } }
    }

    @Test fun wrongSubjectCredentialsOriginOrRetiredOwnerCannotEnterSyntheticMutation() {
        val replay = WindowsBrandFeedbackReplay(170001L)
        assertFails { replay.respond(request(1, aid = 9L)) { true } }
        assertFails { replay.respond(request(1, csrf = "foreign")) { true } }
        assertFails { replay.respond(request(1).newBuilder().url("https://foreign.invalid/x/web-interface/archive/like").build()) { true } }
        assertFails { replay.respond(request(1)) { false } }
        var checks = 0
        assertFails { replay.respond(request(1)) { ++checks == 1 } }
        replay.respond(request(1)) { true }!!.close()
        assertFails { replay.receipt() }
    }

    @Test fun allOtherMutationsRemainWithTheExistingRejectingTransport() {
        val replay = WindowsBrandFeedbackReplay(170001L)
        for (path in listOf("/x/v2/reply/add", "/x/relation/modify", "/x/v3/fav/resource/deal")) {
            val candidate = request(1).newBuilder().url("https://api.bilibili.com$path").build()
            assertNull(replay.respond(candidate) { true })
            assertFails { WindowsCommentComposerReplay.requireReadOnly(candidate.method, candidate.url.host, path) }
        }
    }
}
