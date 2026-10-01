// File: feature/video/ui/section/VideoInfoSection.kt
package com.android.purebilibili.feature.video.ui.section

import coil3.request.crossfade
import com.android.purebilibili.core.ui.components.AppIcon
import com.android.purebilibili.core.ui.motion.folmeExpandEnterTransition
import com.android.purebilibili.core.ui.motion.folmeExpandExitTransition
import com.android.purebilibili.core.ui.components.AppText

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
//  已改用 MaterialTheme.colorScheme.primary
import com.android.purebilibili.core.util.FormatUtils
import com.android.purebilibili.data.model.response.UgcSeason
import com.android.purebilibili.data.model.response.VideoStaff
import com.android.purebilibili.data.model.response.ViewInfo
import com.android.purebilibili.data.model.response.VideoTag
import com.android.purebilibili.core.ui.common.TextSelectionPolicy
import com.android.purebilibili.core.ui.common.copyOnLongPress
import com.android.purebilibili.feature.video.ui.components.VideoCardSkeleton
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.draw.rotate
import com.android.purebilibili.core.ui.common.copyOnClick
import com.android.purebilibili.core.ui.OfficialVerifyBadge
import com.android.purebilibili.core.ui.UserAvatarCornerMarkBadge
import com.android.purebilibili.core.ui.resolveUserAvatarCornerMark
import com.android.purebilibili.core.ui.components.AppIconButton
import com.android.purebilibili.core.ui.components.AppSurface
import com.android.purebilibili.core.ui.components.resolveUpStatsText
import com.android.purebilibili.core.ui.components.resolveUpNameColor
import com.android.purebilibili.core.ui.components.UserUpBadge
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.theme.LocalAppUiStyle
import com.android.purebilibili.core.ui.resolveOfficialVerifyBadgeFromRole
import com.android.purebilibili.core.ui.transition.LocalVideoSharedTransitionSpeedSettings
import com.android.purebilibili.core.ui.transition.resolveVideoMetadataSharedTransitionMotionSpec
import com.android.purebilibili.core.ui.transition.shouldEnableVideoCoverSharedTransition
import com.android.purebilibili.core.ui.transition.shouldEnableVideoMetadataSharedTransition
import com.android.purebilibili.core.ui.transition.shouldUseVideoCardShellSharedBounds
import com.android.purebilibili.core.ui.transition.videoMetadataSharedElementBoundsTransformSpec
import com.android.purebilibili.data.model.response.BgmDetailData
import com.android.purebilibili.data.model.response.BgmInfo
import com.android.purebilibili.data.model.response.AiSummaryData
import com.android.purebilibili.data.model.response.BgmRecommendVideo
import com.android.purebilibili.data.model.response.Owner
import com.android.purebilibili.data.model.response.Stat
import com.android.purebilibili.data.model.response.VideoItem
import com.android.purebilibili.feature.video.ui.FollowButtonTone
import com.android.purebilibili.feature.video.ui.FollowTextTone
import com.android.purebilibili.feature.video.ui.resolveVideoFollowVisualPolicy
import com.android.purebilibili.feature.home.components.cards.ElegantVideoCard
import com.android.purebilibili.feature.home.resolveHomeFeedCardLayout
import com.android.purebilibili.core.store.HomeFeedCardStyle
import com.android.purebilibili.feature.video.ui.components.ShimmerContainer
import com.android.purebilibili.feature.video.ui.components.SkeletonBox
import com.android.purebilibili.feature.video.ui.VideoDetailShapes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.purebilibili.core.ui.AppShapes
import com.android.purebilibili.core.ui.ContainerLevel

import coil3.compose.LocalPlatformContext
import com.bilipai.desktop.ui.LocalDesktopOriginalVideoInfoBindings
import com.android.purebilibili.core.store.DesktopOriginalVideoInfoSettings as SettingsManager
private val useMiuixSpring: Boolean
    @Composable get() = com.android.purebilibili.core.theme.LocalAppUiStyle.current ==
        com.android.purebilibili.core.theme.AppUiStyle.MIUIX

internal const val VIDEO_DESCRIPTION_URL_TAG = "VIDEO_DESCRIPTION_URL"
private val VIDEO_DESCRIPTION_URL_PATTERN =
    """((https?|ftp|file)://[-a-zA-Z0-9+&@#/%?=~_|!:,.;]*[-a-zA-Z0-9+&@#/%=~_|])""".toRegex()
private val VIDEO_DESCRIPTION_INLINE_BVID_PATTERN =
    Regex("""(?<![A-Za-z0-9])BV[a-zA-Z0-9]{10}(?![A-Za-z0-9])""", RegexOption.IGNORE_CASE)
private val VIDEO_DESCRIPTION_TOPIC_PATTERN =
    Regex("""#([^#\n\r\t]+)#""")
