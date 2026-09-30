// GENERATED from app/src/main/java/com/android/purebilibili/feature/dynamic/components/DynamicAdaptiveSegmentedControl.kt; do not edit.
// LF-normalized SHA-256: a18b0abaea2466d176809280036055288a0104fbe38478517c2f5e1ae7ef4208
package com.android.purebilibili.feature.dynamic.components
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.*
import com.android.purebilibili.core.ui.components.*
import top.yukonga.miuix.kmp.blur.Backdrop
@Composable
internal fun DynamicAdaptiveSegmentedControl(
    items: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    itemWidth: Dp,
    height: Dp,
    indicatorHeight: Dp,
    labelFontSize: TextUnit,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = null,
) {
    if (items.isEmpty()) return
        val options = remember(items) {
            items.mapIndexed { index, label -> AppSegmentOption(index, label) }
        }
        AppNativeSegmentedControl(
            options = options,
            selectedValue = selectedIndex,
            modifier = modifier,
            onSelectionChange = onSelected,
        )
}
