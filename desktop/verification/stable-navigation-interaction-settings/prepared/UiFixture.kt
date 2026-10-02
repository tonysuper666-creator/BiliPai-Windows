@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.settings

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.feature.settings.*
import com.bilipai.desktop.DesktopLibrary
import com.bilipai.desktop.appearance.*
import com.bilipai.desktop.data.*
import com.bilipai.desktop.plugins.*
import com.bilipai.desktop.ui.DesktopOriginalHomePreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.*

private class UI(context: kotlin.coroutines.CoroutineContext) {
    private val job = SupervisorJob()
    val errors = mutableListOf<Throwable>()
    private val scope = CoroutineScope(context + job + CoroutineExceptionHandler { _, e -> errors += e })
    val scene = ImageComposeScene(900, 720, coroutineContext = scope.coroutineContext)
    var nanos = 0L
    val pointers = mutableListOf<JsonObject>()
    fun nodes(): List<SemanticsNode> {
        fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
        return scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
    }
    fun matching(label: String) = nodes().filter { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true }
    fun texts() = nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
    suspend fun frame() { nanos += 30_000_000; scene.render(nanos).close(); yield(); delay(3); check(errors.isEmpty()) { errors.toString() } }
    suspend fun await(label: String, ready: () -> Boolean) {
        try { withTimeout(5000) { while (!ready()) frame() }; repeat(4) { frame() } }
        catch (e: TimeoutCancellationException) { error("$label: ${texts()}") }
    }
    suspend fun click(label: String) {
        await(label) { matching(label).any { it.boundsInRoot.width > 0 && it.boundsInRoot.top >= 0 && it.boundsInRoot.bottom <= 720 } }
        val n = matching(label).first { it.boundsInRoot.width > 0 && it.boundsInRoot.top >= 0 && it.boundsInRoot.bottom <= 720 }
        val p = n.boundsInRoot.center
        pointers += buildJsonObject { put("label", label); put("x", p.x); put("y", p.y); put("bounds", n.boundsInRoot.toString()) }
        scene.sendPointerEvent(PointerEventType.Press, p, timeMillis = nanos / 1_000_000, buttons = PointerButtons(isPrimaryPressed = true))
        scene.sendPointerEvent(PointerEventType.Release, p, timeMillis = nanos / 1_000_000 + 55, buttons = PointerButtons())
        repeat(10) { frame() }
    }
    fun save(path: Path) { scene.render(nanos + 1).use { Files.write(path, it.encodeToData(EncodedImageFormat.PNG)!!.bytes) } }
    fun semantics(path: Path) = Files.writeString(path, nodes().joinToString("\n") { "${it.boundsInRoot} ${it.config}" })
    suspend fun close() { scene.close(); job.cancelAndJoin() }
}

