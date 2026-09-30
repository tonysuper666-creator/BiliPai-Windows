@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.settings

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.store.SearchHintSettingsStore
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.feature.settings.*
import com.bilipai.desktop.DesktopLibrary
import com.bilipai.desktop.appearance.*
import com.bilipai.desktop.data.*
import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.*
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext
import kotlin.math.abs

/** Only scheduling is controlled: the supplied Root scope executes the real history repository. */
private class QueuedRootDispatcher:CoroutineDispatcher() {
    private val pending=ConcurrentLinkedQueue<Runnable>()
    override fun dispatch(context:CoroutineContext,block:Runnable) { pending.add(block) }
    val pendingCount get()=pending.size
    fun flush() { repeat(100) { val next=pending.poll()?:return;next.run() } }
}

fun main(args:Array<String>):Unit=runBlocking {
    val output=Path.of(args[0]).toAbsolutePath().normalize();Files.createDirectories(output)
    val appData=Path.of(args[1]).toAbsolutePath().normalize()
    check(Path.of(System.getenv("LOCALAPPDATA")).toAbsolutePath().normalize()==appData)
    val root=DesktopLibrary.directoryForAccount(null).toAbsolutePath().normalize()
    check(root.startsWith(appData)) { "Fixture cannot access the user's settings directory" }
    val source=DesktopRepository(DesktopSessionStore(root.resolve("fixture-session.json"),persistent=false))
    check(source.account.value==null && source.savedAccounts.value.isEmpty())
    val community=DesktopCommunityRepository(source)
    // This is the actual same instance handed to the Tree, search history guard and consumer checks.
    val authoritative=community.searchPreferences
    val discovery=DesktopDiscoveryRepository(source,DesktopDiscoveryPreferences(root))
    val global=DesktopPluginContext(DesktopPluginStore(root))
    val privacy=DesktopPrivacySectionBindings(global,authoritative)
    val settingsRepository=DesktopSettingsSearchRepository(global,authoritative::isPrivacyModeEnabledSync)
    val controller=DesktopSettingsSearchController(settingsRepository)
    val dispatcher=QueuedRootDispatcher()
    val rootJob=SupervisorJob()
    val rootWritesScope=CoroutineScope(rootJob+dispatcher)
    val failures=mutableListOf<Throwable>()
    var updateActions=0
    val cases=mutableListOf<JsonObject>()
    for(style in AppUiStyle.entries) {
        check(!authoritative.privacyMode.value)
        val seed="保留历史-${style.name}"
        controller.record(seed)
        authoritative.record(null,"社区保留历史-${style.name}")
        discovery.setRefreshCount(30)
        val navigator=DesktopSettingsNavigator()
        var time=0L
        var pointerCount=0
        var textInputCount=0
        val scene=ImageComposeScene(width=760,height=1150,coroutineContext=coroutineContext)
        try {
            scene.setContent {
                DesktopAppearanceTheme(DesktopThemeSettings(uiStyle=style,appLanguage=AppLanguage.SIMPLIFIED_CHINESE,hapticFeedbackEnabled=false)) {
                    DesktopSettingsTree(navigator,controller,rootWritesScope,discovery,privacy,{failures+=it},
                        appearanceContent={AppText("fixture: appearance detail port")},
                        pluginsContent={AppText("fixture: plugins detail port")},
                        playbackContent={dismiss->Column {
                            AppText("fixture: playback detail port; no native dialog")
                            AppTextButton(dismiss){AppText("fixture: playback dismiss")}
                        }},
                        backupContent={target,dismiss->Column {
                            check(target==SettingsSearchTarget.WEBDAV_BACKUP)
                            AppText("fixture: backup detail port; no native dialog")
                            AppTextButton(dismiss){AppText("fixture: backup dismiss")}
                        }},
                        systemContent={Column {
                            AppText("fixture: system category; update action requires explicit click")
                            AppTextButton({updateActions++}){AppText("fixture: explicit update action")}
                        }})
                }
            }
            suspend fun settle() {repeat(8){time+=30_000_000;scene.render(time).close();yield()}}
            fun all():List<SemanticsNode> {
                fun tree(node:SemanticsNode):List<SemanticsNode> = listOf(node)+node.children.flatMap(::tree)
                return scene.semanticsOwners.flatMap {tree(it.unmergedRootSemanticsNode)}
            }
            fun matches(text:String)=all().filter {it.config.getOrNull(SemanticsProperties.Text)?.any {line->line.text==text}==true}
            fun label(text:String)=matches(text).lastOrNull()?:error("Missing '$text' at ${navigator.state.value.current}; labels=${all().flatMap{it.config.getOrNull(SemanticsProperties.Text).orEmpty()}.map{it.text}}")
            suspend fun pointer(position:Offset) {
                check(position.x in 0f..760f && position.y in 0f..1150f)
                scene.sendPointerEvent(PointerEventType.Press,position,timeMillis=time/1_000_000,buttons=PointerButtons(isPrimaryPressed=true))
                scene.sendPointerEvent(PointerEventType.Release,position,timeMillis=time/1_000_000+55,buttons=PointerButtons())
                pointerCount++;settle()
            }
            suspend fun click(text:String)=pointer(label(text).boundsInRoot.center)
            suspend fun waitFor(condition:()->Boolean) {
                withTimeout(2500){while(!condition()){delay(10);settle()}};settle()
            }
            suspend fun flushHistory(query:String) {
                withTimeout(2500) {
                    while(query !in settingsRepository.history.first()) {dispatcher.flush();delay(10);settle()}
                    dispatcher.flush()
                }
            }
            suspend fun edit(query:String) {
                var fields=all().filter {it.config.contains(SemanticsProperties.EditableText)}
                if(fields.isEmpty()){click("搜索设置");fields=all().filter {it.config.contains(SemanticsProperties.EditableText)}}
                check(fields.last().config.getOrNull(SemanticsActions.SetText)?.action?.invoke(AnnotatedString(query))==true)
                textInputCount++;waitFor {controller.query.value==query}
            }
            suspend fun image(name:String) {scene.render(time+1).use {
                Files.write(output.resolve("${style.name.lowercase()}-$name.png"),it.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            }}
            fun diskHistory():List<String> {
                val document=Json.parseToJsonElement(Files.readString(root.resolve("plugin-settings.json"))).jsonObject
                val encoded=document["settings"]!!.jsonObject["settings_search_history"]!!.jsonPrimitive.content
                return Json.parseToJsonElement(encoded).jsonArray.map {it.jsonPrimitive.content}
            }
            fun assertRootOrder() {
                check(navigator.state.value.current==DesktopSettingsPage.Root)
                val categories=resolveSettingsRootCategoryOrder()
                val positions=categories.map {label(it.title).boundsInRoot.top}
                check(positions==positions.sorted() && positions.distinct().size==categories.size)
                categories.forEach {check(label(it.subtitle).boundsInRoot.width>0)}
            }
            settle();assertRootOrder();check(updateActions==0);image("root")
            click(SettingsRootCategory.PLAYBACK_QUALITY.title)
            check(navigator.state.value.current==DesktopSettingsPage.Category(SettingsRootCategory.PLAYBACK_QUALITY))
            click(settingsDestinationCopy(SettingsSearchTarget.PLAYBACK).title)
            check(navigator.state.value.current==DesktopSettingsPage.Detail(SettingsSearchTarget.PLAYBACK,SettingsSearchFocusIds.PLAYBACK_DECODER))
            check(label("fixture: playback detail port; no native dialog").boundsInRoot.width>0)
            click("返回");check(navigator.state.value.current is DesktopSettingsPage.Category)
            click("返回");assertRootOrder()

            click("搜索设置")
            val searchPage=navigator.state.value.current as DesktopSettingsPage.Search
            val query=if(style==AppUiStyle.MATERIAL3) "硬件解码" else "首选编码"
            edit(query)
            val result=controller.results.value.first {it.target==SettingsSearchTarget.PLAYBACK && it.focusId==SettingsSearchFocusIds.PLAYBACK_DECODER}
            image("search-decoder")
            val historyBefore=settingsRepository.history.first()
            check(query !in diskHistory())
            click(result.title)
            check(navigator.state.value.current==DesktopSettingsPage.Detail(SettingsSearchTarget.PLAYBACK,SettingsSearchFocusIds.PLAYBACK_DECODER))
            check(all().none {it.config.contains(SemanticsProperties.EditableText)})
            check(dispatcher.pendingCount>0 && settingsRepository.history.first()==historyBefore)
            // The Search surface is already gone. Only now may the supplied Root scope run.
            flushHistory(query)
            check(query in settingsRepository.history.first() && query in diskHistory())
            Files.writeString(output.resolve("${style.name.lowercase()}-history-after-search-disposal.json"),
                JsonArray(diskHistory().map(::JsonPrimitive)).toString())
            image("detail-fixture")
            click("返回")
            check(navigator.state.value.current==searchPage && controller.query.value==query)
            waitFor {all().any {it.config.getOrNull(SemanticsProperties.EditableText)?.text==query}}
            image("search-retained")

            edit("检查更新")
            val update=controller.results.value.first {it.target==SettingsSearchTarget.CHECK_UPDATE}
            click(update.title)
            check(navigator.state.value.current==DesktopSettingsPage.Category(SettingsRootCategory.SYSTEM_ABOUT))
            check(label("fixture: system category; update action requires explicit click").boundsInRoot.width>0)
            check(updateActions==0)
            flushHistory("检查更新")
            click("返回");check(navigator.state.value.current==searchPage && controller.query.value=="检查更新")
            click("返回");assertRootOrder()
            click(SettingsRootCategory.SYSTEM_ABOUT.title)
            check(updateActions==0);click("返回");assertRootOrder()

            click(SettingsRootCategory.HOME_RECOMMENDATION.title)
            check(label("首页推荐来源").boundsInRoot.width>0)
            check(label("单次最多请求 30 条推荐内容，实际显示可能更少").boundsInRoot.width>0)
            check(discovery.refreshCount.value==30)
            click("返回");assertRootOrder()
            click(SettingsRootCategory.STORAGE_BACKUP.title)
            click(settingsDestinationCopy(SettingsSearchTarget.WEBDAV_BACKUP).title)
            check(navigator.state.value.current==DesktopSettingsPage.Detail(SettingsSearchTarget.WEBDAV_BACKUP,null))
            check(label("fixture: backup detail port; no native dialog").boundsInRoot.width>0)
            click("fixture: backup dismiss")
            check(navigator.state.value.current==DesktopSettingsPage.Category(SettingsRootCategory.STORAGE_BACKUP))
            click("返回");assertRootOrder()

            click(SettingsRootCategory.PRIVACY_PERMISSION.title)
            suspend fun toggle(title:String) {
                val y=label(title).boundsInRoot.center.y
                val node=all().filter {it.config.contains(SemanticsProperties.ToggleableState)}.minBy {abs(it.boundsInRoot.center.y-y)}
                pointer(node.boundsInRoot.center)
            }
            val retained=settingsRepository.history.first()
            val communityRetained=authoritative.history(null).value
            toggle("不记录历史");waitFor {authoritative.privacyMode.value}
            check(authoritative.isPrivacyModeEnabledSync())
            controller.record("fixture incognito settings attempt")
            authoritative.record(null,"fixture incognito community attempt")
            check(settingsRepository.history.first()==retained && authoritative.history(null).value==communityRetained)
            val hintBefore=SearchHintSettingsStore.isEnabled(global).first()
            toggle("搜索框默认词");waitFor {global.getSharedPreferences("settings",0).getBoolean("search_default_hint_enabled",true)!=hintBefore}
            toggle("搜索推荐词");waitFor {!authoritative.suggestionsEnabled.value}
            val doc=Json.parseToJsonElement(Files.readString(root.resolve("plugin-settings.json"))).jsonObject
            val authority=Json.parseToJsonElement(Files.readString(root.resolve("search/plugin-settings.json"))).jsonObject
            check(doc["privacy_mode"]==null && doc["settings"]!!.jsonObject["privacy_mode_enabled"]==null)
            check(authority["privacy_mode"]!!.jsonObject["enabled"]!!.jsonPrimitive.boolean)
            check(!authority["settings"]!!.jsonObject["search_suggestions_enabled"]!!.jsonPrimitive.boolean)
            image("privacy-authoritative")
            Files.writeString(output.resolve("${style.name.lowercase()}-shared-settings.json"),doc.toString())
            Files.writeString(output.resolve("${style.name.lowercase()}-authoritative-search.json"),authority.toString())
            toggle("不记录历史");waitFor {!authoritative.privacyMode.value}
            toggle("搜索推荐词");waitFor {authoritative.suggestionsEnabled.value}
            click("返回");assertRootOrder()
            check(updateActions==0 && failures.isEmpty() && controller.error.value==null && privacy.error.value==null)
            check(source.httpClient.dispatcher.runningCallsCount()==0 && source.httpClient.dispatcher.queuedCallsCount()==0)
            cases+=buildJsonObject {
                put("style",style.name);put("actualPointerCount",pointerCount);put("actualSemanticTextInputs",textInputCount)
                put("originalRootOrder",JsonArray(resolveSettingsRootCategoryOrder().map {JsonPrimitive(it.name)}))
                put("queryAndSearchEntryTokenRetainedAcrossDetailPop",true)
                put("realHistoryWriteReleasedAfterSearchSurfaceDisposal",true)
                put("historyAfterSearchDisposalVerifiedFromActualDisk",true)
                put("authoritativePrivacyAndSuggestionsSameCommunityInstance",true)
                put("rootGlobalHasNoDuplicatePrivacyKey",true);put("actualDiscoveryRefreshValueRendered",true)
                put("updateActionExecutions",updateActions);put("passed",true)
            }
        }finally{scene.close();SettingsSearchFocusController.clear()}
    }
    rootJob.cancel()
    withTimeout(3000){while(!rootJob.isCompleted){dispatcher.flush();delay(10)}}
    rootJob.join()
    check(!Files.exists(root.resolve("fixture-session.json")))
    Files.writeString(output.resolve("result.json"),buildJsonObject {
        put("passed",true);put("styles",JsonArray(cases));put("nativeWindowCreated",false)
        put("accountOrNetworkRequests",false);put("realDialogPortExecuted",false)
        put("detailPorts","Explicit fixture labels; actual Tree/Navigator/Controller/Store/Privacy/Discovery are product classes")
        put("settingsDirectoryIsolated",true);put("sessionFileCreated",false)
    }.toString())
    println("Actual product SettingsTree pointer flows and Root-scope disk history passed in both styles; detail ports explicitly fixture-only.")
    Unit
}
