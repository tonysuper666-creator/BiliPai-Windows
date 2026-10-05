package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.EmoteResponse
import com.android.purebilibili.data.model.response.MentionSearchResponse
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Memory-only transport tests. No Main/window/real account/session or socket. */
class WindowsCommentComposerReplayTest {
    private fun withReplay(block: (WindowsCommentComposerReplay, OkHttpClient, AtomicInteger) -> Unit) {
        val root = Files.createTempDirectory("composer-replay-transport-")
        try {
            WindowsCommentComposerReplay(root).use { replay ->
                val escaped = AtomicInteger()
                val client = OkHttpClient.Builder().addInterceptor { chain ->
                    replay.intercept(chain) { true } ?: error("Only the requested read may be mapped")
                }.addInterceptor { escaped.incrementAndGet(); error("A memory-only read tried to escape to a socket") }.build()
                try { block(replay, client, escaped) }
                finally { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
            }
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun originalEmoteDtoContainsAllFourNonemptyPackagesAndPrivateImage() {
        withReplay { replay, client, escaped ->
            client.newCall(Request.Builder().url("https://api.bilibili.com/x/emote/user/panel/web?business=reply").build())
                .execute().use { response ->
                    val dto = Json.decodeFromString(EmoteResponse.serializer(), requireNotNull(response.body).string())
                    assertEquals(0, dto.code)
                    val packages = requireNotNull(dto.data).packages.orEmpty()
                    assertEquals(listOf(1L, 2L, 53L, 4L), packages.map { it.id })
                    assertTrue(packages.all { !it.emote.isNullOrEmpty() && it.emote!!.all { item -> item.url == replay.imageUri } })
                    assertEquals(WindowsCommentComposerReplay.EMOTE, packages.first().emote!!.single().text)
                    assertTrue(Files.isRegularFile(replay.image))
                }
            assertEquals(0, escaped.get())
        }
    }
    @Test fun originalMentionDtoResolvesOnlyTheRequestedSyntheticFriend() {
        withReplay { _, client, escaped ->
            client.newCall(Request.Builder().url("https://api.bilibili.com/x/polymer/web-dynamic/v1/mention/search?keyword=%E5%90%88%E6%88%90").build())
                .execute().use { response ->
                    val dto = Json.decodeFromString(MentionSearchResponse.serializer(), requireNotNull(response.body).string())
                    val friend = requireNotNull(dto.data).groups.single().items.single()
                    assertEquals(WindowsCommentComposerReplay.FRIEND_MID, friend.uid)
                    assertEquals(WindowsCommentComposerReplay.FRIEND_NAME, friend.name)
                    assertEquals("", friend.face)
                }
            assertEquals(0, escaped.get())
        }
    }
    @Test fun noPublishUploadFollowOrOtherPostCanReachTerminalTransport() {
        withReplay { _, client, escaped ->
            for (path in listOf("/x/v2/reply/add", "/x/upload/web/image", "/x/relation/modify", "/x/dynamic/feed/create/dyn")) {
                assertFailsWith<IllegalArgumentException> {
                    client.newCall(Request.Builder().url("https://api.bilibili.com$path").post(ByteArray(0).toRequestBody()).build()).execute()
                }
            }
            assertFailsWith<IllegalArgumentException> {
                client.newCall(Request.Builder().url("https://api.bilibili.com/x/v2/reply/action").build()).execute()
            }
            assertEquals(0, escaped.get())
        }
    }
    @Test fun readRpcPostAllowlistRequiresActualAppHostAndExactMethodPath() {
        WindowsCommentComposerReplay.requireReadOnly("POST", "app.bilibili.com", WindowsCommentSearchReplay.MAIN_RPC)
        WindowsCommentComposerReplay.requireReadOnly("POST", "app.bilibili.com", WindowsCommentSearchReplay.DETAIL_RPC)
        assertFailsWith<IllegalArgumentException> {
            WindowsCommentComposerReplay.requireReadOnly("POST", "api.bilibili.com", WindowsCommentSearchReplay.MAIN_RPC)
        }
        assertFailsWith<IllegalArgumentException> {
            WindowsCommentComposerReplay.requireReadOnly("POST", "app.bilibili.com", WindowsCommentSearchReplay.MAIN_RPC + "/extra")
        }
    }
    @Test fun oldCallerOwnerCannotObtainMentionDataAndImageCannotBeOverwritten() {
        val root = Files.createTempDirectory("composer-replay-retired-")
        try {
            WindowsCommentComposerReplay(root).use { replay ->
                val client = OkHttpClient.Builder().addInterceptor { chain ->
                    requireNotNull(replay.intercept(chain) { false })
                }.build()
                try {
                    assertFailsWith<IllegalStateException> {
                        client.newCall(Request.Builder().url("https://api.bilibili.com/x/polymer/web-dynamic/v1/mention/search").build()).execute()
                    }
                    val original = Files.readAllBytes(replay.image)
                    assertFailsWith<IllegalArgumentException> { WindowsCommentComposerReplay(root) }
                    assertContentEquals(original, Files.readAllBytes(replay.image))
                } finally { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
            }
        } finally { root.toFile().deleteRecursively() }
    }
}
