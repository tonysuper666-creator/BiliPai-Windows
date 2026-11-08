@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.bilipai.desktop.ui

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.android.purebilibili.data.model.response.VideoItem
import com.android.purebilibili.feature.home.components.cards.rememberVideoCardPublishTimeText
import com.android.purebilibili.feature.video.ui.section.resolveCompactPublishTimeRowText
import com.bilipai.desktop.data.AccountSummary
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.data.DesktopSessionStore
import com.bilipai.desktop.data.DesktopUpstreamSession
import com.bilipai.desktop.data.LocalDesktopUpstreamSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopVideoCardPublishTimeTest {
    @Test fun windowOwnerFetchesAndSwitchingAccountsReplacesCachedTime() = runBlocking {
        val store = DesktopSessionStore.temporary()
        for (mid in listOf(11L, 22L)) {
            store.saveAccount(mapOf("SESSDATA" to "fixture-$mid", "bili_jct" to "csrf-$mid"),
                AccountSummary(mid, "Fixture $mid", "", isVip = true))
        }
        val repository = DesktopRepository(store)
        val observedEpochs = Collections.synchronizedList(mutableListOf<Long>())
        val original = DesktopRepository::class.java.getDeclaredField("client").apply { isAccessible = true }
        val client = (original.get(repository) as OkHttpClient).newBuilder().apply {
            interceptors().add(0, okhttp3.Interceptor { chain ->
                val tagClass = Class.forName("com.bilipai.desktop.data.DesktopSessionEpoch")
                val tag = requireNotNull(chain.request().tag(tagClass)) { "request has no window owner" }
                val epoch = tagClass.getDeclaredField("value").apply { isAccessible = true }.getLong(tag)
                observedEpochs += epoch
                val pubdate = if (observedEpochs.size == 1) 1_577_836_800L else 1_609_459_200L
                val json = """{"code":0,"data":{"bvid":"BV1xx411c7mD","aid":42,"cid":1,"pubdate":$pubdate}}"""
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(json.toResponseBody("application/json".toMediaType())).build()
            })
        }.build()
        original.set(repository, client)
        val window = DesktopUpstreamSession(repository) { true }
        val lifecycle = PageTestLifecycleOwner()
        var text = ""
        val scene = ImageComposeScene(400, 100, Density(1f), coroutineContext = coroutineContext) {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle, LocalDesktopUpstreamSession provides window) {
                text = rememberVideoCardPublishTimeText(VideoItem(bvid = "BV1xx411c7mD", aid = 42), true, true)
                BasicText(text)
            }
        }
        suspend fun await(expected: String) {
            repeat(200) {
                scene.render(System.nanoTime()).close()
                if (text == expected) return
                delay(20)
            }
            error("expected $expected, got $text; requests=$observedEpochs")
        }
        try {
            val firstEpoch = repository.sessionEpochFlow.value
            await(resolveCompactPublishTimeRowText(1_577_836_800L))
            assertTrue(store.activateAccount(11))
            val secondEpoch = repository.sessionEpochFlow.value
            assertTrue(firstEpoch != secondEpoch)
            await(resolveCompactPublishTimeRowText(1_609_459_200L))
            assertEquals(listOf(firstEpoch, secondEpoch), observedEpochs.toList())
        } finally {
            scene.close()
            repository.logout()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    @Test fun missingWindowSessionShowsFallbackWithoutStartingARequest() = runBlocking {
        val lifecycle = PageTestLifecycleOwner()
        var text = ""
        val scene = ImageComposeScene(400, 100, Density(1f), coroutineContext = coroutineContext) {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
                text = rememberVideoCardPublishTimeText(VideoItem(bvid = "BV1xx411c7mE", aid = 43), true, true)
                BasicText(text)
            }
        }
        try {
            repeat(30) { scene.render(System.nanoTime()).close(); delay(20) }
            assertEquals("发布时间未知", text)
        } finally { scene.close() }
    }
}
