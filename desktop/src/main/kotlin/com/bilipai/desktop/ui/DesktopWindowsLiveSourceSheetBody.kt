package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable

/** The complete original sheet body runs inside the single owned native dialog. */
@Composable
internal fun DesktopWindowsLiveSourceSheetBody(onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit) { Column(content = content) }
