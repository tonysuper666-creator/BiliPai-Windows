package com.bilipai.desktop.settings
import com.android.purebilibili.core.store.*
import com.android.purebilibili.feature.search.*
import com.android.purebilibili.data.model.response.SearchType
import com.bilipai.desktop.plugins.*
import com.bilipai.desktop.ui.DesktopOriginalHomePreferences
import com.bilipai.desktop.ui.DesktopFavoriteNavigationTypes.BottomBarVisibilityMode
import com.bilipai.desktop.appearance.*
import com.android.purebilibili.core.ui.blur.BlurIntensity
import com.android.purebilibili.core.ui.transition.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.nio.file.*
import kotlinx.serialization.json.*

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.coroutines.CoroutineContext
import java.util.UUID
/** Actual original settings bodies + shared backing/Home/theme consumers. No account/network/window fixture. */
class DesktopFullNavigationSettingsTest {
    @TempDir lateinit var temporary:Path
    private fun verify(value:Boolean,message:String){check(value){message}}
    private class Fixture(output:Path,coroutineContext:CoroutineContext) {
        val output=output.also(Files::createDirectories)
        val root=output.resolve("global")
        val store=DesktopPluginStore(root)
        val context=DesktopPluginContext(store)
        val observerJob=SupervisorJob()
        val observer=CoroutineScope(coroutineContext+observerJob)
        lateinit var home:DesktopOriginalHomePreferences
        suspend fun await(condition:()->Boolean){withTimeout(4000){while(!condition())delay(5)}}
    }
    private suspend fun withFixture(body:suspend Fixture.()->Unit) {
        val fixture=Fixture(temporary.resolve(UUID.randomUUID().toString()),currentCoroutineContext())
        fixture.home=DesktopOriginalHomePreferences.create(fixture.store,fixture.observer,false){false}
        try { fixture.body() } finally { fixture.observerJob.cancelAndJoin() }
    }

    @Test fun individualDefaultsRetainOriginalAggregateDifferences():Unit=runBlocking {
        withFixture {
            verify(DesktopOriginalFullNavigationSettings.getBottomBarOrder(context).first()==listOf("HOME","DYNAMIC","HISTORY","LISTEN_VIDEO","PROFILE"),"Original bottom default")
            verify(DesktopOriginalFullNavigationSettings.getTopTabVisibleTabs(context).first().size==5,"Original top default")
            verify(DesktopOriginalFullNavigationSettings.getBottomBarLabelMode(context).first()==0,"Bottom label default")
            verify(DesktopOriginalFullNavigationSettings.getTopTabLabelMode(context).first()==2,"Top label read default")
            verify(!DesktopOriginalFullNavigationSettings.getTabletUseSidebar(context,false).first() && DesktopOriginalFullNavigationSettings.getTabletUseSidebar(context,true).first(),"Actual viewport default supplied, not persisted")

        }
    }

    @Test fun bottomOrderVisibilityAndLabelsReachRealHomeReader():Unit=runBlocking {
        withFixture {
            DesktopOriginalFullNavigationSettings.setBottomBarOrder(context,listOf("PROFILE","HOME","DYNAMIC","LISTEN_VIDEO","HISTORY"))
            DesktopOriginalFullNavigationSettings.setBottomBarVisibleTabs(context,setOf("PROFILE","HOME","DYNAMIC"))
            DesktopOriginalFullNavigationSettings.setBottomBarLabelMode(context,1)
            DesktopOriginalFullNavigationSettings.setBottomBarVisibilityMode(context,BottomBarVisibilityMode.SCROLL_HIDE)
            await{home.navigation.value.orderedVisibleTabIds==listOf("PROFILE","HOME","DYNAMIC") && home.homeSettings.value.bottomBarLabelMode==1}
            verify(home.navigation.value.bottomBarVisibilityMode==BottomBarVisibilityMode.SCROLL_HIDE,"Visibility consumer")

        }
    }

    @Test fun topLayoutOrderVisibleTabsAndActionsReachRealHomeReader():Unit=runBlocking {
        withFixture {
            DesktopOriginalFullNavigationSettings.setTopTabOrder(context,listOf("LIVE","RECOMMEND","POPULAR","FOLLOW","GAME"))
            DesktopOriginalFullNavigationSettings.setTopTabVisibleTabs(context,setOf("LIVE","POPULAR"))
            DesktopOriginalFullNavigationSettings.setHideTopTabs(context,true)
            DesktopOriginalFullNavigationSettings.setTopTabLabelMode(context,0)
            DesktopOriginalFullNavigationSettings.setHomeTopLayoutOrder(context,HomeTopLayoutOrder.TABS_THEN_SEARCH)
            DesktopOriginalFullNavigationSettings.setHomeTopRightAction(context,HomeTopRightAction.INBOX)
            DesktopOriginalFullNavigationSettings.setHomeHeaderCollapseMode(context,HomeHeaderCollapseMode.OFF)
            await{home.topTabs.value.orderIds.filter{it in home.topTabs.value.visibleIds}==listOf("LIVE","POPULAR") && home.homeSettings.value.hideTopTabs && home.homeSettings.value.topTabLabelMode==0}
            verify(home.homeSettings.value.homeTopLayoutOrder==HomeTopLayoutOrder.TABS_THEN_SEARCH && home.homeSettings.value.homeTopRightAction==HomeTopRightAction.INBOX,"Actual Home actions/layout values")
            verify(!home.homeSettings.value.isHeaderCollapseEnabled,"Legacy collapse key follows canonical setter")

        }
    }

