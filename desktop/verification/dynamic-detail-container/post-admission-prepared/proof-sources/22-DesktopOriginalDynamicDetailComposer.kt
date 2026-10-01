// Original source app/src/main/java/com/android/purebilibili/feature/dynamic/components/DynamicCommentSheet.kt
// OriginalLF_SHA256 cbc5bbcabd8ef19c6c73fb13dca6ff7248bf208cc8128a6dd240fc9f93233c05
package com.android.purebilibili.feature.dynamic.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import com.android.purebilibili.core.ui.AppSpacingTokens
import com.android.purebilibili.core.ui.isMiuixNonGlassEnabled
import com.android.purebilibili.core.ui.rememberAppClearIcon
import com.android.purebilibili.core.ui.components.AppIcon
import com.android.purebilibili.core.ui.components.AppIconButton
import com.android.purebilibili.core.ui.components.AppOutlinedTextField
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.feature.dynamic.resolveDynamicCommentComposerHint
import com.android.purebilibili.feature.dynamic.resolveDynamicCommentImeSubmission
import com.android.purebilibili.feature.home.components.BottomBarMatchedReusableLiquidDock
import com.android.purebilibili.feature.home.components.resolveFloatingDockGeometryScale
import com.android.purebilibili.feature.home.components.resolveSharedBottomBarCapsuleShape
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.blur.Backdrop as MiuixBackdrop

@Composable
fun DesktopOriginalDynamicDetailComposer(
    onPostComment: (String) -> Unit,
    replyTargetUname: String? = null,
    onClearReplyTarget: () -> Unit = {},
    liquidGlassEnabled: Boolean = false,
    backdrop: MiuixBackdrop? = null,
    modifier: Modifier = Modifier,
) {
    var commentText by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    // Actual Windows focus and IME use the existing Compose focus manager/input session.
    val focusManager = LocalFocusManager.current

    LaunchedEffect(replyTargetUname) {
        if (!replyTargetUname.isNullOrBlank()) {
            delay(50L)
            try {
                focusRequester.requestFocus()
            } catch (_: Exception) {}
        }
    }

    DesktopOriginalDynamicDetailComposerContent(
        value = commentText,
        onValueChange = { commentText = it },
        onSubmit = {
            onPostComment(it)
            commentText = ""
            if (!replyTargetUname.isNullOrBlank()) onClearReplyTarget()
            focusManager.clearFocus()
        },
        hint = resolveDynamicCommentComposerHint(replyTargetUname),
        onClearReplyTarget = if (replyTargetUname.isNullOrBlank()) null else onClearReplyTarget,
        liquidGlassEnabled = liquidGlassEnabled,
        backdrop = backdrop,
        focusRequester = focusRequester,
        modifier = modifier,
    )
}

@Composable
private fun DesktopOriginalDynamicDetailComposerContent(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: (String) -> Unit,
    hint: String = resolveDynamicCommentComposerHint(),
    onClearReplyTarget: (() -> Unit)? = null,
    liquidGlassEnabled: Boolean = false,
    backdrop: MiuixBackdrop? = null,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    val useMiuixNonGlassInput = isMiuixNonGlassEnabled()
    val dockShape = resolveSharedBottomBarCapsuleShape()
    val composerHeight = AppSpacingTokens.TripleExtraLarge + AppSpacingTokens.Small
    val composerLensIntensity = resolveFloatingDockGeometryScale(composerHeight.value)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val commentFieldContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        BottomBarMatchedReusableLiquidDock(
            shape = dockShape,
            modifier = Modifier
                .weight(1f)
                .height(composerHeight)
                .then(
                    if (!liquidGlassEnabled) {
                        Modifier
                            .clip(dockShape)
                            .background(commentFieldContainerColor)
                    } else {
                        Modifier
                    }
                ),
            reuseEnabled = liquidGlassEnabled,
            backdrop = backdrop,
            drawShellLens = true,
            shellLensIntensity = composerLensIntensity,
        ) { liquidChromeActive ->
            val fieldColor = if (liquidChromeActive) Color.Transparent else commentFieldContainerColor
            val fieldTextColor = MaterialTheme.colorScheme.onSurface
            val placeholderColor = if (liquidChromeActive) {
                fieldTextColor.copy(alpha = 0.82f)
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
            val keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send)
            val keyboardActions = KeyboardActions(
                onSend = {
                    resolveDynamicCommentImeSubmission(value)?.let(onSubmit)
                },
            )
            if (useMiuixNonGlassInput) {
                AppOutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
                    placeholderText = hint,
                    singleLine = true,
                    keyboardOptions = keyboardOptions,
                    keyboardActions = keyboardActions,
                    shape = dockShape,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = fieldTextColor
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = fieldColor,
                        unfocusedContainerColor = fieldColor,
                        disabledContainerColor = fieldColor,
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        disabledBorderColor = Color.Transparent,
                        focusedTextColor = fieldTextColor,
                        unfocusedTextColor = fieldTextColor,
                        disabledTextColor = fieldTextColor.copy(alpha = 0.72f),
                        focusedPlaceholderColor = placeholderColor,
                        unfocusedPlaceholderColor = placeholderColor,
                        disabledPlaceholderColor = placeholderColor,
                        cursorColor = fieldTextColor,
                    ),
                )
            } else {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
                    placeholder = {
                        AppText(
                            text = hint,
                            color = placeholderColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    singleLine = true,
                    keyboardOptions = keyboardOptions,
                    keyboardActions = keyboardActions,
                    shape = dockShape,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = fieldTextColor
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = fieldColor,
                        unfocusedContainerColor = fieldColor,
                        disabledContainerColor = fieldColor,
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        disabledBorderColor = Color.Transparent,
                        focusedTextColor = fieldTextColor,
                        unfocusedTextColor = fieldTextColor,
                        disabledTextColor = fieldTextColor.copy(alpha = 0.72f),
                        focusedPlaceholderColor = placeholderColor,
                        unfocusedPlaceholderColor = placeholderColor,
                        disabledPlaceholderColor = placeholderColor,
                        cursorColor = fieldTextColor,
                    ),
                )
            }
        }
        if (onClearReplyTarget != null) {
            AppIconButton(onClick = onClearReplyTarget) {
                AppIcon(
                    rememberAppClearIcon(),
                    contentDescription = "取消回复",
                    modifier = Modifier.size(AppSpacingTokens.Large)
                )
            }
        }
    }
}
