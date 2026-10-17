package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.core.network.MessageApi
import com.android.purebilibili.core.store.DesktopFeedSettings
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.DesktopOriginalHomeVideoProtocol
import kotlinx.coroutines.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.lang.reflect.Proxy
import java.util.Collections
import kotlin.test.*

/** Calls the real generated protocol, signers, shared request adapter and feed merger.
 * Only API responses are controlled; no second implementation of feed mapping/filtering. */
class DesktopHomeNativeCardProtocolTest {
    @Test fun `web recommendation keeps both native kinds while filtering ads`() = runBlocking {
        Harness(DesktopFeedSettings.FeedApiType.WEB).use { h ->
            val cards = h.protocol.getHomeVideos(1).getOrThrow()
            assertEquals(listOf("BV1234567890", "bangumi:123", "dynamic:123"), cards.map { it.bvid })
            assertNativeIdentities(cards)
            assertEquals(listOf("getRecommendParams"), h.requests)
        }
    }

    @Test fun `app recommendation honors card_goto and excludes unsupported and advertising cards`() = runBlocking {
        Harness(DesktopFeedSettings.FeedApiType.MOBILE).use { h ->
            val cards = h.protocol.getHomeVideos(1).getOrThrow()
            assertEquals(listOf("BV1234567891", "bangumi:123", "dynamic:123"), cards.map { it.bvid })
            assertNativeIdentities(cards)
            assertEquals(listOf("getMobileFeed"), h.requests)
        }
    }

    @Test fun `merged recommendation keeps same-number episode and dynamic as separate identities`() = runBlocking {
        Harness(DesktopFeedSettings.FeedApiType.MERGED).use { h ->
            val cards = h.protocol.getHomeVideos(1).getOrThrow()
            assertEquals(setOf("BV1234567890", "BV1234567891", "bangumi:123", "dynamic:123"), cards.map { it.bvid }.toSet())
            assertEquals(4, cards.size)
            assertNativeIdentities(cards)
            assertEquals(setOf("getRecommendParams", "getMobileFeedEncoded"), h.requests.toSet())
            assertEquals(2, h.requests.size)
        }
    }

    @Test fun `failed app recommendation falls back through real web protocol with native cards intact`() = runBlocking {
        Harness(DesktopFeedSettings.FeedApiType.MOBILE, appFails = true).use { h ->
            val cards = h.protocol.getHomeVideos(1).getOrThrow()
            assertEquals(listOf("BV1234567890", "bangumi:123", "dynamic:123"), cards.map { it.bvid })
            assertNativeIdentities(cards)
            assertEquals(listOf("getMobileFeed", "getRecommendParams"), h.requests)
        }
    }

    private fun assertNativeIdentities(cards: List<VideoItem>) {
        val episode = cards.single { it.bvid == "bangumi:123" }
        val dynamic = cards.single { it.bvid == "dynamic:123" }
        assertEquals(123L, episode.episodeId)
        assertEquals("123", dynamic.dynamicId)
        for (card in listOf(episode, dynamic)) {
            assertEquals(0L, card.aid)
            assertEquals(0L, card.cid)
        }
        assertEquals(0, dynamic.duration)
    }

    private class Harness(type: DesktopFeedSettings.FeedApiType, appFails: Boolean = false) : AutoCloseable {
        val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        private val json = Json { ignoreUnknownKeys = true }
        private val web = json.decodeFromString<RecommendResponse>("""{"code":0,"data":{"item":[
            {"id":991,"bvid":"BV1234567890","goto":"av"},
            {"goto":"bangumi","param":"123","cid":888},
            {"goto":"picture","param":"123","duration":60},
            {"bvid":"BVadvertise00","goto":"av","ad_info":{}},
            {"goto":"live","param":"123"}
        ]}}""")
        private val app = json.decodeFromString<MobileFeedResponse>(if (appFails) """{"code":-1,"message":"fixture unavailable"}""" else """{"code":0,"data":{"items":[
            {"goto":"av","param":"BV1234567891","player_args":{"aid":992,"cid":87}},
            {"goto":"av","card_goto":"bangumi","param":"123","player_args":{"aid":999,"cid":888}},
            {"card_goto":"picture","param":"123","duration":60},
            {"goto":"av","param":"BVadvertise00","ad_info":{}},
            {"card_goto":"live","param":"123"},
            {"card_goto":"bangumi","param":"bad"}
        ]}}""")
        private val api: BilibiliApi = Proxy.newProxyInstance(BilibiliApi::class.java.classLoader,
            arrayOf(BilibiliApi::class.java)) { _, method, _ ->
            requests += method.name
            when (method.name) {
                "getRecommendParams" -> web
                "getMobileFeed", "getMobileFeedEncoded" -> app
                else -> error("Unexpected Home API: ${method.name}")
            }
        } as BilibiliApi
        private val messages: MessageApi = Proxy.newProxyInstance(MessageApi::class.java.classLoader,
            arrayOf(MessageApi::class.java)) { _, method, _ -> error("Unexpected message API: ${method.name}") } as MessageApi
        val protocol = DesktopOriginalHomeVideoProtocol(DesktopHomeProtocolEnvironment(
            api = api, guestApi = api, messageApi = messages, parentScope = scope,
            isCurrent = { true }, commitIfCurrent = { action -> action(); true },
            feedApiType = { type }, refreshCount = { 10 },
            wbiKeys = { Result.success("0123456789abcdef0123456789abcdef" to "fedcba9876543210fedcba9876543210") },
            accessToken = { "fixture-access" }, csrf = { "fixture-csrf" }, buvid3 = { "fixture-buvid" },
            awaitSessionRestored = {}, ensureBuvid3FromSpi = { error("Unexpected SPI request") },
        ))
        override fun close() { protocol.close(); scope.cancel() }
    }
}
