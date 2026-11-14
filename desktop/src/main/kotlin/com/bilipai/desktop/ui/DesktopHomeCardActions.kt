package com.bilipai.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import com.android.purebilibili.data.model.response.VideoItem
import com.bilipai.desktop.data.VideoCard
import com.bilipai.desktop.data.discoveryVideoCard

/** One ordinary AV video of a Home card, fixed when the menu row runs. aid is the original watch-later
 * resolution (aid, else id); cid stays the item's own cid (0 when the feed did not send one). */
internal class DesktopHomeCardTarget(val bvid: String, val aid: Long, val cid: Long, val card: VideoCard)

/** The target of an ordinary AV card, or null for every special card (dynamic detail, collection,
 * episode, live or any id that is not a video link): those keep the vendored menu and get no action. */
internal fun desktopHomeCardTarget(video: VideoItem): DesktopHomeCardTarget? {
    if (desktopHomeVideoLink(video.bvid) == null) return null
    if (video.isCollectionResource || video.episodeId > 0L) return null
    val aid = video.aid.takeIf { it > 0L } ?: video.id
    return DesktopHomeCardTarget(video.bvid.trim(), aid, video.cid, discoveryVideoCard(video))
}

/** The Home actions the real mounted Root offers to its cards. Each call is one user action. */
internal interface DesktopHomeCardActions {
    /** Opens the original cloud favourite folder drawer for target.aid (aid > 0). */
    fun favorite(target: DesktopHomeCardTarget)
    /** Plays target in the existing player mini window with the semantics of an ordinary click. card is the
     * identity of the calling card in the composition. */
    fun mini(target: DesktopHomeCardTarget, card: Any)
    /** The card left the composition: a mini wait it started is withdrawn (nothing is paused or stopped). */
    fun cardLeft(card: Any)
}

/** Null everywhere except inside the active real Home page of the Root, so every other card keeps its menu.
 * A dynamic local: turning the port on or off recomposes only the cards that read it, never remounts Home. */
internal val LocalDesktopHomeCardActions = compositionLocalOf<DesktopHomeCardActions?> { null }

/** The 收藏 row action, or null when there is no port, no ordinary AV target or no aid. */
internal fun desktopHomeCardFavorite(actions: DesktopHomeCardActions?, video: VideoItem): (() -> Unit)? {
    if (actions == null) return null
    val target = desktopHomeCardTarget(video)?.takeIf { it.aid > 0L } ?: return null
    return { actions.favorite(target) }
}

/** The 小窗播放 row action, or null when there is no port or no ordinary AV target. The card's own
 * composition owns its pending mini wait: removing the card withdraws it. */
@Composable
internal fun desktopHomeCardMini(actions: DesktopHomeCardActions?, video: VideoItem): (() -> Unit)? {
    val card = remember(video) { Any() }
    DisposableEffect(actions, card) { onDispose { actions?.cardLeft(card) } }
    if (actions == null) return null
    val target = desktopHomeCardTarget(video) ?: return null
    return { actions.mini(target, card) }
}
