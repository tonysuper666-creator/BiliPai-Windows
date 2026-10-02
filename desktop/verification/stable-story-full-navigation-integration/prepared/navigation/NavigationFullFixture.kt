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

private fun verify(value:Boolean,message:String){check(value){message}}
fun main(args:Array<String>):Unit=runBlocking {
    val output=Path.of(args[0]).resolve("disk");Files.createDirectories(output)
    val passed=mutableListOf<String>()
    suspend fun test(name:String,body:suspend()->Unit){body();passed+=name}
    val root=output.resolve("global");val store=DesktopPluginStore(root);val context=DesktopPluginContext(store)
    val observerJob=SupervisorJob();val observer=CoroutineScope(coroutineContext+observerJob)
    val home=DesktopOriginalHomePreferences.create(store,observer,false){false}
    suspend fun await(condition:()->Boolean){withTimeout(4000){while(!condition())delay(5)}}
    try {
        test("original defaults and display constraints") {
            verify(DesktopOriginalFullNavigationSettings.getBottomBarOrder(context).first()==listOf("HOME","DYNAMIC","HISTORY","LISTEN_VIDEO","PROFILE"),"Original bottom default")
            verify(DesktopOriginalFullNavigationSettings.getTopTabVisibleTabs(context).first().size==5,"Original top default")
            verify(DesktopOriginalFullNavigationSettings.getBottomBarLabelMode(context).first()==0,"Bottom label default")
            verify(DesktopOriginalFullNavigationSettings.getTopTabLabelMode(context).first()==2,"Top label read default")
            verify(!DesktopOriginalFullNavigationSettings.getTabletUseSidebar(context,false).first() && DesktopOriginalFullNavigationSettings.getTabletUseSidebar(context,true).first(),"Actual viewport default supplied, not persisted")
        }
        test("bottom order visibility and label mode reach actual Home Root reader") {
            DesktopOriginalFullNavigationSettings.setBottomBarOrder(context,listOf("PROFILE","HOME","DYNAMIC","LISTEN_VIDEO","HISTORY"))
            DesktopOriginalFullNavigationSettings.setBottomBarVisibleTabs(context,setOf("PROFILE","HOME","DYNAMIC"))
            DesktopOriginalFullNavigationSettings.setBottomBarLabelMode(context,1)
            DesktopOriginalFullNavigationSettings.setBottomBarVisibilityMode(context,BottomBarVisibilityMode.SCROLL_HIDE)
            await{home.navigation.value.orderedVisibleTabIds==listOf("PROFILE","HOME","DYNAMIC") && home.homeSettings.value.bottomBarLabelMode==1}
            verify(home.navigation.value.bottomBarVisibilityMode==BottomBarVisibilityMode.SCROLL_HIDE,"Visibility consumer")
        }
        test("top order hide label and layout reach actual Home Root reader") {
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
        test("concurrent two facade label edits preserve both normalized labels and foreign values") {
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
        test("concurrent colors and sidebar flags reach original Root navigation") {
            val other=DesktopPluginContext(DesktopPluginStore(root))
            coroutineScope {
                launch(Dispatchers.IO){DesktopOriginalFullNavigationSettings.setBottomBarItemColor(context,"home",3)}
                launch(Dispatchers.IO){DesktopOriginalFullNavigationSettings.setBottomBarItemColor(other,"dynamic",4)}
            }
            DesktopOriginalFullNavigationSettings.setTabletUseSidebar(context,true)
            DesktopOriginalFullNavigationSettings.setSidebarAccountSwitcherEnabled(context,false)
            await{home.navigation.value.bottomBarItemColors["HOME"]==3 && home.navigation.value.bottomBarItemColors["DYNAMIC"]==4 && home.navigation.value.tabletUseSidebar && !home.navigation.value.sidebarAccountSwitcherEnabled}
        }
        test("search order original policy supports malformed unknown duplicate and real disk") {
            DesktopOriginalFullNavigationSettings.setSearchFilterTabOrder(context,listOf("article","unknown","video","article"))
            val tabs=resolveSearchFilterTabs(DesktopOriginalFullNavigationSettings.getSearchFilterTabOrder(context).first())
            verify(tabs.take(2)==listOf(SearchType.ARTICLE,SearchType.VIDEO) && tabs.size==SearchType.entries.size,"Exact original search ordering policy")
        }
        test("canonical disk retains original labels") {
            val disk=Json.parseToJsonElement(Files.readString(root.resolve("plugin-settings.json"))).jsonObject["settings"]!!.jsonObject
            verify(disk["bottom_bar_item_labels"]!!.jsonPrimitive.content.contains("WATCHLATER="),"Original encoded label value")
            Files.writeString(output.resolve("settings-disk.json"),disk.toString())
        }
        test("navigation animation style direction and bounded preview reach original Root reader") {
            DesktopOriginalFullNavigationSettings.setPredictiveBackEnabled(context,true)
            DesktopOriginalFullNavigationSettings.setPredictiveBackAnimationStyle(context,"scale")
            DesktopOriginalFullNavigationSettings.setPredictiveBackExitDirection(context,"always_left")
            DesktopOriginalFullNavigationSettings.setMiuixPredictiveBackMaxProgressPercent(context,120)
            DesktopOriginalFullNavigationSettings.setMiuixTransitionBlurEnabled(context,false)
            DesktopOriginalFullNavigationSettings.setVideoSharedReturnGestureFollowEnabled(context,false)
            await{home.navigation.value.predictiveBackAnimationStyle=="scale" && !home.navigation.value.miuixTransitionBlurEnabled && !home.navigation.value.videoSharedReturnGestureFollowEnabled}
            verify(home.navigation.value.predictiveBackExitDirection=="always_left" && home.navigation.value.miuixPredictiveBackMaxProgressPercent==100,"Original host values and clamp")
        }
        test("custom transition time is normalized by original policy and shared Home reader") {
            DesktopOriginalFullNavigationSettings.setVideoSharedTransitionSpeed(context,VideoSharedTransitionSpeed.CUSTOM)
            DesktopOriginalFullNavigationSettings.setVideoSharedTransitionCustomDurationMillis(context,Int.MAX_VALUE)
            await{home.homeSettings.value.videoSharedTransitionSpeed==VideoSharedTransitionSpeed.CUSTOM && home.homeSettings.value.videoSharedTransitionCustomDurationMillis==VIDEO_SHARED_TRANSITION_CUSTOM_MAX_MILLIS}
        }
        test("real live related and full screen host preferences persist exact upstream fields") {
            DesktopOriginalFullNavigationSettings.setLiveSurfaceCardTransitionEnabled(context,true)
            DesktopOriginalFullNavigationSettings.setRelatedVideoTransitionEnabled(context,false)
            DesktopOriginalFullNavigationSettings.setFullScreenSwipeBackEnabled(context,true)
            verify(DesktopOriginalFullNavigationSettings.getLiveSurfaceCardTransitionEnabled(context).first(),"Real player holder key")
            verify(!DesktopOriginalFullNavigationSettings.getRelatedVideoTransitionEnabled(context).first(),"Real related source policy key")
            verify(DesktopOriginalFullNavigationSettings.getFullScreenSwipeBackEnabled(context).first(),"Real NavDisplay key")
        }
        test("progressive header exclusion and theme projection have real consumers") {
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
        test("existing skeleton authority observes same global key") {
            SkeletonSettingsStore.setBreathingEnabled(context,false)
            verify(!SkeletonSettingsStore.breathingEnabled(DesktopPluginContext(DesktopPluginStore(root))).first(),"Existing original skeleton same global backing")
        }
        test("failed atomic replacement does not publish read dependent changes") {
            val failedRoot=output.resolve("failed");val failed=DesktopPluginStore(failedRoot);val c=DesktopPluginContext(failed)
            Files.createDirectories(failedRoot.resolve("plugin-settings.json"));Files.writeString(failedRoot.resolve("plugin-settings.json/owned"),"block")
            val before=failed.snapshot("settings").value
            verify(runCatching{DesktopOriginalFullNavigationSettings.setBottomBarItemLabel(c,"HOME","never")}.isFailure,"Expected real disk replacement failure")
            verify(failed.snapshot("settings").value===before,"No state publication after failed disk replacement")
        }
        test("restore fence rejects old writer while new same path generation reads restored state") {
            store.freezeWrites()
            verify(runCatching{DesktopOriginalFullNavigationSettings.setBottomBarItemLabel(context,"HOME","stale")}.isFailure,"Old backing rejects")
            val disk=Json.parseToJsonElement(Files.readString(root.resolve("plugin-settings.json"))).jsonObject
            Files.writeString(root.resolve("plugin-settings.json"),JsonObject(disk.toMutableMap().apply{put("settings",buildJsonObject{put("bottom_bar_label_mode",2)})}).toString())
            val fresh=DesktopPluginContext(DesktopPluginStore(root))
            verify(DesktopOriginalFullNavigationSettings.getBottomBarLabelMode(fresh).first()==2,"New restored generation")
            DesktopOriginalFullNavigationSettings.setBottomBarItemLabel(fresh,"HOME","new")
            verify(!Files.readString(root.resolve("plugin-settings.json")).contains("stale"),"Retired writer cannot overwrite restored document")
        }
        Files.writeString(output.resolve("result.json"),buildJsonObject{put("passed",true);put("methods",JsonArray(passed.map(::JsonPrimitive)));put("wholeRootEffectVerified",false)}.toString())
        println("${passed.size} focused original navigation/disk methods passed")
    }finally{observerJob.cancelAndJoin()}
    Unit
}
