package com.bilipai.desktop.diagnostics

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import com.android.purebilibili.feature.settings.DesktopDiagnosticFields
import kotlinx.coroutines.*

/** The retained process consumer must be supplied; page disposal does not stop logging. */
@Composable
internal fun DesktopDiagnosticSettingsSection(
    diagnostics:DesktopDiagnostics,
    onLocalLogs:()->Unit,
    onFailure:(Throwable)->Unit,
    modifier:Modifier=Modifier,
) {
    val enhanced by diagnostics.enhancedEnabled.collectAsState()
    val scope=rememberCoroutineScope()
    val latestFailure by rememberUpdatedState(onFailure)
    Column(modifier) {
        DesktopDiagnosticFields(enhanced,{enabled ->scope.launch {
            try {diagnostics.setEnhancedEnabled(enabled)}
            catch(cancelled:CancellationException){throw cancelled}
            catch(failure:Exception){latestFailure(failure)}
        }},onLocalLogs)
    }
}
