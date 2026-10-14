package com.android.purebilibili.feature.message.notification

import com.android.purebilibili.core.network.grpc.DynamicPersonalFeedPage
import com.android.purebilibili.data.model.response.ArchiveMajor
import com.android.purebilibili.data.model.response.DynamicAuthorModule
import com.android.purebilibili.data.model.response.DynamicContentModule
import com.android.purebilibili.data.model.response.DynamicItem
import com.android.purebilibili.data.model.response.DynamicMajor
import com.android.purebilibili.data.model.response.DynamicModules
import com.android.purebilibili.data.model.response.DynamicTagModule
import com.android.purebilibili.data.model.response.FollowedLiveData
import com.android.purebilibili.data.model.response.FollowedLiveRoom
import com.android.purebilibili.data.model.response.FollowingUser
import com.android.purebilibili.data.model.response.FollowingsData
import com.android.purebilibili.data.model.response.MessageFeedUnreadData
import com.android.purebilibili.data.model.response.PageInfo
import com.android.purebilibili.data.model.response.SessionItem
import com.android.purebilibili.data.model.response.SessionMessage
import com.android.purebilibili.data.model.response.SystemNoticeItem
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MessageNotificationPolicyTest {
    private val enabled = MessageNotificationSettings(enabled = true)
    private val dynamicOnly = enabled.copy(
        notifyPrivateMessages = false, notifyReplies = false, notifyAtMe = false,
        notifyLikes = false, notifySystemNotices = false, notifyLiveAlerts = false,
    )
    private val liveOnly = enabled.copy(
        notifyPrivateMessages = false, notifyReplies = false, notifyAtMe = false,
        notifyLikes = false, notifySystemNotices = false, notifyDynamicUpdates = false,
    )
    private val interactionsOnly = enabled.copy(
        notifyPrivateMessages = false, notifySystemNotices = false,
        notifyDynamicUpdates = false, notifyLiveAlerts = false,
    )

    @Test
    fun timelyModeShortensBothSchedulesWithoutBatteryConstraint() {
        val saving = resolveMessageNotificationTiming(MessageNotificationMode.POWER_SAVING)
        val timely = resolveMessageNotificationTiming(MessageNotificationMode.MORE_TIMELY)
        assertEquals(MessageNotificationTiming(30, 120, true), saving)
        assertEquals(MessageNotificationTiming(15, 60, false), timely)
    }

    @Test
    fun residentPollDropsToIdleFloorOnlyWhenScreenOffAndDischarging() {
        assertEquals(60_000L, resolveResidentPollDelayMs(MessageNotificationMode.MORE_TIMELY, screenOn = true, charging = false))
        assertEquals(60_000L, resolveResidentPollDelayMs(MessageNotificationMode.MORE_TIMELY, screenOn = false, charging = true))
        assertEquals(120_000L, resolveResidentPollDelayMs(MessageNotificationMode.POWER_SAVING, screenOn = true, charging = true))
        assertEquals(RESIDENT_IDLE_POLL_MS, resolveResidentPollDelayMs(MessageNotificationMode.MORE_TIMELY, screenOn = false, charging = false))
        assertEquals(RESIDENT_IDLE_POLL_MS, resolveResidentPollDelayMs(MessageNotificationMode.POWER_SAVING, screenOn = false, charging = false))
    }

    @Test
    fun privateMessagesRespectReadStateMutingSenderAndSessionIdentity() {
        val message = SessionItem(talker_id = 9, unread_count = 1, last_msg = SessionMessage(sender_uid = 9, msg_key = 100))
        val sessions = listOf(
            message,
            message.copy(session_type = 2),
            message.copy(talker_id = 10, unread_count = 0),
            message.copy(talker_id = 11, is_dnd = 1),
            message.copy(talker_id = 12, is_intercept = 1),
            message.copy(talker_id = 13, last_msg = message.last_msg?.copy(sender_uid = 1)),
            message.copy(talker_id = 14, last_msg = null),
            message.copy(talker_id = 15, last_msg = message.last_msg?.copy(msg_key = 0)),
            message.copy(talker_id = 0),
        )
        val result = filterNewPrivateSessions(sessions, 1, mapOf("9_1" to 100L))
        assertEquals(listOf("9_2"), result.map(::sessionNotificationKey))
        assertEquals(listOf("9_1", "9_2"), filterNewPrivateSessions(sessions, 1, emptyMap()).map(::sessionNotificationKey))
    }

    @Test
    fun boundedSeenRetainsNewestItemsEvenWithPageOverlap() {
        val seen = mergeNotificationSeen(listOf(1L, 2L, 3L), listOf(6L, 5L, 4L, 3L), 4)
        assertEquals(listOf(3L, 4L, 5L, 6L), seen)
    }

    @Test
    fun dynamicEligibilityExcludesAdsFoldedSelfAndUnfollowedAuthors() {
        val item = dynamic("1")
        assertTrue(shouldNotifyDynamicItem(item, 1))
        assertFalse(shouldNotifyDynamicItem(item.copy(type = "DYNAMIC_TYPE_AD"), 1))
        assertFalse(shouldNotifyDynamicItem(item.copy(type = "DYNAMIC_TYPE_BANNER"), 1))
        assertFalse(shouldNotifyDynamicItem(item.copy(visible = false), 1))
        assertFalse(shouldNotifyDynamicItem(item, 9))
        assertFalse(shouldNotifyDynamicItem(item.copy(modules = item.modules.copy(
            module_author = DynamicAuthorModule(mid = 9, following = false))), 1))
        assertFalse(shouldNotifyDynamicItem(DynamicItem(), 1))
        assertEquals("video_player/BV1xx411c7mD?cid=0&aid=0&commentRootRpid=0&commentTargetRpid=0", resolveDynamicNotificationRoute(item))
    }

    @Test
    fun liveSessionsOnlyNotifyForRealStartTransitions() {
        val room = live(9, 100)
        val initial = resolveLiveSessionTransitions(listOf(room, live(10, 1).copy(liveStatus = 2)), emptyMap(), false)
        assertEquals(emptyList(), initial.first)
        assertEquals(mapOf(9L to 100L), initial.second)
        assertEquals(emptyList(), resolveLiveSessionTransitions(listOf(room), initial.second, true).first)
        assertEquals(listOf(room.copy(liveTime = 200)), resolveLiveSessionTransitions(listOf(room.copy(liveTime = 200)), initial.second, true).first)
        val offline = resolveLiveSessionTransitions(emptyList(), initial.second, true)
        assertEquals(emptyMap(), offline.second)
        assertEquals(listOf(room), resolveLiveSessionTransitions(listOf(room), offline.second, true).first)
        val unknownTime = room.copy(liveTime = 0)
        assertEquals(emptyList(), resolveLiveSessionTransitions(listOf(unknownTime), mapOf(9L to 0L), true).first)
    }

    @Test
    fun firstScanSilentlyInitializesEveryCategoryThenUnchangedScanIsSilent() = runTest {
        val source = Source().apply {
            sessions = listOf(SessionItem(talker_id = 9, unread_count = 3, last_msg = SessionMessage(sender_uid = 9, msg_key = 1)))
            unread = MessageFeedUnreadData(reply = 1, at = 2, like = 3)
            notices = listOf(SystemNoticeItem(cursor = 7, title = "notice"))
            pages = mapOf((9L to "") to DynamicPersonalFeedPage(items = listOf(dynamic("100"))))
            rooms = mapOf(1 to FollowedLiveData(list = listOf(live(9, 100)), livingNum = 1))
        }
        val poller = MessageNotificationPoller(source)
        val initial = poller.check(enabled, AccountNotificationState(), 1)
        assertEquals(emptyList(), initial.notifications)
        assertEquals("100", initial.state.dynamicBaseline)
        assertEquals(emptyList(), poller.check(enabled, initial.state, 1).notifications)
    }

    @Test
    fun businessFailureDoesNotEstablishUnreadBaselinesAndRecoveryIsSilent() = runTest {
        val source = Source().apply { unreadError = IllegalStateException("not logged in") }
        val poller = MessageNotificationPoller(source)
        val first = poller.check(enabled, AccountNotificationState(), 1)
        assertEquals(null, first.state.replyUnreadCount)
        assertEquals(null, first.state.atUnreadCount)
        assertEquals(null, first.state.likeUnreadCount)
        assertFalse(first.shouldRetry)
        source.unreadError = null
        source.unread = MessageFeedUnreadData(reply = 10, at = 20)
        val next = poller.check(enabled, first.state, 1)
        assertEquals(emptyList(), next.notifications)
        assertEquals(10, next.state.replyUnreadCount)
        assertEquals(20, next.state.atUnreadCount)
    }

    @Test
    fun unreadCountsRebaselineLegacyStateThenNotifyOnlyIncreasesAndResetAfterReads() = runTest {
        val source = Source().apply { unread = MessageFeedUnreadData(reply = 5, at = 2, like = 1) }
        val poller = MessageNotificationPoller(source)
        val legacy = AccountNotificationState(initialized = setOf("reply", "at", "like"))
        val initial = poller.check(interactionsOnly, legacy, 1)
        assertEquals(emptyList(), initial.notifications)
        assertEquals(emptyList(), poller.check(interactionsOnly, initial.state, 1).notifications)

        source.unread = MessageFeedUnreadData(reply = 7, at = 4, like = 3)
        val increased = poller.check(interactionsOnly, initial.state, 1)
        assertEquals(listOf("reply", "at", "like"), increased.notifications.map { it.key })
        assertEquals(listOf(2, 2, 2), increased.notifications.map { it.text.filter(Char::isDigit).toInt() })
        assertEquals(emptyList(), poller.check(interactionsOnly, increased.state, 1).notifications)

        source.unread = MessageFeedUnreadData(at = 1)
        val read = poller.check(interactionsOnly, increased.state, 1)
        assertEquals(emptyList(), read.notifications)
        source.unread = MessageFeedUnreadData(reply = 1, at = 1)
        val next = poller.check(interactionsOnly, read.state, 1)
        assertEquals(listOf("reply"), next.notifications.map { it.key })
        assertEquals(1, next.notifications.single().text.filter(Char::isDigit).toInt())
    }

    @Test
    fun failedUnreadSnapshotPreservesBaselinesWhileSystemNoticesCommit() = runTest {
        val source = Source().apply {
            unreadError = IOException("network interrupted")
            notices = listOf(SystemNoticeItem(cursor = -3, title = "new"))
        }
        val settings = interactionsOnly.copy(notifySystemNotices = true)
        val previous = AccountNotificationState(
            initialized = setOf("sysmsg"), seenSystemCursors = listOf(8),
            replyUnreadCount = 5, atUnreadCount = 4, likeUnreadCount = 3,
        )
        val poller = MessageNotificationPoller(source)
        val failed = poller.check(settings, previous, 1)
        assertEquals(5, failed.state.replyUnreadCount)
        assertEquals(4, failed.state.atUnreadCount)
        assertEquals(3, failed.state.likeUnreadCount)
        assertEquals(listOf("sysmsg"), failed.notifications.map { it.key })
        assertTrue(failed.shouldRetry)

        source.unreadError = null
        source.unread = MessageFeedUnreadData(reply = 7, at = 5, like = 4)
        val recovered = poller.check(settings, failed.state, 1)
        assertEquals(listOf("reply", "at", "like"), recovered.notifications.map { it.key })
        assertEquals(listOf(2, 1, 1), recovered.notifications.map { it.text.filter(Char::isDigit).toInt() })
    }

    @Test
    fun enablingInteractionCategoriesEstablishesIndependentSilentBaselines() = runTest {
        val source = Source().apply { unread = MessageFeedUnreadData(reply = 6, at = 3, like = 2) }
        val poller = MessageNotificationPoller(source)
        val replyOnly = interactionsOnly.copy(notifyAtMe = false, notifyLikes = false)
        val first = poller.check(replyOnly, AccountNotificationState(replyUnreadCount = 5), 1)
        assertEquals(listOf("reply"), first.notifications.map { it.key })
        assertEquals(null, first.state.atUnreadCount)
        assertEquals(null, first.state.likeUnreadCount)
        val enabled = poller.check(interactionsOnly, first.state, 1)
        assertEquals(emptyList(), enabled.notifications)

        source.unread = MessageFeedUnreadData(reply = 6, at = 4, like = 3)
        val next = poller.check(interactionsOnly, enabled.state, 1)
        assertEquals(listOf("at", "like"), next.notifications.map { it.key })
        assertEquals(listOf(1, 1), next.notifications.map { it.text.filter(Char::isDigit).toInt() })
    }

    @Test
    fun dynamicNoUpdatesNeverFetchesPersonalFeedsOrAdvancesBaseline() = runTest {
        val source = Source().apply { updates = 0 }
        val previous = AccountNotificationState(initialized = setOf("dynamic"),
            dynamicBaseline = "100", dynamicUpBaselines = mapOf(9L to "100"))
        val result = MessageNotificationPoller(source).check(dynamicOnly, previous, 1)
        assertEquals(previous, result.state)
        assertEquals(emptyList(), source.feedRequests)
        assertEquals(emptyList(), result.notifications)
    }

    @Test
    fun failedPersonalSecondPageKeepsBaselineWhileOtherCategoryCommits() = runTest {
        val source = Source().apply {
            unread = MessageFeedUnreadData(reply = 10)
            pages = mapOf((9L to "") to DynamicPersonalFeedPage(
                items = listOf(dynamic("120")), hasMore = true, offset = "next"))
            feedErrorOffset = "next"
        }
        val previous = AccountNotificationState(initialized = setOf("dynamic"), dynamicBaseline = "100",
            dynamicUpBaselines = mapOf(9L to "100"), replyUnreadCount = 0)
        val result = MessageNotificationPoller(source).check(enabled, previous, 1)
        assertEquals("100", result.state.dynamicBaseline)
        assertEquals(mapOf(9L to "100"), result.state.dynamicUpBaselines)
        assertEquals(listOf("reply"), result.notifications.map { it.key })
        assertTrue(result.shouldRetry)
        source.feedErrorOffset = null
        source.pages = source.pages + ((9L to "next") to DynamicPersonalFeedPage(
            items = listOf(dynamic("110"), dynamic("100"))))
        val recovered = MessageNotificationPoller(source).check(enabled, result.state, 1)
        assertEquals("120", recovered.state.dynamicBaseline)
        assertEquals(listOf("dynamic:120", "dynamic:110"), recovered.notifications.map { it.key })
        assertEquals(emptyList(), MessageNotificationPoller(source).check(enabled, recovered.state, 1).notifications)
    }

    @Test
    fun repeatedPersonalOffsetPreservesGapAndRecoverySummarizesNewestItems() = runTest {
        val source = Source().apply {
            pages = mapOf(
                (9L to "") to DynamicPersonalFeedPage(items = (109 downTo 102).map { dynamic("$it") },
                    hasMore = true, offset = "next"),
                (9L to "next") to DynamicPersonalFeedPage(items = listOf(dynamic("101")),
                    hasMore = true, offset = "next"),
            )
        }
        val previous = AccountNotificationState(initialized = setOf("dynamic"),
            dynamicBaseline = "100", dynamicUpBaselines = mapOf(9L to "100"))
        val poller = MessageNotificationPoller(source)
        val failed = poller.check(dynamicOnly, previous, 1)
        assertEquals(previous, failed.state)
        assertEquals(emptyList(), failed.notifications)
        source.pages = source.pages + ((9L to "next") to DynamicPersonalFeedPage(items = listOf(dynamic("101"))))
        val recovered = poller.check(dynamicOnly, failed.state, 1)
        assertEquals(listOf("dynamic:109", "dynamic:108", "dynamic:107", "dynamic:summary"),
            recovered.notifications.map { it.key })
        assertEquals(9, recovered.notifications.last().text.filter(Char::isDigit).toInt())
        assertEquals("109", recovered.state.dynamicUpBaselines[9L])
    }

    @Test
    fun newlyFollowedUpEstablishesSilentBaselineEvenWithoutGlobalUpdates() = runTest {
        val source = Source().apply {
            updates = 0
            followingPages = mapOf(1 to FollowingsData(list = listOf(FollowingUser(mid = 9), FollowingUser(mid = 10)), total = 2))
            pages = mapOf((10L to "") to DynamicPersonalFeedPage(items = listOf(dynamic("50", 10))))
        }
        val previous = AccountNotificationState(dynamicBaseline = "100", dynamicUpBaselines = mapOf(9L to "100"))
        val initial = MessageNotificationPoller(source).check(dynamicOnly, previous, 1)
        assertEquals(emptyList(), initial.notifications)
        assertEquals(mapOf(9L to "100", 10L to "50"), initial.state.dynamicUpBaselines)
        source.updates = 1
        source.pages = mapOf(
            (9L to "") to DynamicPersonalFeedPage(items = listOf(dynamic("100"))),
            (10L to "") to DynamicPersonalFeedPage(items = listOf(dynamic("110", 10))),
        )
        val next = MessageNotificationPoller(source).check(dynamicOnly, initial.state, 1)
        assertEquals(listOf("dynamic:110"), next.notifications.map { it.key })
    }

    @Test
    fun oneUpFailureDoesNotDiscardOtherUpsOrAdvanceGlobalProbePastTheFailure() = runTest {
        val source = Source().apply {
            followingPages = mapOf(1 to FollowingsData(list = listOf(FollowingUser(mid = 9), FollowingUser(mid = 10)), total = 2))
            pages = mapOf((9L to "") to DynamicPersonalFeedPage(items = listOf(dynamic("110"))))
            feedErrorHost = 10L
        }
        val previous = AccountNotificationState(dynamicBaseline = "100",
            dynamicUpBaselines = mapOf(9L to "100", 10L to "90"))
        val poller = MessageNotificationPoller(source)
        val partial = poller.check(dynamicOnly, previous, 1)
        assertEquals(listOf("dynamic:110"), partial.notifications.map { it.key })
        assertEquals(mapOf(9L to "110", 10L to "90"), partial.state.dynamicUpBaselines)
        assertEquals("100", partial.state.dynamicBaseline)
        assertTrue(partial.shouldRetry)
        source.feedErrorHost = null
        source.pages = source.pages + ((10L to "") to DynamicPersonalFeedPage(items = listOf(dynamic("120", 10))))
        val recovered = poller.check(dynamicOnly, partial.state, 1)
        assertEquals(listOf("dynamic:120"), recovered.notifications.map { it.key })
        assertEquals("120", recovered.state.dynamicBaseline)
    }

    @Test
    fun followingPaginationIncludesLaterUpsAndIncompleteRefreshNeverPrunesState() = runTest {
        val source = Source().apply {
            updates = 0
            followingPages = mapOf(
                1 to FollowingsData(list = (9L..58L).map { FollowingUser(mid = it) }, total = 51),
                2 to FollowingsData(list = listOf(FollowingUser(mid = 59)), total = 51),
            )
            pages = (10L..59L).associate { (it to "") to DynamicPersonalFeedPage(items = listOf(dynamic("$it", it))) }
        }
        val previous = AccountNotificationState(dynamicBaseline = "100", dynamicUpBaselines = mapOf(9L to "100"))
        val poller = MessageNotificationPoller(source)
        val initial = poller.check(dynamicOnly, previous, 1)
        assertEquals(emptyList(), initial.notifications)
        source.updates = 1
        source.pages = source.pages + mapOf(
            (9L to "") to DynamicPersonalFeedPage(items = listOf(dynamic("100"))),
            (59L to "") to DynamicPersonalFeedPage(items = listOf(dynamic("110", 59))),
        )
        val updated = poller.check(dynamicOnly, initial.state, 1)
        assertEquals(listOf("dynamic:110"), updated.notifications.map { it.key })
        source.followingErrorPage = 2
        val failure = poller.check(dynamicOnly, updated.state, 1)
        assertEquals(updated.state, failure.state)
        assertEquals(emptyList(), failure.notifications)
        assertTrue(failure.shouldRetry)
    }

    @Test
    fun oldPinnedCardDoesNotHideNewItemsOnLaterPersonalPages() = runTest {
        val pinned = dynamic("50").copy(modules = dynamic("50").modules.copy(module_tag = DynamicTagModule("置顶")))
        val source = Source().apply {
            pages = mapOf(
                (9L to "") to DynamicPersonalFeedPage(items = listOf(pinned, dynamic("130")), offset = "next", hasMore = true),
                (9L to "next") to DynamicPersonalFeedPage(items = listOf(dynamic("120"), dynamic("100"))),
            )
        }
        val result = MessageNotificationPoller(source).check(dynamicOnly,
            AccountNotificationState(dynamicBaseline = "100", dynamicUpBaselines = mapOf(9L to "100")), 1)
        assertEquals(listOf("dynamic:130", "dynamic:120"), result.notifications.map { it.key })
        assertEquals("130", result.state.dynamicUpBaselines[9L])
    }

    @Test
    fun newRangeBeyondThreePagesIsCompletedBeforeAdvancingBaseline() = runTest {
        val source = Source().apply {
            pages = mapOf(
                (9L to "") to DynamicPersonalFeedPage(items = listOf(dynamic("140")), offset = "p2", hasMore = true),
                (9L to "p2") to DynamicPersonalFeedPage(items = listOf(dynamic("130")), offset = "p3", hasMore = true),
                (9L to "p3") to DynamicPersonalFeedPage(items = listOf(dynamic("120")), offset = "p4", hasMore = true),
                (9L to "p4") to DynamicPersonalFeedPage(items = listOf(dynamic("110"), dynamic("100"))),
            )
        }
        val result = MessageNotificationPoller(source).check(dynamicOnly,
            AccountNotificationState(dynamicBaseline = "100", dynamicUpBaselines = mapOf(9L to "100")), 1)
        assertEquals(4, result.notifications.last().text.filter(Char::isDigit).toInt())
        assertEquals("140", result.state.dynamicUpBaselines[9L])
    }

    @Test
    fun successfullyEmptyPersonalFeedStillNotifiesItsFirstFutureDynamic() = runTest {
        val source = Source()
        val poller = MessageNotificationPoller(source)
        val initial = poller.check(dynamicOnly, AccountNotificationState(), 1)
        assertEquals("", initial.state.dynamicUpBaselines[9L])
        source.pages = mapOf((9L to "") to DynamicPersonalFeedPage(items = listOf(dynamic("100"))))
        val next = poller.check(dynamicOnly, initial.state, 1)
        assertEquals(listOf("dynamic:100"), next.notifications.map { it.key })
    }

    @Test
    fun systemCursorIsOpaqueAndComparedOnlyByEquality() = runTest {
        val source = Source().apply { notices = listOf(SystemNoticeItem(cursor = -3, title = "new"), SystemNoticeItem(cursor = 8)) }
        val previous = AccountNotificationState(initialized = setOf("sysmsg"), seenSystemCursors = listOf(8))
        val result = MessageNotificationPoller(source).check(enabled.copy(notifyDynamicUpdates = false, notifyLiveAlerts = false), previous, 1)
        assertEquals(listOf("sysmsg"), result.notifications.map { it.key })
        assertEquals(listOf(8L, -3L), result.state.seenSystemCursors)
    }

    @Test
    fun failedLiveScanPreservesPriorSessionsAndCappedScanDoesNotInventOfflineTransitions() = runTest {
        val source = Source().apply {
            rooms = mapOf(1 to FollowedLiveData(listOf(live(9, 100)), livingNum = 20))
            liveErrorPage = 2
        }
        val previous = AccountNotificationState(initialized = setOf("live"), liveSessions = mapOf(99L to 200L))
        val failure = MessageNotificationPoller(source).check(liveOnly, previous, 1)
        assertEquals(previous, failure.state)
        assertEquals(emptyList(), failure.notifications)
        assertTrue(failure.shouldRetry)
        source.liveErrorPage = null
        source.rooms = (1..10).associateWith { FollowedLiveData(listOf(live(it.toLong(), 100)), livingNum = 20, pageinfo = PageInfo(total_page = 20)) }
        val capped = MessageNotificationPoller(source).check(liveOnly, previous, 1)
        assertEquals(200L, capped.state.liveSessions[99L])
        assertEquals((1..5).map { "live:$it" }, capped.notifications.map { it.key })
    }

    @Test
    fun cancellationAndAccountChangeAbortRatherThanCommitPartialCategories() = runTest {
        val source = Source().apply { unreadError = CancellationException("stop") }
        assertFailsWith<CancellationException> { MessageNotificationPoller(source).check(enabled, AccountNotificationState(), 1) }
        var checks = 0
        val changed = MessageNotificationPoller(Source()) {
            if (++checks == 2) throw CancellationException("account changed during request")
        }
        assertFailsWith<CancellationException> { changed.check(enabled, AccountNotificationState(), 1) }
    }
    @Test
    fun eachMessageCenterSubcategorySwitchGatesOnlyItsOwnCategory() = runTest {
        // Seed prior snapshots so every enabled category observes new messages.
        val source = Source().apply {
            sessions = listOf(SessionItem(talker_id = 90, unread_count = 1, last_msg = SessionMessage(sender_uid = 90, msg_key = 1)))
            unread = MessageFeedUnreadData(reply = 91, at = 92, like = 93, sysMsg = 1)
            notices = listOf(SystemNoticeItem(cursor = 99, title = "alert"))
        }
        val baseline = AccountNotificationState(
            initialized = setOf("dm", "sysmsg", "dynamic", "live"),
            sessionMsgKeys = mapOf("90_1" to 999L),
            replyUnreadCount = 1, atUnreadCount = 1, likeUnreadCount = 1,
            seenSystemCursors = listOf(1L), dynamicUpBaselines = mapOf(9L to "100"), dynamicBaseline = "100",
        )
        // Master enabled, all five subcategories on: every category reports one new item.
        val allOn = MessageNotificationPoller(source).check(enabled.copy(notifyDynamicUpdates = false, notifyLiveAlerts = false), baseline, 1)
        assertEquals(setOf("dm:90_1", "reply", "at", "like", "sysmsg"), allOn.notifications.map { it.key }.toSet())

        // Turning off exactly one subcategory switch removes only that category's notice.
        val dmOff = MessageNotificationPoller(source).check(enabled.copy(notifyPrivateMessages = false, notifyDynamicUpdates = false, notifyLiveAlerts = false), baseline, 1)
        assertFalse("dm:90_1" in dmOff.notifications.map { it.key })
        assertTrue(listOf("reply", "at", "like", "sysmsg").all { it in dmOff.notifications.map { n -> n.key } })

        val replyOff = MessageNotificationPoller(source).check(enabled.copy(notifyReplies = false, notifyDynamicUpdates = false, notifyLiveAlerts = false), baseline, 1)
        assertFalse("reply" in replyOff.notifications.map { it.key })
        assertTrue(listOf("dm:90_1", "at", "like", "sysmsg").all { it in replyOff.notifications.map { n -> n.key } })

        val atOff = MessageNotificationPoller(source).check(enabled.copy(notifyAtMe = false, notifyDynamicUpdates = false, notifyLiveAlerts = false), baseline, 1)
        assertFalse("at" in atOff.notifications.map { it.key })
        assertTrue(listOf("dm:90_1", "reply", "like", "sysmsg").all { it in atOff.notifications.map { n -> n.key } })

        val likeOff = MessageNotificationPoller(source).check(enabled.copy(notifyLikes = false, notifyDynamicUpdates = false, notifyLiveAlerts = false), baseline, 1)
        assertFalse("like" in likeOff.notifications.map { it.key })
        assertTrue(listOf("dm:90_1", "reply", "at", "sysmsg").all { it in likeOff.notifications.map { n -> n.key } })

        val sysmsgOff = MessageNotificationPoller(source).check(enabled.copy(notifySystemNotices = false, notifyDynamicUpdates = false, notifyLiveAlerts = false), baseline, 1)
        assertFalse("sysmsg" in sysmsgOff.notifications.map { it.key })
        assertTrue(listOf("dm:90_1", "reply", "at", "like").all { it in sysmsgOff.notifications.map { n -> n.key } })

        // All five off: no message-center notice at all.
        val allOff = MessageNotificationPoller(source).check(
            enabled.copy(notifyPrivateMessages = false, notifyReplies = false, notifyAtMe = false, notifyLikes = false, notifySystemNotices = false, notifyDynamicUpdates = false, notifyLiveAlerts = false),
            baseline, 1,
        )
        assertEquals(emptyList(), allOff.notifications)
    }

    @Test
    fun zeroUnreadCountsClearOnlyReadCategoriesAndDoNotClearPartiallyReadCategories() = runTest {
        val source = Source().apply { unread = MessageFeedUnreadData(at = 2) }
        val previous = AccountNotificationState(replyUnreadCount = 5, atUnreadCount = 4, likeUnreadCount = 3)
        val result = MessageNotificationPoller(source).check(
            interactionsOnly.copy(notifySystemNotices = true), previous, 1,
            setOf("reply", "at", "like", "sysmsg", "interactions:summary"),
        )
        assertEquals(setOf("reply", "like", "sysmsg"), result.cancelNotificationKeys)
        assertEquals(2, result.state.atUnreadCount)
        assertEquals(emptyList(), result.notifications)

        source.unreadError = IOException("unread unavailable")
        val failed = MessageNotificationPoller(source).check(
            interactionsOnly, previous, 1, setOf("reply", "at", "like"),
        )
        assertEquals(emptySet(), failed.cancelNotificationKeys)
        assertEquals(previous.replyUnreadCount, failed.state.replyUnreadCount)
    }

    @Test
    fun alreadyReadSystemNoticesDoNotReappearEvenWhenTheirCursorIsNew() = runTest {
        val source = Source().apply {
            unread = MessageFeedUnreadData()
            notices = listOf(SystemNoticeItem(cursor = 10, title = "read elsewhere"))
        }
        val result = MessageNotificationPoller(source).check(
            interactionsOnly.copy(notifySystemNotices = true),
            AccountNotificationState(initialized = setOf("sysmsg"), seenSystemCursors = listOf(9)), 1,
            setOf("sysmsg"),
        )
        assertEquals(setOf("sysmsg"), result.cancelNotificationKeys)
        assertEquals(emptyList(), result.notifications)
        assertEquals(listOf(9L, 10L), result.state.seenSystemCursors)
    }

    @Test
    fun privateReadSyncQueriesOlderNotifiedSessionsAndPreservesFailedOrUnreadSessions() = runTest {
        val source = Source().apply {
            sessions = listOf(SessionItem(talker_id = 9, unread_count = 0))
            sessionDetails = mapOf((10L to 1) to SessionItem(talker_id = 10, unread_count = 1))
            sessionDetailErrors = setOf(11L to 1)
        }
        val previous = AccountNotificationState(
            initialized = setOf("dm"), pendingPrivateSessionKeys = setOf("9_1", "10_1", "11_1"),
        )
        val privateOnly = interactionsOnly.copy(
            notifyPrivateMessages = true, notifyReplies = false, notifyAtMe = false, notifyLikes = false,
        )
        val active = setOf("dm:9_1", "dm:10_1", "dm:summary")
        val first = MessageNotificationPoller(source).check(privateOnly, previous, 1, active)
        assertEquals(setOf("dm:9_1"), first.cancelNotificationKeys)
        assertEquals(setOf("10_1", "11_1"), first.state.pendingPrivateSessionKeys)
        assertTrue(first.shouldRetry)

        source.sessionDetailErrors = emptySet()
        source.sessionDetails = mapOf(
            (10L to 1) to SessionItem(talker_id = 10, unread_count = 0),
            (11L to 1) to SessionItem(talker_id = 11, unread_count = 0),
        )
        val read = MessageNotificationPoller(source).check(
            privateOnly, first.state, 1, setOf("dm:10_1", "dm:summary"),
        )
        assertEquals(setOf("dm:10_1", "dm:11_1"), read.cancelNotificationKeys)
        assertEquals(emptySet(), read.state.pendingPrivateSessionKeys)
    }

    @Test
    fun pendingDynamicsPollReadProgressWithoutNewPublicationsAndRetainOtherUpsOnFailure() = runTest {
        val source = Source().apply {
            updates = 0
            followingPages = mapOf(1 to FollowingsData(list = listOf(FollowingUser(mid = 9), FollowingUser(mid = 10)), total = 2))
            pages = mapOf((9L to "") to DynamicPersonalFeedPage(readOffset = "110"))
            feedErrorHost = 10L
        }
        val previous = AccountNotificationState(
            dynamicBaseline = "130", dynamicUpBaselines = mapOf(9L to "120", 10L to "130"),
            pendingDynamicIds = mapOf("110" to 9L, "120" to 9L, "130" to 10L),
        )
        val first = MessageNotificationPoller(source).check(
            dynamicOnly, previous, 1, setOf("dynamic:110", "dynamic:120", "dynamic:130", "dynamic:summary"),
        )
        assertEquals(setOf("dynamic:110"), first.cancelNotificationKeys)
        assertEquals(mapOf("120" to 9L, "130" to 10L), first.state.pendingDynamicIds)
        assertEquals(previous.dynamicUpBaselines, first.state.dynamicUpBaselines)
        assertTrue(first.shouldRetry)

        source.feedErrorHost = null
        source.pages = mapOf(
            (9L to "") to DynamicPersonalFeedPage(readOffset = "120"),
            (10L to "") to DynamicPersonalFeedPage(readOffset = "130"),
        )
        val read = MessageNotificationPoller(source).check(
            dynamicOnly, first.state, 1, setOf("dynamic:120", "dynamic:130", "dynamic:summary"),
        )
        assertEquals(setOf("dynamic:120", "dynamic:130"), read.cancelNotificationKeys)
        assertEquals(emptyMap(), read.state.pendingDynamicIds)
        assertEquals(emptyList(), read.notifications)
    }

    @Test
    fun dynamicsReadBeforePollingAreNotNotifiedButThePublicationBaselineAdvances() = runTest {
        val source = Source().apply {
            pages = mapOf((9L to "") to DynamicPersonalFeedPage(
                items = listOf(dynamic("120"), dynamic("110"), dynamic("100")), readOffset = "110",
            ))
        }
        val previous = AccountNotificationState(
            dynamicBaseline = "100", dynamicUpBaselines = mapOf(9L to "100"),
            pendingDynamicIds = mapOf("105" to 9L),
        )
        val result = MessageNotificationPoller(source).check(dynamicOnly, previous, 1, setOf("dynamic:105"))
        assertEquals(setOf("dynamic:105"), result.cancelNotificationKeys)
        assertEquals(listOf("dynamic:120"), result.notifications.map { it.key })
        assertEquals(mapOf("120" to 9L), result.state.pendingDynamicIds)
        assertEquals("120", result.state.dynamicBaseline)
    }

    @Test
    fun overflowDynamicsRemainTrackedUntilTheirOwnReadProgressCoversThem() = runTest {
        val source = Source().apply {
            pages = mapOf((9L to "") to DynamicPersonalFeedPage(items = (104 downTo 100).map { dynamic("$it") }))
        }
        val previous = AccountNotificationState(dynamicBaseline = "100", dynamicUpBaselines = mapOf(9L to "100"))
        val fresh = MessageNotificationPoller(source).check(dynamicOnly, previous, 1)
        assertEquals(setOf("101", "102", "103", "104"), fresh.state.pendingDynamicIds.keys)
        source.updates = 0
        source.pages = mapOf((9L to "") to DynamicPersonalFeedPage(readOffset = "102"))
        val read = MessageNotificationPoller(source).check(
            dynamicOnly, fresh.state, 1, setOf("dynamic:104", "dynamic:103", "dynamic:102", "dynamic:summary"),
        )
        assertEquals(setOf("dynamic:101", "dynamic:102"), read.cancelNotificationKeys)
        assertEquals(setOf("103", "104"), read.state.pendingDynamicIds.keys)
    }

    @Test
    fun failedDynamicRangeDoesNotCommitReadCancellationsOrDropPendingNotices() = runTest {
        val source = Source().apply {
            pages = mapOf((9L to "") to DynamicPersonalFeedPage(
                items = listOf(dynamic("120")), offset = "next", hasMore = true, readOffset = "110",
            ))
            feedErrorOffset = "next"
        }
        val previous = AccountNotificationState(
            initialized = setOf("dynamic"), dynamicBaseline = "100", dynamicUpBaselines = mapOf(9L to "100"),
            pendingDynamicIds = mapOf("105" to 9L),
        )
        val result = MessageNotificationPoller(source).check(dynamicOnly, previous, 1, setOf("dynamic:105"))
        assertEquals(previous, result.state)
        assertEquals(emptySet(), result.cancelNotificationKeys)
        assertEquals(emptyList(), result.notifications)
        assertTrue(result.shouldRetry)
    }

    @Test
    fun invalidReadProgressDoesNotClearDynamicsAndUnsignedIdsUseExactDecimalOrdering() {
        assertFalse(isDynamicNotificationRead("100", ""))
        assertFalse(isDynamicNotificationRead("100", "opaque-offset"))
        assertFalse(isDynamicNotificationRead("100", "-1"))
        assertFalse(isDynamicNotificationRead("18446744073709551615", "18446744073709551614"))
        assertTrue(isDynamicNotificationRead("18446744073709551615", "18446744073709551615"))
        assertTrue(isDynamicNotificationRead("100", "000100"))
    }

    private fun dynamic(id: String, mid: Long = 9L) = DynamicItem(
        id_str = id,
        type = "DYNAMIC_TYPE_AV",
        modules = DynamicModules(
            module_author = DynamicAuthorModule(mid = mid, name = "UP", following = true),
            module_dynamic = DynamicContentModule(major = DynamicMajor(archive = ArchiveMajor(bvid = "BV1xx411c7mD"))),
        ),
    )

    private fun live(uid: Long, time: Long) = FollowedLiveRoom(roomid = uid, uid = uid, liveStatus = 1, liveTime = time)

    private class Source : MessageNotificationSource {
        var sessions = emptyList<SessionItem>()
        var sessionDetails = emptyMap<Pair<Long, Int>, SessionItem>()
        var sessionDetailErrors = emptySet<Pair<Long, Int>>()
        var unread = MessageFeedUnreadData(sysMsg = 1)
        var notices = emptyList<SystemNoticeItem>()
        var unreadError: Exception? = null
        var updates = 1
        var followingPages = mapOf(1 to FollowingsData(list = listOf(FollowingUser(mid = 9, uname = "UP")), total = 1))
        var pages = mapOf((9L to "") to DynamicPersonalFeedPage())
        var rooms = emptyMap<Int, FollowedLiveData>()
        var feedErrorOffset: String? = null
        var feedErrorHost: Long? = null
        var followingErrorPage: Int? = null
        var liveErrorPage: Int? = null
        val feedRequests = mutableListOf<Triple<Long, String, Int>>()
        override suspend fun sessions() = sessions
        override suspend fun sessionDetail(talkerId: Long, sessionType: Int): SessionItem {
            if ((talkerId to sessionType) in sessionDetailErrors) throw IOException("session detail interrupted")
            return sessionDetails.getValue(talkerId to sessionType)
        }
        override suspend fun feedUnread(): MessageFeedUnreadData { unreadError?.let { throw it }; return unread }
        override suspend fun systemNotices() = notices
        override suspend fun dynamicUpdateCount(baseline: String) = updates
        override suspend fun followings(selfMid: Long, page: Int): FollowingsData {
            if (page == followingErrorPage) throw IOException("following list interrupted")
            return followingPages[page] ?: FollowingsData()
        }
        override suspend fun personalDynamicFeed(hostUid: Long, offset: String, page: Int): DynamicPersonalFeedPage {
            feedRequests += Triple(hostUid, offset, page)
            if (offset == feedErrorOffset || hostUid == feedErrorHost) throw IOException("network interrupted")
            return pages.getValue(hostUid to offset)
        }
        override suspend fun followedLive(page: Int): FollowedLiveData {
            if (page == liveErrorPage) throw IOException("network interrupted")
            return rooms[page] ?: FollowedLiveData()
        }
    }
}
