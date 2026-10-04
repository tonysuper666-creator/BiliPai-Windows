package com.bilipai.desktop.appearance

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.purebilibili.feature.settings.SettingsSearchTarget
import com.bilipai.desktop.settings.desktopSettingsSearchFocusAnchor
import com.bilipai.desktop.ui.LocalDesktopOriginalPlayerSettingsContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

@Composable internal fun DesktopWindowsDisplayScaleSettings() {
    val controller = LocalDesktopWindowsDisplayScale.current
    val context = LocalDesktopOriginalPlayerSettingsContext.current
    val settings by controller.settings.collectAsState()
    val writeError by controller.writeError.collectAsState()
    val scope = rememberCoroutineScope()
    val alive = remember(context) { AtomicBoolean(true) }
    DisposableEffect(alive) { onDispose { alive.set(false) } }
    var draft by remember(controller, context) { mutableStateOf<Float?>(null) }
    var error by remember(controller, context) { mutableStateOf<String?>(null) }
    var busy by remember(controller, context) { mutableStateOf(false) }
    fun select(percent: Int) {
        if (busy || !alive.get()) return
        busy = true
        scope.launch {
            try { controller.setPercent(context, percent, alive::get); error = null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (alive.get()) error = failure.message ?: "窗口缩放无法保存" }
            finally { if (alive.get()) { busy = false; draft = null } }
        }
    }
    Card(Modifier.fillMaxWidth().desktopSettingsSearchFocusAnchor(SettingsSearchTarget.APPEARANCE, "windows_display_scale")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("窗口缩放 · ${draft?.roundToInt() ?: settings.percent}%", style = MaterialTheme.typography.titleMedium)
            Text("在 Windows 系统 DPI 上调整整个窗口，字体设置仍单独保留。",
                style = MaterialTheme.typography.bodySmall)
            Slider(value = draft ?: settings.percent.toFloat(),
                onValueChange = { draft = (it / WINDOWS_DISPLAY_STEP_PERCENT).roundToInt() * WINDOWS_DISPLAY_STEP_PERCENT.toFloat() },
                onValueChangeFinished = { draft?.roundToInt()?.let(::select) },
                valueRange = WINDOWS_DISPLAY_MIN_PERCENT.toFloat()..WINDOWS_DISPLAY_MAX_PERCENT.toFloat(),
                steps = (WINDOWS_DISPLAY_MAX_PERCENT - WINDOWS_DISPLAY_MIN_PERCENT) / WINDOWS_DISPLAY_STEP_PERCENT - 1,
                enabled = !busy)
            Text("Ctrl＋滚轮 / Ctrl＋加减调整 · Ctrl＋0 恢复 125%", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { select(WINDOWS_DISPLAY_DEFAULT_PERCENT) }, enabled = !busy) { Text("恢复默认（125%）") }
            (error ?: writeError ?: settings.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
