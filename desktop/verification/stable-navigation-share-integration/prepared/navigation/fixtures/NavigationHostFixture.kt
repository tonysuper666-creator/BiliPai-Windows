package com.bilipai.desktop.ui

import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.android.purebilibili.navigation3.*
import com.android.purebilibili.navigation3.predictiveback.BiliPaiPredictiveBackAnimationStyle
import com.android.purebilibili.core.ui.transition.*
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.jar.JarFile

private val OWNER_KEY = object : CreationExtras.Key<String> {}
private class FixtureViewModel : ViewModel() {
    companion object { var cleared = 0 }
    override fun onCleared() { cleared++ }
}

fun main(args: Array<String>) = runBlocking {
    val candidate = JarFile(args.single())
    val loader = Thread.currentThread().contextClassLoader
    val classes = candidate.entries().asSequence().map { it.name }.filter { it.endsWith(".class") }.toList()
    classes.forEach { Class.forName(it.removeSuffix(".class").replace('/', '.'), false, loader) }
    check(classes.size == 173)
    println("PASS all 173 candidate JVM classes actually load against immutable actual40")

    val platform = DesktopNavigationEntryViewModelPlatform()
    val method = Class.forName("com.android.purebilibili.navigation3.BiliPaiNavDisplayHostKt")
        .getDeclaredMethod("buildMiuixNavViewModelStoreOwner", ViewModelStoreOwner::class.java,
            DesktopNavigationEntryViewModelPlatform::class.java).apply { isAccessible = true }
    val factory = viewModelFactory {
        initializer { check(this[OWNER_KEY] == "actual-extra"); FixtureViewModel() }
    }
    fun entry(): ViewModelStoreOwner = object : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
        override val viewModelStore = ViewModelStore()
        override val defaultViewModelProviderFactory = factory
        override val defaultViewModelCreationExtras = MutableCreationExtras().apply { this[OWNER_KEY] = "actual-extra" }
    }
    val first = entry()
    val wrapped = method.invoke(null, first, platform) as ViewModelStoreOwner
    check(wrapped.viewModelStore === first.viewModelStore)
    check((wrapped as HasDefaultViewModelProviderFactory).defaultViewModelProviderFactory === factory)
    check(wrapped.defaultViewModelCreationExtras[OWNER_KEY] == "actual-extra")
    val same = method.invoke(null, first, platform) as ViewModelStoreOwner
    val one = ViewModelProvider.create(wrapped)["one", FixtureViewModel::class]
    val again = ViewModelProvider.create(same)["one", FixtureViewModel::class]
    val otherEntry = entry()
    val other = method.invoke(null, otherEntry, platform) as ViewModelStoreOwner
    val two = ViewModelProvider.create(other)["one", FixtureViewModel::class]
    check(one === again && two !== one && FixtureViewModel.cleared == 0)
    otherEntry.viewModelStore.clear()
    check(FixtureViewModel.cleared == 1)
    check(ViewModelProvider.create(wrapped)["one", FixtureViewModel::class] === one)
    first.viewModelStore.clear()
    check(FixtureViewModel.cleared == 2)
    println("PASS original host's actual helper preserves KMP factory/extras/exact per-entry store and clear isolation")

    val video = BiliPaiNavKey.VideoDetail("BV-FIXTURE", cid = 701, sourceRoute = "home", openId = 1)
    val controller = BiliPaiNavBackStackController(listOf(BiliPaiNavKey.MainHost))
        .push(BiliPaiNavKey.Settings).push(BiliPaiNavKey.SettingsSearch).push(video)
    check(controller.currentKey == video)
    check(controller.pop().currentKey == BiliPaiNavKey.SettingsSearch)
    check(controller.popToRoot().backStack == listOf(BiliPaiNavKey.MainHost))
    check(BiliPaiPredictiveBackAnimationStyle.fromStorageValue("classic") == BiliPaiPredictiveBackAnimationStyle.CLASSIC)
    check(resolvePredictiveBackGestureBlurProgress(0f) == 1f)
    check(resolvePredictiveBackGestureBlurProgress(1f) == 0f)
    check(!shouldPaintHostOwnedDepthLayer(VideoCardTransitionExposure.Returning, false,
        motionTier = com.android.purebilibili.core.ui.adaptive.MotionTier.Normal,
        realtimeBlurEnabled = true, renderEffectSupported = true))
    check(shouldPaintHostOwnedDepthLayer(VideoCardTransitionExposure.Returning, true,
        motionTier = com.android.purebilibili.core.ui.adaptive.MotionTier.Normal,
        realtimeBlurEnabled = true, renderEffectSupported = true))
    check(!shouldReleaseHostOwnedDepthLayer(VideoCardTransitionExposure.SettledHidden))
    check(shouldReleaseHostOwnedDepthLayer(VideoCardTransitionExposure.Idle))
    println("PASS original stack and drawable-only retained depth/gesture policies")

    val root = Files.createTempDirectory("original-navigation-global-store-")
    val store = DesktopPluginStore(root)
    val context = DesktopPluginContext(store)
    check(DesktopOriginalNavigationHostSettings.getClickToPlay(context).first())
    check(DesktopOriginalNavigationHostSettings.getClickToPlaySync(context))
    check(!DesktopOriginalNavigationHostSettings.getFullScreenSwipeBackEnabled(context).first())
    check(!DesktopOriginalNavigationHostSettings.getVideoTransitionRealtimeBlurEnabled(context).first())
    check(DesktopOriginalNavigationHostSettings.getRelatedVideoTransitionEnabled(context).first())
    context.getSharedPreferences("test_namespace", 0).edit().putString("preserve", "sentinel").apply()
    DesktopOriginalNavigationHostSettings.setClickToPlay(context, false)
    DesktopOriginalNavigationHostSettings.setFullScreenSwipeBackEnabled(context, true)
    DesktopOriginalNavigationHostSettings.setVideoTransitionRealtimeBlurEnabled(context, true)
    DesktopOriginalNavigationHostSettings.setRelatedVideoTransitionEnabled(context, false)
    check(!DesktopOriginalNavigationHostSettings.getClickToPlay(context).first())
    check(!DesktopOriginalNavigationHostSettings.getClickToPlaySync(context))
    check(DesktopOriginalNavigationHostSettings.getFullScreenSwipeBackEnabled(context).first())
    check(DesktopOriginalNavigationHostSettings.getVideoTransitionRealtimeBlurEnabled(context).first())
    check(!DesktopOriginalNavigationHostSettings.getRelatedVideoTransitionEnabled(context).first())
    check(context.getSharedPreferences("test_namespace", 0).getString("preserve", null) == "sentinel")
    val persisted = Files.readString(root.resolve("plugin-settings.json"))
    check(persisted.contains("full_screen_swipe_back_enabled") && persisted.contains("auto_play_cache"))
    println("PASS four original settings keys/defaults/writers and synchronous autoplay cache use the same actual global disk backing")
    println("Scope: prepared source and offline actors only; no Root NavDisplay mounting, Android capability, HWND, gesture, native rendering or account/API")
}