    @Test fun twoFacadeConcurrentLabelEditsPreserveOtherKeys():Unit=runBlocking {
        withFixture {
            store.update("settings",mapOf("foreign" to JsonPrimitive("keep")))
            val other=DesktopPluginContext(DesktopPluginStore(root))
            coroutineScope {
                launch(Dispatchers.IO){DesktopOriginalFullNavigationSettings.setBottomBarItemLabel(context,"home","  我 的  首 页  ")}
                launch(Dispatchers.IO){DesktopOriginalFullNavigationSettings.setBottomBarItemLabel(other,"watch_later","稍后,=+看")}
            }
            await{home.navigation.value.bottomBarItemLabels.size==2}
            verify(home.navigation.value.bottomBarItemLabels["HOME"]=="我 的 首 页","Original whitespace normalization")
            verify(home.navigation.value.bottomBarItemLabels["WATCHLATER"]=="稍后,=+看","Exact UTF8 delimiter roundtrip")
            verify(store.preferences("settings")["foreign"]?.jsonPrimitive?.content=="keep","Unrelated setting retained")
            DesktopOriginalFullNavigationSettings.setBottomBarItemLabel(other,"home","")
            await{home.navigation.value.bottomBarItemLabels==mapOf("WATCHLATER" to "稍后,=+看")}

        }
    }

    @Test fun twoFacadeConcurrentColorsReachRootNavigation():Unit=runBlocking {
        withFixture {
            val other=DesktopPluginContext(DesktopPluginStore(root))
            coroutineScope {
                launch(Dispatchers.IO){DesktopOriginalFullNavigationSettings.setBottomBarItemColor(context,"home",3)}
                launch(Dispatchers.IO){DesktopOriginalFullNavigationSettings.setBottomBarItemColor(other,"dynamic",4)}
            }
            DesktopOriginalFullNavigationSettings.setTabletUseSidebar(context,true)
            DesktopOriginalFullNavigationSettings.setSidebarAccountSwitcherEnabled(context,false)
            await{home.navigation.value.bottomBarItemColors["HOME"]==3 && home.navigation.value.bottomBarItemColors["DYNAMIC"]==4 && home.navigation.value.tabletUseSidebar && !home.navigation.value.sidebarAccountSwitcherEnabled}

        }
    }

    @Test fun searchTabOrderUsesOriginalUnknownAndDuplicatePolicy():Unit=runBlocking {
        withFixture {
            DesktopOriginalFullNavigationSettings.setSearchFilterTabOrder(context,listOf("article","unknown","video","article"))
            val tabs=resolveSearchFilterTabs(DesktopOriginalFullNavigationSettings.getSearchFilterTabOrder(context).first())
            verify(tabs.take(2)==listOf(SearchType.ARTICLE,SearchType.VIDEO) && tabs.size==SearchType.entries.size,"Exact original search ordering policy")

        }
    }

    @Test fun labelsPersistUsingOriginalEncoding():Unit=runBlocking {
        withFixture {
            DesktopOriginalFullNavigationSettings.setBottomBarItemLabel(context,"WATCHLATER","稍后,=+看")

            val disk=Json.parseToJsonElement(Files.readString(root.resolve("plugin-settings.json"))).jsonObject["settings"]!!.jsonObject
            verify(disk["bottom_bar_item_labels"]!!.jsonPrimitive.content.contains("WATCHLATER="),"Original encoded label value")
            Files.writeString(output.resolve("settings-disk.json"),disk.toString())

        }
    }

    @Test fun navigationAnimationStyleAndProgressReachRealHostModel():Unit=runBlocking {
        withFixture {
            DesktopOriginalFullNavigationSettings.setPredictiveBackEnabled(context,true)
            DesktopOriginalFullNavigationSettings.setPredictiveBackAnimationStyle(context,"scale")
            DesktopOriginalFullNavigationSettings.setPredictiveBackExitDirection(context,"always_left")
            DesktopOriginalFullNavigationSettings.setMiuixPredictiveBackMaxProgressPercent(context,120)
            DesktopOriginalFullNavigationSettings.setMiuixTransitionBlurEnabled(context,false)
            DesktopOriginalFullNavigationSettings.setVideoSharedReturnGestureFollowEnabled(context,false)
            await{home.navigation.value.predictiveBackAnimationStyle=="scale" && !home.navigation.value.miuixTransitionBlurEnabled && !home.navigation.value.videoSharedReturnGestureFollowEnabled}
            verify(home.navigation.value.predictiveBackExitDirection=="always_left" && home.navigation.value.miuixPredictiveBackMaxProgressPercent==100,"Original host values and clamp")

        }
    }

