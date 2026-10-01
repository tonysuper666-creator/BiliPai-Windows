package com.android.purebilibili.feature.video.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.components.AppSurface
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.data.model.response.ReplyVoteCard
import com.android.purebilibili.feature.dynamic.components.DynamicVoteDialog
import kotlin.math.roundToInt

@Composable
fun VideoCommentVoteCard(
    card: ReplyVoteCard,
    modifier: Modifier = Modifier,
) {
    if (card.voteId <= 0L || card.options.isEmpty()) return

    var displayedCard by remember(card) { mutableStateOf(card) }
    var selectedOption by remember(card.voteId) { mutableIntStateOf(-1) }
    var showVoteDialog by remember(card.voteId) { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val totalVotes = displayedCard.options.sumOf { it.count }.coerceAtLeast(0L)

    AppSurface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = colors.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppText(
                text = displayedCard.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            AppText(
                text = if (displayedCard.myVoteOption != null) {
                    "${displayedCard.count} 人参与 · 已投票"
                } else {
                    "${displayedCard.count} 人参与 · 选择选项投票"
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                displayedCard.options.forEachIndexed { position, option ->
                    val selected = displayedCard.myVoteOption == option.idx
                    val tint = when (position % 3) {
                        0 -> colors.primaryContainer
                        1 -> colors.secondaryContainer
                        else -> colors.tertiaryContainer
                    }
                    AppSurface(
                        onClick = {
                            selectedOption = option.idx.toInt()
                            showVoteDialog = true
                        },
                        modifier = Modifier
                            .widthIn(min = 144.dp)
                            .heightIn(min = 72.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = tint,
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            AppText(
                                text = if (selected) "✓ ${option.desc}" else option.desc,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            AppText(
                                text = if (totalVotes > 0L) {
                                    "${(option.count * 100.0 / totalVotes).roundToInt()}%"
                                } else {
                                    "0%"
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showVoteDialog) {
        DynamicVoteDialog(
            voteId = card.voteId,
            dynamicId = "",
            onDismiss = { showVoteDialog = false },
            initialOptionIndex = selectedOption,
            onVoteSuccess = { result ->
                displayedCard = displayedCard.copy(
                    count = result.join_num.toLong(),
                    options = displayedCard.options.map { option ->
                        option.copy(count = result.options.firstOrNull { it.opt_idx.toLong() == option.idx }
                            ?.cnt?.toLong() ?: option.count)
                    },
                    myVoteOption = result.my_votes.firstOrNull()?.toLong(),
                )
            },
        )
    }
}
