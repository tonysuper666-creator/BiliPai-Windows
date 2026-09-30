package com.bilipai.desktop.diagnostics

import androidx.compose.runtime.*
import com.android.purebilibili.DesktopPendingCrashLogPrompt
import com.android.purebilibili.core.ui.*
import com.android.purebilibili.core.ui.components.AppText
import kotlinx.coroutines.launch
import java.nio.file.Path

/** Mount once under the actual Root appearance theme; never closes the shared actor. */
@Composable
internal fun DesktopCrashPromptHost(controller:DesktopCrashPromptController,chooseExportPath:suspend()->Path?) {
    val state by controller.state.collectAsState();val scope=rememberCoroutineScope()
    LaunchedEffect(controller){controller.load()}
    DisposableEffect(controller){onDispose{controller.close()}}
    DesktopPendingCrashLogPrompt(state.pending,state.handled){action->scope.launch{controller.handle(action)}}
    if(state.viewerRequested)DesktopLocalDiagnosticViewer(controller.diagnostics,controller::dismissViewer,chooseExportPath)
    state.error?.let{safeError->
        AppAlertDialog(onDismissRequest=controller::dismissError,title={AppText("本地崩溃日志")},text={AppText(safeError)},
            confirmButton={AppDialogAction(onClick={scope.launch{state.retryAction?.let{controller.handle(it)}?:controller.load()}}){AppText(if(state.retryAction==null)"重试读取"else"重试清理")}},
            dismissButton={AppDialogAction(onClick=controller::dismissError){AppText("关闭")}})
    }
}
