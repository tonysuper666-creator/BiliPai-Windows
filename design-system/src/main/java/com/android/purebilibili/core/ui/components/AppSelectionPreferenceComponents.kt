package com.android.purebilibili.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.android.purebilibili.core.ui.AppShapes
import com.android.purebilibili.core.ui.AppPopupSurface
import com.android.purebilibili.core.ui.AppPopupSurfaceType
import com.android.purebilibili.core.ui.AppSurfaceTokens
import com.android.purebilibili.core.ui.ContainerLevel
import com.android.purebilibili.core.ui.LocalAppPopupSurfaceRenderer
import com.android.purebilibili.core.ui.LocalAppThemeConfig
import com.android.purebilibili.core.ui.appContentDialogWidth
import com.android.purebilibili.core.ui.resolveAppContentDialogLayoutPolicy
import com.android.purebilibili.core.ui.resolveAppContentDialogProperties
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.theme.LocalAppUiStyle
import kotlin.math.round
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.preference.WindowSpinnerPreference

enum class AppSingleChoicePresentation(val storageValue: String) {
    WINDOW_POPUP("window_popup"),
    CENTERED_DIALOG("centered_dialog");

    companion object {
        fun fromStorageValue(value: String?): AppSingleChoicePresentation =
            entries.firstOrNull { it.storageValue == value } ?: WINDOW_POPUP
    }
}

val LocalAppSingleChoicePresentation = compositionLocalOf {
    AppSingleChoicePresentation.WINDOW_POPUP
}

@Immutable
data class AppChoiceOption<T>(
    val value: T,
    val label: String,
    val description: String? = null,
)

