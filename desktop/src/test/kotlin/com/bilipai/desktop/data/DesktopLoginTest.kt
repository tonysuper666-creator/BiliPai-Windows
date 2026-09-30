package com.bilipai.desktop.data

import com.android.purebilibili.core.network.AppSignUtils
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.login.*
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.util.Base64
import javax.crypto.Cipher
import kotlinx.serialization.json.*
import kotlin.test.*

class DesktopLoginTest {
    @Test fun originalRsaEncryptionRetainsSaltAndPasswordWithoutUrlDecoding() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pem = "-----BEGIN PUBLIC KEY-----\n${Base64.getMimeEncoder().encodeToString(pair.public.encoded)}\n-----END PUBLIC KEY-----"
        val password = "test-only %2C &密码"
        val encrypted = assertNotNull(RsaEncryption.encryptPassword(password, pem, "salt-"))
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.DECRYPT_MODE, pair.private) }
        assertEquals("salt-$password", cipher.doFinal(Base64.getDecoder().decode(encrypted)).toString(Charsets.UTF_8))
    }

    @Test fun originalSmsBuilderUsesDialingCodeAndIndependentAppBuvid() {
        val region = resolveDefaultPhoneRegion(resolveFallbackPhoneRegions())
        assertEquals(1, region.cid); assertEquals(86, resolveSmsApiCid(region))
        val params = buildAndroidSmsSendParams("13800000000", resolveSmsApiCid(region), "token", "challenge", "validate", "seccode", "app-buvid", "session", 1234)
        assertEquals("86", params["cid"]); assertEquals("app-buvid", params["buvid"])
        assertEquals("app-buvid", params["local_id"]); assertEquals(AppSignUtils.ANDROID_HD_APP_KEY, params["appkey"])
        assertEquals("challenge", params["gee_challenge"])
        assertTrue(AppSignUtils.signForAndroidHdLogin(params)["sign"].orEmpty().isNotBlank())
    }

    @Test fun appCookieInfoWinsOverHeadersAndRetainsLiteralEncoding() {
        val cookies = resolveAppLoginCookies("https://passport.bilibili.com/x/passport-login/oauth2/login".toHttpUrl(),
            listOf("SESSDATA=header%2Cvalue; Path=/; Secure", "bili_jct=header-csrf; Path=/", "unrelated=ignore; Path=/"),
            mapOf("SESSDATA" to "body%2Cvalue", "bili_jct" to "body-csrf", "unrelated" to "ignore"))
        assertEquals("body%2Cvalue", cookies["SESSDATA"]); assertEquals("body-csrf", cookies["bili_jct"])
        assertFalse(cookies.containsKey("unrelated"))
    }

    @Test fun missingNewAccountCredentialsNeverUsePreviousAccount() {
        val cookies = resolveAppLoginCookies("https://passport.bilibili.com/x/passport-login/oauth2/login".toHttpUrl(),
            listOf("SESSDATA=expired; Max-Age=0; Path=/"), mapOf("SESSDATA" to "", "buvid3" to "visitor"))
        assertNull(cookies["SESSDATA"]); assertEquals("visitor", cookies["buvid3"])
        assertFailsWith<IllegalArgumentException> { resolveAppLoginCookies("https://bilibili.com.attacker.invalid/".toHttpUrl(), emptyList(), mapOf("SESSDATA" to "test-only")) }
    }

    @Test fun twoSavedAccountsHaveIndependentTokensAndVisitorCookiesAfterRestart() {
        val file = Files.createTempDirectory("bp-accounts-").resolve("session.json")
        val store = DesktopSessionStore(file)
        store.saveAccount(mapOf("SESSDATA" to "first-test-only", "bili_jct" to "csrf-first", "buvid3" to "first-visitor"), AccountSummary(1, "first", ""),
            imported = true, credentials = DesktopAppCredentials("first-access", "first-refresh", "tv"))
        store.saveFromResponse("https://www.bilibili.com/".toHttpUrl(), listOf(Cookie.Builder().name("sid").value("first-homepage").hostOnlyDomain("www.bilibili.com").path("/").secure().build()))
        store.saveAccount(mapOf("SESSDATA" to "second-test-only", "bili_jct" to "csrf-second", "buvid3" to "second-visitor"), AccountSummary(2, "second", ""),
            imported = true, credentials = DesktopAppCredentials("second-access", "second-refresh", "android"))
        assertFalse(store.currentCookies().containsKey("sid"))
        val restored = DesktopSessionStore(file)
        assertEquals(2L, restored.account.value?.mid)
        assertEquals("android", restored.accessTokenCredentials().second)
        assertTrue(restored.activateAccount(1, AccountSummary(1, "first refreshed", "")))
        assertEquals("first refreshed", restored.account.value?.name)
        assertEquals("first-access" to "tv", restored.accessTokenCredentials())
        assertEquals("first-visitor", restored.currentCookies()["buvid3"])
        assertEquals("first-homepage", restored.loadForRequest("https://www.bilibili.com/".toHttpUrl()).single { it.name == "sid" }.value)
        assertFalse(restored.loadForRequest("https://api.bilibili.com/".toHttpUrl()).any { it.name == "sid" })
        restored.logout(); assertNull(restored.account.value); assertEquals(2, restored.accounts.value.size)
        assertTrue(restored.activateAccount(2)); assertTrue(restored.removeAccount(1)); assertEquals(2L, restored.account.value?.mid)
        assertTrue(restored.removeAccount(2)); assertNull(restored.account.value); assertTrue(restored.accounts.value.isEmpty())
    }

    @Test fun webReauthenticationClearsAppTokenAndStoredSecretsUseWindowsProtection() {
        val file = Files.createTempDirectory("bp-account-secrets-").resolve("session.json")
        val store = DesktopSessionStore(file); val account = AccountSummary(9, "test", "")
        store.saveAccount(mapOf("SESSDATA" to "test-only-account-session", "bili_jct" to "test-only-csrf"), account, true,
            DesktopAppCredentials("test-only-access", "test-only-refresh", "tv"))
        if (System.getProperty("os.name").startsWith("Windows")) {
            val saved = Files.readString(file)
            assertTrue(saved.contains("dpapi:v1:"))
            listOf("test-only-account-session", "test-only-csrf", "test-only-access", "test-only-refresh").forEach { assertFalse(saved.contains(it)) }
        }
        store.saveAccount(mapOf("SESSDATA" to "new-test-only-session", "bili_jct" to "csrf"), account, true, preserveAccessToken = false)
        assertNull(store.appCredentials()); assertNull(DesktopSessionStore(file).appCredentials())
    }

    @Test fun staleResponsesAndVisitorBootstrapCannotCrossAnAccountSwitch() {
        val store = DesktopSessionStore.temporary()
        val epoch = store.generation
        store.requestGeneration.set(epoch)
        try {
            store.saveAccount(mapOf("SESSDATA" to "test-only-new-session"), AccountSummary(1, "test", ""))
            store.saveFromResponse("https://www.bilibili.com/".toHttpUrl(), listOf(Cookie.Builder().name("sid").value("old-response").domain("bilibili.com").path("/").build()))
            assertFalse(store.currentCookies().containsKey("sid"))
            assertFailsWith<BiliApiException> { store.loadForRequest("https://api.bilibili.com/".toHttpUrl()) }
            assertFailsWith<BiliApiException> { store.saveSpiCookies(mapOf("buvid3" to "old-spi"), epoch) }
        } finally { store.requestGeneration.remove() }
        assertEquals("test-only-new-session", store.loadForRequest("https://api.bilibili.com/".toHttpUrl()).single { it.name == "SESSDATA" }.value)
    }

    @Test fun candidateSessionIsTemporaryAndDoesNotOverwritePersistedCredentials() {
        val store = DesktopSessionStore.temporary()
        store.saveFromResponse("https://passport.bilibili.com/".toHttpUrl(), listOf(Cookie.Builder().name("SESSDATA").value("candidate-only").hostOnlyDomain("passport.bilibili.com").path("/").secure().build()))
        assertNull(store.account.value)
        assertEquals("candidate-only", store.currentCookies()["SESSDATA"])
        store.logout(); assertTrue(store.currentCookies().isEmpty())
    }

    @Test fun geetestConfigurationMirrorsOriginalAndNeverInjectsClosingScript() {
        val config = desktopGeetestConfig("({\"status\":\"success\",\"data\":{\"type\":\"fullpage\",\"payload\":\"</script>\"}})", "test-gt", "test-challenge")
        assertEquals("bind", config["product"]?.jsonPrimitive?.content)
        assertEquals(false, config["offline"]?.jsonPrimitive?.boolean)
        assertEquals("https://", config["protocol"]?.jsonPrimitive?.content)
        val html = desktopGeetestHtml(config, "/11111111-1111-1111-1111-111111111111/result")
        assertTrue(html.contains("https://static.geetest.com/static/js/fullpage.0.0.0.js"))
        assertTrue(html.contains("\\u003c/script\\u003e")); assertFalse(html.contains("payload\":\"</script>"))
        assertFalse(html.contains("test-token")); assertFailsWith<IllegalArgumentException> { desktopGeetestHtml(config, "/evil'route") }
        assertFails { desktopGeetestConfig("{\"status\":\"failed\",\"data\":{}}", "gt", "challenge") }
    }

    @Test fun riskRefererAcceptsOnlyRealHttpsBilibiliAndHdLoginDoesNotAddWebReferer() {
        requireBiliRiskUrl("https://passport.bilibili.com/h5-app/passport/risk/verify?tmp_token=test-only")
        assertFailsWith<IllegalArgumentException> { requireBiliRiskUrl("http://passport.bilibili.com/") }
        assertFailsWith<IllegalArgumentException> { requireBiliRiskUrl("https://bilibili.com.attacker.invalid/") }
        val url = "https://passport.bilibili.com/x/safecenter/common/sms/send".toHttpUrl()
        assertNull(DesktopRepository.resolveReferer(url, null))
        assertEquals("https://passport.bilibili.com/risk", DesktopRepository.resolveReferer(url, "https://passport.bilibili.com/risk"))
        assertTrue(DesktopRepository.resolvePlatformUserAgent(url, null).contains("mobi_app/android_hd"))
        assertEquals("explicit-app-agent", DesktopRepository.resolvePlatformUserAgent(url, "explicit-app-agent"))
        assertTrue(DesktopRepository.resolvePlatformUserAgent("https://app.bilibili.com/x/v2/space/likearc".toHttpUrl(), null).contains("BiliDroid/8.43.0"))
    }

    @Test fun likedAggregatePreservesRealBvCidCountsAndPaging() {
        val data = LikedVideosData(count = 41, item = listOf(SpaceAggregateArchiveItem(aid = 170001, firstCid = 1234, title = "liked", author = "author", cover = "//image.example/test", play = 20)))
        val result = communityLikedPage(data, 1)
        assertEquals("BV17x411w7KC", result.items.single().bvid)
        assertEquals(1234L, result.items.single().preferredCid)
        assertEquals(20L, result.items.single().playCount); assertTrue(result.hasMore)
        assertFalse(communityLikedPage(data, 3).hasMore)
    }
}
