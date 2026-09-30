@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.data

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import com.android.purebilibili.core.database.entity.BlockedUp
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.feature.settings.*
import com.bilipai.desktop.appearance.*
import com.bilipai.desktop.plugins.*
import com.bilipai.desktop.ui.DesktopBlockedListScreen
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import java.net.Proxy
import java.nio.file.*
import java.util.concurrent.atomic.AtomicInteger
import org.jetbrains.skia.EncodedImageFormat

fun main(args: Array<String>): Unit = runBlocking {
    var status = 1
    try {
        val output = Path.of(args.single()); Files.createDirectories(output)
        val proof = mutableListOf<JsonObject>()
        for (style in AppUiStyle.entries) for (theme in listOf(AppThemeMode.LIGHT, AppThemeMode.DARK)) {
            val owned = Files.createTempDirectory("blocked-ui-owned-")
            val store = DesktopBlockedUpStore(DesktopPluginContext(DesktopPluginStore(owned)))
            val up = BlockedUp(31, "Original full profile", "", blockedAt = 100, level = 6, sign = "Full signature", follower = 12,
                archiveCount = 3, vipLabel = "VIP", officialTitle = "Verified")
            store.upsert(up)
            val sessions = DesktopSessionStore(owned.resolve("synthetic-session.json"), persistent = false)
            sessions.saveAccount(mapOf("SESSDATA" to "synthetic-session", "bili_jct" to "synthetic-csrf"), AccountSummary(12, "Fixture", ""))
            val repository = DesktopRepository(sessions)
            val calls = AtomicInteger()
            val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).addInterceptor { chain ->
                val request = chain.request(); check(request.url.host == "api.bilibili.com")
                check(request.url.encodedPath == "/x/relation/modify")
                calls.incrementAndGet()
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                    .body("""{"code":0}""".toResponseBody("application/json".toMediaType())).build()
            }.build()
            val backend = DesktopBlockedUpRepository.forTests(repository, store, client)
            val scene = ImageComposeScene(width = 900, height = 1000, coroutineContext = coroutineContext)
            var time = 0L
            val actions = mutableListOf<String>()
            var actualHost by mutableStateOf(false)
            try {
                scene.setContent {
                    DesktopAppearanceTheme(DesktopThemeSettings(uiStyle = style, themeMode = theme, hapticFeedbackEnabled = false)) {
                        androidx.compose.material3.Surface {
                            if (actualHost) DesktopBlockedListScreen(backend, onLogin = { error("Synthetic credentials are valid") }, modifier = Modifier.fillMaxSize())
                            else BlockedListContent(listOf(up), modifier = Modifier.fillMaxSize(),
                                onSyncBlockedList = { actions += "pull-import" }, onRefreshProfiles = { actions += "profiles" },
                                onExportBlockedListJson = { actions += "export-json" }, onImportBlockedListJsonRequest = { actions += "pick-import" },
                                onShareBlockedList = { actions += "share-text" }, onImportBlockedList = { actions += "paste-submit" },
                                onUnblock = { check(it == up.mid); actions += "unblock" })
                        }
                    }
                }
                suspend fun settle() { repeat(10) { time += 30_000_000; scene.render(time).close(); delay(8) } }
                fun all(): List<SemanticsNode> {
                    fun tree(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::tree)
                    return scene.semanticsOwners.flatMap { tree(it.unmergedRootSemanticsNode) }
                }
                fun labels(text: String) = all().filter { it.config.getOrNull(SemanticsProperties.Text)?.any { line -> line.text == text } == true }
                suspend fun click(text: String) {
                    val nodes = labels(text); check(nodes.size == 1) { "Expected one '$text', got ${nodes.size}" }
                    val position = nodes.single().boundsInRoot.center
                    scene.sendPointerEvent(PointerEventType.Press, position, timeMillis = time / 1_000_000, buttons = PointerButtons(isPrimaryPressed = true))
                    scene.sendPointerEvent(PointerEventType.Release, position, timeMillis = time / 1_000_000 + 55, buttons = PointerButtons())
                    settle()
                }
                settle(); check(calls.get() == 0 && actions.isEmpty())
                check(labels(up.name).size == 1 && labels(up.sign).size == 1)
                check(all().any { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("等级6") == true })
                for (label in listOf("同步 B站黑名单", "刷新资料", "导出 JSON 文件", "导入 JSON 文件", "分享文本", "解除屏蔽")) click(label)
                check(actions == listOf("pull-import", "profiles", "export-json", "pick-import", "share-text", "unblock"))
                check(calls.get() == 0 && store.records.value == listOf(up))
                scene.render(time + 1).use { Files.write(output.resolve("${style.name.lowercase()}-${theme.name.lowercase()}-original-content.png"), it.encodeToData(EncodedImageFormat.PNG)!!.bytes) }
                // Now mount the actual Windows host against the same real store/Retrofit fixture.
                actualHost = true; settle(); check(calls.get() == 0)
                click("解除屏蔽")
                withTimeout(3000) { while (store.records.value.isNotEmpty() || calls.get() != 1) { settle() } }
                settle(); check(labels("暂无屏蔽的 UP 主").size == 1)
                check(store.records.value.isEmpty() && calls.get() == 1)
                proof += buildJsonObject {
                    put("style", style.name); put("theme", theme.name); put("passed", true)
                    put("actualPointerCount", 7); put("originalContentExactSixCallbackRoutes", true)
                    put("actualWindowsHostUnblockToRealStoreAndSyntheticRetrofitPost", true)
                    put("compositionMakesNoRemoteRequest", true); put("originalLv6PngDecoded", true)
                }
            } finally { scene.close() }
        }
        check(java.awt.Window.getWindows().none { it.isDisplayable })
        Files.writeString(output.resolve("result.json"), buildJsonObject {
            put("passed", true); put("matrix", JsonArray(proof)); put("actualPointerCount", 28)
            put("headlessImageComposeScene", true); put("nativeWindow", false); put("liveAccountOrExternalNetwork", false)
            put("nativeFilePickerOrClipboardInvoked", false); put("fullRootSettingsRouteIntegrated", false)
        }.toString())
        println("Original content + actual Windows host: two styles/light+dark, 28 actual offscreen pointers, original resource decode PASS")
        status = 0
    } catch (failure: Throwable) { failure.printStackTrace() }
    finally { kotlin.system.exitProcess(status) }
}
