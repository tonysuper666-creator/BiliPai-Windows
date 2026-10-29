// Replaces upstream design-system/src/main/java/com/android/purebilibili/core/ui/AppWindowBlurBehind.kt sha256=7c8a0875951b3c27338ca4ea48327ac109ce6c4f2df003c9316fe77f6572c5f5
// Upstream blurs the Activity content view or the dialog window through android.view/WindowManager; the desktop blurs the modal content target the shell provides (DesktopOriginalWindowModalBlur.kt). With no target the effect is not applied, as upstream does below API 31.
package com.android.purebilibili.core.ui
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bilipai.desktop.ui.LocalDesktopOriginalModalContentBlurTarget
import com.bilipai.desktop.ui.DesktopOriginalModalContentBlurTarget
@Composable
fun ModalWindowBlurBehindEffect(
    enabled: Boolean = true,
    radius: Dp = 24.dp,
    progress: () -> Float = { 1f },
) {
    val target = LocalDesktopOriginalModalContentBlurTarget.current
    val density = LocalDensity.current
    val latestProgress by rememberUpdatedState(progress)
    DisposableEffect(target, enabled, radius, density) {
        if (!enabled || target == null) return@DisposableEffect onDispose { }
        val owner = Any()
        val maxRadius = with(density) { radius.toPx() }
        fun updateBlur() {
            val fraction = latestProgress().let { if (it.isFinite()) it.coerceIn(0f, 1f) else 0f }
            ModalContentBlur.acquire(target, owner, maxRadius * fraction)
        }
        updateBlur()
        val observation = target.observeProgress({ latestProgress() }, ::updateBlur)
        onDispose {
            observation.close()
            ModalContentBlur.release(target, owner)
        }
    }
}

internal object ModalContentBlur {
    private val owners = java.util.WeakHashMap<DesktopOriginalModalContentBlurTarget, MutableMap<Any, Float>>()
    private val appliedRadii = java.util.WeakHashMap<DesktopOriginalModalContentBlurTarget, Float>()

    fun acquire(view: DesktopOriginalModalContentBlurTarget, owner: Any, radius: Float) {
        owners.getOrPut(view) { mutableMapOf() }[owner] = radius
        update(view)
    }

    fun release(view: DesktopOriginalModalContentBlurTarget, owner: Any) {
        owners[view]?.remove(owner)
        update(view)
        if (owners[view].isNullOrEmpty()) {
            owners.remove(view)
            appliedRadii.remove(view)
        }
    }

    private fun update(view: DesktopOriginalModalContentBlurTarget) {
        val radius = owners[view]?.values?.maxOrNull() ?: 0f
        if (appliedRadii[view] == radius) return
        appliedRadii[view] = radius
        view.setBlurRadiusPx(radius)
    }
}
