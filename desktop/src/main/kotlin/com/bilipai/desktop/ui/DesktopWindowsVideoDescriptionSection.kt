package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.purebilibili.core.store.DesktopOriginalVideoInfoSettings as SettingsManager
import com.android.purebilibili.core.ui.common.copyOnLongPress
import com.android.purebilibili.data.model.response.VideoTag
import com.android.purebilibili.data.model.response.BgmInfo
import com.android.purebilibili.feature.video.ui.section.resolveBgmTagInfo
import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.bilipai.desktop.audio.DesktopBgmMusicTarget
import com.bilipai.desktop.audio.resolveDesktopBgmMusicTarget
import com.bilipai.desktop.plugins.DesktopPluginContext
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.data.model.response.ViewInfo
import com.android.purebilibili.feature.video.ui.section.buildVideoDescriptionAnnotatedString
import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackUiState

/** The complete original description parser, in the existing Windows introduction.
 * Its links and selection share the same immutable metadata, VM load and native source.
 * Root remains the only navigation owner and performs final source/account admission. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable internal fun DesktopWindowsVideoDescriptionSection(
    assembly: DesktopOriginalVideoOwnerAssembly,
    info: ViewInfo,
    sourceOwner: DesktopOriginalVideoAcceptedPublication,
    tags: List<VideoTag>,
    settingsContext: DesktopPluginContext,
    stillOwned: () -> Boolean,
    onMetadataNavigation: (BiliPaiNavKey, () -> Boolean) -> Unit,
    onLink: (String, () -> Boolean) -> Unit,
) {
    key(assembly, sourceOwner, info, tags) {
        val videoTags = remember { tags.toList() }
        val context = settingsContext
        val latestOwned by rememberUpdatedState(stillOwned)
        val latestLink by rememberUpdatedState(onLink)
        val latestMetadataNavigation by rememberUpdatedState(onMetadataNavigation)
        val loadToken = remember { assembly.playback.captureDesktopLoadState().currentLoadRequestToken }
        val lease = remember {
            DesktopWindowsVideoMetadataLease {
                latestOwned() && assembly.owns() && assembly.native.isCurrent(sourceOwner) &&
                    (assembly.playback.captureDesktopPlaybackState() as? VideoPlaybackUiState.Success)?.let { current ->
                        current.info.desc == info.desc && current.info.descV2 == info.descV2 &&
                            current.videoTags == videoTags &&
                            desktopWindowsVideoMetadataMatchesSource(current.info, sourceOwner.request,
                                assembly.playback.captureDesktopLoadState(), loadToken)
                    } == true
            }
        }
        DisposableEffect(lease) { onDispose { lease.close() } }
        val linkListener = remember(lease) {
            LinkInteractionListener { link ->
                val clickable = link as? LinkAnnotation.Clickable
                if (clickable != null && lease.isOwned()) latestLink(clickable.tag, lease::isOwned)
            }
        }
        val onTagClick: (String) -> Unit = remember(lease) {
            { keyword ->
                if (lease.isOwned()) latestMetadataNavigation(BiliPaiNavKey.Search(keyword), lease::isOwned)
            }
        }
        val onBgmClick: (BgmInfo) -> Unit = remember(lease) {
            { bgm ->
                if (lease.isOwned()) {
                    // The existing original BGM resolver retains sid/aid/cid and Web fallback.
                    val destination = when (val target = resolveDesktopBgmMusicTarget(bgm, info.bvid, info.cid)) {
                        is DesktopBgmMusicTarget.Detail -> BiliPaiNavKey.BgmDetail(
                            target.musicId, target.aid, target.cid, target.showVideos)
                        is DesktopBgmMusicTarget.Web -> BiliPaiNavKey.Web(target.url)
                        null -> null
                    }
                    if (destination != null && lease.isOwned()) latestMetadataNavigation(destination, lease::isOwned)
                }
            }
        }
        val urlColor = MaterialTheme.colorScheme.primary
        val description = remember(info.desc, info.descV2, urlColor, linkListener) {
            buildVideoDescriptionAnnotatedString(
                desc = info.desc,
                descV2 = info.descV2,
                urlColor = urlColor,
                linkListener = linkListener,
            )
        }
        if (lease.isOwned()) {
            // Preserve original global/native text selection, including the tag labels.
            // Original copyOnLongPress intentionally delegates to this selection host.
            SelectionContainer {
                Column {
                    if (description.text.isNotBlank()) {
                        AppText(description, modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (videoTags.isNotEmpty()) {
                        val videoTagSize by SettingsManager
                            .getVideoTagSizePreset(context)
                            .collectAsStateWithLifecycle(
                                initialValue = com.android.purebilibili.core.ui.components.AppTagChipSize.STANDARD
                            )
                        val tagMetrics = com.android.purebilibili.core.ui.components
                            .resolveAppTagChipMetrics(videoTagSize)
                        Column {
                            Spacer(Modifier.height(8.dp))
                            // Keep native touch expansion without reserving a 48dp layout box per tag.
                            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                                androidx.compose.foundation.layout.FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(tagMetrics.itemSpacingHorizontal),
                                    verticalArrangement = Arrangement.spacedBy(tagMetrics.itemSpacingVertical)
                                ) {
                                    videoTags.take(10).forEach { tag ->
                                        com.android.purebilibili.core.ui.components.AppTagChip(
                                            label = if (tag.tag_type == "bgm") tag.tag_name.replaceFirst("发现", "♫ BGM：") else tag.tag_name,
                                            onClick = {
                                                val bgm = resolveBgmTagInfo(tag)
                                                if (bgm != null) onBgmClick(bgm) else onTagClick(tag.tag_name)
                                            },
                                            modifier = Modifier.copyOnLongPress(tag.tag_name, "标签"),
                                            size = videoTagSize,
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
}
