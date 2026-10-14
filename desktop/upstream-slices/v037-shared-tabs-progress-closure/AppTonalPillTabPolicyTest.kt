package com.android.purebilibili.core.ui.renderer.material3

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppTonalPillTabPolicyTest {

    @Test
    fun `tonal pill geometry matches piliplus search tabs`() {
        assertEquals(13.sp, resolveTonalPillTabDefaultLabelFontSize())
        assertEquals(20.dp, resolveTonalPillTabCornerRadius())
        assertEquals(12.dp, resolveTonalPillTabItemHorizontalPadding())
        assertEquals(4.dp, resolveTonalPillTabVerticalInset())
        assertEquals(40.dp, resolveTonalPillTabDefaultHeight())
    }

    @Test
    fun `pill renderer uses tonal container roles without divider`() {
        val source = loadSource(
            "src/main/java/com/android/purebilibili/core/ui/renderer/material3/AppMaterial3TonalPillTabRow.kt",
        )
        assertTrue(source.contains("colorScheme.secondaryContainer"))
        assertTrue(source.contains("colorScheme.onSecondaryContainer"))
        assertTrue(source.contains("colorScheme.outline"))
        assertTrue(!source.contains("Divider"))
        // Pager 连续驱动与选中索引动画两种模式都要支持。
        assertTrue(source.contains("indicatorPositionProvider"))
        assertTrue(source.contains("animateFloatAsState"))
    }

    @Test
    fun `presentation enum only routes md3 non glass branch`() {
        val routing = loadSource(
            "src/main/java/com/android/purebilibili/core/ui/components/AppSegmentedControl.kt",
        )
        assertTrue(routing.contains("enum class AppTabRowIndicatorPresentation"))
        // 只有 MATERIAL3 分支转发该参数,MIUIX 分支保持原样。
        val materialBranch = routing
            .substringAfter("AppSegmentedRenderer.MATERIAL3 -> AppMaterial3TabRow(")
            .substringBefore("AppSegmentedRenderer.MIUIX")
        assertTrue(materialBranch.contains("indicatorPresentation = indicatorPresentation"))
        assertTrue(!materialBranch.contains("AppMiuixTabRow"))
    }

    private fun loadSource(path: String): String {
        val normalized = path.removePrefix("design-system/")
        return listOf(
            File("design-system/$path"),
            File("../design-system/$path"),
            File(path),
            File("../$path"),
        ).firstOrNull(File::exists)?.readText() ?: error("Cannot locate $path")
    }
}
