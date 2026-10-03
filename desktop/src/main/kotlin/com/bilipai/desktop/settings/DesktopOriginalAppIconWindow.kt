package com.bilipai.desktop.settings

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import com.android.purebilibili.core.store.AppIconAppearance
import com.android.purebilibili.feature.settings.resolveIconOptionPreviewRes
import kotlinx.coroutines.*
import java.awt.EventQueue
import java.awt.Window
import javax.imageio.ImageIO

/** Actual window/taskbar consumer. Does not rewrite the installed EXE's PE resources.
 * Mount at the live Root for every page, not only while IconSettings is open. */
@Composable
internal fun DesktopOriginalAppIconWindowConsumer(
    preferences: DesktopOriginalAppIconPreferences,
    hostWindow: Window,
    owns: () -> Boolean,
    onFailure: (Throwable) -> Unit,
) {
    val state by preferences.state.collectAsState(initial = preferences.initialState)
    val appearance by preferences.appearance.collectAsState(initial = preferences.initialAppearance)
    val systemDark = isSystemInDarkTheme()
    val latestOwns by rememberUpdatedState(owns)
    val latestFailure by rememberUpdatedState(onFailure)
    val resource = resolveDesktopOriginalLauncherIconResource(state.appIcon, appearance, systemDark)
    LaunchedEffect(hostWindow, resource, preferences) {
        if (!latestOwns()) return@LaunchedEffect
        try {
            val image = withContext(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                check(latestOwns()) { "Icon window owner retired before decoding" }
                DesktopOriginalAppIconWindowConsumerMarker::class.java.getResourceAsStream(resource).use { input ->
                    requireNotNull(input) { "Original launcher PNG is absent: $resource" }
                    requireNotNull(ImageIO.read(input)) { "Original launcher PNG cannot be decoded: $resource" }
                }
            }
            currentCoroutineContext().ensureActive()
            if (!latestOwns()) return@LaunchedEffect
            withContext(Dispatchers.Main.immediate) {
                check(EventQueue.isDispatchThread()) { "Actual Window icon updates must run on EDT" }
                currentCoroutineContext().ensureActive()
                if (latestOwns()) hostWindow.iconImages = listOf(image)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            if (latestOwns()) latestFailure(failure)
        }
    }
}

private object DesktopOriginalAppIconWindowConsumerMarker

internal fun resolveDesktopOriginalLauncherIconResource(
    key: String, appearance: AppIconAppearance, systemDark: Boolean,
): String {
    val preview = resolveIconOptionPreviewRes(key, appearance)
    return resolveDesktopOriginalIconResource(
        if (key == "icon_3d") "ic_launcher_3d_round" else preview,
        systemDark,
    )
}
