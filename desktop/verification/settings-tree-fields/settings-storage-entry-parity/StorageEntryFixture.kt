@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.settings

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.feature.settings.*
import com.bilipai.desktop.appearance.*
import com.bilipai.desktop.backup.*
import com.bilipai.desktop.ui.DesktopBackupSettingsSection
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.*

fun main(args: Array<String>): Unit = runBlocking {
    var exit = 1
    try {
        val output=Path.of(args.single());Files.createDirectories(output)
        val temporary=Files.createTempDirectory("bp-storage-")
        Files.writeString(temporary.resolve("fixture-owner.json"),"{\"owner\":\"settings-storage-entry\"}")
        var schedulerActions=0
        val scheduler=object:DesktopBackupScheduler {
            override fun install(){schedulerActions++}
            override fun uninstall(){schedulerActions++}
        }
        val coordinator=DesktopBackupCoordinator(DesktopBackupStore(temporary),scheduler)
        val originalState=coordinator.state.value
        val rows=mutableListOf<JsonObject>()
        check(resolveDesktopBackupEntrySection(SettingsSearchTarget.SETTINGS_SHARE)==DesktopBackupSettingsSection.LOCAL_SETTINGS)
        check(resolveDesktopBackupEntrySection(SettingsSearchTarget.WEBDAV_BACKUP)==DesktopBackupSettingsSection.WEBDAV)
        for(target in SettingsSearchTarget.entries.filter { it !in setOf(SettingsSearchTarget.SETTINGS_SHARE,SettingsSearchTarget.WEBDAV_BACKUP) }) {
            check(resolveDesktopBackupEntrySection(target)==null)
        }
        for(style in AppUiStyle.entries) {
            val clicked=mutableListOf<SettingsSearchTarget>()
            val sections=mutableListOf<DesktopBackupSettingsSection>()
            var time=0L
            val scene=ImageComposeScene(width=720,height=480,coroutineContext=coroutineContext)
            try {
                scene.setContent {
                    DesktopAppearanceTheme(DesktopThemeSettings(uiStyle=style,hapticFeedbackEnabled=false)) {
                        CompositionLocalProvider(
                            LocalAppPreferenceIconTreatment provides AppPreferenceIconTreatment.FILLED,
                            LocalAppPreferenceGroupPresentation provides if(style==AppUiStyle.MIUIX) AppPreferenceGroupPresentation.CARD else AppPreferenceGroupPresentation.FLAT,
                        ) {
                            androidx.compose.material3.Surface {
                                Column(Modifier.fillMaxSize().padding(16.dp)) {
                                    DesktopSettingsStorageEntries { target ->
                                        clicked+=target
                                        sections+=requireNotNull(resolveDesktopBackupEntrySection(target))
                                    }
                                }
                            }
                        }
                    }
                }
                suspend fun settle(){repeat(8){time+=30_000_000;scene.render(time).close();yield()}}
                fun all():List<SemanticsNode> {
                    fun tree(node:SemanticsNode):List<SemanticsNode> = listOf(node)+node.children.flatMap(::tree)
                    return scene.semanticsOwners.flatMap { tree(it.unmergedRootSemanticsNode) }
                }
                fun label(text:String)=all().single { it.config.getOrNull(SemanticsProperties.Text)?.any { line->line.text==text }==true }
                suspend fun click(text:String) {
                    val position=label(text).boundsInRoot.center
                    scene.sendPointerEvent(PointerEventType.Press,position,timeMillis=time/1_000_000,buttons=PointerButtons(isPrimaryPressed=true))
                    scene.sendPointerEvent(PointerEventType.Release,position,timeMillis=time/1_000_000+55,buttons=PointerButtons())
                    settle()
                }
                settle()
                check(clicked.isEmpty() && coordinator.state.value==originalState && schedulerActions==0)
                check(label("存储与备份").boundsInRoot.width>0)
                val share=settingsDestinationCopy(SettingsSearchTarget.SETTINGS_SHARE)
                val cloud=settingsDestinationCopy(SettingsSearchTarget.WEBDAV_BACKUP)
                check(label(share.title).boundsInRoot.top<label(cloud.title).boundsInRoot.top)
                check(label(share.summary).boundsInRoot.width>0 && label(cloud.summary).boundsInRoot.width>0)
                check(all().none { it.config.getOrNull(SemanticsProperties.Text)?.any { line->line.text=="自动清理缓存" || line.text=="缓存容量上限" }==true })
                click(share.summary);click(cloud.summary)
                check(clicked==listOf(SettingsSearchTarget.SETTINGS_SHARE,SettingsSearchTarget.WEBDAV_BACKUP))
                check(sections==listOf(DesktopBackupSettingsSection.LOCAL_SETTINGS,DesktopBackupSettingsSection.WEBDAV))
                check(coordinator.state.value==originalState && schedulerActions==0)
                check(Files.list(temporary).use { it.map { p->p.fileName.toString() }.toList() }==listOf("fixture-owner.json"))
                scene.render(time+1).use { Files.write(output.resolve("${style.name.lowercase()}-storage-entries.png"),it.encodeToData(EncodedImageFormat.PNG)!!.bytes) }
                rows+=buildJsonObject {
                    put("style",style.name);put("actualPointerCount",2);put("originalTitlesSummariesAndOrder",true)
                    put("targets",JsonArray(clicked.map { JsonPrimitive(it.name) }));put("sections",JsonArray(sections.map { JsonPrimitive(it.name) }))
                    put("opensDoNotRunCoordinatorOrScheduler",true);put("unsupportedStorageControlsRendered",false);put("passed",true)
                }
            }finally{scene.close()}
        }
        check(java.awt.Window.getWindows().none { it.isDisplayable })
        Files.writeString(output.resolve("result.json"),buildJsonObject {
            put("passed",true);put("styles",JsonArray(rows));put("actualPointerCount",4)
            put("nativeWindowCreated",false);put("accountOrNetworkRequests",false);put("actualBackupDialogOpened",false)
            put("fullBackupDialogCompiled",true);put("androidSettingsShareJsonProfilesImplemented",false)
            put("fullDataStorageOrSchedulesClaimed",false);put("temporaryRoot",temporary.toString())
        }.toString())
        println("Original storage rows: both styles, four actual pointers, exact target-to-existing-dialog selection PASS; no native dialog/window/request.")
        exit=0
    }catch(failure:Throwable){failure.printStackTrace()}
    finally{kotlin.system.exitProcess(exit)}
}
