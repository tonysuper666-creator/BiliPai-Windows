package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.components.AppSingleChoiceRow
import com.android.purebilibili.feature.plugin.CdnTransferRuntime
import com.android.purebilibili.feature.video.ui.components.PlaybackCdnDiagnostics
import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackUiState

/** Compact Windows entry to the complete original live transfer diagnostics.
 * The caller supplies its actual displayed source and final presentation gate;
 * this section borrows the same settings/runtime and never owns media or requests.
 * Invoke inside the existing Root window/shell/settings composition locals.
 */
@Composable
internal fun DesktopWindowsVideoTransportSection(
    success: VideoPlaybackUiState.Success,
    sourceOwner: DesktopOriginalVideoAcceptedPublication,
    enabled: Boolean,
    stillOwned: () -> Boolean,
    admit: ((() -> Unit) -> Boolean),
    onSwitchCdnTo: (Int) -> Unit,
    onProbeCdnCandidates: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings = LocalDesktopOriginalPlayerSettingsContext.current
    key(sourceOwner) {
        val latestSuccess by rememberUpdatedState(success)
        val latestEnabled by rememberUpdatedState(enabled)
        val latestOwned by rememberUpdatedState(stillOwned)
        val latestAdmission by rememberUpdatedState(admit)
        val latestSwitch by rememberUpdatedState(onSwitchCdnTo)
        val latestProbe by rememberUpdatedState(onProbeCdnCandidates)
        fun current(): Boolean = latestEnabled && latestOwned() &&
            settings.isCurrentForOriginalWrite() && !latestSuccess.isQualitySwitching &&
            latestSuccess.info.bvid == sourceOwner.request.bvid &&
            latestSuccess.info.cid == sourceOwner.request.cid
        // The original diagnostics' own runtime/parallel-setting actions retain
        // the caller's source gate too, including across asynchronous disk writes.
        val context = remember(settings, sourceOwner) {
            DesktopOriginalPlayerSettingsContext(settings.pluginContext, ::current, { action ->
                var applied = false
                current() && latestAdmission {
                    if (current()) { action(); applied = true }
                } && applied
            })
        }
        var showLines by remember { mutableStateOf(false) }
        var showDiagnostics by remember { mutableStateOf(false) }
        val diagnosticsByIndex = success.cdnLineDiagnostics.associateBy { it.index }
        val count = success.cdnCount
        Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("播放线路", style = MaterialTheme.typography.labelLarge)
            Text(
                diagnosticsByIndex[success.currentCdnIndex]?.displayName
                    ?: if (count > 0) "当前线路 ${success.currentCdnIndex + 1} / $count" else "暂无可用线路",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(enabled = current() && count > 1, onClick = {
                    if (current()) showLines = !showLines
                }) { Text(if (showLines) "收起线路" else "切换线路") }
                TextButton(enabled = current() && count > 0 && !success.isCdnProbing, onClick = {
                    if (current()) latestProbe()
                }) { Text(if (success.isCdnProbing) "检测中…" else "检测线路") }
                TextButton(enabled = current(), onClick = {
                    if (current()) showDiagnostics = !showDiagnostics
                }) { Text(if (showDiagnostics) "收起传输状态" else "传输状态") }
            }
            if (current()) CompositionLocalProvider(LocalDesktopOriginalPlayerSettingsContext provides context) {
                val binding = rememberDesktopCdnTransferPanelBinding()
                if (showLines) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(count) { index ->
                        val diagnostic = diagnosticsByIndex[index]
                        AppSingleChoiceRow(
                            selected = index == success.currentCdnIndex,
                            onClick = {
                                if (current() && index in 0 until latestSuccess.cdnCount) {
                                    // Same original host preference and VM source replacement;
                                    // preparation/IO starts after the short mutation has returned.
                                    binding.mutate { CdnTransferRuntime.preferHost(diagnostic?.host) }
                                    if (current() && index != latestSuccess.currentCdnIndex) latestSwitch(index)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(diagnostic?.displayName ?: "线路 ${index + 1}",
                                    style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                                diagnostic?.let {
                                    Text(listOfNotNull(it.sourceLabel, it.statusLabel,
                                        it.latencyMs?.let { latency -> "$latency ms" },
                                        it.speedKbps?.let { speed -> "${speed / 1024} Mbps" }).joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                if (showDiagnostics) PlaybackCdnDiagnostics(
                    diagnostics = success.cdnLineDiagnostics,
                    checking = success.isCdnProbing,
                    canCheck = count > 0 && current(),
                    onCheck = { if (current()) latestProbe() },
                    onSwitchTo = { index ->
                        if (current() && index in 0 until latestSuccess.cdnCount) latestSwitch(index)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
