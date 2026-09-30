package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.AppDialogAction
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.core.ui.components.AppSurface
import kotlinx.coroutines.*

/** Stays mounted after readiness; only loading/error/stopped UI receives the optional error theme. */
@Composable
internal fun <T:Any> DesktopDiscoveryStorageBoundary(
    guard:DesktopDiscoveryStorageGuard<T>,
    sessionEpoch:Long,
    onRestart:(()->Unit)?,
    modifier:Modifier=Modifier,
    errorTheme:@Composable (@Composable ()->Unit)->Unit = {body->body()},
    content:@Composable (T)->Unit,
) {
    val result by guard.result.collectAsState()
    val scope=rememberCoroutineScope()
    LaunchedEffect(guard,sessionEpoch){guard.load()}
    DisposableEffect(guard){onDispose{guard.close()}}
    val ready=result?.getOrNull()
    if(guard.isActive&&ready!=null){content(ready);return}
    errorTheme {
        AppSurface(modifier) {
        if(!guard.isActive) {
            Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                AppText("本地设置读取已停止，请重新启动应用。")
                onRestart?.let{restart->AppDialogAction(onClick=restart){AppText("重新启动应用")}}
            }
        } else {
            val failure=result?.exceptionOrNull()
            Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                if(failure==null)AppText("正在读取本地发现页设置…")
                else {
                    AppText("本地存储无法读取")
                    // Only our fixed safe classification is displayed. Arbitrary raw JSON/path exceptions are not.
                    AppText((failure as? DesktopDiscoveryStorageFailure)?.message
                        ?: "发现页本地资料无法读取，原文件保持不变；请修复文件后重新启动应用。")
                    Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        if(guard.canRetry)AppDialogAction(onClick={scope.launch{guard.load()}}){AppText("重试读取")}
                        onRestart?.let{restart->AppDialogAction(onClick=restart){AppText("重新启动应用")}}
                    }
                }
            }
        }
        }
    }
}
