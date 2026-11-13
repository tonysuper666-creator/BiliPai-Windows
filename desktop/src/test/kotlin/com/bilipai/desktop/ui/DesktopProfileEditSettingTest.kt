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
import com.android.purebilibili.feature.home.UserState
import com.android.purebilibili.feature.profile.ProfileUiState
import com.android.purebilibili.feature.profile.ProfileViewModel
import com.bilipai.desktop.appearance.DesktopAppearanceTheme
import com.bilipai.desktop.appearance.DesktopThemeSettings
import com.bilipai.desktop.data.DesktopSearchPreferences
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.plugins.asAndroidContext
import java.lang.reflect.Proxy
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow

/** Real settings host -> disk preferences -> retained ProfileScreen's actual button. */
class DesktopProfileEditSettingTest {
    @Test fun switchWritesTheProfilePreferenceAndRestoresItFromDisk(): Unit = runBlocking {
        DesktopPageTestFixture().use { fixture ->
            val context = fixture.pluginContext
            val profile = DesktopOriginalProfilePreferences(context.store, DesktopSearchPreferences(context.store.root),
                { true }, { it(); true }) { error("unrelated theme write") }
            val failures = mutableListOf<Throwable>()
            val navigation = object : NavigationEventDispatcherOwner {
                override val navigationEventDispatcher = NavigationEventDispatcher()
            }
            val profileFailures = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
            val profileJob = SupervisorJob(fixture.scope.coroutineContext[Job])
            val profileScope = CoroutineScope(profileJob + Dispatchers.Default + CoroutineExceptionHandler { _, e -> profileFailures += e })
            fun mountProfile(preferences: DesktopProfilePreferences): ImageComposeScene {
                val favorite = DesktopFavoriteEnvironment(profileScope, unused(), null, null, unused(),
                    { true }, { null }, { null }, {}, null, null, null, null, null, null)
                val accounts = port<DesktopProfileAccountPort> { name -> when (name) {
                    "getAccounts" -> emptyList<Any>()
                    "getActiveAccountMid", "currentMid" -> 1408L
                    "getPlaybackAccountMid" -> null
                    "hasSession" -> true
                    else -> error("Account operation forbidden: $name")
                } }
                val window = port<DesktopProfilePlatform> { name -> when (name) {
                    "getConfiguration" -> MutableStateFlow(DesktopProfileWindowConfiguration(800, 1400))
                    "getSupportsRenderEffectBackedHaze" -> false
                    "acquireSystemBars" -> AutoCloseable {}
                    else -> error("Platform operation forbidden: $name")
                } }
                val analytics = port<DesktopProfileAnalytics> { name -> when (name) {
                    "logScreenView", "syncUserContext" -> Unit
                    else -> error("Unexpected analytics: $name")
                } }
                val environment = DesktopProfileEnvironment(profileScope, { true }, { it(); true },
                    unused(), unused(), unused(), unused(), DesktopOriginalProfileSplashProtocol(unused(), unused()),
                    unused(), favorite.favorite, favorite.pgc, { null }, accounts, preferences, window, unused(), analytics)
                val binding = DesktopOriginalProfileBinding(environment)
                // Seed only transient server data. Keep the real ViewModel, preference collector and
                // ProfileScreen; an inactive retained page avoids network/account loading entirely.
                val field = ProfileViewModel::class.java.getDeclaredField("_uiState").apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST")
                val state = field.get(binding.viewModel) as DesktopOwnedProfileState<ProfileUiState>
                state.value = ProfileUiState.Success(UserState(isLogin = true, mid = 1408L, name = "TON1408 synthetic profile"))
                val noNavigation = { error("Unrelated navigation") }
                val routes = DesktopProfileNavigation(noNavigation, noNavigation, noNavigation, noNavigation, noNavigation,
                    { noNavigation() }, noNavigation, noNavigation, noNavigation, noNavigation,
                    { _, _, _ -> noNavigation() }, { noNavigation() }, { noNavigation() },
                    noNavigation, noNavigation, noNavigation, { noNavigation() }, { _, _ -> noNavigation() }, noNavigation)
                return ImageComposeScene(800, 1400, Density(1f), coroutineContext = coroutineContext) {
                    DesktopAppearanceTheme(DesktopThemeSettings(), systemLanguageTags = listOf("zh-CN")) {
                        CompositionLocalProvider(LocalLifecycleOwner provides fixture.lifecycleOwner,
                            LocalNavigationEventDispatcherOwner provides navigation) {
                            DesktopOriginalProfileHost(binding, routes, false, 0, true, null, null, null, null, false, null)
                        }
                    }
                }
            }
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
            var profileScene: ImageComposeScene? = null
            fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
            fun nodes() = scene.semanticsOwners.flatMap { descendants(it.unmergedRootSemanticsNode) }
            fun profileNodes() = requireNotNull(profileScene).semanticsOwners.flatMap { descendants(it.unmergedRootSemanticsNode) }
            fun hasProfileText(text: String) = profileNodes().any { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
            }
            fun editButtonVisible() = profileNodes().any { node ->
                node.config.getOrNull(SemanticsActions.OnClick) != null && descendants(node).any {
                    it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "编辑资料" } == true
                }
            }
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
                            profileScene?.render(System.nanoTime())?.close()
                            assertTrue(profileFailures.isEmpty(), profileFailures.toString())
                            if (predicate()) break
                            delay(16)
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    throw AssertionError("$description; failures=$failures", e)
                }
            }
            suspend fun assertProfileButton(visible: Boolean) {
                await("real ProfileScreen edit button visible=$visible") {
                    hasProfileText("TON1408 synthetic profile") && editButtonVisible() == visible
                }
                // Absence must survive collection/recomposition, rather than pass on an empty tree.
                repeat(12) {
                    delay(16)
                    requireNotNull(profileScene).render(System.nanoTime()).close()
                    assertTrue(hasProfileText("TON1408 synthetic profile"))
                    assertEquals(visible, editButtonVisible())
                    assertTrue(profileFailures.isEmpty(), profileFailures.toString())
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
                profileScene = mountProfile(profile)
                assertProfileButton(false)
                click()
                await("control writes on and Profile adapter observes it") {
                    state() == ToggleableState.On && profile.getShowProfileEditButton().first()
                }
                assertProfileButton(true)
                assertTrue(coldProfile().getShowProfileEditButton().first(), "fresh disk-backed store restores on")
                profileScene?.close()
                profileScene = mountProfile(coldProfile())
                assertProfileButton(true)
                // Resume with the live store: independently constructed stores do not share updates.
                profileScene?.close()
                profileScene = mountProfile(profile)
                assertProfileButton(true)
                click()
                await("control writes off and Profile adapter observes it") {
                    state() == ToggleableState.Off && !profile.getShowProfileEditButton().first()
                }
                assertProfileButton(false)
                assertFalse(coldProfile().getShowProfileEditButton().first(), "fresh disk-backed store restores off")
                profileScene?.close()
                profileScene = mountProfile(coldProfile())
                assertProfileButton(false)
                assertTrue(failures.isEmpty(), failures.toString())
            } finally {
                profileScene?.close()
                profileJob.cancelAndJoin()
                scene.close()
                navigation.navigationEventDispatcher.dispose()
            }
        }
    }

    private companion object {
        inline fun <reified T> port(crossinline call: (String) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
                when (method.name) {
                    "toString" -> "Test ${T::class.java.simpleName}"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> call(method.name)
                }
            } as T
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
