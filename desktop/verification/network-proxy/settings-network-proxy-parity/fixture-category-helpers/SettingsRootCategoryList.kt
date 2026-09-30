// GENERATED from app/src/main/java/com/android/purebilibili/feature/settings/ui/SettingsSections.kt; do not edit.
// LF-normalized SHA-256: c8178e7a048512348678555b529180f7a969a658eca4019d484aff8603581a1b
package com.android.purebilibili.feature.settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.AppShapes
import com.android.purebilibili.core.ui.ContainerLevel
import com.android.purebilibili.core.ui.isMiuixNonGlassEnabled
import com.android.purebilibili.core.ui.components.AppIcon
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.core.ui.components.AppPreferenceDivider as SettingsDivider
import com.android.purebilibili.core.ui.components.AppPreferenceGroup as SettingsGroup
import com.android.purebilibili.core.ui.components.AppPreferenceGroupPresentation
import com.android.purebilibili.core.ui.components.rememberAdaptiveListVisualCapabilities
import com.android.purebilibili.core.ui.components.rememberAdaptivePreferenceIconContainerColor
import com.android.purebilibili.core.ui.components.rememberAdaptivePreferenceIconContentColor
import com.bilipai.desktop.settings.DesktopSettingsVectors
import com.bilipai.desktop.settings.DesktopSettingsCategorySymbols
import com.bilipai.desktop.settings.DesktopSettingsCategoryVectors

@Composable
internal fun SettingsAdaptiveDivider() {
    val visualSpec = rememberAdaptiveListVisualCapabilities().componentSpec
    SettingsDivider(startIndent = visualSpec.dividerStartIndentDp.dp)
}

@Composable
internal fun SettingsCardGroup(
    content: @Composable ColumnScope.() -> Unit
) {
    SettingsGroup(
        presentation = if (isMiuixNonGlassEnabled()) {
            AppPreferenceGroupPresentation.CARD
        } else {
            AppPreferenceGroupPresentation.FLAT
        },
    ) {
        content()
    }
}

@Composable
internal fun SettingsRootCategoryListSection(
    categories: List<SettingsRootCategory>,
    onCategoryClick: (SettingsRootCategory) -> Unit,
    onDonateClick: () -> Unit,
) {
    val siblingTints = remember(categories.size) {
        resolveSettingsSiblingIconTints(categories.size + 1)
    }
    val donateVisual = rememberSettingsEntryVisual(SettingsSearchTarget.DONATE)
    SettingsCardGroup {
        categories.forEachIndexed { index, category ->
            val visual = rememberSettingsEntryVisual(category.searchTarget)
            SettingsRootCategoryRow(
                title = category.title,
                subtitle = category.subtitle,
                icon = visual.icon,
                iconPainter = visual.iconResId?.let { rememberVectorPainter(DesktopSettingsVectors.vector(it)) },
                iconTint = siblingTints[index],
                iconSizeDp = visual.iconSizeDp,
                onClick = { onCategoryClick(category) },
            )
            SettingsAdaptiveDivider()
        }
        SettingsRootCategoryRow(
            title = settingsDestinationCopy(SettingsSearchTarget.DONATE).title,
            subtitle = settingsDestinationCopy(SettingsSearchTarget.DONATE).summary,
            icon = donateVisual.icon,
            iconPainter = donateVisual.iconResId?.let { rememberVectorPainter(DesktopSettingsVectors.vector(it)) },
            iconTint = siblingTints.last(),
            iconSizeDp = donateVisual.iconSizeDp,
            onClick = onDonateClick,
        )
    }
}

@Composable
private fun SettingsRootCategoryRow(
    title: String,
    subtitle: String,
    icon: ImageVector?,
    iconPainter: androidx.compose.ui.graphics.painter.Painter?,
    iconTint: Color,
    iconSizeDp: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visualSpec = resolveSettingsVisualSpec()
    val effectiveIconTint = rememberAdaptivePreferenceIconContainerColor(iconTint)
    val iconContentColor = rememberAdaptivePreferenceIconContentColor(effectiveIconTint)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                horizontal = 16.dp,
                vertical = visualSpec.categoryRowVerticalPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(visualSpec.categoryIconBubbleSize)
                .clip(AppShapes.container(ContainerLevel.Field))
                .background(effectiveIconTint),
            contentAlignment = Alignment.Center,
        ) {
            when {
                iconPainter != null -> AppIcon(
                    painter = iconPainter,
                    contentDescription = null,
                    tint = iconContentColor,
                    modifier = Modifier.size(iconSizeDp.dp),
                )
                icon != null -> AppIcon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconContentColor,
                    modifier = Modifier.size(iconSizeDp.dp),
                )
            }
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            AppText(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            AppText(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AppIcon(
            imageVector = DesktopSettingsCategoryVectors.vector(DesktopSettingsCategorySymbols.ms_keyboard_arrow_right_24),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            modifier = Modifier.size(14.dp),
        )
    }
}
