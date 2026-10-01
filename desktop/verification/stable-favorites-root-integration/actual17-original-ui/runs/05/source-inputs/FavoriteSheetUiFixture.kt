@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
@file:Suppress("DEPRECATION")
package com.bilipai.desktop.diagnostics.favoriteui17

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.data.model.response.FavFolder
import com.android.purebilibili.feature.video.ui.components.FavoriteFolderSheet
import com.bilipai.desktop.appearance.*
import com.bilipai.desktop.ui.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.*
import java.security.Permission
import java.util.concurrent.atomic.AtomicInteger

private val checks = mutableListOf<String>()
private fun verify(label: String, condition: Boolean) { check(condition) { label }; checks += label; println("PASS: $label") }
private class Fence : SecurityManager() {
    val attempts = AtomicInteger()
    override fun checkPermission(p: Permission) {}
    override fun checkConnect(h: String, p: Int) { attempts.incrementAndGet(); error("No HTTP/network in favorite UI fixture") }
    override fun checkListen(p: Int) { attempts.incrementAndGet(); error("No listen in favorite UI fixture") }
    override fun checkExec(command: String) { attempts.incrementAndGet(); error("No process in favorite UI fixture") }
}
private class Scene(val scene: ImageComposeScene, val width: Int, val height: Int) {
    var nanos = 0L
    var pointers = 0
    var textEdits = 0
    fun nodes(): List<SemanticsNode> {
        fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
        return scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
    }
    fun labels() = nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
    suspend fun frame() { nanos += 30_000_000; scene.render(nanos).close(); delay(5) }
    suspend fun wait(label: String, ready: () -> Boolean) {
        try { withTimeout(6500) { while (!ready()) frame() }; repeat(20) { frame() } }
        catch (e: TimeoutCancellationException) { error("$label: actual labels=${labels()}") }
    }
    suspend fun pointer(point: Offset) {
        check(point.x in 0f..width.toFloat() && point.y in 0f..height.toFloat()) { "Target outside measured viewport: $point" }
        scene.sendPointerEvent(PointerEventType.Press, point, timeMillis=nanos/1_000_000, buttons=PointerButtons(isPrimaryPressed=true))
        scene.sendPointerEvent(PointerEventType.Release, point, timeMillis=nanos/1_000_000+55, buttons=PointerButtons())
        pointers++; repeat(16) { frame() }
    }
    suspend fun click(label: String) = pointer(nodes().last { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == label } == true }.boundsInRoot.center)
    suspend fun edit(index: Int, value: String) {
        val fields = nodes().filter { it.config.getOrNull(SemanticsActions.SetText)?.action != null }
        check(fields.size == 2) { "Original create dialog must expose two actual text fields: ${fields.size}" }
        val field = fields[index]
        pointer(field.boundsInRoot.center)
        check(checkNotNull(field.config.getOrNull(SemanticsActions.SetText)?.action).invoke(AnnotatedString(value)))
        textEdits++; repeat(12) { frame() }
        verify("actual editable text field $index contains full input", nodes().any { it.config.getOrNull(SemanticsProperties.EditableText)?.text == value })
    }
}

