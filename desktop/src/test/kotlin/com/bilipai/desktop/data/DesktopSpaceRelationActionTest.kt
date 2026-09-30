package com.bilipai.desktop.data

import com.android.purebilibili.core.network.BilibiliApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlin.test.*

class DesktopSpaceRelationActionTest {
    @Test fun blacklistRemovalUsesOriginalActSixAndAuthorizedCsrfOnce() = runBlocking {
        val sessions = DesktopSessionStore.temporary()
        sessions.saveAccount(mapOf("SESSDATA" to "fixture-only", "bili_jct" to "fixture-csrf"), AccountSummary(1, "fixture", ""))
        val repository = DesktopRepository(sessions)
        DesktopRepository::class.java.getDeclaredField("visitorInitialized").apply { isAccessible = true }.set(repository, true)
        DesktopRepository::class.java.getDeclaredField("visitorGeneration").apply { isAccessible = true }.set(repository, repository.sessionEpoch)
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body("{\"code\":0,\"message\":\"ok\"}".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = Retrofit.Builder().baseUrl("https://api.bilibili.com/").client(client)
            .addConverterFactory(Json { ignoreUnknownKeys = true }.asConverterFactory("application/json".toMediaType())).build().create(BilibiliApi::class.java)
        val social = DesktopSocialRepository(repository)
        DesktopSocialRepository::class.java.getDeclaredField("api").apply { isAccessible = true }.set(social, api)
        social.removeBlacklist(22)
        assertEquals(1, requests.size)
        assertEquals("/x/relation/modify", requests.single().url.encodedPath)
        val form = requests.single().body as FormBody
        val fields = (0 until form.size).associate { form.name(it) to form.value(it) }
        assertEquals(mapOf("fid" to "22", "act" to "6", "csrf" to "fixture-csrf"), fields)
        assertFailsWith<IllegalArgumentException> { social.removeBlacklist(1) }
        assertFailsWith<IllegalArgumentException> { social.removeBlacklist(0) }
        assertEquals(1, requests.size)
    }

    @Test fun anonymousBlacklistRemovalFailsBeforeAnyRequest() = runBlocking {
        val social = DesktopSocialRepository(DesktopRepository(DesktopSessionStore.temporary()))
        assertFailsWith<BiliApiException> { social.removeBlacklist(22) }
    }
}
