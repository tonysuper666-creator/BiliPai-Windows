package com.bilipai.desktop.ui

import com.android.purebilibili.feature.home.HomeVideoClickRequest
import com.android.purebilibili.navigation.HomeNavigationTarget
import com.android.purebilibili.navigation.HomeVideoNavigationIntent
import com.android.purebilibili.navigation.resolveHomeNavigationTarget
import com.android.purebilibili.navigation.resolveHomeVideoNavigationIntent

/** The exact original Home target/intent resolvers own routing precedence. Root's real video
 * entry must retain all intent fields (CID, vertical geometry, click source and source route).
 * Reducing this to VideoCard alone loses the original return-transition/Story context.
 * All routes are required; no empty/default navigation branch is introduced. */
internal fun dispatchDesktopOriginalHomeVideo(
    request: HomeVideoClickRequest,
    commitIfCurrent: ((() -> Unit) -> Boolean),
    onVideo: (HomeVideoNavigationIntent, String) -> Unit,
    onVideoRoute: (String, String) -> Unit,
    onDynamic: (String) -> Unit,
): Boolean {
    val target = resolveHomeNavigationTarget(request) ?: return false
    return commitIfCurrent {
        when (target) {
            is HomeNavigationTarget.Video -> {
                val intent = resolveHomeVideoNavigationIntent(request)
                if (intent != null) onVideo(intent, intent.sourceRoute ?: "home")
                else onVideoRoute(target.route, request.sourceRoute ?: "home")
            }
            is HomeNavigationTarget.DynamicDetail -> onDynamic(target.dynamicId)
        }
    }
}
