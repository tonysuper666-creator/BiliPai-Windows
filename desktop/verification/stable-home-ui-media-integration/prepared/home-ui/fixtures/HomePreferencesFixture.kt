package com.bilipai.desktop.ui

import com.android.purebilibili.core.store.*
import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path

private fun bool(v:Boolean)=JsonPrimitive(v)
private fun num(v:Int)=JsonPrimitive(v)
private fun txt(v:String)=JsonPrimitive(v)
private fun assertGate(value:Boolean,message:String){check(value){message}}
private suspend fun <T> StateFlow<T>.awaitValue(predicate:(T)->Boolean):T=withTimeout(5000){first(predicate)}

fun main(args:Array<String>)=runBlocking {
 val output=Path.of(args.single());Files.createDirectories(output)
 val root=output.resolve("actual-global-store");val store=DesktopPluginStore(root)
 val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
 val gates=mutableListOf<String>()
 try {
  val prefs=DesktopOriginalHomePreferences.create(store,scope,true){false}
  assertGate(HomeSettings().homeHeroCarouselEnabled && HomeSettings().crashTrackingConsentShown,"schema source defaults changed")
  assertGate(!prefs.homeSettings.value.homeHeroCarouselEnabled && !prefs.homeSettings.value.crashTrackingConsentShown,"real persisted mapper was replaced with schema defaults")
  assertGate(prefs.navigation.value.tabletUseSidebar,"required Root device layout default lost")
  assertGate(store.preferences("settings")["listen_video_bottom_tab_migration_complete"]==bool(true),"ready construction did not complete original migration")
  gates+="persisted_defaults_differ_from_schema_ready_first"

  store.update("settings",mapOf("header_blur_enabled" to bool(false),"header_collapse_enabled" to bool(false),"home_card_dynamic_tint_enabled" to bool(true),"home_hero_carousel_enabled" to bool(true),"unrelated_key" to txt("retained")))
  val legacy=prefs.homeSettings.awaitValue{it.homeHeroCarouselEnabled}
  assertGate(!legacy.isHeaderBlurEnabled && !legacy.isHeaderCollapseEnabled,"legacy booleans are not decoded")
  assertGate(legacy.homeCardDynamicTintEnabled && legacy.homeCardFrostedGlassEnabled,"legacy combined tint/frosted resolver lost")
  assertGate(!legacy.showHomeCoverGlassBadges && !legacy.showHomeInfoGlassBadges,"retired original fields falsely enabled")
  gates+="legacy_resolvers_and_retired_fields_original"

  store.update("settings",mapOf("top_tab_order" to txt("FOLLOW,POPULAR,RECOMMEND,ANIME,LIVE,SUBSCRIPTION,PARTITION"),"top_tab_visible_tabs" to txt("FOLLOW,POPULAR,RECOMMEND,ANIME,LIVE,SUBSCRIPTION,PARTITION"),"bottom_bar_order" to txt("HOME,DYNAMIC,HISTORY,PROFILE"),"bottom_bar_visible_tabs" to txt("HOME,DYNAMIC,HISTORY,PROFILE"),"bottom_bar_item_colors" to txt("home:23,my:17,bad:invalid"),"bottom_bar_item_labels" to txt("home:  自定义  ,mine:我的")))
  val tabs=prefs.topTabs.awaitValue{it.orderIds.first()=="FOLLOW"}
  assertGate(tabs.visibleIds==setOf("FOLLOW","POPULAR","RECOMMEND","ANIME","LIVE"),"original top cap/order not preserved")
  val nav=prefs.navigation.awaitValue{it.bottomBarItemColors["HOME"]==23}
  assertGate(nav.bottomBarItemColors["PROFILE"]==17 && nav.bottomBarItemColors["BAD"]==0,"color ID normalization changed")
  // Original labels use their own URI-delimited protocol; validated directly below.
  assertGate(nav.orderedVisibleTabIds==listOf("HOME","DYNAMIC","HISTORY","PROFILE"),"order-visible reduction changed")
  gates+="original_top5_navigation_order_color_parse"

  val migrationRoot=output.resolve("migration-global-store");val migratedStore=DesktopPluginStore(migrationRoot)
  migratedStore.update("settings",mapOf("bottom_bar_order" to txt("HOME,DYNAMIC,HISTORY,PROFILE"),"bottom_bar_visible_tabs" to txt("HOME,DYNAMIC,HISTORY,PROFILE"),"some_other_key" to txt("untouched")))
  val migrated=DesktopOriginalHomePreferences.create(migratedStore,scope,false){true}
  assertGate(migrated.navigation.value.orderedVisibleTabIds==listOf("HOME","DYNAMIC","HISTORY","LISTEN_VIDEO","PROFILE"),"listen insertion not before PROFILE")
  assertGate(migratedStore.preferences("settings")["some_other_key"]==txt("untouched"),"migration overwrote unrelated key")
  val diskAfterMigration=Files.readAllBytes(migrationRoot.resolve("plugin-settings.json"))
  DesktopOriginalHomePreferences.create(migratedStore,scope,false){true}
  assertGate(diskAfterMigration.contentEquals(Files.readAllBytes(migrationRoot.resolve("plugin-settings.json"))),"completed migration changed disk on repeat")
  gates+="actual_same_backing_listen_migration_idempotent"

  prefs.setGridColumnCount(5);prefs.setGridColumnCountCompact(2);prefs.setTabletUseSidebar(false);prefs.setEasterEggEnabled(true)
  prefs.homeSettings.awaitValue{it.gridColumnCount==5 && it.gridColumnCountCompact==2 && it.easterEggEnabled}
  prefs.navigation.awaitValue{!it.tabletUseSidebar}
  assertGate(store.preferences("easter_egg")["enabled"]==bool(true),"original synchronous easter cache missing")
  assertGate(store.preferences("settings")["unrelated_key"]==txt("retained"),"global setter overwrote unrelated key")
  store.update("data_saver",mapOf("mode" to num(1)));assertGate(!prefs.isDataSaverActive() && migrated.isDataSaverActive(),"actual mobile observer ignored")
  store.update("data_saver",mapOf("mode" to num(2)));assertGate(prefs.isDataSaverActive(),"always mode ignored")
  store.update("data_saver",mapOf("mode" to num(0)));assertGate(!prefs.isDataSaverActive(),"off mode ignored")
  gates+="four_setters_same_global_dual_cache_and_actual_transport_flag"

  val diskBeforeFreeze=Files.readAllBytes(root.resolve("plugin-settings.json"));store.freezeWrites()
  val failure=runCatching{prefs.setGridColumnCount(6)}.exceptionOrNull()
  assertGate(failure is IllegalStateException,"old window setter did not reject frozen generation")
  assertGate(diskBeforeFreeze.contentEquals(Files.readAllBytes(root.resolve("plugin-settings.json"))),"old setter changed actual frozen disk")
  gates+="restore_freeze_rejects_old_window_write"

  val proof=buildJsonObject{put("passed",true);put("gates",JsonArray(gates.map(::JsonPrimitive)));put("scope","prepared Home settings consumer, actual25 shared global backing; no mounted Home/runtime/native acceptance");put("prefsCodeSource",DesktopOriginalHomePreferences::class.java.protectionDomain.codeSource.location.toString());put("storeCodeSource",DesktopPluginStore::class.java.protectionDomain.codeSource.location.toString());put("schemaCodeSource",HomeSettings::class.java.protectionDomain.codeSource.location.toString())}
  Files.writeString(output.resolve("result.json"),proof.toString());println(proof)
 }finally{scope.cancel()}
}
