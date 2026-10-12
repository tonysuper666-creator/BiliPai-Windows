package com.bilipai.desktop.data

import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.data.model.response.FavoriteResourceData
import com.android.purebilibili.data.model.response.FavoriteResourceResponse
import com.android.purebilibili.data.model.response.SimpleApiResponse
import com.android.purebilibili.data.repository.ContentRequestException
import com.android.purebilibili.feature.list.DesktopFavoriteEnvironment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.Continuation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Actual original Repository + owned API adapter. The API port is sealed to two
 * bounded protocol responses; no Root, account, HTTP transport or UI is started. */
class DesktopFavoriteRepositoryResultSemanticsTest {
    private class Fixture {
        private val owner = SupervisorJob()
        private val scope = CoroutineScope(owner + Dispatchers.Default)
        @Volatile var presented = true
        var reply: (String) -> Any = { error("A fixture response must be explicitly supplied: $it") }
        val calls = CopyOnWriteArrayList<Pair<String, List<Any?>>>()
        private val api = Proxy.newProxyInstance(BilibiliApi::class.java.classLoader, arrayOf(BilibiliApi::class.java)) {
            _, method, arguments ->
            check(method.name in setOf("getFavoriteSeasonList", "batchDelFavResource"))
            val values = arguments.orEmpty().filterNot { it is Continuation<*> }
            calls += method.name to values
            reply(method.name)
        } as BilibiliApi
        val environment = DesktopFavoriteEnvironment.forFolderDrawer(scope, api, { presented && owner.isActive },
            { "fixture-csrf" }, { 17L }, { error("Repository result tests must not produce visible feedback") })
        val repository get() = environment.favorite
        suspend fun close() = owner.cancelAndJoin()
    }

    private val failureCases = listOf(
        -101 to "登录已失效，请重新扫码登录",
        -404 to "内容不存在或已被删除",
        -412 to "请求暂时受限，请稍后重试",
        -799 to "请求失败（-799）",
    )

    @Test fun seasonFailureRetainsServerCodeAndOriginalTypedError(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            for ((code, message) in failureCases) {
                fixture.reply = { FavoriteResourceResponse(code = code, message = "") }
                val failure = assertIs<ContentRequestException>(fixture.repository.getFavoriteSeasonList(41L, 3).exceptionOrNull())
                assertEquals(code, failure.code)
                assertEquals(message, failure.message)
            }
            assertTrue(fixture.calls.all { it.first == "getFavoriteSeasonList" && it.second == listOf(41L, 3, 20) })
        } finally { fixture.close() }
    }

    @Test fun removalFailureRetainsCodeAndExactOriginalRequest(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            for ((code, message) in failureCases) {
                fixture.reply = { SimpleApiResponse(code = code, message = "") }
                val failure = assertIs<ContentRequestException>(fixture.repository.removeResource(23L, 91L).exceptionOrNull())
                assertEquals(code, failure.code)
                assertEquals(message, failure.message)
            }
            assertTrue(fixture.calls.all { it.first == "batchDelFavResource" && it.second == listOf(23L, "91:2", "fixture-csrf") })
        } finally { fixture.close() }
    }

    @Test fun successfulResponsesKeepOriginalDataAndBoolean(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            val data = FavoriteResourceData(has_more = true)
            fixture.reply = { name -> if (name == "getFavoriteSeasonList") FavoriteResourceResponse(data = data) else SimpleApiResponse() }
            assertSame(data, fixture.repository.getFavoriteSeasonList(41L, 1).getOrThrow())
            assertEquals(true, fixture.repository.removeResource(23L, 91L).getOrThrow())
            assertEquals(listOf("getFavoriteSeasonList", "batchDelFavResource"), fixture.calls.map { it.first })
        } finally { fixture.close() }
    }

    @Test fun missingSeasonDataKeepsTheOriginalFailureBranch(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            fixture.reply = { FavoriteResourceResponse(code = 0, message = "", data = null) }
            val failure = assertIs<ContentRequestException>(fixture.repository.getFavoriteSeasonList(41L, 1).exceptionOrNull())
            assertEquals(0, failure.code)
            assertEquals("请求失败（0）", failure.message)
        } finally { fixture.close() }
    }

    @Test fun unknownCodePreservesTheServerExplanation(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            fixture.reply = { name -> if (name == "getFavoriteSeasonList") FavoriteResourceResponse(code = -799, message = "bounded explanation")
                else SimpleApiResponse(code = -799, message = "bounded explanation") }
            val failures = listOf(fixture.repository.getFavoriteSeasonList(41L, 1).exceptionOrNull(),
                fixture.repository.removeResource(23L, 91L).exceptionOrNull())
            failures.forEach { failure ->
                assertEquals(-799, assertIs<ContentRequestException>(failure).code)
                assertEquals("bounded explanation", failure.message)
            }
        } finally { fixture.close() }
    }

    @Test fun cancellationEscapesBothActualRepositoryCalls(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            fixture.reply = { throw CancellationException("synthetic API cancellation") }
            assertFailsWith<CancellationException> { fixture.repository.getFavoriteSeasonList(41L, 1) }
            assertFailsWith<CancellationException> { fixture.repository.removeResource(23L, 91L) }
            assertEquals(2, fixture.calls.size)
        } finally { fixture.close() }
    }

    @Test fun retiredPageCannotConsumeAnOtherwiseSuccessfulReply(): Unit = runBlocking {
        for (season in listOf(true, false)) {
            val fixture = Fixture()
            try {
                fixture.reply = { fixture.presented = false; if (season) FavoriteResourceResponse(data = FavoriteResourceData()) else SimpleApiResponse() }
                assertFailsWith<CancellationException> {
                    if (season) fixture.repository.getFavoriteSeasonList(41L, 1) else fixture.repository.removeResource(23L, 91L)
                }
                assertEquals(1, fixture.calls.size)
            } finally { fixture.close() }
        }
    }
}
