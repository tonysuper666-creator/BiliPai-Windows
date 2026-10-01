// GENERATED from app/src/main/java/com/android/purebilibili/feature/video/ui/section/VideoInfoSection.kt; do not edit.
// LF-normalized SHA-256: b3a84152a38284bea0336b7df37c674cac78b4bab46d29a6e216a6a1f8fbc8ef
package com.android.purebilibili.feature.video.ui.section
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.*
import com.android.purebilibili.core.ui.*
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.core.util.FormatUtils
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.video.ui.*
import com.bilipai.desktop.ui.LocalDesktopCreatorTeamBindings
import kotlinx.coroutines.launch
@Composable
internal fun DesktopOriginalVideoHonors(info:ViewInfo,argueMsgShown:Boolean,onDescriptionUrlClick:((String)->Unit)?) {
    val videoBadges = remember(info.isUpowerExclusive, info.isUpowerPreview, info.isCooperation) {
        resolveVideoDetailBadges(info)
    }
    
    Column {
        if (videoBadges.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                videoBadges.forEach { badge ->
                    VideoDetailBadgeChip(
                        text = badge,
                        emphasized = badge.startsWith("充电专属")
                    )
                }
            }
        }

        // 视频荣誉徽标(全站排行榜/每周必看/入站必刷/热门):可点击跳转对应榜单页
        val honorChips = info.honorReply?.honor.orEmpty().mapNotNull { honor ->
            resolveVideoHonorChipText(
                type = honor.type,
                honorName = honor.honorName,
                descContent = honor.desc?.content,
                weeklyRecommendNum = honor.weeklyRecommendNum
            )?.let { text ->
                val jumpUrl = resolveVideoHonorJumpUrl(
                    type = honor.type,
                    honorUrl = honor.honorUrl,
                    weeklyRecommendNum = honor.weeklyRecommendNum,
                    honorText = "${honor.honorName} ${honor.desc?.content.orEmpty()}"
                ) ?: return@mapNotNull null
                Triple(honor, text, jumpUrl)
            }
        }
        if (honorChips.isNotEmpty()) {
            // 紧跟统计行/徽标区:上方无徽标时收紧到 3dp,避免与播放量行隔离太远。
            Spacer(Modifier.height(if (videoBadges.isNotEmpty()) 6.dp else 3.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                honorChips.forEach { (honor, text, jumpUrl) ->
                    VideoHonorChip(
                        text = text,
                        onClick = { onDescriptionUrlClick?.invoke(jumpUrl) }
                    )
                }
            }
        }

        // UP 主视频声明(PiliPlus argue_msg)+ 禁止转载(rights.no_reprint):
        // 声明小字置于 BGM 胶囊之上,与荣誉胶囊形成"胶囊区→声明区"的统一观感。
        val argueMsg = info.argueInfo?.argueMsg.orEmpty()
        val noReprint = info.rights.noReprint == 1
        if (argueMsgShown && (argueMsg.isNotBlank() || noReprint)) {
            Spacer(Modifier.height(6.dp))
            if (argueMsg.isNotBlank()) {
                VideoArgueMsgRow(argueMsg = argueMsg)
            }
            if (argueMsg.isNotBlank() && noReprint) {
                Spacer(Modifier.height(4.dp))
            }
            if (noReprint) {
                VideoArgueMsgRow(argueMsg = "未经作者授权，请勿转载")
            }
        }

    }
}

@Composable
internal fun VideoDetailBadgeChip(
    text: String,
    emphasized: Boolean
) {
    com.android.purebilibili.core.ui.components.AppStatusBadge(
        label = text,
        emphasized = emphasized,
    )
}