fun main(args: Array<String>): Unit = runBlocking {
    val output = Path.of(args[0]); Files.createDirectories(output)
    val taskAppData = Path.of(System.getenv("LOCALAPPDATA")).toAbsolutePath().normalize()
    check(taskAppData.startsWith(output.toAbsolutePath().normalize()))
    check(DesktopLibrary.directoryForAccount(null).toAbsolutePath().normalize().startsWith(taskAppData))
    val cases = mutableListOf<JsonObject>()
    for (style in AppUiStyle.entries) for (dark in listOf(false, true)) {
        val name = "${style.name.lowercase()}-${if (dark) "dark" else "light"}"
        val dir = output.resolve(name); Files.createDirectories(dir)
        val store = DesktopPluginStore(dir.resolve("global")); val context = DesktopPluginContext(store)
        val cardPreferences = DesktopHomeCardPreferences(context)
        val repository = DesktopRepository(DesktopSessionStore.temporary())
        val community = DesktopCommunityRepository(repository)
        val discovery = DesktopDiscoveryRepository(repository, DesktopDiscoveryPreferences(dir.resolve("discovery")))
        val privacy = DesktopPrivacySectionBindings(context, community.searchPreferences)
        val search = DesktopSettingsSearchController(DesktopSettingsSearchRepository(context) { false })
        val navigator = DesktopSettingsNavigator()
        val homeJob = SupervisorJob(); val homeScope = CoroutineScope(coroutineContext + homeJob)
        val home = DesktopOriginalHomePreferences.create(store, homeScope, false) { false }
        val failures = mutableListOf<Throwable>(); val ui = UI(coroutineContext)
        try {
            ui.scene.setContent {
                DesktopAppearanceTheme(DesktopThemeSettings(uiStyle = style,
                    themeMode = if (dark) AppThemeMode.DARK else AppThemeMode.LIGHT,
                    appLanguage = AppLanguage.SIMPLIFIED_CHINESE, hapticFeedbackEnabled = false)) {
                    CompositionLocalProvider(LocalDesktopHomeCardPreferences provides cardPreferences) {
                        DesktopSettingsTree(navigator, search, homeScope, discovery, privacy, { failures += it },
                            appearanceContent = { AppText("fixture existing appearance detail port") },
                            pluginsContent = { AppText("fixture existing plugins detail port") },
                            playbackContent = { AppText("fixture unused playback port") },
                            backupContent = { _, _ -> AppText("fixture unused backup port") },
                            blockedListContent = { AppText("fixture unused blocked port") },
                            systemContent = { AppText("fixture unused system port") })
                    }
                }
            }
            ui.click("导航与交互")
            check(navigator.state.value.current == DesktopSettingsPage.Category(SettingsRootCategory.NAVIGATION_INTERACTION))
            ui.click(settingsDestinationCopy(SettingsSearchTarget.BOTTOM_BAR).title)
            check((navigator.state.value.current as DesktopSettingsPage.Detail).target == SettingsSearchTarget.BOTTOM_BAR)
            ui.await("original bottom start focus consumed") { SettingsSearchFocusController.request.value == null }
            check("下滑合体" !in ui.texts())
            ui.save(dir.resolve("navigation-before.png"))
            ui.click("悬浮底栏")
            ui.await("floating consumer") { !home.homeSettings.value.isBottomBarFloating }
            ui.click("导航图标交叉缩放")
            ui.click("底栏搜索联动")
            ui.await("conditional original fields") { "下滑合体" in ui.texts() && "列表精简搜索" in ui.texts() }
            ui.click("下滑合体"); ui.click("列表精简搜索")
            ui.await("actual Home/settings observer") { home.homeSettings.value.let { !it.isBottomBarFloating && it.navigationIconCrossScaleEnabled &&
                it.isBottomBarSearchEnabled && !it.linkedDockMergeOnScrollEnabled && it.listScopedSearchEnabled } }
            ui.save(dir.resolve("navigation-after.png")); ui.semantics(dir.resolve("navigation.semantics.txt"))
            ui.click("返回")
            ui.click(settingsDestinationCopy(SettingsSearchTarget.ANIMATION).title)
            ui.await("original animation start consumed") { SettingsSearchFocusController.request.value == null }
            ui.click("进场动画"); ui.click("过渡动画")
            ui.await("actual card observer") { home.homeSettings.value.cardAnimationEnabled && !home.homeSettings.value.cardTransitionEnabled }
            ui.save(dir.resolve("animation-after.png")); ui.semantics(dir.resolve("animation.semantics.txt"))
            navigator.openDetail(SettingsSearchTarget.BOTTOM_BAR, SettingsSearchFocusIds.BOTTOM_BAR_TOP_TABS)
            val unavailableToken = SettingsSearchFocusController.request.value!!.token
            ui.await("unsupported focus truthful") { "搜索命中的具体设置尚未接入；此页保留已可用的原版控件。" in ui.texts() }
            check(SettingsSearchFocusController.request.value?.token == unavailableToken)
            ui.click("返回"); check(SettingsSearchFocusController.request.value == null)
            check(failures.isEmpty()) { failures.toString() }
            val disk = Json.parseToJsonElement(Files.readString(store.root.resolve("plugin-settings.json"))).jsonObject["settings"]!!.jsonObject
            check(disk["card_animation_enabled"]!!.jsonPrimitive.boolean && !disk["card_transition_enabled"]!!.jsonPrimitive.boolean)
            Files.writeString(dir.resolve("settings-disk.json"), disk.toString())
            Files.writeString(dir.resolve("pointer.json"), JsonArray(ui.pointers).toString())
            cases += buildJsonObject { put("name", name); put("passed", true); put("pointerCount", ui.pointers.size)
                put("finiteViewport", "900x720"); put("sevenOriginalSettersAndActualHomeObserver", true)
                put("unsupportedFocusPreserved", true); put("appearancePluginsPortsFixtureOnly", true) }
        } finally {
            SettingsSearchFocusController.clear(); ui.close(); homeJob.cancelAndJoin()
            repository.httpClient.dispatcher.cancelAll(); repository.httpClient.connectionPool.evictAll()
            repository.httpClient.dispatcher.executorService.shutdownNow()
        }
    }
    Files.writeString(output.resolve("result.json"), buildJsonObject { put("passed", true); put("cases", JsonArray(cases))
        put("wholeMainRuntimeAccepted", false); put("nativeWindowCreated", false); put("realAccountOrRemoteRequest", false)
        put("fullBottomBarAnimationComplete", false) }.toString())
    println("4 finite Tree/selected-original-controls scenes passed: M3/Miuix light/dark; actual pointers, seven setters, disk and Home observer, guarded missing focus.")
    Unit
}
