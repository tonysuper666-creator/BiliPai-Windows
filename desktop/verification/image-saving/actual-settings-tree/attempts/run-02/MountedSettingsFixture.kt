@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.settingsfixture

import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import com.android.purebilibili.core.network.policy.AppHttpProxySettings
import com.android.purebilibili.core.store.NetworkProxyStore
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.feature.settings.*
import com.bilipai.desktop.appearance.*
import com.bilipai.desktop.data.*
import com.bilipai.desktop.plugins.*
import com.bilipai.desktop.settings.*
import com.bilipai.desktop.ui.DesktopImageSaveLifetime
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.jetbrains.skia.EncodedImageFormat
import java.awt.GraphicsEnvironment
import java.awt.Window
import java.nio.file.*

/** Actual Main Tree/dialog/prefs/lifetime/store. Chooser alone is a synthetic null result.
 * No product override, full Shell/runtime/player or real desktop window acceptance.
 */
fun main(args: Array<String>): Unit = runBlocking {
    check(GraphicsEnvironment.isHeadless() && Window.getWindows().isEmpty())
    val output = Path.of(args[0]); Files.createDirectories(output)
    val root = Path.of(args[1]).toAbsolutePath().normalize()
    check(Path.of(System.getenv("LOCALAPPDATA")).toAbsolutePath().normalize() == root)
    val store = DesktopPluginStore(root.resolve("global-prefs"))
    val context = DesktopPluginContext(store)
    NetworkProxyStore.init(context)
    check(!NetworkProxyStore.getSync().enabled)
    val sessions = DesktopSessionStore(root.resolve("fixture-session.json"), persistent = false)
    val repository = DesktopRepository(sessions)
    val blocked = DesktopBlockedUpStore(context)
    val discovery = DesktopDiscoveryRepository(repository, DesktopDiscoveryPreferences(store.root, blocked))
    val searchPreferences = DesktopSearchPreferences(root.resolve("original-search-authority"))
    val privacy = DesktopPrivacySectionBindings(context, searchPreferences)
    val life = DesktopImageSaveLifetime { false }
    val location = DesktopImageSaveLocationPreferences(store, life::withCommit)
    val settingsJob = SupervisorJob(coroutineContext[Job])
    val settingsScope = CoroutineScope(coroutineContext + settingsJob)
    val failures = mutableListOf<Throwable>()
    val messages = mutableListOf<String>()
    val checks = mutableListOf<JsonObject>()
    var chooserCalls = 0
    val storedUri = root.resolve("already-selected-directory").toUri().toString()
    val other = JsonPrimitive("same-global-value")

    for (style in AppUiStyle.entries) {
        store.update("settings", mapOf("image_save_tree_uri" to JsonPrimitive(storedUri), "other_settings_key" to other))
        val navigator = DesktopSettingsNavigator()
        navigator.openCategory(SettingsRootCategory.STORAGE_BACKUP)
        val search = DesktopSettingsSearchController(DesktopSettingsSearchRepository(context, searchPreferences::isPrivacyModeEnabledSync))
        var nanos = 0L; var pointers = 0; var textInputs = 0
        val scene = ImageComposeScene(width = 820, height = 980, coroutineContext = coroutineContext)
        try {
            scene.setContent {
                DesktopAppearanceTheme(DesktopThemeSettings(uiStyle = style, hapticFeedbackEnabled = false)) {
                    DesktopSettingsTree(navigator, search, settingsScope, discovery, privacy,
                        onFailure = { failures += it }, appearanceContent = {}, pluginsContent = {},
                        playbackContent = {}, backupContent = { _, _ -> }, blockedListContent = {}, systemContent = {},
                        imageSavePathContent = { initially ->
                            DesktopImageSavePathSettings(location, settingsScope,
                                chooseDirectory = { chooserCalls++; null }, stillOwned = life::isActive,
                                onFailure = { failures += it }, onMessage = { messages += it }, openInitially = initially)
                        })
                }
            }
            suspend fun settle() { repeat(8) { nanos += 30_000_000L; scene.render(nanos).close(); yield() } }
            fun nodes(): List<SemanticsNode> {
                fun tree(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::tree)
                return scene.semanticsOwners.flatMap { tree(it.unmergedRootSemanticsNode) }
            }
            fun labels() = nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
            fun matches(text: String) = nodes().filter { it.config.getOrNull(SemanticsProperties.Text)?.any { line -> line.text == text } == true }
            suspend fun waitFor(condition: () -> Boolean) { withTimeout(3500) { while (!condition()) { delay(10); settle() } }; settle() }
            suspend fun pointer(position: Offset) {
                check(position.x in 0f..820f && position.y in 0f..980f)
                scene.sendPointerEvent(PointerEventType.Press, position, timeMillis = nanos/1_000_000, buttons = PointerButtons(isPrimaryPressed = true))
                scene.sendPointerEvent(PointerEventType.Release, position, timeMillis = nanos/1_000_000 + 55, buttons = PointerButtons())
                pointers++; settle()
            }
            suspend fun click(text: String) {
                val n = matches(text).lastOrNull() ?: error("Missing '$text': ${labels()}")
                pointer(n.boundsInRoot.center)
            }
            suspend fun image(name: String) { scene.render(nanos+1).use { Files.write(output.resolve("${style.name.lowercase()}-$name.png"), it.encodeToData(EncodedImageFormat.PNG)!!.bytes) } }
            settle()
            check(navigator.state.value.current is DesktopSettingsPage.Category)
            check(matches("图片保存位置").isNotEmpty() && labels().contains("已选择目录"))
            val originalOwners = scene.semanticsOwners.size
            click("图片保存位置")
            check(matches("选择图片目录").isNotEmpty() && scene.semanticsOwners.size > originalOwners)
            image("storage-original-dialog")
            val before = chooserCalls
            click("选择图片目录")
            waitFor { chooserCalls == before+1 && matches("选择图片目录").isEmpty() }
            check(location.getImageSaveTreeUriSync() == storedUri && store.preferences("settings")["other_settings_key"] == other)
            check(life.isActive() && settingsJob.isActive)
            image("storage-after-null-chooser")

            click("搜索设置")
            waitFor { navigator.state.value.current is DesktopSettingsPage.Search }
            val editor = nodes().lastOrNull { it.config.contains(SemanticsProperties.EditableText) } ?: error("No original search editor")
            check(editor.config.getOrNull(SemanticsActions.SetText)?.action?.invoke(AnnotatedString("图片保存位置")) == true)
            textInputs++; settle()
            check(search.results.value.any { it.target == SettingsSearchTarget.IMAGE_SAVE_PATH })
            click("图片保存位置")
            waitFor { (navigator.state.value.current as? DesktopSettingsPage.Detail)?.target == SettingsSearchTarget.IMAGE_SAVE_PATH && matches("恢复默认").isNotEmpty() }
            image("search-detail-original-dialog")
            click("恢复默认")
            waitFor { location.getImageSaveTreeUriSync() == null && matches("恢复默认").isEmpty() }
            check(store.preferences("settings")["other_settings_key"] == other && "image_save_tree_uri" !in store.preferences("settings"))
            check(messages.last() == "已恢复默认图片保存位置" && life.isActive() && settingsJob.isActive)
            image("search-detail-after-reset")
            check(failures.isEmpty() && repository.account.value == null && repository.savedAccounts.value.isEmpty())
            check(Window.getWindows().isEmpty())
            checks += buildJsonObject {
                put("style", style.name); put("passed", true); put("actualPointerPairs", pointers); put("actualSearchEditorActions", textInputs)
                put("storageRowToOriginalSceneDialog", true); put("cancelledFakeChooserPreservesValue", true)
                put("actualSearchResultToImageSaveDetail", true); put("resetOriginalKeyOnly", true)
                put("popupCloseDoesNotRetireRootScopeOrLifetime", true); put("actualSystemChooser", false)
            }
        } finally { scene.close(); SettingsSearchFocusController.clear() }
    }
    val sourceNames = listOf(
        "com.bilipai.desktop.settings.DesktopSettingsTreeKt",
        "com.bilipai.desktop.settings.DesktopImageSavePathSettingsKt",
        "com.bilipai.desktop.settings.DesktopImageSaveLocationPreferences",
        "com.bilipai.desktop.ui.DesktopImageSaveLifetime",
        "com.bilipai.desktop.plugins.DesktopPluginStore",
        "com.android.purebilibili.feature.settings.DesktopOriginalImageSavePathDialogKt",
        "com.android.purebilibili.feature.settings.SettingsImageSavePathEntryKt",
    )
    val expectedMain = Path.of(System.getProperty("proof.main.jar")).toAbsolutePath().normalize()
    val bindings = sourceNames.map { name ->
        val uri = Class.forName(name).protectionDomain.codeSource.location.toURI()
        check(Path.of(uri).toAbsolutePath().normalize() == expectedMain)
        buildJsonObject { put("class", name); put("codeSource", uri.toString()) }
    }
    life.close(); settingsJob.cancelAndJoin()
    repository.httpClient.dispatcher.cancelAll(); repository.httpClient.connectionPool.evictAll(); repository.httpClient.dispatcher.executorService.shutdownNow()
    check(!Files.exists(root.resolve("fixture-session.json")) && Window.getWindows().isEmpty())
    Files.writeString(output.resolve("result.json"), buildJsonObject {
        put("passed", true); put("flows", JsonArray(checks)); put("classBindings", JsonArray(bindings))
        put("productOverrides", 0); put("realGlobalPluginStore", true); put("nativeWindow", false)
        put("fullShellMounted", false); put("externalAccountRequest", false); put("realSystemChooser", false)
    }.toString())
    println("PASS: actual Main04 Tree/storage and search/detail original dialog flows; null chooser boundary only; no HWND or full Shell.")
}