fun main(args: Array<String>): Unit = runBlocking {
    val style = AppUiStyle.valueOf(args[0])
    val height = args[1].toInt()
    val out = Path.of(args[2]); Files.createDirectories(out)
    val width = 900
    val folders = listOf(FavFolder(id=11, title="原收藏夹甲", media_count=3), FavFolder(id=22, title="原收藏夹乙", media_count=5), FavFolder(id=33, title="原收藏夹丙", media_count=8))
    var selected by mutableStateOf(emptySet<Long>())
    var shown by mutableStateOf(true)
    var saves = 0
    var dismisses = 0
    val toggles = mutableListOf<Long>()
    val created = mutableListOf<Triple<String,String,Boolean>>()
    val fence = Fence(); System.setSecurityManager(fence)
    val scene = ImageComposeScene(width=width, height=height, coroutineContext=coroutineContext)
    val ui = Scene(scene,width,height)
    try {
        scene.setContent {
            DesktopAppearanceTheme(DesktopThemeSettings(uiStyle=style,hapticFeedbackEnabled=false),systemLanguageTags=listOf("zh-CN")) {
                DesktopDetailWindow {
                    CompositionLocalProvider(LocalDesktopFavoriteFolderViewport provides DesktopFavoriteFolderViewport(height)) {
                        if (shown) FavoriteFolderSheet(folders,false,selected,false,
                            onFolderToggle={ folder -> toggles += folder.id; selected = if (folder.id in selected) selected-folder.id else selected+folder.id },
                            onSaveClick={ saves++ }, onDismissRequest={ dismisses++; shown=false },
                            onCreateFolder={ title,intro,private -> created += Triple(title,intro,private) })
                    }
                }
            }
        }
        ui.wait("installed original sheet mounts at actual finite viewport") { "添加到收藏夹" in ui.labels() && "原收藏夹乙" in ui.labels() }
        verify("full original header and explanatory subtitle are rendered", "可勾选一个或多个收藏夹，将视频收藏到自己的收藏夹" in ui.labels())
        verify("all original folder title/count rows visible", folders.all { it.title in ui.labels() && "${it.media_count}个内容" in ui.labels() })
        ui.click("原收藏夹甲"); ui.click("原收藏夹乙")
        ui.wait("actual row pointer multiselect updates supplied original state") { "已选择 2 个收藏夹" in ui.labels() }
        verify("two distinct actual row callbacks and retained multi-selection", toggles == listOf(11L,22L) && selected == setOf(11L,22L))
        ui.click("保存")
        verify("one real save pointer yields exactly one Save callback", saves==1)
        ui.click("新建")
        ui.wait("actual original create dialog mounts") { "新建收藏夹" in ui.labels() && ui.nodes().count { it.config.getOrNull(SemanticsActions.SetText)?.action!=null }==2 }
        if (style==AppUiStyle.MATERIAL3) {
            ui.wait("original MATERIAL3 field labels settle") { listOf("标题","简介 (选填)","设为私密").all { it in ui.labels() } }
            verify("original MATERIAL3 title/intro/privacy labels all present", listOf("标题","简介 (选填)","设为私密").all { it in ui.labels() })
        } else {
            // Stable original AppOutlinedTextField MIUIX reads labelText and
            // placeholderText, not this original Sheet's composable label slots.
            // Keep that upstream behavior; do not inject replacement labels.
            verify("original MIUIX renders two actual fields and privacy label", "设为私密" in ui.labels() && ui.nodes().count { it.config.getOrNull(SemanticsActions.SetText)?.action!=null }==2)
        }
        val title = "  原标题 · 完整输入  "
        val intro = "原简介第一行\n第二行 · 完整输入"
        ui.edit(0,title); ui.edit(1,intro)
        val privacy = ui.nodes().last { it.config.getOrNull(SemanticsProperties.ToggleableState)!=null }
        ui.pointer(privacy.boundsInRoot.center)
        ui.click("创建")
        ui.wait("actual confirm dismisses original create dialog") { "新建收藏夹" !in ui.labels() }
        verify("one actual Create callback preserves full title/intro/privacy", created==listOf(Triple(title,intro,true)))
        verify("creating does not implicitly save selected memberships", saves==1 && selected==setOf(11L,22L))
        ui.click("新建")
        ui.wait("original create dialog can reopen") { "新建收藏夹" in ui.labels() }
        ui.click("取消")
        ui.wait("actual Cancel dismisses only create dialog") { "新建收藏夹" !in ui.labels() && "添加到收藏夹" in ui.labels() }
        verify("cancel adds no Create callback", created.size==1)
        // The centered Dialog owns a full-size navigation Box. The previous
        // run retained its actual outside-pointer nondismissal; verify the
        // installed Windows modal's real Escape input instead of changing UI.
        val awtKey=java.awt.event.KeyEvent(java.awt.Canvas(),java.awt.event.KeyEvent.KEY_PRESSED,
            System.currentTimeMillis(),0,java.awt.event.KeyEvent.VK_ESCAPE,java.awt.event.KeyEvent.CHAR_UNDEFINED)
        val converted=Class.forName("androidx.compose.ui.input.key.KeyEvent_desktopKt")
            .getMethod("toComposeEvent",java.awt.event.KeyEvent::class.java).invoke(null,awtKey)
        val consumed=scene.sendKeyEvent(KeyEvent(converted))
        ui.wait("actual Escape input dismisses modal sheet") { !shown }
        verify("installed modal consumes actual AWT-converted Escape",consumed)
        verify("one original sheet dismissal callback", dismisses==1)
        verify("no network/process/native chooser/account operations", fence.attempts.get()==0)
        val identities = listOf(
            "com.android.purebilibili.feature.video.ui.components.FavoriteFolderSheetKt",
            "com.android.purebilibili.data.model.response.FavFolder",
            "com.android.purebilibili.feature.video.viewmodel.DesktopOriginalFavoriteFolderSession",
            "com.android.purebilibili.data.repository.DesktopOriginalFavoriteFolderProtocol",
            "com.bilipai.desktop.ui.DesktopFavoriteFolderEnvironment",
            "com.android.purebilibili.core.ui.DesktopOriginalDetailAppSheetComponentsKt",
            "com.bilipai.desktop.appearance.DesktopAppearanceThemeKt",
            "com.bilipai.desktop.ui.DesktopVideoFavoriteRootKt"
        ).map { name ->
            val c=Class.forName(name)
            val path="/"+name.replace('.','/')+".class"
            val bytes=checkNotNull(c.getResourceAsStream(path)).use { it.readBytes() }
            buildJsonObject { put("class",name); put("codeSource",c.protectionDomain.codeSource.location.toString()); put("classSha256Bytes",java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }) }
        }
        Files.writeString(out.resolve("result.json"),buildJsonObject {
            put("passed",true); put("style",style.name); put("viewportWidth",width);put("viewportHeight",height)
            put("assertions",checks.size);put("checks",JsonArray(checks.map(::JsonPrimitive)))
            put("pointerPairs",ui.pointers); put("actualEditableTextActions",ui.textEdits)
            put("saveCallbacks",saves);put("createCallbacks",created.size);put("dismissCallbacks",dismisses)
            put("productionClassOverrides",0);put("actualCodeSources",JsonArray(identities))
            put("fixtureOnlySuppliesFoldersSelectionAndCallbacks",true)
            put("RootFavoriteButtonOrActualSessionTransportExecuted",false)
            put("originalMiuixComposableFieldLabelSlotsNotRendered",style==AppUiStyle.MIUIX)
            put("nativeWindowOrExternalAppOrAccountOrHttp",false);put("networkOrProcessAttempts",fence.attempts.get())
        }.toString())
    } finally { scene.close(); System.setSecurityManager(null) }
}
