package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.plugin.SkipAction
import com.android.purebilibili.data.model.response.SponsorSegment

/** Only a compact view of the installed VM's current manual action. The callback
 * retains the exact source and segment; it never seeks or marks a plugin itself. */
@Composable internal fun DesktopWindowsSponsorSkipSection(
    facade: DesktopUnifiedPlaybackFacade,
    assembly: DesktopOriginalVideoOwnerAssembly,
    source: DesktopOriginalVideoAcceptedPublication?,
    action: SkipAction.ShowButton?,
    segment: SponsorSegment?,
    current: () -> Boolean,
) {
    if (source == null || action == null || segment == null || action.skipToMs <= 0L ||
        action.segmentId.isBlank() || action.segmentId != segment.UUID || action.skipToMs != segment.endTimeMs) return
    val label = action.label.ifBlank { "跳过片段" }
    TextButton(onClick = { facade.executeManualSkip(assembly, source, segment, action, current) },
        enabled = current(), modifier = Modifier.widthIn(max = 120.dp).semantics { contentDescription = label }) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