@Composable
internal fun VideoArgueMsgRow(argueMsg: String) {
    Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)
    ) {
        androidx.compose.material3.Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(13.dp)
        )
        AppText(
            text = argueMsg,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun VideoHonorChip(
    text: String,
    onClick: (() -> Unit)? = null
) {
    AppSurface(
        onClick = onClick ?: {},
        enabled = onClick != null,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
    ) {
        AppText(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
internal fun CreatorTeamSection(
    staff: List<VideoStaff>,
    ownerMid: Long,
    onMemberClick: (Long) -> Unit
) {
    val ActionRepository = LocalDesktopCreatorTeamBindings.current
    if (staff.isEmpty()) return
    // 每个成员的关注状态:null=查询中;经 followStateChanges 与全局动作同步。
    val followStates = remember(staff) { mutableStateMapOf<Long, Boolean>() }
    LaunchedEffect(staff) {
        staff.filter { it.mid > 0L && it.mid != ownerMid }.forEach { member ->
            followStates[member.mid] =
                ActionRepository
                    .checkFollowStatus(member.mid)
        }
    }
    LaunchedEffect(Unit) {
        ActionRepository.followStateChanges.collect { change ->
            if (followStates.containsKey(change.mid)) {
                followStates[change.mid] = change.isFollowing
            }
        }
    }
    val scope = rememberCoroutineScope()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppText(
                text = "创作团队",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.weight(1f))
            AppText(
                text = "共 ${staff.size} 位",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            staff.forEach { member ->
                CreatorTeamMemberChip(
                    member = member,
                    showFollow = member.mid > 0L && member.mid != ownerMid,
                    isFollowing = followStates[member.mid] ?: false,
                    onFollowToggle = {
                        scope.launch {
                            val target = !(followStates[member.mid] ?: false)
                            val ok = ActionRepository
                                .followUser(member.mid, target)
                                .getOrDefault(false)
                            if (ok) followStates[member.mid] = target
                        }
                    },
                    onClick = { onMemberClick(member.mid) }
                )
            }
        }
    }
}

@Composable
private fun CreatorTeamMemberChip(
    member: VideoStaff,
    showFollow: Boolean,
    isFollowing: Boolean,
    onFollowToggle: () -> Unit,
    onClick: () -> Unit
) {
    val followDarkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val followVisualPolicy = remember(isFollowing, followDarkTheme) {
        resolveVideoFollowVisualPolicy(
            isFollowing = isFollowing,
            darkTheme = followDarkTheme,
        )
    }
    val officialBadge = remember(member.official) {
        resolveOfficialVerifyBadgeFromRole(
            type = member.official.type,
            role = member.official.role,
            title = member.official.title,
            desc = member.official.desc,
            compact = true
        )
    }
    Row(
        modifier = Modifier
            .clip(AppShapes.container(ContainerLevel.Chip))
            .clickable(enabled = member.mid > 0L, onClick = onClick)
            .padding(end = 4.dp)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(36.dp)) {
            AsyncImage(
                model = ImageRequest.Builder(LocalPlatformContext.current)
                    .data(FormatUtils.fixImageUrl(member.face))
                    .crossfade(true)
                    .build(),
                contentDescription = "${member.name} 头像",
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop
            )
            UserAvatarCornerMarkBadge(
                mark = resolveUserAvatarCornerMark(
                    officialType = member.official.type,
                    vipStatus = member.vip.status,
                ),
                modifier = Modifier.align(Alignment.BottomEnd),
                badgeSize = 14.dp,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column(
            modifier = Modifier.widthIn(min = 64.dp, max = 112.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                AppText(
                    text = member.name,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (officialBadge != null) {
                    OfficialVerifyBadge(
                        badge = officialBadge,
                        compact = true
                    )
                }
            }
            if (member.title.isNotBlank()) {
                AppText(
                    text = member.title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (showFollow) {
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClick = onFollowToggle),
                contentAlignment = Alignment.Center
            ) {
                AppSurface(
                    color = when (followVisualPolicy.detailButtonTone) {
                        FollowButtonTone.PRIMARY -> MaterialTheme.colorScheme.primary
                        FollowButtonTone.PRIMARY_CONTAINER -> MaterialTheme.colorScheme.primaryContainer
                    },
                    shape = VideoDetailShapes.action(),
                    modifier = Modifier.heightIn(min = 28.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    ) {
                        AppText(
                            text = if (isFollowing) "已关注" else "关注",
                            style = MaterialTheme.typography.labelMedium,
                            color = when (followVisualPolicy.detailTextTone) {
                                FollowTextTone.ON_PRIMARY -> MaterialTheme.colorScheme.onPrimary
                                FollowTextTone.ON_PRIMARY_CONTAINER -> MaterialTheme.colorScheme.onPrimaryContainer
                            },
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}