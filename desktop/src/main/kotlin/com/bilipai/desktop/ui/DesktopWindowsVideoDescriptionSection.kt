package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.fillMaxWidth
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
@Composable internal fun DesktopWindowsVideoDescriptionSection(
    assembly: DesktopOriginalVideoOwnerAssembly,
    info: ViewInfo,
    sourceOwner: DesktopOriginalVideoAcceptedPublication,
    stillOwned: () -> Boolean,
    onLink: (String, () -> Boolean) -> Unit,
) {
    key(assembly, sourceOwner, info) {
        val latestOwned by rememberUpdatedState(stillOwned)
        val latestLink by rememberUpdatedState(onLink)
        val loadToken = remember { assembly.playback.captureDesktopLoadState().currentLoadRequestToken }
        val lease = remember {
            DesktopWindowsVideoMetadataLease {
                latestOwned() && assembly.owns() && assembly.native.isCurrent(sourceOwner) &&
                    (assembly.playback.captureDesktopPlaybackState() as? VideoPlaybackUiState.Success)?.let { current ->
                        current.info.desc == info.desc && current.info.descV2 == info.descV2 &&
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
        val urlColor = MaterialTheme.colorScheme.primary
        val description = remember(info.desc, info.descV2, urlColor, linkListener) {
            buildVideoDescriptionAnnotatedString(
                desc = info.desc,
                descV2 = info.descV2,
                urlColor = urlColor,
                linkListener = linkListener,
            )
        }
        if (lease.isOwned() && description.text.isNotBlank()) {
            SelectionContainer {
                AppText(description, modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
