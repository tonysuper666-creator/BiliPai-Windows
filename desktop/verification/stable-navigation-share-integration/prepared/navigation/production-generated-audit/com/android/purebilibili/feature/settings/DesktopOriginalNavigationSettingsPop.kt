// Source: app/src/main/java/com/android/purebilibili/feature/settings/SettingsNavHierarchyPolicy.kt
// Original LF SHA256: ced1fdca02adbf8229bf57bab696704babf4a5ac88ca3a2c1732adc305f572bd
package com.android.purebilibili.feature.settings
import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.android.purebilibili.navigation3.BiliPaiNavRouteTransition

internal fun resolveSettingsNavPopTransition(
    fromRoute: String?,
    toRoute: String?,
    activeMainHostRoute: String? = null,
): BiliPaiNavRouteTransition? {
    if (!isSettingsSubtreeRoute(fromRoute)) return null
    val normalizedTo = toRoute?.substringBefore("?")?.takeIf { it.isNotBlank() } ?: return null
    val normalizedActive = activeMainHostRoute?.substringBefore("?")?.takeIf { it.isNotBlank() }
    // 只要底栏有活跃 tab，pop 回 MainHost 时就以该 tab 作为设置树的“父级”，
    // 使首页/动态/我的等任意入口打开设置根页后都能命中设置树 iOS pop 动画。
    val effectiveParentRoute = if (
        normalizedTo == BiliPaiNavKey.MainHost.routeBase &&
        normalizedActive != null
    ) {
        normalizedActive
    } else {
        normalizedTo
    }
    if (isSettingsNavHierarchyTransition(
            parentRoute = effectiveParentRoute,
            childRoute = fromRoute,
        )
    ) {
        return BiliPaiNavRouteTransition.SETTINGS_IOS_PUSH_POP
    }
    // 设置子树任意页直接回到 MainHost，且底栏仍在 Settings：统一走设置 iOS pop，
    // 覆盖非严格父子边（如搜索直达 animation 后一键返回）。
    if (normalizedTo == BiliPaiNavKey.MainHost.routeBase && isSettingsSubtreeRoute(normalizedActive)) {
        return BiliPaiNavRouteTransition.SETTINGS_IOS_PUSH_POP
    }
    return null
}

internal fun resolveSettingsNavPopTransition(
    fromKey: BiliPaiNavKey?,
    toKey: BiliPaiNavKey?,
    activeMainHostRoute: String? = null,
): BiliPaiNavRouteTransition? {
    if (fromKey == null || toKey == null) return null
    return resolveSettingsNavPopTransition(
        fromRoute = fromKey.routeBase,
        toRoute = toKey.routeBase,
        activeMainHostRoute = activeMainHostRoute,
    )
}

internal fun isSettingsNavPopTransition(
    fromKey: BiliPaiNavKey?,
    toKey: BiliPaiNavKey?,
    activeMainHostRoute: String? = null,
): Boolean {
    return resolveSettingsNavPopTransition(
        fromKey = fromKey,
        toKey = toKey,
        activeMainHostRoute = activeMainHostRoute,
    ) != null
}