@Composable
fun <T> AppSingleChoicePreference(
    title: String,
    selectedValue: T,
    options: List<AppChoiceOption<T>>,
    onValueChange: (T) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null,
    enabled: Boolean = true,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    dialogTitle: String = title,
    presentation: AppSingleChoicePresentation = LocalAppSingleChoicePresentation.current,
) {
    if (presentation == AppSingleChoicePresentation.WINDOW_POPUP &&
        LocalAppUiStyle.current == AppUiStyle.MIUIX
    ) {
        val selectedIndex = options.indexOfFirst { it.value == selectedValue }.coerceAtLeast(0)
        val dropdownItems = remember(options) {
            options.map { option ->
                DropdownItem(
                    text = option.label,
                    summary = option.description,
                )
            }
        }
        WindowSpinnerPreference(
            items = dropdownItems,
            selectedIndex = selectedIndex,
            title = title,
            summary = subtitle,
            enabled = enabled,
            modifier = modifier.alpha(if (enabled) 1f else 0.6f),
            startAction = icon?.let { imageVector ->
                {
                    Icon(
                        imageVector = imageVector,
                        contentDescription = null,
                        // 与其他设置条目一致：MD3 官方推荐预设下为 onSurfaceVariant 单色，
                        // 其余预设保留多彩语义色（MIUIX 等）。
                        tint = rememberAdaptivePreferenceIconTint(iconTint),
                        modifier = Modifier.size(24.dp),
                    )
                }
            },
            onSelectedIndexChange = { index ->
                options.getOrNull(index)?.value?.let { requestedValue ->
                    if (shouldDispatchAppChoiceSelection(selectedValue, requestedValue)) {
                        onValueChange(requestedValue)
                    }
                }
            },
        )
        return
    }

    // MD3 预设或居中弹窗模式：统一样式渲染条目，保证列表间距与弹出样式无关。
    // 「跟随选项弹出」时点击条目在锚点处展开 MD3 DropdownMenu。

    var dialogVisible by rememberSaveable { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.value == selectedValue }?.label
    val useWindowMenu = presentation == AppSingleChoicePresentation.WINDOW_POPUP

    Box(modifier = modifier.alpha(if (enabled) 1f else 0.6f)) {
        AppPreference(
            icon = icon,
            title = title,
            subtitle = subtitle,
            value = selectedLabel,
            onClick = when {
                !enabled -> null
                useWindowMenu -> ({ menuExpanded = true })
                else -> ({ dialogVisible = true })
            },
            iconTint = iconTint,
            showChevron = enabled,
            // 跟随弹出的菜单锚定在行尾部（值区域）：点击的是行尾的当前值，
            // 菜单在其下方展开，符合 M3「菜单锚定触发元素」的规范。
            trailingContent = if (useWindowMenu) {
                {
                    AppDropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        options.forEach { option ->
                            val selected = option.value == selectedValue
                            AppDropdownMenuItem(
                                text = {
                                    Column {
                                        AppText(
                                            text = option.label,
                                            style = MaterialTheme.typography.bodyLarge,
                                        )
                                        option.description?.let { description ->
                                            AppText(
                                                text = description,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                },
                                leadingIcon = if (selected) {
                                    {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                } else {
                                    null
                                },
                                onClick = {
                                    menuExpanded = false
                                    if (shouldDispatchAppChoiceSelection(selectedValue, option.value)) {
                                        onValueChange(option.value)
                                    }
                                },
                            )
                        }
                    }
                }
            } else {
                null
            },
        )
    }

    if (dialogVisible) {
        AppSingleChoiceDialog(
            title = dialogTitle,
            selectedValue = selectedValue,
            options = options,
            onValueSelected = { value ->
                if (shouldDispatchAppChoiceSelection(selectedValue, value)) {
                    onValueChange(value)
                }
                dialogVisible = false
            },
            onDismissRequest = { dialogVisible = false },
        )
    }
}

@Composable
fun <T> AppSingleChoiceDialog(
    title: String,
    selectedValue: T,
    options: List<AppChoiceOption<T>>,
    onValueSelected: (T) -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val configuration = LocalConfiguration.current
    val maxDialogHeight = (configuration.screenHeightDp * 0.8f).dp
    val layoutPolicy = remember { resolveAppContentDialogLayoutPolicy(maxWidthDp = 420) }
    // Dialog 使用独立平台窗口；在进入窗口子组合前固定应用主题色，避免其默认色
    // 在“系统深色 + 应用手动浅色”时从窗口配置重新跟随系统。
    val dialogContentColor = MaterialTheme.colorScheme.onSurface
    val dialogSecondaryContentColor = MaterialTheme.colorScheme.onSurfaceVariant
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = resolveAppContentDialogProperties(
            usePlatformDefaultWidth = layoutPolicy.usePlatformDefaultWidth,
        ),
    ) {
        AppPopupSurface(
            type = AppPopupSurfaceType.DIALOG,
            modifier = modifier
                .appContentDialogWidth(policy = layoutPolicy, wrapHeight = false)
                .heightIn(max = maxDialogHeight),
            shape = AppShapes.container(ContainerLevel.Dialog),
            // 层级用更高一档容器色表达，不用 tonalElevation：elevation 会把
            // surfaceTint 混进容器色，自定义亮种子下弹窗会被染成过饱和色。
            containerColor = AppSurfaceTokens.surfaceContainerHigh(),
        ) {
            Column(modifier = Modifier.padding(vertical = 12.dp)) {
                AppText(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = dialogContentColor,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    options.forEach { option ->
                        val selected = option.value == selectedValue
                        AppSingleChoiceRow(
                            selected = selected,
                            onClick = { onValueSelected(option.value) },
                            modifier = Modifier
                                .fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                AppText(
                                    text = option.label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = dialogContentColor,
                                )
                                option.description?.let { description ->
                                    Spacer(modifier = Modifier.height(2.dp))
                                    AppText(
                                        text = description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = dialogSecondaryContentColor,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AppSliderDialogPreference(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null,
    steps: Int = 0,
    enabled: Boolean = true,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    valueFormatter: (Float) -> String = { it.toString() },
    dialogTitle: String = title,
) {
    var dialogVisible by rememberSaveable { mutableStateOf(false) }

    Box(modifier = modifier.alpha(if (enabled) 1f else 0.6f)) {
        AppPreference(
            icon = icon,
            title = title,
            subtitle = subtitle,
            value = valueFormatter(value),
            onClick = if (enabled) ({ dialogVisible = true }) else null,
            iconTint = iconTint,
            showChevron = enabled,
        )
    }

    if (dialogVisible) {
        AppSliderDialog(
            title = dialogTitle,
            value = value,
            valueRange = valueRange,
            steps = steps,
            valueFormatter = valueFormatter,
            onConfirm = { resolvedValue ->
                onValueChange(resolvedValue)
                dialogVisible = false
            },
            onDismissRequest = { dialogVisible = false },
        )
    }
}

/**
 * 滑块确认弹窗尺寸策略（委托统一内容 Dialog 策略）。
 *
 * 按钮区必须用内容尺寸按钮，不可复用 iOS Alert 的 [com.android.purebilibili.core.ui.AppDialogAction]
 * （会 fillMaxSize 撑满父级）。
 */
@Immutable
data class AppSliderDialogLayoutPolicy(
    val usePlatformDefaultWidth: Boolean,
    val horizontalPaddingDp: Int,
    val minWidthDp: Int,
    val maxWidthDp: Int,
)

fun resolveAppSliderDialogLayoutPolicy(): AppSliderDialogLayoutPolicy {
    val base = resolveAppContentDialogLayoutPolicy(maxWidthDp = 420)
    return AppSliderDialogLayoutPolicy(
        usePlatformDefaultWidth = base.usePlatformDefaultWidth,
        horizontalPaddingDp = base.horizontalPaddingDp,
        minWidthDp = base.minWidthDp,
        maxWidthDp = base.maxWidthDp,
    )
}

@Composable
fun AppSliderDialog(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onConfirm: (Float) -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    valueFormatter: (Float) -> String = { it.toString() },
) {
    var draftValue by remember(value, valueRange, steps) {
        mutableFloatStateOf(resolveAppSliderDialogValue(value, valueRange, steps))
    }
    val layoutPolicy = remember { resolveAppSliderDialogLayoutPolicy() }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = resolveAppContentDialogProperties(
            usePlatformDefaultWidth = layoutPolicy.usePlatformDefaultWidth,
        ),
    ) {
        AppPopupSurface(
            type = AppPopupSurfaceType.DIALOG,
            modifier = modifier.appContentDialogWidth(
                policy = resolveAppContentDialogLayoutPolicy(
                    maxWidthDp = layoutPolicy.maxWidthDp,
                    minWidthDp = layoutPolicy.minWidthDp,
                    horizontalPaddingDp = layoutPolicy.horizontalPaddingDp,
                ),
            ),
            shape = AppShapes.container(ContainerLevel.Dialog),
            // 与 AppSingleChoiceDialog 一致：容器色表达层级，不用 tonalElevation tint。
            containerColor = AppSurfaceTokens.surfaceContainerHigh(),
        ) {
            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
                AppText(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(18.dp))
                AppText(
                    text = valueFormatter(draftValue),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Spacer(modifier = Modifier.height(8.dp))
                AppSlider(
                    value = draftValue,
                    onValueChange = { draftValue = it },
                    valueRange = valueRange,
                    steps = steps,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 内容尺寸按钮：避免 AppDialogAction 在 iOS 预设下 fillMaxSize 把弹窗撑满屏高
                    AppTextButton(onClick = onDismissRequest) {
                        AppText("取消")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    AppTextButton(
                        onClick = {
                            onConfirm(resolveAppSliderDialogValue(draftValue, valueRange, steps))
                        },
                    ) {
                        AppText("确定")
                    }
                }
            }
        }
    }
}

fun <T> shouldDispatchAppChoiceSelection(
    selectedValue: T,
    requestedValue: T,
): Boolean = selectedValue != requestedValue

fun resolveAppSliderDialogValue(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
): Float {
    val start = valueRange.start
    val end = valueRange.endInclusive
    if (end <= start) return start

    val clamped = value.coerceIn(start, end)
    val intervalCount = steps.coerceAtLeast(0) + 1
    if (intervalCount <= 1) return clamped

    val interval = (end - start) / intervalCount
    return (start + round((clamped - start) / interval) * interval).coerceIn(start, end)
}
