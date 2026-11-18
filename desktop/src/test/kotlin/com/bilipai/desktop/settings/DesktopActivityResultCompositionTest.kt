package com.bilipai.desktop.settings

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.bilipai.desktop.compat.UnsupportedDesktopCapability
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext
import com.bilipai.desktop.ui.LocalDesktopUpstreamAndroidContext
import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive

@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class DesktopActivityResultCompositionTest {
    @Test fun registrationSurvivesNewContractsAndRetiresWithTheComposition(): Unit = runBlocking {
        val root = Files.createTempDirectory("bp-activity-result-composition-")
        try {
            val plugin = DesktopPluginContext(DesktopPluginStore(root))
            plugin.store.update("settings", mapOf("hw_decode" to JsonPrimitive(false)))
            val before = plugin.store.preferences("settings")
            val page = DesktopOriginalPlayerSettingsContext(plugin, { true }, {
                error("Activity-result registration and rejected launches must not write preferences")
            })
            var visible by mutableStateOf(true)
            var contract by mutableStateOf<ActivityResultContract<Intent, ActivityResult>>(
                ActivityResultContracts.StartActivityForResult(),
            )
            var callbacks = 0
            var observed: ActivityResultLauncher<Intent>? = null
            var observedContract: ActivityResultContract<Intent, ActivityResult>? = null
            var absent = false
            val scene = ImageComposeScene(320, 200, Density(1f)) {
                CompositionLocalProvider(LocalDesktopUpstreamAndroidContext provides page.androidContext) {
                    if (visible) {
                        val launcher = rememberLauncherForActivityResult(contract) { callbacks++ }
                        SideEffect { observed = launcher; observedContract = contract; absent = false }
                    } else {
                        SideEffect { absent = true }
                    }
                }
            }
            suspend fun await(condition: () -> Boolean) {
                withTimeout(5_000) {
                    do {
                        scene.render(System.nanoTime()).close()
                        if (condition()) return@withTimeout
                        delay(12)
                    } while (true)
                }
            }
            val request = Intent("test.permission")
            fun assertUnavailable(launcher: ActivityResultLauncher<Intent>, action: String) {
                val failure = assertFailsWith<UnsupportedDesktopCapability> { launcher.launch(request) }
                assertContains(failure.message.orEmpty(), action)
                assertEquals(0, callbacks)
                assertEquals(before, DesktopPluginStore(root).preferences("settings"))
            }
            try {
                await { observed != null }
                val first = assertNotNull(observed)
                assertUnavailable(first, request.action)

                val replacement = ActivityResultContracts.StartActivityForResult()
                contract = replacement
                await { observedContract === replacement }
                assertSame(first, observed, "New contract instances must not unregister the launcher")
                assertUnavailable(first, request.action)

                // Also prove the retained launcher delegates to the current contract, not the first one.
                var conversions = 0
                val latest = object : ActivityResultContract<Intent, ActivityResult>() {
                    override fun createIntent(context: Context, input: Intent): Intent {
                        assertSame(page.androidContext, context)
                        assertSame(request, input)
                        conversions++
                        return Intent("test.latest-contract")
                    }
                    override fun parseResult(resultCode: Int, intent: Intent?): ActivityResult =
                        error("Desktop must not fabricate or parse activity results")
                }
                contract = latest
                await { observedContract === latest }
                assertSame(first, observed)
                assertUnavailable(first, "test.latest-contract")
                assertEquals(1, conversions)

                visible = false
                await { absent }
                val failure = assertFailsWith<IllegalStateException> { first.launch(request) }
                assertContains(failure.message.orEmpty(), "unregistered")
                assertEquals(1, conversions, "Unregistered launchers must not enter the contract")

                visible = true
                await { !absent && observed !== first }
                val second = assertNotNull(observed)
                assertNotSame(first, second)
                assertUnavailable(second, "test.latest-contract")
                assertEquals(2, conversions)
            } finally {
                scene.close()
            }
            val failure = assertFailsWith<IllegalStateException> { assertNotNull(observed).launch(request) }
            assertContains(failure.message.orEmpty(), "unregistered")
            assertEquals(0, callbacks)
            assertEquals(before, DesktopPluginStore(root).preferences("settings"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
