package com.android.purebilibili.feature.bangumi

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.android.purebilibili.data.model.response.BangumiEpisode
import java.util.UUID

private val LocalBangumiCardSelected = staticCompositionLocalOf<((String) -> Unit)?> { null }

internal data class BangumiCardTransition(
    val modifier: Modifier,
    val onClick: () -> Unit,
    val enabled: Boolean,
)

/** Saved per rendered card, rather than per season: duplicate posters must not share a target. */
@Composable
internal fun rememberBangumiCardTransition(onClick: () -> Unit): BangumiCardTransition {
    val key = rememberSaveable { "bangumi-card:${UUID.randomUUID()}" }
    val onSelected = LocalBangumiCardSelected.current
    return BangumiCardTransition(
        modifier = if (onSelected != null) bangumiCardSharedBounds(Modifier, key) else Modifier,
        enabled = onSelected != null,
        onClick = {
            onSelected?.invoke(key)
            onClick()
        },
    )
}

@Composable
private fun bangumiCardSharedBounds(modifier: Modifier, key: String): Modifier =
    com.android.purebilibili.core.ui.transition.sharedCardContainerBounds(modifier, key)

private data class OpenBangumiCard(val seasonId: Long, val epId: Long, val sourceKey: String)

private val OpenBangumiCardSaver = Saver<OpenBangumiCard?, List<Any>>(
    save = { card -> card?.let { listOf(it.seasonId, it.epId, it.sourceKey) } ?: emptyList() },
    restore = { values ->
        if (values.isEmpty()) null else OpenBangumiCard(
            seasonId = values[0] as Long,
            epId = values[1] as Long,
            sourceKey = values[2] as String,
        )
    },
)

/** The same official container transform and timing used by subscription articles. */
@Composable
internal fun BangumiCardTransitionHost(
    onBangumiClick: (Long) -> Unit,
    onBangumiEpisodeClick: (Long, Long) -> Unit,
    onPlayEpisode: ((Long, BangumiEpisode) -> Unit)?,
    onReviewsClick: (Long, String) -> Unit,
    onUserClick: (Long) -> Unit,
    content: @Composable ((Long) -> Unit, (Long, Long) -> Unit, Boolean) -> Unit,
) {
    if (onPlayEpisode == null) {
        content(onBangumiClick, onBangumiEpisodeClick, true)
        return
    }
    var sourceKey by rememberSaveable { mutableStateOf<String?>(null) }
    com.android.purebilibili.core.ui.transition.SharedCardContainerHost<OpenBangumiCard>(
        saver = OpenBangumiCardSaver,
        keyOf = { it.sourceKey },
        content = { open, visible ->
            CompositionLocalProvider(LocalBangumiCardSelected provides { sourceKey = it }) {
                content(
                    { seasonId -> sourceKey?.let { open(OpenBangumiCard(seasonId, 0L, it)) } ?: onBangumiClick(seasonId) },
                    { seasonId, epId -> sourceKey?.let { open(OpenBangumiCard(seasonId, epId, it)) } ?: onBangumiEpisodeClick(seasonId, epId) },
                    visible,
                )
            }
        },
        destination = { card, close, replace, ready, modifier ->
            BangumiDetailScreen(
                seasonId = card.seasonId,
                epId = card.epId,
                onBack = close,
                onEpisodeClick = onPlayEpisode,
                contentTransitionReady = ready,
                onSeasonClick = { replace(card.copy(seasonId = it, epId = 0L)) },
                onReviewsClick = onReviewsClick,
                onUserClick = onUserClick,
                modifier = modifier,
            )
        },
    )
}
