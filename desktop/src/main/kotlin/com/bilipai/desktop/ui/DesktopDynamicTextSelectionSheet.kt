package com.bilipai.desktop.ui
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import com.android.purebilibili.core.ui.AppAlertDialog

/** Windows dialog window; exact upstream text-selection content stays inside. */
@Composable internal fun DesktopDynamicTextSelectionSheet(onDismissRequest:()->Unit,content:@Composable ColumnScope.()->Unit) {
    AppAlertDialog(onDismissRequest=onDismissRequest,text={Column(content=content)},confirmButton={})
}
