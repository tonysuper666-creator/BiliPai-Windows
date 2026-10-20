package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.BangumiFilter
import com.android.purebilibili.feature.bangumi.BangumiDetailState
import com.android.purebilibili.feature.bangumi.BangumiDisplayMode
import com.android.purebilibili.feature.bangumi.BangumiFollowSession
import com.android.purebilibili.feature.bangumi.BangumiHubUiState
import com.android.purebilibili.feature.bangumi.BangumiIndexChoice
import com.android.purebilibili.feature.bangumi.BangumiIndexFilterGroupUi
import com.android.purebilibili.feature.bangumi.BangumiListState
import com.android.purebilibili.feature.bangumi.BangumiSearchState
import com.android.purebilibili.feature.bangumi.BangumiUiState
import com.android.purebilibili.feature.bangumi.MyFollowState
import com.android.purebilibili.feature.bangumi.MyFollowStats
import com.android.purebilibili.feature.bangumi.TimelineState
import com.android.purebilibili.feature.bangumi.defaultMyFollowTypeForSeasonType
import com.android.purebilibili.feature.bangumi.resolveBangumiIndexChoiceDisplayLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

/** The v0.4.6 Bangumi hub pages: the single view model state, the year range label of the index filter and the Windows
 * follow-session identity of the independent pages. Pure values: no window, native player, GPU or network. */
class DesktopBangumiHubPagesV046Test {
    private fun yearGroup(field: String = "year", label: String = "年份") = BangumiIndexFilterGroupUi(
        field = field,
        label = label,
        choices = emptyList(),
    )

    private fun displayLabel(text: String, glass: Boolean, group: BangumiIndexFilterGroupUi = yearGroup()): String =
        resolveBangumiIndexChoiceDisplayLabel(group, BangumiIndexChoice(label = text, keyword = text), glass)

    @Test fun signedInPagesShareTheRootAccountEpochIdentityWithThePgcPresenter() {
        val session = desktopOriginalBangumiPagesFollowSession(signedIn = true, accountEpoch = 4L)
        assertEquals(desktopOriginalBangumiFollowSession(4L), session)
        assertEquals(null, session.mid)
        assertEquals("account-epoch:4", session.session)
        assertNotEquals(session, desktopOriginalBangumiPagesFollowSession(signedIn = true, accountEpoch = 5L))
    }

    @Test fun signedOutPagesUseTheConstantNoTrackingSessionSoTheBackgroundVerificationStandsDown() {
        val first = desktopOriginalBangumiPagesFollowSession(signedIn = false, accountEpoch = 4L)
        assertEquals(BangumiFollowSession(null, null), first)
        assertEquals(first, desktopOriginalBangumiPagesFollowSession(signedIn = false, accountEpoch = 9L))
        assertEquals(true, first.session.isNullOrEmpty())
        assertNotEquals(first, desktopOriginalBangumiPagesFollowSession(signedIn = true, accountEpoch = 4L))
    }

    @Test fun yearRangeLabelsKeepTheCenturyAndDropItFromTheSecondYearOnlyForGlass() {
        assertEquals("2010–19", displayLabel("2010-2019", glass = true))
        assertEquals("2000–09年", displayLabel("2000–2009年", glass = true))
        assertEquals("2020–25", displayLabel("2020至2025", glass = true))
        assertEquals("2010–19", displayLabel("2019 ~ 2010", glass = true))
    }

    @Test fun yearRangeLabelsStayWhollyUnchangedWhenTheyCannotBeShortened() {
        assertEquals("2010-2019", displayLabel("2010-2019", glass = false))
        assertEquals("1999-2005", displayLabel("1999-2005", glass = true))
        assertEquals("2024", displayLabel("2024", glass = true))
        assertEquals("全部", displayLabel("全部", glass = true))
        assertEquals("2010-2019", displayLabel("2010-2019", glass = true, group = yearGroup(field = "style_id", label = "风格")))
    }

    @Test fun aGroupWhoseLabelNamesAYearIsTreatedAsTheYearGroup() {
        val group = yearGroup(field = "release", label = "上映年份")
        assertEquals("1990–99", displayLabel("1990-1999", glass = true, group = group))
    }

    @Test fun theSingleUiStateStartsWhereTheSeparateFlowsStarted() {
        val state = BangumiUiState()
        assertEquals(BangumiDisplayMode.LIST, state.displayMode)
        assertEquals(1, state.selectedType)
        assertSame(BangumiListState.Loading, state.listState)
        assertSame(TimelineState.Loading, state.timelineState)
        assertSame(BangumiDetailState.Loading, state.detailState)
        assertSame(BangumiSearchState.Idle, state.searchState)
        assertSame(MyFollowState.Loading, state.myFollowState)
        assertEquals(defaultMyFollowTypeForSeasonType(1), state.myFollowType)
        assertEquals("", state.searchKeyword)
    }

    @Test fun theSingleUiStateKeepsTheElevenLegacyFieldsInTheirDeclaredOrder() {
        val stats = MyFollowStats(bangumiTotal = 3, cinemaTotal = 5)
        val filter = BangumiFilter(year = "2020")
        // Positional on purpose: a field that is inserted, dropped or reordered in BangumiUiState either stops
        // type-checking here or lands in the wrong property below.
        val state = BangumiUiState(
            BangumiDisplayMode.MY_FOLLOW,
            4,
            BangumiListState.Loading,
            TimelineState.Loading,
            BangumiDetailState.Loading,
            BangumiSearchState.Loading,
            MyFollowState.Loading,
            2,
            stats,
            filter,
            "x",
        )
        assertEquals(BangumiDisplayMode.MY_FOLLOW, state.displayMode)
        assertEquals(4, state.selectedType)
        assertSame(BangumiListState.Loading, state.listState)
        assertSame(TimelineState.Loading, state.timelineState)
        assertSame(BangumiDetailState.Loading, state.detailState)
        assertSame(BangumiSearchState.Loading, state.searchState)
        assertSame(MyFollowState.Loading, state.myFollowState)
        assertEquals(2, state.myFollowType)
        assertSame(stats, state.myFollowStats)
        assertSame(filter, state.filter)
        assertEquals("x", state.searchKeyword)
    }

    @Test fun theHubStateCarriesAnAccountGenerationThatNeverAdvancesInsideAWindowsOwner() {
        assertEquals(0L, BangumiHubUiState().accountGeneration)
        assertEquals(0L, BangumiHubUiState().copy(isLoggedIn = true).accountGeneration)
    }
}
