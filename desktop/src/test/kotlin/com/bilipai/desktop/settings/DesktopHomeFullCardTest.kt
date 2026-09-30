package com.bilipai.desktop.settings

import com.android.purebilibili.core.store.*
import com.android.purebilibili.feature.home.*
import com.android.purebilibili.feature.home.components.cards.*
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.core.network.BilibiliApi
import com.bilipai.desktop.data.*
import com.bilipai.desktop.palette.DesktopPalette
import com.bilipai.desktop.plugins.*
import com.bilipai.desktop.ui.encodeDesktopVideoRouteCover
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.nio.file.Files
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import androidx.compose.ui.graphics.toArgb
import org.jetbrains.skia.Bitmap
import kotlin.test.*

class DesktopHomeFullCardTest {
    private fun context(document:String?=null):DesktopPluginContext {
        val root=Files.createTempDirectory("bp-hfc-")
        if(document!=null)Files.writeString(root.resolve("plugin-settings.json"),document)
        return DesktopPluginContext(DesktopPluginStore(root))
    }
    @Test fun originalPersistedDefaultsUseReadMapperInsteadOfCardConstructorDefaults() {
        val s=DesktopHomeCardVisualPreferences(context()).initialSettings()
        assertFalse(s.cardAnimationEnabled);assertFalse(s.showHomeUpBadges);assertFalse(s.showHomeUpAvatars)
        assertFalse(s.compactVideoStatsOnCover);assertTrue(s.showHomePublishTime);assertFalse(s.showFullVideoCardContent)
        assertFalse(s.videoCardLongPressActionEnabled);assertFalse(s.homeCardDynamicTintEnabled);assertFalse(s.showOnlineCount)
        assertEquals(HomeDurationStyle.OUTSIDE_COVER,s.homeDurationStyle)
    }
    @Test fun durationLegacyMigrationAndExplicitChoiceSurviveFreshStore():Unit=runBlocking {
        val c=context("""{"settings":{"home_video_duration_badges_visible":false}}""")
        val p=DesktopHomeCardVisualPreferences(c);assertEquals(HomeDurationStyle.HIDDEN,p.initialSettings().homeDurationStyle)
        p.setHomeDurationStyle(HomeDurationStyle.OVERLAY_TEXT_ONLY)
        val cold=DesktopHomeCardVisualPreferences(DesktopPluginContext(DesktopPluginStore(c.store.root)))
        assertEquals(HomeDurationStyle.OVERLAY_TEXT_ONLY,cold.initialSettings().homeDurationStyle)
        val disk=Json.parseToJsonElement(Files.readString(c.store.root.resolve("plugin-settings.json"))).jsonObject["settings"]!!.jsonObject
        assertEquals(true,disk["home_video_duration_badges_visible"]!!.jsonPrimitive.boolean)
    }
    @Test fun tintMigrationKeepsLegacyFrostSeparateFromNewTint():Unit=runBlocking {
        val p=DesktopHomeCardVisualPreferences(context("""{"settings":{"home_card_dynamic_tint_enabled":true}}"""))
        p.setHomeCardDynamicTintEnabled(false)
        assertTrue(p.initialSettings().homeCardFrostedGlassEnabled);assertFalse(p.initialSettings().homeCardDynamicTintEnabled)
        p.setHomeCardDynamicTintEnabled(true);assertTrue(p.initialSettings().homeCardFrostedGlassEnabled)
    }
    @Test fun crossFacadeDependentSetterReadsAfterPriorTransactionCommit():Unit=runBlocking {
        val c=context();val second=DesktopHomeCardVisualPreferences(DesktopPluginContext(DesktopPluginStore(c.store.root)))
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val writer=async(Dispatchers.IO){c.store.updateFromSnapshot("settings") {
            entered.countDown();assertTrue(release.await(3,TimeUnit.SECONDS));mapOf("home_card_dynamic_tint_enabled" to JsonPrimitive(true))
        }}
        assertTrue(entered.await(3,TimeUnit.SECONDS))
        val setter=async(Dispatchers.IO){second.setHomeCardDynamicTintEnabled(false)}
        delay(50);assertFalse(setter.isCompleted)
        release.countDown();writer.await();setter.await()
        assertTrue(second.initialSettings().homeCardFrostedGlassEnabled);assertFalse(second.initialSettings().homeCardDynamicTintEnabled)
    }
    @Test fun diskFailurePublishesNothingAndAllowsSuccessfulRetry():Unit=runBlocking {
        val c=context();val p=DesktopHomeCardVisualPreferences(c);val before=c.store.snapshot("settings").value
        Files.createDirectory(c.store.root.resolve("plugin-settings.json"))
        assertFails{p.setHomeUpBadgesVisible(true)}
        assertSame(before,c.store.snapshot("settings").value);assertFalse(p.initialSettings().showHomeUpBadges)
        Files.delete(c.store.root.resolve("plugin-settings.json"));p.setHomeUpBadgesVisible(true)
        assertTrue(p.initialSettings().showHomeUpBadges)
    }
    @Test fun frozenGenerationRejectsBeforeRunningTransactionAndFreshGenerationCanWrite():Unit=runBlocking {
        val c=context();val stale=DesktopHomeCardVisualPreferences(c);c.store.freezeWrites()
        var called=false
        assertFailsWith<IllegalStateException>{c.store.updateFromSnapshot("settings"){called=true;emptyMap()}}
        assertFalse(called);assertFailsWith<IllegalStateException>{stale.setHomeUpBadgesVisible(true)}
        val fresh=DesktopHomeCardVisualPreferences(DesktopPluginContext(DesktopPluginStore(c.store.root)))
        fresh.setHomeUpBadgesVisible(true);assertTrue(fresh.initialSettings().showHomeUpBadges)
        assertFalse(stale.initialSettings().showHomeUpBadges)
    }
    @Test fun serverItemRetainsRealMetadataWhileNavigationKeepsExactIdentity() {
        val item=Json{ignoreUnknownKeys=true}.decodeFromString<VideoItem>("""{"bvid":"BVFixtureOriginal","aid":77,"cid":501,"title":"真实字段 fixture","pic":"https://fixture.invalid/cover","duration":240,"owner":{"mid":99,"name":"UP","face":"https://fixture.invalid/avatar"},"stat":{"view":234567,"danmaku":53,"reply":27,"favorite":17,"like":765},"isFollowed":true,"rights":{"pay":1},"pubdate":1700000000,"progress":65,"view_at":1700000001}""")
        val card=discoveryVideoCard(item)
        assertEquals(501L,card.preferredCid);assertEquals(99L,card.authorMid);assertEquals(234567L,card.playCount)
        assertEquals(27,item.stat.reply);assertEquals(53,item.stat.danmaku);assertTrue(item.isFollowed)
        assertNotNull(resolveVideoPremiumBadgeLabel(item.rights));assertEquals(77L,resolveWatchLaterAid(item))
        assertEquals(88L,resolveWatchLaterAid(item.copy(aid=0,id=88)))
    }
    @Test fun onlineMetadataCacheRespectsTtlAndCancellationDoesNotPoisonRetry():Unit=runBlocking {
        var now=0L;var calls=0;var cancelled=true
        val store=VideoCardOnlineCountStore({_,_->calls++;if(cancelled)throw CancellationException("fixture");" 123 "},{now},100)
        assertFailsWith<CancellationException>{store.refreshIfNeeded("BVfixture",501)}
        cancelled=false;store.refreshIfNeeded("BVfixture",501);store.refreshIfNeeded("BVfixture",501)
        assertEquals(2,calls);assertEquals("123",store.observe("BVfixture",501).value)
        now=101;store.refreshIfNeeded("BVfixture",501);assertEquals(3,calls)
        assertFalse(shouldLoadVideoCardOnlineCount(false,"BVfixture",501));assertFalse(shouldLoadVideoCardOnlineCount(true,"BVfixture",0))
    }
    @Test fun originalQuantizerPopulationAndUsefulSwatchSelectionUseRealSkiaPixels() {
        val palette=DesktopPalette.quantize(IntArray(100){if(it<75)0xffc83228.toInt() else 0xff2876c8.toInt()},16)
        assertEquals(100,palette.sumOf{it.population});assertEquals(0xffc83028.toInt(),VideoCardCoverColorStore.resolveRepresentativeSwatch(palette)!!.rgb)
        Bitmap().use{bitmap->assertTrue(bitmap.allocN32Pixels(640,360));bitmap.erase(0xffc83228.toInt())
            assertEquals(0xffc83028.toInt(),VideoCardCoverColorStore.extractRepresentativeColor(bitmap)!!.toArgb())}
        assertEquals(0xff808080.toInt(),VideoCardCoverColorStore.resolveRepresentativeSwatch(listOf(DesktopPalette.Swatch(0xff808080.toInt(),10)))!!.rgb)
    }
    @Test fun utf8RouteBindingKeepsOriginalUriUnreservedCharactersAndEncodesSecretsAsQueryData() {
        assertEquals("https%3A%2F%2Ffixture.invalid%2F%E5%9B%BE%3Fx%3D1%26y%3Da%20b",encodeDesktopVideoRouteCover("https://fixture.invalid/图?x=1&y=a b"))
        assertEquals("_-!.~'()*",encodeDesktopVideoRouteCover("_-!.~'()*"))
    }
    @Test fun actualOriginalRetrofitPostUsesExactFieldsAndHttp503NeverResubmits():Unit=runBlocking {
        val count=AtomicInteger();var body="";val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/x/v2/history/toview/add"){exchange->
            count.incrementAndGet();assertEquals("POST",exchange.requestMethod);body=exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
            exchange.responseHeaders.add("Retry-After","0");exchange.sendResponseHeaders(503,-1);exchange.close()
        };server.start()
        try {
            val client=desktopHomeCardOwnedClient(OkHttpClient(),7,{7},true)
            val api=Retrofit.Builder().baseUrl("http://127.0.0.1:${server.address.port}/").client(client)
                .addConverterFactory(Json.asConverterFactory("application/json".toMediaType())).build().create(BilibiliApi::class.java)
            assertFails{api.addToWatchLater(77,"synthetic-csrf")}
            assertEquals(1,count.get());assertEquals("aid=77&csrf=synthetic-csrf",body)
        }finally{server.stop(0)}
    }
    @Test fun originatingEpochRejectsBeforeSocketAndDuringResponseWithoutPublishing() {
        val epoch=AtomicLong(8);val sends=AtomicInteger();val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/fixture"){exchange->sends.incrementAndGet();epoch.set(9);exchange.sendResponseHeaders(200,-1);exchange.close()};server.start()
        try {
            val client=desktopHomeCardOwnedClient(OkHttpClient(),7,{epoch.get()},false)
            val request=Request.Builder().url("http://127.0.0.1:${server.address.port}/fixture").build()
            assertFailsWith<BiliApiException>{client.newCall(request).execute()};assertEquals(0,sends.get())
            epoch.set(7);assertFailsWith<BiliApiException>{client.newCall(request).execute()};assertEquals(1,sends.get())
        }finally{server.stop(0)}
    }
}
