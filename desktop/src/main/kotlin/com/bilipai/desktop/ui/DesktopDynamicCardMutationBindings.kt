package com.bilipai.desktop.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.android.purebilibili.feature.dynamic.components.DynamicCardNavigationActions

/** Root owns the actual timeline/UP/cache models. These are required confirmed
 * action bindings, never a second cache or an optimistic parallel state model.
 */
internal class DesktopDynamicCardMutationBindings(
    val markNotInterested: suspend (String) -> Unit,
    val likeConfirmed: (String, Boolean) -> Unit,
    val repostConfirmed: (String) -> Unit,
    val removed: (String) -> Unit,
    val unfoldRelated: (String) -> Unit,
)

internal val LocalDesktopDynamicCardMutations = staticCompositionLocalOf<DesktopDynamicCardMutationBindings?> { null }

/** Root supplies native music/episode/collection/course routes in the original
 * navigation schema. Community adds its local detail/back route underneath it.
 */
internal val LocalDesktopDynamicCardNavigation = staticCompositionLocalOf<DynamicCardNavigationActions?> { null }
