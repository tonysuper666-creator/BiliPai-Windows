package com.android.purebilibili.feature.video.screen

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class DesktopVideoNavigationOptionsTest {
    @Test fun optionsCopyTheBaseAndPreserveExplicitFalseVerticalIntent() {
        val base = mutableMapOf<String, Any>("owner" to 17L, VIDEO_NAV_TARGET_CID_KEY to 3L)
        val options = checkNotNull(buildVideoNavigationOptions(base, 9L, " cover.png ", true, false))
        assertEquals(3L, base[VIDEO_NAV_TARGET_CID_KEY])
        assertEquals(mapOf("owner" to 17L, VIDEO_NAV_TARGET_CID_KEY to 9L,
            VIDEO_NAV_COVER_URL_KEY to "cover.png", VIDEO_NAV_START_FULLSCREEN_KEY to true,
            VIDEO_NAV_IS_VERTICAL_KEY to false), options)
        assertTrue(resolveVideoNavigationStartInFullscreen(options))
        assertEquals(false, resolveVideoNavigationIsVertical(options))
        base["owner"] = 18L
        assertEquals(17L, options["owner"])
    }

    @Test fun absentIntentStaysAbsentAndEmptyOverridesKeepTheBase() {
        assertNull(buildVideoNavigationOptions())
        assertFalse(resolveVideoNavigationStartInFullscreen(null))
        assertNull(resolveVideoNavigationIsVertical(null))
        val base = mapOf<String, Any>(VIDEO_NAV_START_FULLSCREEN_KEY to true)
        assertSame(base, buildVideoNavigationOptions(base, -1L, "  "))
        assertNull(resolveVideoNavigationIsVertical(base))
        assertEquals(true, resolveVideoNavigationIsVertical(buildVideoNavigationOptions(isVertical = true)))
    }
}
