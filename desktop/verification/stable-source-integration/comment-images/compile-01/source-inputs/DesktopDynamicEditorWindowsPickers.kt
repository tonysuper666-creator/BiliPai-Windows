package com.bilipai.desktop.ui

import java.awt.Component
import java.util.Calendar
import java.util.Date
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.JSpinner
import javax.swing.SpinnerDateModel
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

/** User-triggered Windows dialog seam for Android's file/date-time activities.
 * This adapter is prepared and compiled; the isolated proof never opens it.
 */
internal class DesktopDynamicEditorWindowsPickers(
    private val selectedImages: DesktopDynamicEditorSelectedImages,
    private val stillOwned: () -> Boolean,
    private val parent: () -> Component?,
    private val onFailure: (String) -> Unit,
) {
    private fun onEventThread(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeLater(block)
    }
    fun pickImages(maxItems: Int, onSelected: (List<String>) -> Unit) = onEventThread {
        if (!stillOwned()) return@onEventThread
        val chooser = JFileChooser().apply {
            dialogTitle = "选择图片"
            isMultiSelectionEnabled = maxItems > 1
            isAcceptAllFileFilterUsed = false
            fileFilter = FileNameExtensionFilter("图片", "png", "jpg", "jpeg", "gif", "webp", "bmp", "avif", "heic", "heif", "tif", "tiff")
        }
        if (chooser.showOpenDialog(parent()) == JFileChooser.APPROVE_OPTION && stillOwned()) {
            try {
                val sources = DesktopDynamicGallerySelection(selectedImages, stillOwned).acceptResult(
                    approved = true, single = chooser.selectedFile?.toPath(),
                    multiple = chooser.selectedFiles.map { it.toPath() }.takeIf { it.isNotEmpty() },
                    maxItems = maxItems.coerceIn(1, 18))
                if (stillOwned()) onSelected(sources)
            } catch (failure: Exception) {
                if (stillOwned()) onFailure("无法读取所选图片")
            }
        }
    }
    fun chooseDateAndTime(initialMillis: Long, onSelected: (Int, Int, Int, Int, Int) -> Unit) = onEventThread {
        if (!stillOwned()) return@onEventThread
        val input = JSpinner(SpinnerDateModel(Date(initialMillis), null, null, Calendar.MINUTE))
        input.editor = JSpinner.DateEditor(input, "yyyy-MM-dd HH:mm")
        if (JOptionPane.showConfirmDialog(parent(), input, "选择开播时间", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION && stillOwned()) {
            try {
                input.commitEdit()
                val selected = Calendar.getInstance().apply { time = input.value as Date }
                if (stillOwned()) onSelected(selected.get(Calendar.YEAR), selected.get(Calendar.MONTH),
                    selected.get(Calendar.DAY_OF_MONTH), selected.get(Calendar.HOUR_OF_DAY), selected.get(Calendar.MINUTE))
            } catch (failure: Exception) {
                if (stillOwned()) onFailure("日期格式无效")
            }
        }
    }
}
