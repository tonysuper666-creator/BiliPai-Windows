@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.android.purebilibili.data.repository.DesktopOriginalProfileSplashProtocol
import com.android.purebilibili.feature.list.DesktopFavoriteEnvironment
import com.bilipai.desktop.appearance.DesktopAppearanceTheme
import com.bilipai.desktop.appearance.DesktopThemeSettings
import com.bilipai.desktop.data.DesktopSearchPreferences
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.plugins.asAndroidContext
import java.lang.reflect.Proxy
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** Uses the mounted desktop settings host and the Profile's real preference adapter. */
class DesktopProfileEditSettingTest {
    @Test fun switchWritesTheProfilePreferenceAndRestoresItFromDisk(): Unit = runBlocking {
        DesktopPageTestFixture().use { fixture ->
            val context = fixture.pluginContext
            val profile = DesktopOriginalProfilePreferences(context.store, DesktopSearchPreferences(context.store.root),
                { true }, { it(); true }) { error("unrelated theme write") }
            val failures = mutableListOf<Throwable>()
            val services = DesktopOriginalHomeSettingsRootServices(fixture.home.settings, DesktopFavoritePreferences(context.store), {}) {
                    scope, owns, admit, _ ->
                val favorite = DesktopFavoriteEnvironment(scope, unused(), null, null, unused(),
                    owns, { null }, { null }, {}, null, null, null, null, null, null)
                DesktopProfileEnvironment(scope, owns, admit, unused(), unused(), unused(), unused(),
                    DesktopOriginalProfileSplashProtocol(unused(), unused()), unused(), favorite.favorite, favorite.pgc,
                    { null }, unused(), unused(), unused(), unused(), unused())
            }
            val background = object : DesktopHomeWindowBackgroundPort {
                override val isInBackground = false
                override fun addListener(listener: DesktopHomeWindowBackgroundPort.Listener) {}
                override fun removeListener(listener: DesktopHomeWindowBackgroundPort.Listener) {}
            }
            val platform = DesktopHomePlatform(false, false, false, false, false, false, background, 0f, false)
            val navigation = object : NavigationEventDispatcherOwner {
                override val navigationEventDispatcher = NavigationEventDispatcher()
            }
            val identity = Any()
            val scene = ImageComposeScene(800, 3000, Density(1f), coroutineContext = coroutineContext) {
                DesktopAppearanceTheme(DesktopThemeSettings(), systemLanguageTags = listOf("zh-CN")) {
                    CompositionLocalProvider(LocalDesktopHomePlatform provides platform,
                        LocalDesktopUpstreamAndroidContext provides context.asAndroidContext(),
                        LocalLifecycleOwner provides fixture.lifecycleOwner,
                        LocalNavigationEventDispatcherOwner provides navigation) {
                        DesktopOriginalHomeSettingsRootHost(identity, services, context, { true }, { it(); true },
                            {}, {}, { failures += it })
                    }
                }
            }
            fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
            fun nodes() = scene.semanticsOwners.flatMap { descendants(it.unmergedRootSemanticsNode) }
            fun isTitle(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text)
                ?.any { it.text == "编辑资料按钮" } == true
            fun control() = nodes().filter { node ->
                node.config.getOrNull(SemanticsActions.OnClick) != null && descendants(node).any(::isTitle)
            }.minByOrNull { it.boundsInRoot.width * it.boundsInRoot.height }
            suspend fun await(description: String, predicate: suspend () -> Boolean) {
                try {
                    withTimeout(8000) {
                        while (true) {
                            scene.render(System.nanoTime()).close()
                            if (predicate()) break
                            delay(16)
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    throw AssertionError("$description; failures=$failures", e)
                }
            }
            fun state() = control()?.config?.getOrNull(SemanticsProperties.ToggleableState)
            fun click() { assertTrue(requireNotNull(requireNotNull(control()).config[SemanticsActions.OnClick].action).invoke()) }
            fun coldProfile(): DesktopOriginalProfilePreferences {
                val cold = DesktopPluginStore(context.store.root)
                return DesktopOriginalProfilePreferences(cold, DesktopSearchPreferences(cold.root),
                    { true }, { it(); true }) { error("unrelated theme write") }
            }
            try {
                assertFalse(profile.getShowProfileEditButton().first())
                await("default-off desktop control") { state() == ToggleableState.Off }
                click()
                await("control writes on and Profile adapter observes it") {
                    state() == ToggleableState.On && profile.getShowProfileEditButton().first()
                }
                assertTrue(coldProfile().getShowProfileEditButton().first(), "fresh disk-backed store restores on")
                click()
                await("control writes off and Profile adapter observes it") {
                    state() == ToggleableState.Off && !profile.getShowProfileEditButton().first()
                }
                assertFalse(coldProfile().getShowProfileEditButton().first(), "fresh disk-backed store restores off")
                assertTrue(failures.isEmpty(), failures.toString())
            } finally {
                scene.close()
                navigation.navigationEventDispatcher.dispose()
            }
        }
    }

    private companion object {
        inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
            when (method.name) {
                "toString" -> "Uncalled ${T::class.java.simpleName}"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> error("Unrelated port invoked: ${T::class.java.simpleName}.${method.name}")
            }
        } as T
    }
}
