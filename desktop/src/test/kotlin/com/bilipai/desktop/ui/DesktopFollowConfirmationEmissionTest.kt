package com.bilipai.desktop.ui

import androidx.compose.runtime.Composer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Proves that the carried upstream FollowActionConfirmation.kt is compiled into the Windows build with its shape and
 * that its alert goes through the Windows owned-window adapter. Reflection and class-file bytes only: no composition,
 * window or GPU is created. */
class DesktopFollowConfirmationEmissionTest {
    private val carried by lazy {
        Class.forName("com.android.purebilibili.core.ui.components.FollowActionConfirmationKt")
    }
    private val method by lazy {
        carried.declaredMethods.single { it.name == "rememberFollowActionWithConfirmation" }
    }

    @Test
    fun theCarriedFunctionIsCompiledOnceWithTheUpstreamParameters() {
        assertEquals(
            listOf(Any::class.java, String::class.java, java.lang.Boolean.TYPE, Function0::class.java),
            method.parameterTypes.take(4),
            "target key, user name, relation and the action, in the upstream order",
        )
        assertTrue(method.parameterTypes.any { it == Composer::class.java }, "it is a composable function")
    }

    @Test
    fun itReturnsTheGuardedClickHandlerAsAFunction() {
        assertEquals(Function0::class.java, method.returnType)
        assertTrue(java.lang.reflect.Modifier.isStatic(method.modifiers))
    }

    @Test
    fun theCarriedAlertIsBoundToTheWindowsOwnedWindowAdapterAndNotToThePlainAlert() {
        val bytes = assertNotNull(carried.getResourceAsStream("FollowActionConfirmationKt.class")).use { it.readBytes() }
        val constants = String(bytes, Charsets.ISO_8859_1)
        assertTrue(
            constants.contains("com/bilipai/desktop/ui/DesktopWindowsFollowConfirmationDialogKt"),
            "the import alias must bind the upstream AppAlertDialog call to the desktop-owned adapter",
        )
        assertFalse(
            constants.contains("com/android/purebilibili/core/ui/AppDialogComponentsKt"),
            "an in-canvas AppAlertDialog under the heavyweight video canvas could hide the confirmation",
        )
    }
}
