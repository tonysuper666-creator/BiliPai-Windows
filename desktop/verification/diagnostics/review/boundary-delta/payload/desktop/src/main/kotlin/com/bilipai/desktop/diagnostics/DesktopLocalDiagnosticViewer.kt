package com.bilipai.desktop.diagnostics

import androidx.compose.runtime.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.*
import com.android.purebilibili.core.ui.components.AppText
import kotlinx.coroutines.*
import java.nio.file.Path

/** Local viewer only; the host's explicit chooser supplies an export destination. */
@Composable
internal fun DesktopLocalDiagnosticViewer(
    diagnostics:DesktopDiagnostics,
    onDismiss:()->Unit,
    chooseExportPath:suspend ()->Path?,
) {
    var content by remember(diagnostics){mutableStateOf("正在读取本地日志…")}
    var busy by remember{mutableStateOf(false)}
    var result by remember{mutableStateOf<String?>(null)}
    val scope=rememberCoroutineScope()
    val consumerError by diagnostics.error.collectAsState()
    LaunchedEffect(diagnostics) {
        try {content=diagnostics.viewLocal()}
        catch(cancelled:CancellationException){throw cancelled}
        catch(_:Exception){content="本地诊断日志无法读取，请重试";result="操作失败，请重试"}
    }
    fun action(block:suspend ()->Unit) {if(busy)return;busy=true;scope.launch {
        try {block()}
        catch(cancelled:CancellationException){throw cancelled}
        catch(_:Exception){result="操作失败，请重试"}
        finally{busy=false}
    }}
    AppAlertDialog(
        onDismissRequest={if(!busy)onDismiss()},
        title={AppText("本地诊断日志")},
        text={Column(Modifier.widthIn(max=720.dp)) {
            AppText("仅显示应用本地脱敏日志；导出时再脱敏，不自动上传。")
            SelectionContainer {Box(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState())) {AppText(content)}}
            (result ?: consumerError ?: if(busy)"正在操作…" else null)?.let{AppText(it)}
        }},
        confirmButton={Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            AppDialogAction(onClick={action {
                chooseExportPath()?.let {diagnostics.exportTo(it);result="日志已导出到你选择的本地文件"}
            }}){AppText("导出日志")}
            AppDialogAction(onClick={action {
                diagnostics.clearAll();content=diagnostics.viewLocal();result="本地诊断日志已清理"
            }}){AppText("清理日志")}
        }},
        dismissButton={AppDialogAction(onClick={if(!busy)onDismiss()}){AppText("关闭")}},
    )
}
