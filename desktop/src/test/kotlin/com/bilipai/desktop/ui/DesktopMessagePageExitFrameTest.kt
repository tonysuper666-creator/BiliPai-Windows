package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.android.purebilibili.core.network.SearchApi
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.ui.transition.VideoCardTransitionClock
import com.android.purebilibili.feature.settings.AppThemeMode
import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.bilipai.desktop.appearance.DesktopAppearanceTheme
import com.bilipai.desktop.appearance.DesktopThemeSettings
import com.bilipai.desktop.data.*
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import java.awt.GraphicsEnvironment
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking

/** Leaving a message page (Back from 消息设置, or a sidebar tab replacing the stack) removes its NavEntry
 * first and composes the leaving page once more for its exit frame. That frame of a signed-in account must
 * show nothing instead of asking the Root for an entry it no longer retains. */
class DesktopMessagePageExitFrameTest {
    @Test fun aMessagePageNoLongerInTheStackComposesNothingForItsExitFrame() {
        check(GraphicsEnvironment.isHeadless()) { "CPU component tests require the existing headless test JVM" }
        val fixture = RootFixture()
        val pages = DesktopOriginalMessagePagesRoot(fixture.repository,
            DesktopCommunityRepository(fixture.repository, DesktopBlockedUpStore(fixture.plugins)),
            fixture.routes, fixture.environment, { _, _ -> error("No message link") }, false)
        val scene = ImageComposeScene(900, 700, Density(1.5f)) {
            DesktopAppearanceTheme(
                DesktopThemeSettings(uiStyle = AppUiStyle.MATERIAL3, themeMode = AppThemeMode.LIGHT, hapticFeedbackEnabled = false),
                systemLanguageTags = listOf("zh-CN"),
            ) {
                Box(Modifier.fillMaxSize()) {
                    Text("still alive")
                    DesktopOriginalMessagePageRootHost(BiliPaiNavKey.Inbox, pages, fixture.routes, active = false)
                }
            }
        }
        var nanos = 0L
        fun settle() = repeat(6) { nanos += 30_000_000L; scene.render(nanos).close() }
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        fun texts() = scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
            .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
        try {
            assertTrue(pages.isOwned(), "The Root still owns its message pages")
            assertFalse(fixture.routes.containsEntry(BiliPaiNavKey.Inbox), "The Inbox entry has already left the stack")
            settle()
            assertTrue("still alive" in texts(), "The window keeps rendering through the exit frame")
            assertFalse("登录后查看账号内容" in texts(), "A signed-in account never sees the login gate for a leaving page")
        } finally {
            scene.close()
            pages.close()
            fixture.close()
        }
    }

    private inner class RootFixture : AutoCloseable {
        val folder = Files.createTempDirectory("message-exit-frame-root-47-")
        val sessions = DesktopSessionStore(folder.resolve("synthetic-session.json"), persistent = false)
        val repository = DesktopRepository(sessions)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = DesktopPluginStore(folder.resolve("global"))
        val plugins = DesktopPluginContext(store)
        val environment: DesktopHomeEnvironment
        val routes: DesktopOriginalRootRouteAssembly

        init {
            sessions.saveAccount(mapOf("SESSDATA" to "synthetic-47-A", "bili_jct" to "synthetic-only"),
                AccountSummary(47L, "Exit frame synthetic fixture", ""))
            val blocked = DesktopBlockedUpStore(plugins)
            val discovery = DesktopDiscoveryPreferences(folder.resolve("discovery"), blocked)
            val preferences = runBlocking { DesktopOriginalHomePreferences.create(store, scope, false, { false }) }
            val analytics = DesktopHomeRootAnalytics(store, null, { true })
            val pages = object : DesktopHomeEmbeddedRetainedOwner, DesktopHomeEmbeddedPages by unused<DesktopHomeEmbeddedPages>() {
                override fun close() = Unit
                override suspend fun closeAndJoin() = Unit
            }
            val entry = DesktopHomeRetainedEntry.create(repository, discovery, plugins, scope,
                repository.sessionEpoch, 47L, { true }, preferences, MutableStateFlow(false), { false },
                { analytics }, DesktopBlockedUpRepository(repository, blocked),
                { error("No authentication transport observation") }, { error("No Home feedback") }, { _, _ -> pages })
            val lifecycle = object : LifecycleOwner {
                override val lifecycle: Lifecycle = LifecycleRegistry.createUnsafe(this).apply {
                    currentState = Lifecycle.State.RESUMED
                }
            }
            environment = DesktopHomeEnvironment(preferences, plugins, lifecycle,
                MutableStateFlow(false), analytics, { DesktopOriginalHomeGlobalNamespace(store, it) },
                MutableStateFlow(null), { _, _ -> error("No wallpaper request") }, {}, { _, _ -> }, pages,
                { _, _ -> error("No image save") }, { error("No rendering feedback") }, unused<DesktopHomeOverlayPorts>())
            val media = DesktopHomeMediaLifetime(entry.gate.scope, entry.gate::owns, entry.gate::commit) {
                error("No native media request")
            }
            val mediaPorts = DesktopHomeActualMediaPorts(media, { error("No wallpaper MIME request") },
                { _, _, _, _ -> error("No wallpaper rendering") }, MutableStateFlow(false), { error("No media feedback") }).ports
            val returns = DesktopHomeReturnNavigationOwner(entry.gate::owns, entry.gate::commit, { action -> action(); true },
                { Offset.Zero }, { 47L }, VideoCardTransitionClock(), { false }, { false }, { true })
            val root = DesktopHomeRetainedRoot(entry, environment, mediaPorts, returns,
                DesktopLiveNavigationBinding(entry.requests.environment, unused<SearchApi>(), store),
                media, DesktopHomeLottieBinding(repository.httpClient, entry.gate::owns, entry.gate::commit,
                    { error("No animation request") }))
            // The sidebar tab already replaced the stack: Inbox is gone, MainHost is current.
            val stack = mutableStateListOf<BiliPaiNavKey>(BiliPaiNavKey.MainHost)
            routes = DesktopOriginalRootRouteAssembly(root, stack, DesktopOriginalRootRoutePlatform(
                { action -> action(); true }, { _, _ -> }, { _, _ -> error("No video resolution") }, {},
                { error("Unexpected root Back") }), { setOf("home") })
        }

        override fun close() {
            routes.close()
            scope.cancel()
            folder.toFile().deleteRecursively()
        }
    }

    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java)
    ) { proxy, method, args ->
        when {
            method.declaringClass == Any::class.java && method.name == "toString" -> "Unused ${T::class.java.simpleName}"
            method.declaringClass == Any::class.java && method.name == "hashCode" -> System.identityHashCode(proxy)
            method.declaringClass == Any::class.java && method.name == "equals" -> proxy === args?.singleOrNull()
            else -> error("Unexpected unrelated ${T::class.java.simpleName}.${method.name}")
        }
    } as T
}
