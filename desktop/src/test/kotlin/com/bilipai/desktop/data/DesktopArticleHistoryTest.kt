package com.bilipai.desktop.data

import com.android.purebilibili.core.network.BilibiliApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.io.TempDir
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class DesktopArticleHistoryTest {
    @TempDir lateinit var root: Path

    @Test fun privacyIsSuccessfulNoOpBeforeCredentialsOrTransport(): Unit = runBlocking {
        var published = 0
        assertTrue(reportDesktopArticleHistory(10, { true }, 1, 7, { 1 }, { 7 },
            { fail("Private article must not capture CSRF") }, { published++ }) { fail("No privacy request") })
        assertEquals(0, published)
    }

    @Test fun actualRetrofitFormPreservesOriginalArticleTypeAndEscapesCsrfOnce(): Unit = runBlocking {
        var captured: Request? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            captured = chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body("{\"code\":0,\"message\":\"ok\"}".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = Retrofit.Builder().baseUrl("https://api.bilibili.com/").client(client)
            .addConverterFactory(Json { ignoreUnknownKeys = true }.asConverterFactory("application/json".toMediaType()))
            .build().create(BilibiliApi::class.java)
        var published = 0
        try {
        assertTrue(reportDesktopArticleHistory(123, { false }, 1, 7, { 1 }, { 7 }, { "fixture&csrf+%" },
            { published++ }) { api.reportHistory(it).code })
        val request = assertNotNull(captured)
        assertEquals("POST", request.method); assertEquals("/x/v2/history/report", request.url.encodedPath)
        val form = request.body as FormBody
        val fields = (0 until form.size).associate { form.name(it) to form.value(it) }
        assertEquals(mapOf("aid" to "123", "type" to "5", "csrf" to "fixture&csrf+%"), fields)
        assertEquals(1, published)
        } finally {
            client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll()
            assertTrue(client.dispatcher.executorService.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS))
        }
    }

    @Test fun guestInvalidArticleAndMissingCsrfDoNotPublishOrSend(): Unit = runBlocking {
        val forbidden: suspend (Map<String, String>) -> Int = { fail("No authorized article write") }
        val notify: () -> Unit = { fail("No false history refresh") }
        assertTrue(reportDesktopArticleHistory(12, { false }, 1, null, { 1 }, { null }, { fail("Guest CSRF") }, notify, forbidden))
        assertTrue(reportDesktopArticleHistory(0, { false }, 1, 7, { 1 }, { 7 }, { "csrf" }, notify, forbidden))
        assertTrue(reportDesktopArticleHistory(12, { false }, 1, 7, { 1 }, { 7 }, { "" }, notify, forbidden))
    }

    @Test fun staleEpochAndChangedAccountAreRejectedBeforeCredentials(): Unit = runBlocking {
        val forbidden: suspend (Map<String, String>) -> Int = { fail("Old article cannot use new account") }
        assertFalse(reportDesktopArticleHistory(12, { false }, 1, 7, { 2 }, { 7 }, { fail("Old epoch") }, send = forbidden))
        assertFalse(reportDesktopArticleHistory(12, { false }, 1, 7, { 1 }, { 8 }, { fail("Changed owner") }, send = forbidden))
    }

    @Test fun switchingSessionDuringCsrfCaptureNeverSends(): Unit = runBlocking {
        var epoch = 1L
        assertFalse(reportDesktopArticleHistory(12, { false }, 1, 7, { epoch }, { 7 }, { epoch = 2; "csrf" }) {
            fail("Captured credentials belong to the old epoch")
        })
    }

    @Test fun privacyEnabledDuringCsrfCaptureBecomesSuccessfulNoOp(): Unit = runBlocking {
        var private = false
        assertTrue(reportDesktopArticleHistory(12, { private }, 1, 7, { 1 }, { 7 }, { private = true; "csrf" }) {
            fail("Privacy must be rechecked before sending")
        })
    }

    @Test fun apiFailureAndIOExceptionNeverNotifyHistory(): Unit = runBlocking {
        val notify: () -> Unit = { fail("No false bus event") }
        assertFalse(reportDesktopArticleHistory(12, { false }, 1, 7, { 1 }, { 7 }, { "csrf" }, notify) { -101 })
        assertFalse(reportDesktopArticleHistory(12, { false }, 1, 7, { 1 }, { 7 }, { "csrf" }, notify) { throw java.io.IOException("fixture") })
    }

    @Test fun switchedAccountDuringResponseNeverPublishesIntoNewBrowseMemory(): Unit = runBlocking {
        var mid = 7L
        assertFalse(reportDesktopArticleHistory(12, { false }, 1, 7, { 1 }, { mid }, { "csrf" },
            { fail("Old response cannot refresh new account") }) { mid = 8; 0 })
    }

    @Test fun cancelledTransportAndBestEffortWrapperBothPropagateCancellation(): Unit = runBlocking {
        assertFailsWith<CancellationException> {
            reportDesktopArticleHistory(12, { false }, 1, 7, { 1 }, { 7 }, { "csrf" }) { throw CancellationException("fixture") }
        }
        assertFailsWith<CancellationException> {
            articleContentWithBestEffortHistory("loaded article") { throw CancellationException("fixture") }
        }
    }

    @Test fun reportFailuresKeepTheAlreadyLoadedArticleIdentity(): Unit = runBlocking {
        val document = Any()
        assertSame(document, articleContentWithBestEffortHistory(document) { false })
        assertSame(document, articleContentWithBestEffortHistory(document) { throw java.io.IOException("fixture") })
    }

    @Test fun anotherFacadeEnablingPrivacyBlocksGuestAndAccountSearchWrites(): Unit = runBlocking {
        val old = DesktopSearchPreferences(root)
        old.record(7, "saved")
        val writer = DesktopSearchPreferences(root)
        writer.setPrivacyMode(true)
        assertFalse(old.privacyMode.value)
        val privacyFile = root.resolve("search/plugin-settings.json")
        val before = Files.readAllBytes(privacyFile)
        old.record(7, "private account"); old.record(null, "private guest")
        assertEquals(listOf("saved"), old.history(7).value.map { it.keyword })
        assertTrue(old.history(null).value.isEmpty())
        assertContentEquals(before, Files.readAllBytes(privacyFile))
    }

    @Test fun latestDisabledPrivacyAllowsSearchDespiteStaleEnabledFlow(): Unit = runBlocking {
        val writer = DesktopSearchPreferences(root); writer.setPrivacyMode(true)
        val old = DesktopSearchPreferences(root)
        writer.setPrivacyMode(false)
        assertTrue(old.privacyMode.value)
        old.record(7, "  normal search  ")
        assertEquals(listOf("normal search"), old.history(7).value.map { it.keyword })
        assertFalse(old.isPrivacyModeEnabledSync())
    }
}
