package com.bilipai.desktop.settings

import com.android.purebilibili.core.store.updatedSettingsSearchHistory
import com.android.purebilibili.feature.settings.*
import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.*

class DesktopSettingsSearchTest {
    private fun context() = DesktopPluginContext(DesktopPluginStore(Files.createTempDirectory("bilipai-settings-search-fixture-")))
    @Test fun exactOriginalSynonymsPinyinAndSpecificFocus(): Unit = runBlocking {
        assertTrue(resolveSettingsSearchResults("   ").isEmpty())
        assertTrue(resolveSettingsSearchResults("字幕", 0).isEmpty())
        val codec=resolveSettingsSearchResults(" HEVC ").first()
        assertEquals(SettingsSearchTarget.PLAYBACK,codec.target);assertEquals(SettingsSearchFocusIds.PLAYBACK_DECODER,codec.focusId)
        val audio=resolveSettingsSearchResults("Hi - Res").first()
        assertEquals(SettingsSearchTarget.PLAYBACK,audio.target);assertEquals(SettingsSearchFocusIds.PLAYBACK_NETWORK,audio.focusId)
        for(query in listOf("倍速","beisu","bs")) assertTrue(resolveSettingsSearchResults(query).any{it.target==SettingsSearchTarget.PLAYBACK})
        val sentence=resolveSettingsSearchResults("我想关闭自动启用字幕").first()
        assertEquals(SettingsSearchFocusIds.PLAYBACK_INTERACTION,sentence.focusId)
        val limited=resolveSettingsSearchResults("设置",3);assertTrue(limited.size<=3);assertEquals(limited.size,limited.distinctBy{it.target}.size)
        assertTrue(resolveSettingsSearchResults("不存在的zqwxj设置").isEmpty())
    }
    @Test fun originalHistoryDedupCapPrivacyAndSharedFacadeAtomicity(): Unit = runBlocking {
        val context=context();var privacy=false
        val a=DesktopSettingsSearchRepository(context){privacy}
        val b=DesktopSettingsSearchRepository(DesktopPluginContext(DesktopPluginStore(context.store.root))){privacy}
        a.record("  HEVC  ");b.record("hevc");assertEquals(listOf("hevc"),a.history.first())
        privacy=true;a.record("secret fixture");assertEquals(listOf("hevc"),b.history.first())
        b.delete("HEVC");assertEquals(listOf("hevc"),a.history.first())
        b.delete("hevc");assertTrue(a.history.first().isEmpty());privacy=false
        coroutineScope { (0 until 12).map { i -> async(Dispatchers.Default){(if(i%2==0)a else b).record("query-$i")} }.awaitAll() }
        assertEquals((0 until 12).map{"query-$it"}.toSet(),a.history.first().toSet())
        for(i in 12 until 30)a.record("query-$i")
        assertEquals(20,a.history.first().size);assertEquals("query-29",a.history.first().first())
        b.clear();assertTrue(a.history.first().isEmpty())
        assertEquals(listOf("Keep"),updatedSettingsSearchHistory(listOf("Keep")," "))
    }
    @Test fun originalCategoriesNavigationAndFocusTokenOwnership(): Unit = runBlocking {
        assertEquals(8,resolveSettingsRootCategoryOrder().size)
        @Suppress("DEPRECATION") val old=SettingsRootCategory.CONTENT_PLAYBACK
        assertEquals(SettingsRootCategory.PLAYBACK_QUALITY,canonicalSettingsRootCategory(old))
        SettingsSearchTarget.entries.forEach { assertNotNull(resolveSettingsRootCategoryForSearchTarget(it)) }
        assertEquals(4,resolveSettingsNavDepth("external_media?source=fixture"))
        assertEquals("js_plugin",resolveSettingsNavParentRoute("external_media?source=fixture"))
        assertEquals(SettingsRootCategory.PLUGINS_EXTENSIONS,resolveSettingsRootCategoryForRoute("js_plugin?fixture=1"))
        assertTrue(isSettingsNavHierarchyTransition("settings_search","playback_settings"))
        assertEquals(com.android.purebilibili.navigation3.BiliPaiNavKey.SettingsCategory(SettingsRootCategory.SYSTEM_ABOUT),resolveSettingsSearchNavigation(SettingsSearchResult(SettingsSearchTarget.CHECK_UPDATE,"","","")))
        val c=DesktopSettingsSearchController(DesktopSettingsSearchRepository(context()){false})
        var category:SettingsRootCategory?=null;var destination:SettingsSearchResult?=null
        c.activate(SettingsSearchResult(SettingsSearchTarget.PRIVACY_PERMISSION,"","",""),{category=it},{destination=it})
        assertEquals(SettingsRootCategory.PRIVACY_PERMISSION,category);assertNull(destination)
        val codec=resolveSettingsSearchResults("HEVC").first();c.activate(codec,{category=it},{destination=it})
        assertEquals(codec,destination);val oldToken=assertNotNull(SettingsSearchFocusController.request.value).token
        SettingsSearchFocusController.submit(SettingsSearchTarget.PLAYBACK,SettingsSearchFocusIds.PLAYBACK_INTERACTION)
        val newRequest=assertNotNull(SettingsSearchFocusController.request.value)
        SettingsSearchFocusController.clear(oldToken);assertEquals(newRequest,SettingsSearchFocusController.request.value)
        SettingsSearchFocusController.clear(newRequest.token);assertNull(SettingsSearchFocusController.request.value)
    }
    @Test fun originalRoleVectorsAreConcreteAndKeepGlyphSizes(): Unit = runBlocking {
        val resources=mutableSetOf<String>()
        SettingsIconRole.entries.forEach { role ->
            val name=resolveSettingsMaterialSymbolResource(role);resources+=name
            val vector=DesktopSettingsVectors.load(name)
            assertEquals(name,vector.name);assertTrue(vector.root.size>0);assertTrue(vector.viewportWidth>0)
        }
        assertTrue(resources.size>150)
        assertNotEquals(resolveSettingsMaterialSymbolResource(SettingsIconRole.HARDWARE_DECODER),resolveSettingsMaterialSymbolResource(SettingsIconRole.SUBTITLE))
        assertEquals(19,resolveSettingsSemanticIconSizeDp(SettingsIconRole.NAVIGATION,com.android.purebilibili.core.ui.AppSemanticIconFamily.MIUIX))
        assertEquals(20,resolveSettingsSemanticIconSizeDp(SettingsIconRole.NAVIGATION,com.android.purebilibili.core.ui.AppSemanticIconFamily.MATERIAL))
        assertFailsWith<IllegalStateException>{DesktopSettingsVectors.load("invented-icon")}
    }
    @Test fun cancellationAndFrozenBackingDoNotBecomeSilentSuccess(): Unit = runBlocking {
        val context=context();val repo=DesktopSettingsSearchRepository(context){false};val c=DesktopSettingsSearchController(repo)
        repo.record("persisted");context.store.freezeWrites();c.record("must not persist")
        assertNotNull(c.error.value);assertEquals(listOf("persisted"),repo.history.first())
        val cancelled=DesktopSettingsSearchController(DesktopSettingsSearchRepository(context()){throw CancellationException("cancelled fixture")})
        try {cancelled.record("anything");fail("Cancellation swallowed")}catch(e:CancellationException){assertEquals("cancelled fixture",e.message)}
        assertNull(cancelled.error.value)
    }
}
