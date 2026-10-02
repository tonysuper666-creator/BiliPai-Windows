package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

/** Physical invocation above the SAME installed original NavDisplay. The only
 * Comment/Holder roots borrow the retained Home gallery Assets instead of
 * constructing another image/network actor. Its scope follows the same epoch,
 * while the global Window settings/capture/image owners remain app-scoped. */
@Composable internal fun DesktopOriginalVideoReadyRootMount(
    environment: DesktopOriginalVideoRootWindowEnvironment,
    shell: DesktopOriginalVideoShellOwner,
    resources: DesktopOriginalVideoRootShellResources,
    content: @Composable () -> Unit,
) {
    val comments = LocalDesktopOriginalCommentRootOwner.current
    val commentPlatform = LocalDesktopCommentBindings.current
    val assembler = remember(environment, shell, resources, comments, commentPlatform) {
        DesktopOriginalVideoRootAssembler(environment, shell, resources, comments, commentPlatform)
    }
    DesktopOriginalVideoShellMount(environment, shell, { assembler.factory },
        { _, owner -> assembler.Platforms(owner) }, assembler::afterDrain,
        assembler::afterUnconstructedDrain, content)
}
