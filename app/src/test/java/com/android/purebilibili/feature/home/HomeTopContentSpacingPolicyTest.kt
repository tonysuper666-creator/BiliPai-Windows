package com.android.purebilibili.feature.home

import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.theme.AppUiStyle
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeTopContentSpacingPolicyTest {
    @Test
    fun bottomBarSearchRemovesTheHiddenTopSearchReservation() {
        assertEquals(
            HomeTopSearchRowMetrics(0.dp, 0.dp),
            resolveHomeTopSearchRowMetrics(
                configuredHeight = 48.dp,
                configuredTabsSpacing = 6.dp,
                bottomBarSearchEnabled = true,
                hideTopTabs = false,
            ),
        )
    }

    @Test
    fun topSearchReservationReturnsWhenTopTabsAreHidden() {
        assertEquals(
            HomeTopSearchRowMetrics(48.dp, 6.dp),
            resolveHomeTopSearchRowMetrics(
                configuredHeight = 48.dp,
                configuredTabsSpacing = 6.dp,
                bottomBarSearchEnabled = true,
                hideTopTabs = true,
            ),
        )
    }

    @Test
    fun topSearchReservationRemainsWhenBottomBarSearchIsDisabled() {
        assertEquals(
            HomeTopSearchRowMetrics(48.dp, 6.dp),
            resolveHomeTopSearchRowMetrics(
                configuredHeight = 48.dp,
                configuredTabsSpacing = 6.dp,
                bottomBarSearchEnabled = false,
                hideTopTabs = false,
            ),
        )
    }


    @Test
    fun `md3 non glass tightens the reserved tabs to content padding`() {
        assertEquals(
            12.dp,
            resolveHomeTabsToContentTighteningDp(AppUiStyle.MATERIAL3, liquidGlassEnabled = false),
        )
    }

    @Test
    fun `glass dock and miuix keep the full reservation`() {
        assertEquals(0.dp, resolveHomeTabsToContentTighteningDp(AppUiStyle.MATERIAL3, liquidGlassEnabled = true))
        assertEquals(0.dp, resolveHomeTabsToContentTighteningDp(AppUiStyle.MIUIX, liquidGlassEnabled = false))
        assertEquals(0.dp, resolveHomeTabsToContentTighteningDp(AppUiStyle.MIUIX, liquidGlassEnabled = true))
    }
}
