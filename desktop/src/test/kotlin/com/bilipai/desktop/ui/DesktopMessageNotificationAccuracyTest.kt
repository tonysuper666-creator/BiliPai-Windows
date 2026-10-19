package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.DesktopDynamicAuthorUpdatesApi
import com.android.purebilibili.core.network.DesktopDynamicAuthorUpdatesService
import com.android.purebilibili.core.network.grpc.DesktopDynamicPersonalFeedApi
import com.android.purebilibili.core.network.grpc.ProtoWire
import com.android.purebilibili.data.model.response.DynamicAuthorUpdateItem
import com.android.purebilibili.data.model.response.DynamicAuthorUpdatesPage
import com.android.purebilibili.data.model.response.DynamicAuthorUpdatesPageResponse
import com.android.purebilibili.data.model.response.DynamicAuthorUpdatesPortalData
import com.android.purebilibili.data.model.response.DynamicAuthorUpdatesPortalResponse
import com.android.purebilibili.feature.message.notification.AccountNotificationState
import com.android.purebilibili.feature.message.notification.PendingMessageNotification
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** No Windows, GPU or network: the token gate of the DynAllPersonal preload, the recent-author snapshot
 * wiring and the tray-menu degradation that replaces withdrawing an already submitted balloon. */
class DesktopMessageNotificationAccuracyTest {
    private val preloadPath = "/bilibili.app.dynamic.v2.Dynamic/DynAllPersonal"

    @Test
    fun `a session without an App token fails the preload before any request`() {
        runBlocking {
            for (token in listOf(null, "", "   ")) {
                var requests = 0
                val api = DesktopDynamicPersonalFeedApi({ _, _ -> requests++; ByteArray(0) }, { token })
                val failure = assertFailsWith<IllegalStateException> { api.load(7L) }
                assertEquals(DESKTOP_DYNAMIC_PRELOAD_APP_AUTHORIZATION_REASON, failure.message, "token=$token")
                assertEquals(0, requests, "token=$token")
            }
        }
    }

    @Test
    fun `an App token lets the preload issue exactly one DynAllPersonal request`() {
        runBlocking {
            val paths = mutableListOf<String>()
            val api = DesktopDynamicPersonalFeedApi({ path, _ ->
                paths += path
                ProtoWire.message(ProtoWire.string(2, "cursor"), ProtoWire.bool(3, false))
            }, { "access-token" })
            val page = api.load(7L)
            assertEquals(listOf(preloadPath), paths)
            assertTrue(page.items.isEmpty())
            assertEquals("cursor", page.offset)
            assertEquals(false, page.hasMore)
        }
    }

    @Test
    fun `the blocked reason is only reported for a missing or blank token`() {
        assertEquals(DESKTOP_DYNAMIC_PRELOAD_APP_AUTHORIZATION_REASON, desktopDynamicPreloadBlockedReason(null))
        assertEquals(DESKTOP_DYNAMIC_PRELOAD_APP_AUTHORIZATION_REASON, desktopDynamicPreloadBlockedReason(""))
        assertEquals(DESKTOP_DYNAMIC_PRELOAD_APP_AUTHORIZATION_REASON, desktopDynamicPreloadBlockedReason(" \t"))
        assertNull(desktopDynamicPreloadBlockedReason("access-token"))
    }

    @Test
    fun `the dynamic category stops before its first request when no App token exists`() {
        for (token in listOf(null, "", "  ")) {
            val failure = assertFailsWith<IllegalStateException> { requireDesktopDynamicPreloadAuthorization(token) }
            assertEquals(DESKTOP_DYNAMIC_PRELOAD_APP_AUTHORIZATION_REASON, failure.message, "token=$token")
        }
        requireDesktopDynamicPreloadAuthorization("access-token")
    }

    private class FakeAuthorUpdatesService(
        private val portal: DynamicAuthorUpdatesPortalResponse,
        private val pages: Map<String, DynamicAuthorUpdatesPageResponse>,
    ) : DesktopDynamicAuthorUpdatesService {
        val calls = mutableListOf<String>()
        override suspend fun getDynamicAuthorUpdatesPortal(upListMore: Int, webLocation: String): DynamicAuthorUpdatesPortalResponse {
            calls += "portal:$upListMore:$webLocation"
            return portal
        }
        override suspend fun getDynamicAuthorUpdatesPage(offset: String, platform: String, webLocation: String): DynamicAuthorUpdatesPageResponse {
            calls += "page:$offset:$platform:$webLocation"
            return pages.getValue(offset)
        }
    }

