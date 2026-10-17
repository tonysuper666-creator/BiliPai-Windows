package com.android.purebilibili.core.ui.renderer.miuix

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.android.purebilibili.core.ui.components.appDesktopFocusableItemVisuals
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.SwitchDefaults

@Composable
internal fun AppMiuixSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier,
    enabled: Boolean,
) {
    ProvideAppMiuixHapticFeedback {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            // Miuix's native enabled thumbs are white in both themes. The app's
            // onPrimary/onSecondary roles can become dark for readable text on a
            // custom accent; they must not recolor the switch handles. Keep track
            // accents and disabled colors native. This renderer is only reached
            // for non-glass Miuix; Material3 and glass keep their own color rules.
            colors = SwitchDefaults.switchColors(
                checkedThumbColor = Color.White,
                uncheckedThumbColor = Color.White,
            ),
            modifier = modifier.appDesktopFocusableItemVisuals(enabled && onCheckedChange != null),
        )
    }
}
