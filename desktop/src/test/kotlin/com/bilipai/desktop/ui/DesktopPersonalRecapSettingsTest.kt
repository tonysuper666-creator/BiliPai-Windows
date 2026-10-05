package com.bilipai.desktop.ui

import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import kotlin.test.*

class DesktopPersonalRecapSettingsTest {
    @Test fun originalDefaultAndExplicitOffPersistWithoutChangingOtherSettings(): Unit = runBlocking {
        val path=Files.createTempDirectory("recap-settings-test-")
        try {
            val store=DesktopPluginStore(path);val context=DesktopPluginContext(store)
            store.update("settings",mapOf("other" to JsonPrimitive("keep")))
            val owned=DesktopOriginalPlayerSettingsContext(context,{true},{ action->action();true })
            assertFalse(DesktopPersonalRecapSettings.getSubscriptionRecapEnabled(context).first())
            DesktopPersonalRecapSettings.setSubscriptionRecapEnabled(owned,true)
            assertTrue(DesktopFavoritePreferences(store).personalRecapEnabled.first())
            DesktopPersonalRecapSettings.setSubscriptionRecapEnabled(owned,false)
            assertFalse(DesktopPersonalRecapSettings.getSubscriptionRecapEnabled(DesktopPluginContext(DesktopPluginStore(path))).first())
            assertEquals(JsonPrimitive("keep"),store.preferences("settings")["other"])
            assertEquals(setOf("other","subscription_recap_enabled"),store.preferences("settings").keys)
        } finally { path.toFile().deleteRecursively() }
    }
    @Test fun actualContextFinalAdmissionRejectsRetirementWithoutWritingPreference(): Unit = runBlocking {
        val path=Files.createTempDirectory("recap-settings-retire-")
        try {
            val store=DesktopPluginStore(path);val context=DesktopPluginContext(store);var owned=true
            val current=DesktopOriginalPlayerSettingsContext(context,{owned},{ action->owned=false;action();true })
            val failed=runCatching { DesktopPersonalRecapSettings.setSubscriptionRecapEnabled(current,true) }
            assertIs<CancellationException>(failed.exceptionOrNull())
            assertFalse(DesktopPersonalRecapSettings.getSubscriptionRecapEnabled(context).first())
            assertFalse(store.preferences("settings").containsKey("subscription_recap_enabled"))
        } finally { path.toFile().deleteRecursively() }
    }
    @Test fun cancelledActualCallerCannotWriteEvenWhileEntryRemainsOwned(): Unit = runBlocking {
        val path=Files.createTempDirectory("recap-settings-cancel-")
        try {
            val context=DesktopPluginContext(DesktopPluginStore(path))
            val owned=DesktopOriginalPlayerSettingsContext(context,{true},{ action->action();true })
            val failure=CompletableDeferred<Throwable?>()
            val request=launch(start=CoroutineStart.UNDISPATCHED) {
                currentCoroutineContext().cancel()
                failure.complete(runCatching { DesktopPersonalRecapSettings.setSubscriptionRecapEnabled(owned,true) }.exceptionOrNull())
            }
            request.join();assertIs<CancellationException>(failure.await())
            assertFalse(DesktopPersonalRecapSettings.getSubscriptionRecapEnabled(context).first())
            assertFalse(context.store.preferences("settings").containsKey("subscription_recap_enabled"))
        } finally { path.toFile().deleteRecursively() }
    }
}
