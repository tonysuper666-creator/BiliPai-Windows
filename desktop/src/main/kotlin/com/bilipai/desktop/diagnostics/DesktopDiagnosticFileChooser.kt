package com.bilipai.desktop.diagnostics

import kotlinx.coroutines.suspendCancellableCoroutine
import java.awt.Component
import java.nio.file.Path
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Called only by the actual export button. No default export or upload is scheduled. */
internal suspend fun chooseDesktopDiagnosticExportFile(parent:Component?=null):Path? = suspendCancellableCoroutine {continuation ->
    val chooser=JFileChooser().apply {
        dialogTitle="导出本地诊断日志"
        selectedFile=java.io.File("bilipai_log_${SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())}.txt")
    }
    continuation.invokeOnCancellation {SwingUtilities.invokeLater {chooser.cancelSelection()}}
    SwingUtilities.invokeLater {
        if(!continuation.isActive)return@invokeLater
        try {
            val selected=if(chooser.showSaveDialog(parent)==JFileChooser.APPROVE_OPTION)chooser.selectedFile.toPath() else null
            if(continuation.isActive)continuation.resume(selected)
        }catch(failure:Exception){if(continuation.isActive)continuation.resumeWithException(failure)}
    }
}
