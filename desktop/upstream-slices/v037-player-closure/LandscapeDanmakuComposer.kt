package com.android.purebilibili.feature.video.ui.components

import com.android.purebilibili.core.ui.motion.folmeExpandEnterTransition
import com.android.purebilibili.core.ui.motion.folmeExpandExitTransition
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import com.android.purebilibili.core.ui.AppShapes
import com.android.purebilibili.core.ui.ContainerLevel
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.android.purebilibili.core.ui.components.AppButton
import com.android.purebilibili.core.ui.components.AppCheckbox
import com.android.purebilibili.core.ui.components.AppCircularProgressIndicator
import com.android.purebilibili.core.ui.components.AppFilterChip
import com.android.purebilibili.core.ui.components.AppIcon
import com.android.purebilibili.core.ui.components.AppIconButton
import androidx.compose.material3.MaterialTheme
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.core.ui.components.AppTextButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Close
import kotlinx.coroutines.delay

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LandscapeDanmakuComposer(
    visible: Boolean,
    onDismiss: () -> Unit,
    onSend: (message: String, color: Int, mode: Int, fontSize: Int, attentionCommand: Boolean) -> Unit,
    isSending: Boolean,
    initialColor: Int,
    initialMode: Int,
    initialFontSize: Int,
    initialText: String,
    initialAttentionCommand: Boolean,
    onDraftChange: (String, Boolean) -> Unit,
    onSelectionChange: (color: Int, mode: Int, fontSize: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (!visible) return
    com.android.purebilibili.core.ui.AppModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        presentationOverride = com.android.purebilibili.core.ui.AppModalPresentation.BottomSheet,
        sheetSurfaceModifier = Modifier.imePadding(),
    ) {
        LandscapeDanmakuComposerContent(
            visible = visible,
            onDismiss = onDismiss,
            onSend = onSend,
            isSending = isSending,
            initialColor = initialColor,
            initialMode = initialMode,
            initialFontSize = initialFontSize,
            initialText = initialText,
            initialAttentionCommand = initialAttentionCommand,
            onDraftChange = onDraftChange,
            onSelectionChange = onSelectionChange,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun LandscapeDanmakuComposerContent(
    visible: Boolean,
    onDismiss: () -> Unit,
    onSend: (message: String, color: Int, mode: Int, fontSize: Int, attentionCommand: Boolean) -> Unit,
    isSending: Boolean,
    initialColor: Int,
    initialMode: Int,
    initialFontSize: Int,
    initialText: String,
    initialAttentionCommand: Boolean,
    onDraftChange: (String, Boolean) -> Unit,
    onSelectionChange: (color: Int, mode: Int, fontSize: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val colorOptions = remember { danmakuSendColorOptions() }
    val modeOptions = remember { danmakuSendModeOptions() }
    val fontSizeOptions = remember { danmakuSendFontSizeOptions() }

    var text by rememberSaveable { mutableStateOf(initialText) }
    var selectedColor by rememberSaveable { mutableIntStateOf(initialColor) }
    var selectedMode by rememberSaveable { mutableIntStateOf(initialMode) }
    var selectedFontSize by rememberSaveable { mutableIntStateOf(initialFontSize) }
    var attentionCommandChecked by rememberSaveable { mutableStateOf(initialAttentionCommand) }
    val useMiuixSpring = com.android.purebilibili.core.theme.LocalAppUiStyle.current == com.android.purebilibili.core.theme.AppUiStyle.MIUIX
    var showStylePanel by remember { mutableStateOf(false) }
    var showAdvancedOptions by remember { mutableStateOf(false) }
    var showCustomColorPicker by remember { mutableStateOf(false) }
    var lastCustomColor by remember { mutableIntStateOf(0x66CCFF) }

    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    DisposableEffect(keyboardController, focusManager) {
        onDispose {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
        }
    }

    var inputWasVisible by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(visible, showStylePanel, showCustomColorPicker) {
        if (!visible) {
            inputWasVisible = false
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
            return@LaunchedEffect
        }
        if (!inputWasVisible) {
            inputWasVisible = true
            val selection = resolveDanmakuSendSelectionState(
                initialColor = initialColor,
                initialMode = initialMode,
                initialFontSize = initialFontSize,
                colorOptions = colorOptions.map { it.value },
                modeOptions = modeOptions.map { it.value },
                fontSizeOptions = fontSizeOptions.map { it.value }
            )
            text = initialText
            selectedColor = selection.color
            selectedMode = selection.mode
            selectedFontSize = selection.fontSize
            attentionCommandChecked = initialAttentionCommand
            showStylePanel = false
            showAdvancedOptions = false
        }
        if (showStylePanel || showCustomColorPicker) {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
            return@LaunchedEffect
        }
        delay(80)
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    LaunchedEffect(selectedColor, selectedMode, selectedFontSize, visible) {
        if (!visible) return@LaunchedEffect
        onSelectionChange(selectedColor, selectedMode, selectedFontSize)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {


        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AppIconButton(
                onClick = {
                    focusManager.clearFocus(force = true)
                    keyboardController?.hide()
                    onDismiss()
                },
                modifier = Modifier.size(48.dp)
            ) {
                AppIcon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "关闭",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f),
                    modifier = Modifier.size(18.dp)
                )
            }

            com.android.purebilibili.core.ui.components.AppOutlinedTextField(
                value = text,
                onValueChange = {
                    if (it.length <= 100) {
                        text = it
                        onDraftChange(it, attentionCommandChecked)
                    }
                },
                modifier = Modifier.weight(1f).testTag("danmaku_landscape_composer_input"),
                focusRequester = focusRequester,
                enabled = !isSending,
                placeholderText = "发个友善的弹幕见证当下",
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (text.isNotBlank() && !isSending) {
                            onSend(
                                text.trim(),
                                selectedColor,
                                selectedMode,
                                selectedFontSize,
                                attentionCommandChecked
                            )
                        }
                    }
                ),
            )

            AppIconButton(
                onClick = { showStylePanel = !showStylePanel }
            ) {
                AppIcon(
                    imageVector = Icons.Outlined.Palette,
                    contentDescription = "弹幕样式",
                    tint = if (showStylePanel) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.86f)
                    }
                )
            }

            AppButton(
                onClick = {
                    if (text.isNotBlank() && !isSending) {
                        onSend(
                            text.trim(),
                            selectedColor,
                            selectedMode,
                            selectedFontSize,
                            attentionCommandChecked
                        )
                    }
                },
                enabled = text.isNotBlank() && !isSending,
                modifier = Modifier
                    .height(48.dp)
                    .width(72.dp),
                shape = AppShapes.container(ContainerLevel.Pill),
                contentPadding = PaddingValues(horizontal = 12.dp)
            ) {
                if (isSending) {
                    AppCircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    AppText(
                        text = "发送",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppText(
                text = if (showStylePanel) "样式仅作用于本条弹幕" else "回车或点发送即可提交",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                style = MaterialTheme.typography.labelSmall
            )
            AppText(
                text = "${text.length}/100",
                color = if (text.length > 90) {
                    MaterialTheme.colorScheme.error.copy(alpha = 0.92f)
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
                },
                style = MaterialTheme.typography.labelSmall
            )
        }

        AnimatedVisibility(
            visible = showStylePanel,
            enter = folmeExpandEnterTransition(useMiuixSpring),
            exit = folmeExpandExitTransition(useMiuixSpring)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DanmakuColorOptions(
                    selectedColor = selectedColor,
                    onColorSelected = { selectedColor = it },
                    onCustomColor = { showCustomColorPicker = true },
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    modeOptions.forEach { option ->
                        AppFilterChip(
                            selected = selectedMode == option.value,
                            onClick = { selectedMode = option.value },
                            label = { AppText(option.label, style = MaterialTheme.typography.labelMedium) }
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    fontSizeOptions.forEach { option ->
                        AppFilterChip(
                            selected = selectedFontSize == option.value,
                            onClick = { selectedFontSize = option.value },
                            label = { AppText(option.label, style = MaterialTheme.typography.labelMedium) }
                        )
                    }
                }

                if (showAdvancedOptions) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(AppShapes.container(ContainerLevel.Field))
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            .clickable {
                                attentionCommandChecked = !attentionCommandChecked
                                onDraftChange(text, attentionCommandChecked)
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppCheckbox(
                            checked = attentionCommandChecked,
                            onCheckedChange = {
                                attentionCommandChecked = it
                                onDraftChange(text, it)
                            }
                        )
                        Column {
                            AppText("内嵌关注按钮", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall)
                            AppText(
                                "发送视频内嵌关注按钮",
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                } else {
                    AppTextButton(
                        onClick = { showAdvancedOptions = true },
                        contentPadding = PaddingValues(horizontal = 0.dp)
                    ) {
                        AppText("更多发送选项", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }

    if (showCustomColorPicker) {
        DanmakuCustomColorPickerDialog(
            initialColor = lastCustomColor,
            onConfirm = { picked ->
                lastCustomColor = picked
                selectedColor = picked
                showCustomColorPicker = false
            },
            onDismiss = { showCustomColorPicker = false }
        )
    }
}