    @Test fun customTransitionDurationUsesOriginalNormalization():Unit=runBlocking {
        withFixture {
            DesktopOriginalFullNavigationSettings.setVideoSharedTransitionSpeed(context,VideoSharedTransitionSpeed.CUSTOM)
            DesktopOriginalFullNavigationSettings.setVideoSharedTransitionCustomDurationMillis(context,Int.MAX_VALUE)
            await{home.homeSettings.value.videoSharedTransitionSpeed==VideoSharedTransitionSpeed.CUSTOM && home.homeSettings.value.videoSharedTransitionCustomDurationMillis==VIDEO_SHARED_TRANSITION_CUSTOM_MAX_MILLIS}

        }
    }

    @Test fun realHolderAndNavDisplayFlagsUseExactOriginalKeys():Unit=runBlocking {
        withFixture {
            DesktopOriginalFullNavigationSettings.setLiveSurfaceCardTransitionEnabled(context,true)
            DesktopOriginalFullNavigationSettings.setRelatedVideoTransitionEnabled(context,false)
            DesktopOriginalFullNavigationSettings.setFullScreenSwipeBackEnabled(context,true)
            verify(DesktopOriginalFullNavigationSettings.getLiveSurfaceCardTransitionEnabled(context).first(),"Real player holder key")
            verify(!DesktopOriginalFullNavigationSettings.getRelatedVideoTransitionEnabled(context).first(),"Real related source policy key")
            verify(DesktopOriginalFullNavigationSettings.getFullScreenSwipeBackEnabled(context).first(),"Real NavDisplay key")

        }
    }

    @Test fun progressiveAndHeaderExclusionReachSharedThemeConfig():Unit=runBlocking {
        withFixture {
            val prefs=DesktopThemePrefs(store)
            DesktopOriginalFullNavigationSettings.setProgressiveTopBlurEnabled(context,true)
            var config=buildDesktopAppThemeConfig(prefs.initialSettings())
            verify(config.progressiveTopBlurEnabled && !config.headerBlurEnabled,"Progressive enables and header disables")
            DesktopOriginalFullNavigationSettings.setHeaderBlurEnabled(context,true)
            DesktopOriginalFullNavigationSettings.setProgressiveTopFadeEnabled(context,false)
            DesktopOriginalFullNavigationSettings.setBottomBarBlurEnabled(context,true)
            DesktopOriginalFullNavigationSettings.setBlurIntensity(context,BlurIntensity.APPLE_DOCK)
            config=buildDesktopAppThemeConfig(prefs.initialSettings())
            verify(!config.progressiveTopBlurEnabled && config.headerBlurEnabled && !config.progressiveTopFadeEnabled && config.bottomBarBlurEnabled && config.blurIntensity==BlurIntensity.APPLE_DOCK,"Original exclusive setter and live config projection")

        }
    }

    @Test fun skeletonUsesExistingGlobalAuthority():Unit=runBlocking {
        withFixture {
            SkeletonSettingsStore.setBreathingEnabled(context,false)
            verify(!SkeletonSettingsStore.breathingEnabled(DesktopPluginContext(DesktopPluginStore(root))).first(),"Existing original skeleton same global backing")

        }
    }

    @Test fun replacementFailureDoesNotPublishChanges():Unit=runBlocking {
        withFixture {
            val failedRoot=output.resolve("failed");val failed=DesktopPluginStore(failedRoot);val c=DesktopPluginContext(failed)
            Files.createDirectories(failedRoot.resolve("plugin-settings.json"));Files.writeString(failedRoot.resolve("plugin-settings.json/owned"),"block")
            val before=failed.snapshot("settings").value
            verify(runCatching{DesktopOriginalFullNavigationSettings.setBottomBarItemLabel(c,"HOME","never")}.isFailure,"Expected real disk replacement failure")
            verify(failed.snapshot("settings").value===before,"No state publication after failed disk replacement")

        }
    }

    @Test fun oldRestoreGenerationCannotOverwriteFreshDisk():Unit=runBlocking {
        withFixture {
            store.freezeWrites()
            verify(runCatching{DesktopOriginalFullNavigationSettings.setBottomBarItemLabel(context,"HOME","stale")}.isFailure,"Old backing rejects")
            val disk=Json.parseToJsonElement(Files.readString(root.resolve("plugin-settings.json"))).jsonObject
            Files.writeString(root.resolve("plugin-settings.json"),JsonObject(disk.toMutableMap().apply{put("settings",buildJsonObject{put("bottom_bar_label_mode",2)})}).toString())
            val fresh=DesktopPluginContext(DesktopPluginStore(root))
            verify(DesktopOriginalFullNavigationSettings.getBottomBarLabelMode(fresh).first()==2,"New restored generation")
            DesktopOriginalFullNavigationSettings.setBottomBarItemLabel(fresh,"HOME","new")
            verify(!Files.readString(root.resolve("plugin-settings.json")).contains("stale"),"Retired writer cannot overwrite restored document")

        }
    }
}
