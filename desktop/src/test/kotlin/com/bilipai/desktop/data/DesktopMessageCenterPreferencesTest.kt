package com.bilipai.desktop.data

import com.android.purebilibili.core.store.MessageCenterPreferences
import com.android.purebilibili.core.store.UnreadBadgeMode
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.plugins.asAndroidContext
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DesktopMessageCenterPreferencesTest {
    @TempDir lateinit var directory: Path

    @Test fun messagePreferencesShareRepositoryStorageAndSurviveReopening() = runBlocking {
        fun context() = DesktopPluginContext(DesktopPluginStore(directory)).asAndroidContext()
        val store = DesktopPluginStore(directory)
        val page = DesktopPluginContext(store).asAndroidContext()
        val repository = DesktopRepositoryStorageContext(page)

        assertFalse(MessageCenterPreferences.disabledLikes(page).first())
        assertFalse(desktopMessageLikesDisabled(repository))
        MessageCenterPreferences.setDisabledLikes(page, true)
        MessageCenterPreferences.setDynamicBadgeMode(page, UnreadBadgeMode.DOT)
        MessageCenterPreferences.setMessageBadgeMode(page, UnreadBadgeMode.HIDDEN)
        assertTrue(desktopMessageLikesDisabled(repository))

        // Retire the in-memory backing so the next context must reload the document from disk.
        store.freezeWrites()
        val reopened = context()
        assertTrue(MessageCenterPreferences.disabledLikes(reopened).first())
        assertTrue(desktopMessageLikesDisabled(reopened))
        assertEquals(UnreadBadgeMode.DOT, MessageCenterPreferences.dynamicBadgeMode(reopened).first())
        assertEquals(UnreadBadgeMode.HIDDEN, MessageCenterPreferences.messageBadgeMode(reopened).first())
        MessageCenterPreferences.setDisabledLikes(reopened, false)
        assertFalse(desktopMessageLikesDisabled(reopened))
    }

    @Test fun retiredAdmissionRejectsMessagePreferenceWrites() = runBlocking {
        val store = DesktopPluginStore(directory)
        val context = DesktopPluginContext(store).asAndroidContext(
            { throw CancellationException("retired") },
            { throw CancellationException("retired") },
        )
        assertFailsWith<CancellationException> { MessageCenterPreferences.setDisabledLikes(context, true) }
        assertFalse(desktopMessageLikesDisabled(DesktopPluginContext(store).asAndroidContext()))
    }
}
