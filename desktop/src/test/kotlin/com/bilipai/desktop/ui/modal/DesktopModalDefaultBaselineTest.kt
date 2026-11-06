@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.bilipai.desktop.ui.modal

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.ui.AppModalBottomSheet
import com.android.purebilibili.feature.settings.AppThemeMode
import com.bilipai.desktop.appearance.DesktopAppearanceTheme
import com.bilipai.desktop.appearance.DesktopThemeSettings
import org.junit.jupiter.api.Test
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlin.test.assertTrue

/** Run this unchanged against the same clean base and candidate; compare pixel hashes mechanically. */
class DesktopModalDefaultBaselineTest {
    @Test fun untaggedSheetRendersThroughTheExistingUpstreamOverload() {
        val output = File(System.getProperty("ton1194.defaultOutput", "build/ton1194-default"))
        output.mkdirs()
        for (style in AppUiStyle.entries) {
            val scheduler = TestCoroutineScheduler()
            val scene = ImageComposeScene(800, 600, Density(1f), coroutineContext = StandardTestDispatcher(scheduler)) {
                DesktopAppearanceTheme(DesktopThemeSettings(uiStyle = style, themeMode = AppThemeMode.LIGHT),
                    systemLanguageTags = listOf("en")) {
                    // No purpose parameter: this call must continue to compile on the clean base.
                    AppModalBottomSheet(onDismissRequest = {}) {
                        Column(Modifier.fillMaxWidth().height(180.dp).padding(16.dp).testTag("baseline-body")) { Text("Unchanged upstream sheet") }
                    }
                }
            }
            try {
                repeat(30) {
                    scheduler.runCurrent()
                    scene.render(it * 30_000_000L).close()
                    scheduler.runCurrent()
                }
                fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
                val body = scene.semanticsOwners.flatMap { descendants(it.unmergedRootSemanticsNode) }
                    .single { it.config.getOrNull(SemanticsProperties.TestTag) == "baseline-body" }
                assertTrue(body.boundsInWindow.width > 0f && body.boundsInWindow.height > 0f)
                scene.render(1_000_000_000L).use { image ->
                    image.encodeToData()!!.use { data ->
                        val hash = MessageDigest.getInstance("SHA-256").digest(data.bytes).joinToString("") { "%02x".format(it) }
                        assertTrue(data.bytes.size > 1000)
                        output.resolve("${style.name}.sha256").writeText(hash + "\n")
                    }
                }
            } finally { scene.close() }
        }
    }
}
