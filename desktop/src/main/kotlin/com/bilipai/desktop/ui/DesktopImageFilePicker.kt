package com.bilipai.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import java.awt.EventQueue
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

internal class DesktopImageFilePicker(private val launchPicker: () -> Unit) { fun launch() = launchPicker() }

/** Windows file picker for one image, in place of Android's visual media picker. Upstream reads the chosen file through
 * its file URI (the desktop ContentResolver reads local files only). A picker whose dialog left composition delivers
 * nothing. */
@Composable internal fun rememberDesktopImageFilePicker(onImage: (android.net.Uri?) -> Unit): DesktopImageFilePicker {
    val current by rememberUpdatedState(onImage)
    val composed = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { composed.set(false) } }
    return remember { DesktopImageFilePicker {
        if (composed.get()) EventQueue.invokeLater {
            val chooser = JFileChooser().apply { fileFilter = FileNameExtensionFilter("图片", "jpg", "jpeg", "png", "gif", "webp"); isMultiSelectionEnabled = false }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION && composed.get()) current(android.net.Uri.fromFile(chooser.selectedFile))
        }
    } }
}
