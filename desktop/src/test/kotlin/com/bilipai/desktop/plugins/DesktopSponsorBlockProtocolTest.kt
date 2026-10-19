package com.bilipai.desktop.plugins

import com.android.purebilibili.data.model.response.SponsorCategory
import com.android.purebilibili.data.repository.buildSponsorBlockClientRequest
import com.android.purebilibili.data.repository.buildSponsorBlockHttpClient
import com.android.purebilibili.data.repository.buildSponsorBlockVoteBody
import com.android.purebilibili.feature.plugin.SponsorBlockConfig
import com.android.purebilibili.feature.plugin.validateSponsorBlockUserId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Windows view of the v0.4.6 SponsorBlock fixes: vote JSON body, client request headers and a stable config ID. */
class DesktopSponsorBlockProtocolTest {
    @Test
    fun clientRequestReplacesSharedHeadersAndPreservesThePayload() {
        val original = Request.Builder()
            .url("https://bsbsb.top/api/skipSegments?videoID=BV1xx411c7mD")
            .header("Origin", "https://www.bilibili.com")
            .header("User-Agent", "old-version")
            .build()

        val request = buildSponsorBlockClientRequest(original, "1.2.3-debug")

        assertEquals("https://github.com/jay3-yy/BiliPai", request.header("Origin"))
        assertEquals("1.2.3-debug", request.header("x-ext-version"))
        assertEquals("BiliPai/1.2.3-debug", request.header("User-Agent"))
        assertEquals(original.url, request.url)
        assertEquals(original.method, request.method)
        assertSame(original.body, request.body)
    }

    @Test
    fun voteBodyUsesTheDocumentedVoteTypesAsJsonNumbers() {
        listOf(0, 1, 20).forEach { voteType ->
            val body = buildSponsorBlockVoteBody("private-user-id", "segment-id", voteType)

            assertEquals(
                buildJsonObject {
                    put("UUID", "segment-id")
                    put("userID", "private-user-id")
                    put("type", voteType)
                },
                Json.parseToJsonElement(body)
            )
        }
    }

    @Test
    fun voteBodyRejectsNegativeVoteTypesAndRequiresExactlyOneKind() {
        assertFailsWith<IllegalArgumentException> {
            buildSponsorBlockVoteBody("private-user-id", "segment-id", voteType = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            buildSponsorBlockVoteBody("private-user-id", "segment-id")
        }
        assertFailsWith<IllegalArgumentException> {
            buildSponsorBlockVoteBody("private-user-id", "segment-id", 0, SponsorCategory.INTRO)
        }
    }

    @Test
    fun categoryVoteOmitsTheType() {
        assertEquals(
            buildJsonObject {
                put("UUID", "segment-id")
                put("userID", "private-user-id")
                put("category", SponsorCategory.INTRO)
            },
            Json.parseToJsonElement(
                buildSponsorBlockVoteBody("private-user-id", "segment-id", category = SponsorCategory.INTRO)
            )
        )
    }

    @Test
    fun theClientKeepsItsTimeoutsAndAddsExactlyTheOriginalRequestInterceptor() {
        val base = OkHttpClient.Builder().build()
        val client = buildSponsorBlockHttpClient(base)

        assertEquals(base.protocols, client.protocols)
        assertEquals(5_000, client.connectTimeoutMillis)
        assertEquals(5_000, client.readTimeoutMillis)
        assertEquals(base.interceptors.size + 1, client.interceptors.size)
    }

    @Test
    fun windowsBindsTheOriginalBuildVersionToAValidHeaderValue() {
        val version = desktopSponsorBlockVersionName()
        assertTrue(version.isNotBlank())
        assertTrue(version.all { it.code in 0x20..0x7e }, "header value must stay printable ASCII")
        val request = buildSponsorBlockClientRequest(Request.Builder().url("https://bsbsb.top/api").build(), version)
        assertEquals("BiliPai/$version", request.header("User-Agent"))
    }

    @Test
    fun configNormalizationCreatesAValidStableUserIdAndKeepsAnImportedOne() {
        val config = SponsorBlockConfig().normalized()

        assertNull(validateSponsorBlockUserId(config.userId))
        assertEquals(config.userId, config.normalized().userId)
        val imported = "a".repeat(32)
        assertEquals(imported, SponsorBlockConfig(userId = imported).normalized().userId)
    }
}