private val VIDEO_DESCRIPTION_MENTION_PATTERN =
    Regex("""@[^\s@,，。:：;；!！?？/\\]{1,32}""")

internal fun buildVideoDescriptionAnnotatedString(
    desc: String,
    urlColor: Color,
    linkListener: androidx.compose.ui.text.LinkInteractionListener? = null
): AnnotatedString = buildVideoDescriptionAnnotatedString(
    desc = desc,
    descV2 = emptyList(),
    urlColor = urlColor,
    linkListener = linkListener
)

/**
 * 构建简介富文本。descV2 非空时按分段渲染:type=2 的 @提及带 biz_id,
 * 点击直达 space.bilibili.com/{mid};纯文本回退时 @xxx 高亮并跳用户搜索。
 */
internal fun buildVideoDescriptionAnnotatedString(
    desc: String,
    descV2: List<com.android.purebilibili.data.model.response.VideoDescSegment>,
    urlColor: Color,
    linkListener: androidx.compose.ui.text.LinkInteractionListener? = null
): AnnotatedString {
    if (descV2.isEmpty()) {
        return buildRawDescriptionAnnotatedString(desc, urlColor, linkListener)
    }
    return buildAnnotatedString {
        descV2.forEach { segment ->
            if (segment.type == 2 && segment.bizId > 0 && segment.rawText.isNotBlank()) {
                withLink(
                    androidx.compose.ui.text.LinkAnnotation.Clickable(
                        tag = "https://space.bilibili.com/${segment.bizId}",
                        styles = null,
                        linkInteractionListener = linkListener,
                    )
                ) {
                    withStyle(SpanStyle(color = urlColor, textDecoration = TextDecoration.Underline)) {
                        append("@${segment.rawText}")
                    }
                }
            } else {
                append(buildRawDescriptionAnnotatedString(segment.rawText, urlColor, linkListener))
            }
        }
    }
}

private fun buildRawDescriptionAnnotatedString(
    desc: String,
    urlColor: Color,
    linkListener: androidx.compose.ui.text.LinkInteractionListener?
): AnnotatedString {
    data class LinkMatch(
        val range: IntRange,
        val annotation: String,
        val displayText: String,
        val priority: Int
    )

    val matches = mutableListOf<LinkMatch>()
    VIDEO_DESCRIPTION_URL_PATTERN.findAll(desc).forEach { match ->
        matches += LinkMatch(
            range = match.range,
            annotation = match.value,
            displayText = match.value,
            priority = 0
        )
    }
    VIDEO_DESCRIPTION_INLINE_BVID_PATTERN.findAll(desc).forEach { match ->
        val overlapsUrl = matches.any { existing ->
            match.range.first <= existing.range.last && match.range.last >= existing.range.first
        }
        if (!overlapsUrl) {
            matches += LinkMatch(
                range = match.range,
                annotation = "https://www.bilibili.com/video/${match.value}",
                displayText = match.value,
                priority = 1
            )
        }
    }
    VIDEO_DESCRIPTION_TOPIC_PATTERN.findAll(desc).forEach { match ->
        val overlapsUrl = matches.any { existing ->
            match.range.first <= existing.range.last && match.range.last >= existing.range.first
        }
        if (!overlapsUrl) {
            val topic = match.groupValues[1].trim()
            if (topic.isNotEmpty()) {
                val encoded = java.net.URLEncoder.encode(topic, java.nio.charset.StandardCharsets.UTF_8.name())
                matches += LinkMatch(
                    range = match.range,
                    annotation = "bilibili://search?keyword=$encoded",
                    displayText = match.value,
                    priority = 2
                )
            }
        }
    }
    VIDEO_DESCRIPTION_MENTION_PATTERN.findAll(desc).forEach { match ->
        val overlapsUrl = matches.any { existing ->
            match.range.first <= existing.range.last && match.range.last >= existing.range.first
        }
        if (!overlapsUrl) {
            val mention = match.value.removePrefix("@").trim()
            if (mention.isNotEmpty()) {
                val encoded = java.net.URLEncoder.encode(mention, java.nio.charset.StandardCharsets.UTF_8.name())
                matches += LinkMatch(
                    range = match.range,
                    // desc_v2 缺失时拿不到 mid,回退到站内用户搜索页。
                    annotation = "https://search.bilibili.com/upuser?keyword=$encoded",
                    displayText = match.value,
                    priority = 2
                )
            }
        }
    }
    matches.sortWith(compareBy<LinkMatch> { it.range.first }.thenBy { it.priority })

    return buildAnnotatedString {
        var lastIndex = 0
        matches.forEach { match ->
            if (lastIndex < match.range.first) {
                append(desc.substring(lastIndex, match.range.first))
            }
            withLink(
                androidx.compose.ui.text.LinkAnnotation.Clickable(
                    tag = match.annotation,
                    styles = null,
                    linkInteractionListener = linkListener,
                )
            ) {
                withStyle(SpanStyle(color = urlColor, textDecoration = TextDecoration.Underline)) {
                    append(match.displayText)
                }
            }
            lastIndex = match.range.last + 1
        }
        if (lastIndex < desc.length) {
            append(desc.substring(lastIndex))
        }
    }
}