    @Test
    fun `the recent-author snapshot walks the portal and every later page with the original query values`() {
        runBlocking {
            val service = FakeAuthorUpdatesService(
                DynamicAuthorUpdatesPortalResponse(0, DynamicAuthorUpdatesPortalData(DynamicAuthorUpdatesPage(true,
                    listOf(DynamicAuthorUpdateItem(11L, true), DynamicAuthorUpdateItem(12L, null)), "c1"))),
                mapOf("c1" to DynamicAuthorUpdatesPageResponse(0, DynamicAuthorUpdatesPage(false,
                    listOf(DynamicAuthorUpdateItem(21L, false)), ""))),
            )
            val updates = DesktopDynamicAuthorUpdatesApi(service).load()
            // An author without a flag stays unknown; only an explicit false may close a red dot.
            assertEquals(mapOf(11L to true, 21L to false), updates)
            assertEquals(listOf("portal:1:333.1365", "page:c1:web:333.1365"), service.calls)
        }
    }

    @Test
    fun `a failed second page cannot publish the first page as a snapshot`() {
        runBlocking {
            val service = FakeAuthorUpdatesService(
                DynamicAuthorUpdatesPortalResponse(0, DynamicAuthorUpdatesPortalData(DynamicAuthorUpdatesPage(true,
                    listOf(DynamicAuthorUpdateItem(11L, false)), "c1"))),
                emptyMap(),
            )
            assertFailsWith<NoSuchElementException> { DesktopDynamicAuthorUpdatesApi(service).load() }
            // The failure came from the second page request, after the portal had already succeeded.
            assertEquals(listOf("portal:1:333.1365", "page:c1:web:333.1365"), service.calls)
        }
    }

    private fun item(key: String, group: String, summary: Boolean = false) =
        PendingMessageNotification(key, 1, "route", key, key, group, summary)

    @Test
    fun `a group summary entry ends once the server cleared everything it summarised`() {
        val entries = DesktopMessageNotificationEntries()
        entries.apply(listOf(item("dynamic:1", "dynamic"), item("dynamic:2", "dynamic"),
            item("dynamic:summary", "dynamic", summary = true)), emptySet(), mapOf("dynamic" to 5))
        assertEquals(setOf("dynamic:1", "dynamic:2", "dynamic:summary"), entries.keys())
        // Five were pending: the three overflow ids are cancelled with the two visible ones.
        entries.apply(emptyList(), setOf("dynamic:1", "dynamic:2", "dynamic:3", "dynamic:4", "dynamic:5"),
            mapOf("dynamic" to 0))
        assertTrue(entries.keys().isEmpty())
    }

    @Test
    fun `a summary entry stays while unread items are still pending`() {
        val entries = DesktopMessageNotificationEntries()
        entries.apply(listOf(item("dynamic:1", "dynamic"), item("dynamic:summary", "dynamic", summary = true)),
            emptySet(), mapOf("dynamic" to 4))
        entries.apply(emptyList(), setOf("dynamic:1"), mapOf("dynamic" to 3))
        assertEquals(setOf("dynamic:summary"), entries.keys())
    }

    @Test
    fun `unknown pending counts keep every summary entry`() {
        val entries = DesktopMessageNotificationEntries()
        entries.apply(listOf(item("dynamic:1", "dynamic"), item("dynamic:summary", "dynamic", summary = true)), emptySet())
        entries.apply(emptyList(), setOf("dynamic:1"))
        assertEquals(setOf("dynamic:summary"), entries.keys())
    }

    @Test
    fun `clearing one group never removes the summary of another group`() {
        val entries = DesktopMessageNotificationEntries()
        entries.apply(listOf(item("dm:1_1", "dm"), item("dm:summary", "dm", summary = true),
            item("dynamic:1", "dynamic"), item("dynamic:summary", "dynamic", summary = true)),
            emptySet(), mapOf("dynamic" to 4, "dm" to 6))
        entries.apply(emptyList(), setOf("dynamic:1", "dynamic:2", "dynamic:3", "dynamic:4"), mapOf("dynamic" to 0, "dm" to 6))
        assertEquals(setOf("dm:1_1", "dm:summary"), entries.keys())
    }

    @Test
    fun `cancelled direct message keys end the dm summary once nothing is pending`() {
        val entries = DesktopMessageNotificationEntries()
        entries.apply(listOf(item("dm:1_1", "dm"), item("dm:summary", "dm", summary = true)),
            emptySet(), mapOf("dm" to 2))
        assertEquals(setOf("dm:1_1", "dm:summary"), entries.keys())
        // Two sessions were pending; the second one was never shown but is cancelled with the visible one.
        entries.apply(emptyList(), setOf("dm:1_1", "dm:2_1"), mapOf("dm" to 0))
        assertTrue(entries.keys().isEmpty())
    }

    @Test
    fun `the pending counts mirror the committed baseline`() {
        val state = AccountNotificationState(
            pendingDynamicIds = mapOf("100" to 11L, "101" to 12L, "102" to 11L),
            pendingPrivateSessionKeys = setOf("5_1"),
        )
        assertEquals(mapOf("dynamic" to 3, "dm" to 1), desktopPendingSummaryCounts(state))
        assertEquals(mapOf("dynamic" to 0, "dm" to 0), desktopPendingSummaryCounts(AccountNotificationState()))
    }
}
