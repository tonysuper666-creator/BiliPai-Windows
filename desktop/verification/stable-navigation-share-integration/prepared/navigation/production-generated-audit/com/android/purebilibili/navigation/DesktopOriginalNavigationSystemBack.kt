// Source: app/src/main/java/com/android/purebilibili/navigation/AppTopLevelNavigationPolicy.kt
// Original LF SHA256: cbb6d92e8bcba3dfdf5df6dff06b8e61ccccaea9973a2d5e04fd0b5cd54917e6
package com.android.purebilibili.navigation
import com.android.purebilibili.feature.home.components.BottomNavItem

internal enum class AppSystemBackAction {
    RETURN_TO_HOME_TAB,
    NAVIGATE_UP,
    FINISH_ACTIVITY
}

internal fun resolveAppSystemBackAction(
    isAtMainHostRoot: Boolean,
    currentBottomItem: BottomNavItem,
    homeItem: BottomNavItem = BottomNavItem.HOME
): AppSystemBackAction {
    if (!isAtMainHostRoot) {
        return AppSystemBackAction.NAVIGATE_UP
    }
    if (currentBottomItem != homeItem) {
        return AppSystemBackAction.RETURN_TO_HOME_TAB
    }
    return AppSystemBackAction.FINISH_ACTIVITY
}

internal fun shouldInterceptSystemBackForAppAction(
    action: AppSystemBackAction
): Boolean {
    return action == AppSystemBackAction.RETURN_TO_HOME_TAB
}