/**
 * Video Info Section Components
 * 
 * Contains components for displaying video information:
 * - VideoTitleSection: Video title with expand/collapse
 * - VideoTitleWithDesc: Title + stats + description
 * - UpInfoSection: UP owner info with follow button
 * - DescriptionSection: Video description
 * 
 * Requirement Reference: AC3.1 - Video info components in dedicated file
 */

internal fun resolveVideoInfoInitialExpandedState(
    hasDescription: Boolean,
    hasTags: Boolean,
    defaultExpanded: Boolean = false
): Boolean = defaultExpanded && (hasDescription || hasTags)

@Composable
fun VideoTitleSection(
    info: ViewInfo,
    animateLayout: Boolean = true,
    onUpClick: (Long) -> Unit = {}
) {
    var expanded by remember { mutableStateOf(false) }
    val publishTimeRowText = remember(info.pubdate, info.tname, info.title) {
        resolvePublishTimeRowText(
            pubdate = info.pubdate,
            partitionName = info.tname,
            title = info.title
        )
    }
    val emphasizePublishTime = remember(info.tname, info.title) {
        shouldEmphasizePrecisePublishTime(
            partitionName = info.tname,
            title = info.title
        )
    }
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .clickable { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        // Title row (expandable)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            SelectionContainer(
                modifier = Modifier
                    .weight(1f)
                    .then(if (animateLayout) Modifier.animateContentSize() else Modifier)
            ) {
                AppText(
                    text = info.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = if (expanded) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.copyOnLongPress(info.title, "视频标题")
                )
            }
            Spacer(Modifier.width(4.dp))
            AppIcon(
                imageVector = if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(18.dp)
            )
        }
        
        Spacer(Modifier.height(2.dp))
        
        // Stats row (views, danmaku)
        AppText(
            text = "${FormatUtils.formatStat(info.stat.view.toLong())}  \u2022  ${FormatUtils.formatStat(info.stat.danmaku.toLong())}\u5f39\u5e55",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            maxLines = 1
        )

        if (publishTimeRowText.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            if (emphasizePublishTime) {
                AppSurface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f),
                    shape = com.android.purebilibili.core.ui.AppShapes.container(
                        com.android.purebilibili.core.ui.ContainerLevel.Field
                    )
                ) {
                    AppText(
                        text = publishTimeRowText,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.92f),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            } else {
                AppText(
                    text = publishTimeRowText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
fun VideoDetailSponsorLabelChip(
    label: String,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
) {
    AppSurface(
        modifier = modifier,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                AppIcon(
                    imageVector = Icons.Outlined.Shield,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp)
                )
                AppIcon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(9.dp)
                )
            }
            AppText(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                lineHeight = MaterialTheme.typography.labelSmall.fontSize,
                maxLines = maxLines
            )
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
fun VideoTitleWithDesc(
    info: ViewInfo,
    videoTags: List<VideoTag> = emptyList(),  //  视频标签
    bgmList: List<BgmInfo> = emptyList(),
    onlineCount: String = "",
    showOnlineCount: Boolean = true,
    transitionEnabled: Boolean = false,  // 🔗 共享元素过渡开关
    isQuickReturnLimitedForSharedElements: Boolean = false,
    sourceRouteForSharedElement: String? = null,
    animateLayout: Boolean = true,
    onDescriptionUrlClick: ((String) -> Unit)? = null,
    onBgmClick: (BgmInfo) -> Unit = {},
    onTagClick: (String) -> Unit = {},
    onRelatedVideoClick: (String, Long) -> Unit = { _, _ -> },
    // PiliPlus 式标题前缀徽标（赞助/恰饭等），空串不展示
    sponsorLabel: String = "",
    // 信息行末尾的紧凑入口插槽（AI 总结 / 视频笔记图标）
    trailingStatsContent: (@Composable () -> Unit)? = null
) {
    val context = LocalDesktopOriginalVideoInfoBindings.current.context
    val isMaterial3 = LocalAppUiStyle.current == AppUiStyle.MATERIAL3
    val horizontalPadding = if (isMaterial3) 16.dp else 12.dp
    val defaultExpanded by SettingsManager
        .getVideoInfoDefaultExpanded(context)
        .collectAsStateWithLifecycle(initialValue = false)
    val argueMsgShown by com.android.purebilibili.core.store.DesktopOriginalVideoMetadataSettings
        .getVideoArgueMsgShown(context)
        .collectAsStateWithLifecycle(initialValue = true)
    var expanded by remember(info.bvid, info.desc, videoTags.size, defaultExpanded) {
        mutableStateOf(
            resolveVideoInfoInitialExpandedState(
                hasDescription = info.desc.isNotBlank(),
                hasTags = videoTags.isNotEmpty(),
                defaultExpanded = defaultExpanded
            )
        )
    }
    val publishTimeRowText = remember(info.pubdate, info.tname, info.title) {
        resolvePublishTimeRowText(
            pubdate = info.pubdate,
            partitionName = info.tname,
            title = info.title
        )
    }
    val emphasizePublishTime = remember(info.tname, info.title) {
        shouldEmphasizePrecisePublishTime(
            partitionName = info.tname,
            title = info.title
        )
    }
    // PiliPlus 同款：信息行直接展示完整 yyyy-MM-dd HH:mm
    val fullPublishTimeText = remember(info.pubdate) {
        FormatUtils.formatPrecisePublishTime(timestampSeconds = info.pubdate)
    }
    val onlineCountText = remember(showOnlineCount, onlineCount) {
        resolveVideoDetailOnlineCountText(
            showOnlineCount = showOnlineCount,
            onlineCount = onlineCount
        )
    }
    val videoBadges = remember(info.isUpowerExclusive, info.isUpowerPreview, info.isCooperation) {
        resolveVideoDetailBadges(info)
    }
    
    //  尝试获取共享元素作用域
    val sharedTransitionScope = com.android.purebilibili.core.ui.LocalSharedTransitionScope.current
    val animatedVisibilityScope = com.android.purebilibili.core.ui.LocalAnimatedVisibilityScope.current
    val coverSharedEnabled = shouldEnableVideoCoverSharedTransition(
        transitionEnabled = transitionEnabled,
        hasSharedTransitionScope = sharedTransitionScope != null,
        hasAnimatedVisibilityScope = animatedVisibilityScope != null
    )
    val useCardContainerSharedBounds = shouldUseVideoCardShellSharedBounds(
        sourceRoute = sourceRouteForSharedElement,
        transitionEnabled = coverSharedEnabled
    )
    val metadataSharedEnabled = shouldEnableVideoMetadataSharedTransition(
        coverSharedEnabled = coverSharedEnabled,
        isQuickReturnLimited = isQuickReturnLimitedForSharedElements,
        useCardContainerSharedBounds = useCardContainerSharedBounds
    )
    val sharedTransitionSpeedSettings = LocalVideoSharedTransitionSpeedSettings.current
    val metadataSharedTransitionMotionSpec = remember(
        metadataSharedEnabled,
        sharedTransitionSpeedSettings
    ) {
        resolveVideoMetadataSharedTransitionMotionSpec(
            transitionEnabled = metadataSharedEnabled,
            speedSettings = sharedTransitionSpeedSettings
        )
    }
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = horizontalPadding, vertical = if (isMaterial3) 4.dp else 3.dp)
    ) {
        val stackSponsorLabel = sponsorLabel.isNotBlank() && shouldStackSponsorLabelAboveTitle(sponsorLabel)
        if (stackSponsorLabel) {
            // 长徽标独立成行，避免挤压标题
            VideoDetailSponsorLabelChip(
                label = sponsorLabel,
                modifier = Modifier.fillMaxWidth(),
                maxLines = 2,
            )
        }
        // Title row (expandable); top-aligned so the sponsor badge lines up with the first title line
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { expanded = !expanded },
            verticalAlignment = Alignment.Top
        ) {
            //  共享元素过渡 - 标题
            var titleModifier = if (animateLayout) Modifier.animateContentSize() else Modifier

            //  注意：使用 ExperimentalSharedTransitionApi 注解需要上下文
            if (metadataSharedEnabled) {
                with(requireNotNull(sharedTransitionScope)) {
                     titleModifier = titleModifier.sharedBounds(
                        sharedContentState = rememberSharedContentState(
                            key = com.android.purebilibili.core.ui.transition.videoTitleSharedElementKey(
                                info.bvid,
                                sourceRoute = sourceRouteForSharedElement
                            )
                        ),
                        animatedVisibilityScope = requireNotNull(animatedVisibilityScope),
                        boundsTransform = { initialBounds, targetBounds ->
                            videoMetadataSharedElementBoundsTransformSpec(
                                motion = metadataSharedTransitionMotionSpec,
                                initialBounds = initialBounds,
                                targetBounds = targetBounds
                            )
                        }
                    )
                }
            }

            if (sponsorLabel.isNotBlank() && !stackSponsorLabel) {
                VideoDetailSponsorLabelChip(
                    label = sponsorLabel,
                    modifier = Modifier.padding(end = 6.dp, top = 2.dp)
                )
            }
            SelectionContainer(modifier = Modifier.weight(1f)) {
                AppText(
                    text = info.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = titleModifier
                )
            }

            val rotateAngle by animateFloatAsState(
                targetValue = if (expanded) 180f else 0f, // 展开时旋转180度
                animationSpec = tween(durationMillis = 300), // 设置动画时长和曲线
                label = "IconRotation"
            )
            AppIcon(
                imageVector = Icons.Outlined.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .rotate(rotateAngle)
                    .size(20.dp)
                    .padding(4.dp)
            )
        }
        
        Spacer(Modifier.height(if (isMaterial3) 4.dp else 3.dp))
        
        // Stats row
        Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            itemVerticalAlignment = Alignment.CenterVertically
        ) {
            // Stats Row split for shared element transitions
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Views
                var viewsModifier = Modifier.wrapContentSize()
                if (metadataSharedEnabled) {
                    with(requireNotNull(sharedTransitionScope)) {
                        viewsModifier = viewsModifier.sharedBounds(
                            sharedContentState = rememberSharedContentState(
                                key = com.android.purebilibili.core.ui.transition.videoViewsSharedElementKey(
                                    info.bvid,
                                    sourceRoute = sourceRouteForSharedElement
                                )
                            ),
                            animatedVisibilityScope = requireNotNull(animatedVisibilityScope),
                            boundsTransform = { initialBounds, targetBounds ->
                                videoMetadataSharedElementBoundsTransformSpec(
                                    motion = metadataSharedTransitionMotionSpec,
                                    initialBounds = initialBounds,
                                    targetBounds = targetBounds
                                )
                            }
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = viewsModifier) {
                    AppIcon(
                        imageVector = Icons.Outlined.PlayCircleOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(Modifier.width(2.dp))
                    AppText(
                        text = FormatUtils.formatStat(info.stat.view.toLong()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.width(10.dp))

                // Danmaku
                var danmakuModifier = Modifier.wrapContentSize()
                if (metadataSharedEnabled) {
                    with(requireNotNull(sharedTransitionScope)) {
                        danmakuModifier = danmakuModifier.sharedBounds(
                            sharedContentState = rememberSharedContentState(
                                key = com.android.purebilibili.core.ui.transition.videoDanmakuSharedElementKey(
                                    info.bvid,
                                    sourceRoute = sourceRouteForSharedElement
                                )
                            ),
                            animatedVisibilityScope = requireNotNull(animatedVisibilityScope),
                            boundsTransform = { initialBounds, targetBounds ->
                                videoMetadataSharedElementBoundsTransformSpec(
                                    motion = metadataSharedTransitionMotionSpec,
                                    initialBounds = initialBounds,
                                    targetBounds = targetBounds
                                )
                            }
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = danmakuModifier) {
                    AppIcon(
                        imageVector = Icons.Outlined.Subtitles,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(Modifier.width(2.dp))
                    AppText(
                        text = FormatUtils.formatStat(info.stat.danmaku.toLong()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

            }
            if (onlineCountText.isNotBlank()) {
                AppText(
                    text = onlineCountText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.82f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (publishTimeRowText.isNotBlank()) {
                if (emphasizePublishTime) {
                    AppSurface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f),
                        shape = com.android.purebilibili.core.ui.AppShapes.container(
                            com.android.purebilibili.core.ui.ContainerLevel.Field
                        )
                    ) {
                        AppText(
                            text = publishTimeRowText,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.92f),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                } else {
                    AppText(
                        text = fullPublishTimeText.ifBlank { publishTimeRowText },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }
        trailingStatsContent?.invoke()
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = expanded,
            enter = if (animateLayout) {
                folmeExpandEnterTransition(useMiuixSpring)
            } else {
                androidx.compose.animation.EnterTransition.None
            },
            exit = if (animateLayout) {
                folmeExpandExitTransition(useMiuixSpring)
            } else {
                androidx.compose.animation.ExitTransition.None
            }
        ) {
            AppText(
                text = info.bvid,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.66f),
                modifier = Modifier
                    .padding(top = 6.dp)
                    .copyOnClick(info.bvid, "BV号")
            )
        }

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

        // [新增] BGM Info Row
        if (bgmList.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            DesktopOriginalInlineBgmSection(
                bgmList = bgmList,
                onBgmClick = onBgmClick,
                onRelatedVideoClick = onRelatedVideoClick
            )
        }

        //  Description - 默认隐藏，展开后显示
        androidx.compose.animation.AnimatedVisibility(
            visible = expanded && info.desc.isNotBlank(),
            enter = if (animateLayout) {
                folmeExpandEnterTransition(useMiuixSpring)
            } else {
                androidx.compose.animation.EnterTransition.None
            },
            exit = if (animateLayout) {
                folmeExpandExitTransition(useMiuixSpring)
            } else {
                androidx.compose.animation.ExitTransition.None
            }
        ) {
            Column {
                Spacer(Modifier.height(6.dp))
                val descriptionUrlColor = MaterialTheme.colorScheme.primary
                val descriptionLinkListener = remember(onDescriptionUrlClick) {
                    onDescriptionUrlClick?.let { handler ->
                        androidx.compose.ui.text.LinkInteractionListener { link ->
                            handler((link as androidx.compose.ui.text.LinkAnnotation.Clickable).tag)
                        }
                    }
                }
                val descriptionText = remember(
                    info.desc,
                    info.descV2,
                    descriptionUrlColor,
                    descriptionLinkListener
                ) {
                    buildVideoDescriptionAnnotatedString(
                        desc = info.desc,
                        descV2 = info.descV2,
                        urlColor = descriptionUrlColor,
                        linkListener = descriptionLinkListener
                    )
                }
                val descriptionModifier = if (animateLayout) {
                    Modifier.animateContentSize()
                } else {
                    Modifier
                }
                // 原生链接分发：链接点击在 Text 内部处理，与划选（SelectionContainer）
                // 和外层手势不再竞争，恢复无条件划选容器。
                SelectionContainer {
                    AppText(
                        text = descriptionText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        modifier = descriptionModifier
                    )
                }
            }
        }
        
        //  Tags - 默认隐藏，展开后显示
        androidx.compose.animation.AnimatedVisibility(
            visible = expanded && videoTags.isNotEmpty(),
            enter = if (animateLayout) {
                folmeExpandEnterTransition(useMiuixSpring)
            } else {
                androidx.compose.animation.EnterTransition.None
            },
            exit = if (animateLayout) {
                folmeExpandExitTransition(useMiuixSpring)
            } else {
                androidx.compose.animation.ExitTransition.None
            }
        ) {
            val videoTagSize by SettingsManager
                .getVideoTagSizePreset(context)
                .collectAsStateWithLifecycle(
                    initialValue = com.android.purebilibili.core.ui.components.AppTagChipSize.STANDARD
                )
            val tagMetrics = com.android.purebilibili.core.ui.components
                .resolveAppTagChipMetrics(videoTagSize)
            Column {
                Spacer(Modifier.height(8.dp))
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(tagMetrics.itemSpacingHorizontal),
                    verticalArrangement = Arrangement.Top
                ) {
                    videoTags.take(10).forEach { tag ->
                        com.android.purebilibili.core.ui.components.AppTagChip(
                            label = if (tag.tag_type == "bgm") tag.tag_name.replaceFirst("发现", "♫ BGM：") else tag.tag_name,
                            onClick = {
                                val bgm = resolveBgmTagInfo(tag)
                                if (bgm != null) onBgmClick(bgm) else onTagClick(tag.tag_name)
                            },
                            modifier = Modifier
                                .padding(bottom = tagMetrics.itemSpacingVertical)
                                .copyOnLongPress(tag.tag_name, "标签"),
                            size = videoTagSize,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
fun UpInfoSection(
    info: ViewInfo,
    isFollowing: Boolean = false,
    onFollowClick: () -> Unit = {},
    onUpClick: (Long) -> Unit = {},
    showOwnerAvatar: Boolean = true,
    followerCount: Int? = null,
    videoCount: Int? = null,
    transitionEnabled: Boolean = false,  // 🔗 共享元素过渡开关
    isQuickReturnLimitedForSharedElements: Boolean = false,
    sourceRouteForSharedElement: String? = null,
    horizontalPadding: androidx.compose.ui.unit.Dp = 12.dp,
    modifier: Modifier = Modifier,
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
) {
    val VideoRepository = LocalDesktopOriginalVideoInfoBindings.current
    val playerControlVisibility by SettingsManager
        .getPlayerControlVisibilitySettings(LocalDesktopOriginalVideoInfoBindings.current.context)
        .collectAsStateWithLifecycle(
            initialValue = com.android.purebilibili.core.store.PlayerControlVisibilitySettings()
        )
    //  尝试获取共享元素作用域
    val sharedTransitionScope = com.android.purebilibili.core.ui.LocalSharedTransitionScope.current
    val animatedVisibilityScope = com.android.purebilibili.core.ui.LocalAnimatedVisibilityScope.current
    val coverSharedEnabled = shouldEnableVideoCoverSharedTransition(
        transitionEnabled = transitionEnabled,
        hasSharedTransitionScope = sharedTransitionScope != null,
        hasAnimatedVisibilityScope = animatedVisibilityScope != null
    )
    val useCardContainerSharedBounds = shouldUseVideoCardShellSharedBounds(
        sourceRoute = sourceRouteForSharedElement,
        transitionEnabled = coverSharedEnabled
    )
    val metadataSharedEnabled = shouldEnableVideoMetadataSharedTransition(
        coverSharedEnabled = coverSharedEnabled,
        isQuickReturnLimited = isQuickReturnLimitedForSharedElements,
        useCardContainerSharedBounds = useCardContainerSharedBounds
    )
    val sharedTransitionSpeedSettings = LocalVideoSharedTransitionSpeedSettings.current
    val metadataSharedTransitionMotionSpec = remember(
        metadataSharedEnabled,
        sharedTransitionSpeedSettings
    ) {
        resolveVideoMetadataSharedTransitionMotionSpec(
            transitionEnabled = metadataSharedEnabled,
            speedSettings = sharedTransitionSpeedSettings
        )
    }
    val upStatsText = resolveUpStatsText(
        followerCount = followerCount,
        videoCount = videoCount
    )
    val showInlineOwnerIdentity = shouldShowInlineOwnerIdentity(showOwnerAvatar = showOwnerAvatar)

    BoxWithConstraints(modifier = modifier) {
        val isCompact = maxWidth.isSpecified && shouldUseCompactUpInfoLayout(maxWidth.value.toInt())

        val avatarContent: @Composable () -> Unit = {
            if (showOwnerAvatar) {
                val avatarSize = if (isCompact) 32.dp else 35.dp
                var sharedFaceModifier: Modifier = Modifier
                if (metadataSharedEnabled) {
                    with(requireNotNull(sharedTransitionScope)) {
                        sharedFaceModifier = Modifier.sharedBounds(
                            sharedContentState = rememberSharedContentState(
                                key = com.android.purebilibili.core.ui.transition.videoAvatarSharedElementKey(
                                    info.bvid,
                                    sourceRoute = sourceRouteForSharedElement
                                )
                            ),
                            animatedVisibilityScope = requireNotNull(animatedVisibilityScope),
                            boundsTransform = { initialBounds, targetBounds ->
                                videoMetadataSharedElementBoundsTransformSpec(
                                    motion = metadataSharedTransitionMotionSpec,
                                    initialBounds = initialBounds,
                                    targetBounds = targetBounds
                                )
                            },
                            clipInOverlayDuringTransition = OverlayClip(CircleShape)
                        )
                    }
                }
                val ownerStaff = info.staff.firstOrNull { it.mid == info.owner.mid }

                if (info.owner.face.isNotBlank()) {
                    OwnerDecoratedAvatar(
                        faceUrl = info.owner.face,
                        ownerMid = info.owner.mid,
                        modifier = Modifier.size(avatarSize),
                        badgeSize = if (isCompact) 11.dp else 12.dp,
                        fallbackOfficialType = ownerStaff?.official?.type,
                        fallbackVipStatus = ownerStaff?.vip?.status,
                        faceModifier = sharedFaceModifier,
                        contentDescription = "UP主头像",
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(avatarSize)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .then(sharedFaceModifier),
                        contentAlignment = Alignment.Center
                    ) {
                        AppIcon(
                            imageVector = Icons.Outlined.AccountCircle,
                            contentDescription = "UP主标识",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            } else {
                UserUpBadge()
            }
        }

        val upNameContent: @Composable (Modifier) -> Unit = { rowModifier ->
            Row(
                modifier = rowModifier,
                verticalAlignment = Alignment.CenterVertically
            ) {
                var upNameModifier: Modifier = Modifier

                if (metadataSharedEnabled) {
                    with(requireNotNull(sharedTransitionScope)) {
                        upNameModifier = upNameModifier.sharedBounds(
                            sharedContentState = rememberSharedContentState(
                                key = com.android.purebilibili.core.ui.transition.videoUpNameSharedElementKey(
                                    info.bvid,
                                    sourceRoute = sourceRouteForSharedElement
                                )
                            ),
                            animatedVisibilityScope = requireNotNull(animatedVisibilityScope),
                            boundsTransform = { initialBounds, targetBounds ->
                                videoMetadataSharedElementBoundsTransformSpec(
                                    motion = metadataSharedTransitionMotionSpec,
                                    initialBounds = initialBounds,
                                    targetBounds = targetBounds
                                )
                            }
                        )
                    }
                }

                upNameModifier = upNameModifier.copyOnLongPress(info.owner.name, "UP主名称")

                if (showInlineOwnerIdentity) {
                    if (info.owner.face.isNotBlank()) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalPlatformContext.current)
                                .data(FormatUtils.fixImageUrl(info.owner.face))
                                .crossfade(true)
                                .build(),
                            contentDescription = "UP主头像",
                            modifier = Modifier
                                .padding(horizontal = 2.dp)
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                        )
                    } else {
                        UserUpBadge(modifier = Modifier.padding(horizontal = 2.dp))
                    }
                    Spacer(Modifier.width(4.dp))
                }
                val ownerStaff = info.staff.firstOrNull { it.mid == info.owner.mid }
                val fallbackVipStatus = ownerStaff?.vip?.status ?: 0
                val fallbackVipType = ownerStaff?.vip?.type ?: 0
                val onSurfaceColor = MaterialTheme.colorScheme.onSurface
                val secondaryColor = MaterialTheme.colorScheme.secondary
                val ownerNameColor by produceState<Color>(
                    initialValue = resolveUpNameColor(
                        vipStatus = fallbackVipStatus,
                        vipType = fallbackVipType,
                        onSurface = onSurfaceColor,
                        secondary = secondaryColor,
                    ),
                    key1 = info.owner.mid,
                ) {
                    val card = if (info.owner.mid > 0L) {
                        VideoRepository.getCreatorCardStats(info.owner.mid).getOrNull()
                    } else {
                        null
                    }
                    value = resolveUpNameColor(
                        vipStatus = card?.vipStatus ?: fallbackVipStatus,
                        vipType = card?.vipType ?: fallbackVipType,
                        onSurface = onSurfaceColor,
                        secondary = secondaryColor,
                    )
                }
                SelectionContainer {
                    AppText(
                        text = info.owner.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = ownerNameColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = upNameModifier
                    )
                }
            }
        }

        val followButtonContent: @Composable () -> Unit = {
            var followActionModifier = Modifier.heightIn(min = if (isCompact) 26.dp else 28.dp)
            if (metadataSharedEnabled) {
                with(requireNotNull(sharedTransitionScope)) {
                    followActionModifier = followActionModifier.sharedBounds(
                        sharedContentState = rememberSharedContentState(
                            key = com.android.purebilibili.core.ui.transition.videoUpActionSharedElementKey(
                                info.bvid,
                                sourceRoute = sourceRouteForSharedElement
                            )
                        ),
                        animatedVisibilityScope = requireNotNull(animatedVisibilityScope),
                        boundsTransform = { initialBounds, targetBounds ->
                            videoMetadataSharedElementBoundsTransformSpec(
                                motion = metadataSharedTransitionMotionSpec,
                                initialBounds = initialBounds,
                                targetBounds = targetBounds
                            )
                        },
                        clipInOverlayDuringTransition = OverlayClip(VideoDetailShapes.action())
                    )
                }
            }

            val followDarkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
            val followVisualPolicy = remember(isFollowing, followDarkTheme) {
                resolveVideoFollowVisualPolicy(
                    isFollowing = isFollowing,
                    darkTheme = followDarkTheme,
                )
            }
            AppSurface(
                onClick = onFollowClick,
                color = when (followVisualPolicy.detailButtonTone) {
                    FollowButtonTone.PRIMARY -> MaterialTheme.colorScheme.primary
                    FollowButtonTone.PRIMARY_CONTAINER -> MaterialTheme.colorScheme.primaryContainer
                },
                shape = VideoDetailShapes.action(),
                modifier = followActionModifier
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = if (isCompact) 8.dp else 12.dp)
                ) {
                    AppText(
                        text = if (isFollowing) "\u5df2\u5173\u6ce8" else "\u5173\u6ce8",
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

        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isCompact) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onUpClick(info.owner.mid) }
                        .padding(horizontal = horizontalPadding, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    avatarContent()

                    Spacer(Modifier.width(10.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        upNameContent(Modifier.fillMaxWidth())

                        Spacer(modifier = Modifier.height(4.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (playerControlVisibility.showFollowButton) {
                                followButtonContent()
                            }
                            if (!upStatsText.isNullOrBlank()) {
                                AppText(
                                    text = upStatsText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (trailingContent != null) {
                                trailingContent()
                            }
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onUpClick(info.owner.mid) }
                        .padding(horizontal = horizontalPadding, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    avatarContent()

                    Spacer(Modifier.width(10.dp))

                    // UP owner name row
                    Column(modifier = Modifier.weight(1f)) {
                        upNameContent(Modifier)
                        if (!upStatsText.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            AppText(
                                text = upStatsText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    if (playerControlVisibility.showFollowButton) {
                        followButtonContent()
                    }
                    if (trailingContent != null) {
                        if (playerControlVisibility.showFollowButton) {
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        trailingContent()
                    }
                }
            }
            if (shouldShowCreatorTeamSection(info)) {
                CreatorTeamSection(
                    staff = info.staff,
                    ownerMid = info.owner.mid,
                    onMemberClick = onUpClick
                )
            }
        }
    }
}

@Composable
fun DescriptionSection(desc: String) {
    var expanded by remember { mutableStateOf(false) }

    if (desc.isBlank()) return

    AppSurface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .animateContentSize()
        ) {
            SelectionContainer {
                AppText(
                    text = desc,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f),
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (desc.length > 100 || desc.lines().size > 3) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded }
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppText(
                        text = if (expanded) "\u6536\u8d77" else "\u5c55\u5f00\u66f4\u591a",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    AppIcon(
                        imageVector = if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
