// Original source app/src/main/java/com/android/purebilibili/core/ui/blur/RecoverableVisualEffects.kt
// LF SHA256 e37ef5012d302af1f8a02d5c7e4932c0a7644f06ae1d5907947602ee672b430e
package com.android.purebilibili.core.ui.blur
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.bilipai.desktop.ui.LocalDesktopHomePlatform
import com.bilipai.desktop.ui.DesktopHomeWindowBackgroundPort
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import java.util.Collections
import java.util.WeakHashMap

private val recoverableBlurGates: MutableMap<HazeState, MutableState<Boolean>> =
    Collections.synchronizedMap(WeakHashMap())

@Composable
fun recoverableBlurEnabled(state: HazeState): Boolean {
    val gate = recoverableBlurGates[state] ?: return true
    return gate.value
}

fun Modifier.hazeSourceCompat(state: HazeState): Modifier {
    return hazeSource(state)
}

internal fun shouldEnableRecoverableHeavyVisualEffects(
    userEnabled: Boolean,
    isAppInBackground: Boolean
): Boolean {
    return userEnabled && !isAppInBackground
}

@Composable
internal fun rememberRecoverableHazeState(
    userEnabled: Boolean = true,
    initialBlurEnabled: Boolean = true,
    platform: com.bilipai.desktop.ui.DesktopHomePlatform = LocalDesktopHomePlatform.current
): HazeState {
    var isAppInBackground by remember(platform.background) { mutableStateOf(platform.background.isInBackground) }
    var recreationKey by remember { mutableIntStateOf(0) }
    val shouldRecreateState = platform.recreateHazeOnResume
    val hazeState = remember(recreationKey) {
        HazeState()
    }
    val blurGate = remember(hazeState, initialBlurEnabled) {
        mutableStateOf(initialBlurEnabled).also { recoverableBlurGates[hazeState] = it }
    }

    DisposableEffect(hazeState) {
        recoverableBlurGates[hazeState] = blurGate
        onDispose {
            recoverableBlurGates.remove(hazeState)
        }
    }

    DisposableEffect(shouldRecreateState) {
        val listener = object : DesktopHomeWindowBackgroundPort.Listener {
            override fun onEnterBackground() {
                isAppInBackground = true
                if (shouldRecreateState) {
                    recreationKey += 1
                }
            }

            override fun onEnterForeground() {
                isAppInBackground = false
                if (shouldRecreateState) {
                    recreationKey += 1
                }
            }
        }
        platform.background.addListener(listener)
        onDispose {
            platform.background.removeListener(listener)
        }
    }

    SideEffect {
        blurGate.value = shouldEnableRecoverableHeavyVisualEffects(
            userEnabled = userEnabled,
            isAppInBackground = isAppInBackground
        )
    }

    return hazeState
}
