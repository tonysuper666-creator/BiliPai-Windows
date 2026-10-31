package com.android.purebilibili.navigation

/**
 * The one value upstream's navigation/PagerSelectionMotion.kt reads from navigation/AppTopLevelNavigationPolicy.kt.
 * That policy still compiles from its pre-merge subset (the root navigation producers), which does not carry this constant.
 * When the policy compiles from the merged root this declaration fails to compile (redeclaration) and goes away; until
 * then DesktopPagerSelectionMotionConstantsTest pins it to upstream's value.
 */
internal const val BOTTOM_PAGER_TARGET_PREWARM_FRAMES = 1
