package com.bilipai.desktop.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopPagerSelectionMotionConstantsTest {
    @Test
    fun `prewarm frame count equals the upstream navigation policy value`() {
        val upstream = listOf(
            File("../app/src/main/java/com/android/purebilibili/navigation/AppTopLevelNavigationPolicy.kt"),
            File("app/src/main/java/com/android/purebilibili/navigation/AppTopLevelNavigationPolicy.kt"),
        ).first { it.isFile }.readText()
        val declared = Regex("""const val BOTTOM_PAGER_TARGET_PREWARM_FRAMES = (\d+)""").find(upstream)!!.groupValues[1].toInt()
        assertEquals(declared, com.android.purebilibili.navigation.BOTTOM_PAGER_TARGET_PREWARM_FRAMES)
    }
}
