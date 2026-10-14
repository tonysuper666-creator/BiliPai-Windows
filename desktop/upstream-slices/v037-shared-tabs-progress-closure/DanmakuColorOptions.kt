package com.android.purebilibili.feature.video.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.components.AppFilterChip
import com.android.purebilibili.core.ui.components.AppText

@Composable
internal fun DanmakuColorOptions(
    selectedColor: Int,
    onColorSelected: (Int) -> Unit,
    onCustomColor: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = danmakuSendColorOptions()
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            AppFilterChip(
                selected = selectedColor == option.value,
                onClick = { onColorSelected(option.value) },
                label = { AppText(option.label) },
            )
        }
        AppFilterChip(
            selected = selectedColor >= 0 && options.none { it.value == selectedColor },
            onClick = onCustomColor,
            label = { AppText("自定义") },
        )
    }
}
