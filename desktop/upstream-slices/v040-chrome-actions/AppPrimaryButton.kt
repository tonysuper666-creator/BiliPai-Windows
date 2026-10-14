@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.android.purebilibili.core.ui.components

import com.android.purebilibili.core.ui.AppContentColors

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.theme.LocalAppUiStyle
import com.android.purebilibili.core.ui.AppSpacingTokens
import com.android.purebilibili.core.ui.LocalAppThemeConfig
import com.android.purebilibili.core.ui.resolveButtonContentColor
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.theme.LocalContentColor as MiuixLocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun AppPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    leadingIcon: ImageVector? = null,
): Unit = AppExactTouchTargets {
    ProvideAppElasticFeedback(enabled = enabled && !isLoading) {
        val haptic = LocalHapticFeedback.current
        val hapticEnabled = LocalAppThemeConfig.current.hapticFeedbackEnabled
        val interactionSource = remember { MutableInteractionSource() }
        val primaryContentColor = resolveButtonContentColor(
            containerColor = AppContentColors.accent(),
            contentColor = AppContentColors.onAccent(),
            colorScheme = MaterialTheme.colorScheme,
        )

        if (LocalAppUiStyle.current == AppUiStyle.MATERIAL3) {
            Button(
                onClick = {
                    if (hapticEnabled) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                },
                modifier = modifier.appElasticPress(enabled = enabled && !isLoading),
                enabled = enabled && !isLoading,
                shapes = ButtonDefaults.shapes(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppContentColors.accent(),
                    contentColor = primaryContentColor,
                    disabledContainerColor = AppContentColors.primaryText().copy(alpha = 0.12f),
                    disabledContentColor = AppContentColors.primaryText().copy(alpha = 0.38f),
                ),
                contentPadding = AppButtonDefaults.ContentPadding,
                interactionSource = interactionSource,
            ) {
                if (isLoading) {
                    AppCircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = primaryContentColor,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        if (leadingIcon != null) {
                            Icon(
                                imageVector = leadingIcon,
                                contentDescription = null,
                                tint = primaryContentColor,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(modifier = Modifier.width(AppSpacingTokens.Small))
                        }
                        Text(
                            text = text,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
            return@ProvideAppElasticFeedback
        }

        AppButton(
            onClick = {
                if (hapticEnabled) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
            modifier = modifier.heightIn(min = 48.dp),
            enabled = enabled && !isLoading,
            interactionSource = interactionSource,
        ) {
            val contentColor = MiuixLocalContentColor.current
            if (isLoading) {
                AppCircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = contentColor,
                    strokeWidth = 2.dp,
                )
            } else {
                if (leadingIcon != null) {
                    Icon(
                        imageVector = leadingIcon,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(AppSpacingTokens.Small))
                }
                MiuixText(text = text, style = MiuixTheme.textStyles.button)
            }
        }
    }
}
