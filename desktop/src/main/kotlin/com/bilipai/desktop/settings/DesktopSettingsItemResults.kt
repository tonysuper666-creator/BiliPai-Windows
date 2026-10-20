package com.bilipai.desktop.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.components.AppPreference
import com.android.purebilibili.core.ui.components.AppPreferenceDivider
import com.android.purebilibili.core.ui.components.AppPreferenceGroup
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.core.ui.components.LocalAppTextSearchQuery
import com.android.purebilibili.core.ui.components.rememberAdaptiveListVisualCapabilities
import com.android.purebilibili.feature.settings.SettingsSearchResult
import com.android.purebilibili.feature.settings.SettingsSearchResultsSection
import com.android.purebilibili.feature.settings.rememberSettingsEntryVisual

/** The two headings of the search page when rows are listed above the sections. */
internal object DesktopSettingsItemSearchStrings {
    const val ITEMS_TITLE = "设置项"
    const val SECTIONS_TITLE = "页面与分组"
}

/**
 * The result list of the Windows settings search: the matched rows first, then the section results that the rows do not
 * already cover. With no matched row this is exactly the original results section, as it was before rows existed.
 */
@Composable
internal fun DesktopSettingsSearchResultsBody(
    query: String,
    rows: List<DesktopSettingsItemHit>,
    sections: List<SettingsSearchResult>,
    onRowClick: (DesktopSettingsItemHit) -> Unit,
    onSectionClick: (SettingsSearchResult) -> Unit,
) {
    if (rows.isEmpty()) {
        SettingsSearchResultsSection(sections, onSectionClick)
        return
    }
    DesktopSettingsItemResultsSection(query, rows, onRowClick)
    val visibleSections = desktopSettingsVisibleSectionResults(sections, rows)
    if (visibleSections.isNotEmpty()) {
        DesktopSettingsSearchHeading(DesktopSettingsItemSearchStrings.SECTIONS_TITLE)
        SettingsSearchResultsSection(visibleSections, onSectionClick)
    }
}

@Composable
private fun DesktopSettingsSearchHeading(title: String) {
    AppText(title, Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleSmall)
}

/** One preference row per matched setting: title, breadcrumb and description snippet, the keyword highlighted. */
@Composable
internal fun DesktopSettingsItemResultsSection(
    query: String,
    rows: List<DesktopSettingsItemHit>,
    onRowClick: (DesktopSettingsItemHit) -> Unit,
) {
    val visualSpec = rememberAdaptiveListVisualCapabilities().componentSpec
    Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(modifier = Modifier.height(8.dp))
        DesktopSettingsSearchHeading(DesktopSettingsItemSearchStrings.ITEMS_TITLE)
        AppPreferenceGroup {
            rows.forEachIndexed { index, row ->
                key(row.item.settingId) {
                    val visual = rememberSettingsEntryVisual(row.item.target)
                    CompositionLocalProvider(LocalAppTextSearchQuery provides query) {
                        AppPreference(
                            icon = visual.icon,
                            title = row.item.title,
                            subtitle = row.subtitle,
                            onClick = { onRowClick(row) },
                            iconTint = visual.iconTint,
                        )
                    }
                    if (index != rows.lastIndex) {
                        AppPreferenceDivider(startIndent = visualSpec.dividerStartIndentDp.dp)
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}
