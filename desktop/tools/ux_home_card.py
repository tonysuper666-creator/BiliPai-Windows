"""Desktop Home card interaction layer on the generated VideoCard text (desktop UX program, slice H2, spec 3.6 and 6.7).

The Home card producer (extract-upstream-home-full-card.py) calls apply_home_card once per emitted file, right after the
recommendation badge layer, through one hook line (the v043_home_recommendation_badges pattern). The layer touches one generated
file and nothing else, VideoCard.kt (the grid card of the Home feed; its ElegantVideoCard function), with seven exact-once
replacements of slice H2 (EDITS), four of slice K3 (COVER_EDITS), fourteen of slice K3b (LAYOUT_EDITS), four of slice K3d
(HOVER_EDITS), three of slice K3e (COPY_EDITS), one of slice K3f (SEPARATOR_EDITS) and two of slice K3i
(ACTION_EDITS, all below):

  * the two imports of the vendored dropdown menu become import aliases of the desktop-owned menu, so the call text of the card is
    the vendored call text (named arguments expanded, onDismissRequest, offset, and the ColumnScope trailing lambda):
        import com.bilipai.desktop.ui.DesktopCardContextMenu as AppDropdownMenu
        import com.bilipai.desktop.ui.DesktopCardContextMenuItem as AppDropdownMenuItem
  * two imports and two modifier calls after the hover effect of the card box: desktopCardSecondaryClick (the right click opens the
    same menu as the overflow button, enabled only for a card that has a menu) and desktopCardHandCursor;
  * the size of the overflow box, anchored on its neighbouring contentDescription and menuButtonCoordsRef lines because the token
    AppChromeSizeTokens.MinimumTouchTarget is used twice in the card (the unfavorite box and the overflow box): only the overflow
    box asks desktopCardOverflowTarget for its size (32 dp while card_interactions is ON, the vendored size while it is OFF);
  * three emoji-free labels, each through desktopCardMenuLabel(original, plain): the default of dismissMenuText and the literals of
    the watch later and unfavorite menu rows. The original text (with its emoji) stays in the call and is what the OFF flag shows. No
    caller passes its own dismissMenuText (checked by the adaptation suite), so changing the default is enough.

Slice K3 adds the keyboard focus of the cover (spec 2 focus ring, spec 4 Home item 4), four more exact-once edits in
COVER_EDITS, applied after the seven above, with the helpers in desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopHomeCardFrame.kt:

  * two imports, desktopHomeCoverFocusTarget and desktopHomeCoverFocusRing;
  * the shared state homeCoverFocus, declared once next to adaptiveHoverShape, before the card root Box;
  * the focus target on the cover Box, right after its test tag: Enter calls triggerCardClick (the cover tap path), and
    Shift+F10 or the menu key call openDismissMenu with the cover anchor when the card has a menu (hasOverflowMenu, as the
    right click);
  * the ring on the card root Box, after its position recorder: the card shell clips the cover, the root does not.

Slice K3b adds the three line card content (spec 4 Home item 3), fourteen more exact-once edits in LAYOUT_EDITS, applied last.
All of them read the card_interactions flag once, through the local homeCardThreeLine (desktopCardInteractionsOn), so the OFF flag
renders the vendored card:

  * the cover is 16:9 with a 12 dp corner (the card shell uses the same corner), and its compact stats strip carries play,
    danmaku and the duration (the duration moves onto the cover when the duration style is the default OUTSIDE_COVER; HIDDEN
    still hides it), so there is no separate stat row or duration row under the cover;
  * the title is two lines of 14 sp / 20 sp Medium (the vendored max and min line counts are kept);
  * one owner row: the UP name, ellipsized on one line, then "· <time>" (the compact publish time without its "发布于 " prefix);
    the recommendation reason badge and the separate publish row are not shown, and the overflow button keeps its 32 dp target
    (desktopCardOverflowTarget of slice H2) at the end of the info block. No like count is shown, as in the vendored card.

Slice K3d adds the hover of the card and its quick actions (spec 4 Home item 3, spec 7 video card, spec 8), four more
exact-once edits in HOVER_EDITS, applied after all of the above, with the helpers in DesktopHomeCardFrame.kt:

  * one import, desktopHomeCardHoverSurface;
  * the shared state homeCardHover, declared once next to homeCoverFocus;
  * the hover of the card root: while the flag is ON (homeCardThreeLine) the root takes desktopHomeCardHoverSurface (an 8 percent
    primary wash on a base 8 dp wider than the card, no scale, no shadow); while it is OFF the root keeps the vendored call
    adaptiveCardHoverEffect(shape = adaptiveHoverShape) unchanged;
  * DesktopHomeCardQuickActions as the last child of the cover Box, in its top end corner: watch later calls the card's own
    onWatchLater, more calls openDismissMenu with the more button as anchor when the card has a menu (hasOverflowMenu).
Undoing only HOVER_EDITS gives the text of H2, K3 and K3b exactly.

Slice K3e adds the copy link of the card (spec 4, menu item 5), three more exact-once edits in COPY_EDITS, applied after all
of the above, with the helpers in DesktopHomeCardFrame.kt:

  * the clipboard and the link of the card, read once next to homeCardHover: homeCardClipboard is the existing clipboard
    boundary (LocalDesktopTextClipboard of the appearance package), homeCardCopyLink is the link of this video
    (desktopHomeVideoLink, null when the bvid is not a video id), and copyHomeCardLink is the action that writes it once
    through desktopHomeCopyVideoLink, or null when there is no link;
  * the copy action of the cover focus target (onCopy): Ctrl+C on the focused cover is one more key action of the frame (COPY).
    The shortcut belongs to the card, so text editing and selection outside it keep their Ctrl+C;
  * one row of the card menu, "复制链接（Ctrl C）", after the vendored rows and before 不感兴趣: it closes the menu, as every
    other row does, and writes the link; it plays nothing and calls neither watch later nor dismiss, and the row is not shown
    when the card has no link.
The row lives inside the existing menu and nowhere else: a card without onDismiss and onWatchLater keeps its hasOverflowMenu
policy and shows no menu, and with the flag OFF homeCardCopyLink stays null, so the vendored menu keeps its three rows and
behaviour. Undoing only COPY_EDITS gives the text of H2, K3, K3b and K3d exactly.

Slice K3f adds one separator line in front of 不感兴趣 (spec 4: the negative row goes after a separator), one more
exact-once edit in SEPARATOR_EDITS, applied after all of the above, with the wrapper in DesktopCardContextMenu.kt:

  * the line is the kit divider (outlineVariant on one physical pixel at any scale), so no new visual value is invented;
  * it is drawn only when 不感兴趣 is drawn and one of the three rows above it is drawn too, so a card without that row,
    or with no row above it, keeps a menu free of a lone line;
  * the wrapper paints nothing while the flag is OFF, and the line is not focusable or clickable: the menu order, the
    single callback of every row, Escape and the focus back to the cover stay the vendored behaviour. The vendored menu
    keeps no line, as it always had none.
Undoing only SEPARATOR_EDITS gives the text of H2, K3, K3b, K3d and K3e exactly.

Slice K3i adds two rows of the card menu, 收藏 and 小窗播放, two more exact-once edits in ACTION_EDITS, applied after all
of the above, with the helpers in DesktopHomeCardActions.kt:

  * the Home actions of the real mounted Root, read once after the copy link: homeCardActionsPort is
    LocalDesktopHomeCardActions (null everywhere except inside the active Home page of the Root), homeCardActions is that
    port only while homeCardThreeLine (the flag) is on, favoriteHomeCard opens the original cloud favourite folder drawer
    (null for a special card or aid <= 0) and miniHomeCard plays the video in the existing player mini window with the
    semantics of an ordinary click (null for a special card);
  * the two rows go after the vendored rows and before 复制链接, so the menu reads 稍后再看 / 取消收藏 / 收藏 / 小窗播放 /
    复制链接 / separator / 不感兴趣, at most seven items. Each row closes the menu, as every other row does, and runs its
    action once; neither row plays, favourites or dismisses anything by itself.
A row exists only with an action, and an action exists only with a copy link (same flag, same video id), so the separator
guard of K3f stays exact. With the flag OFF, or outside the Root Home page, both actions stay null and the vendored menu keeps
its rows and behaviour. Undoing only ACTION_EDITS gives the text of H2, K3, K3b, K3d, K3e and K3f exactly.

Not covered by this layer: the single-column display mode (AppWindowActionMenu), StoryVideoCard and DynamicCard. This is a
desktop-owned layer: there is no upstream archive, no manifest and no pinned predecessor hash. Every anchor is counted on the text
it receives and must occur exactly once, so a change of an earlier layer (the v041 status labels, the v043 recommendation badges,
the v046 tint policy and cascade definers) that moves one of these lines fails closed with AssertionError instead of editing nothing
or editing twice. Each edit is recorded as an audit row (the v043 shape) and is reversible: reverse() restores the received text
byte for byte.

Gradle input tracking (hunk RH3b): the module is an input of the extractUpstreamHomeFullCard task in desktop/build.gradle.kts,
because the generated text changes when this file does.
"""
import hashlib

CARD = 'VideoCard.kt'
UI = 'com.bilipai.desktop.ui.'

# The three vendored labels as they stand in the generated text (the emoji prefix, a space, the plain label) and the plain labels.
# The first two are literal characters in the vendored source, the third is written as a Kotlin escape pair there.
WATCH_LATER_PLAIN = '稍后再看'
UNFAVORITE_PLAIN = '取消收藏'
DISMISS_PLAIN = '不感兴趣'
# The accessible name of the quick "more" action of slice K3d; the overflow button under the title keeps "更多操作".
QUICK_MORE_PLAIN = '更多'
WATCH_LATER_ORIGINAL = '\U0001F550 ' + WATCH_LATER_PLAIN
UNFAVORITE_ORIGINAL = '\U0001F494 ' + UNFAVORITE_PLAIN
DISMISS_ORIGINAL_KOTLIN = '\\uD83D\\uDEAB ' + DISMISS_PLAIN  # backslash-u escapes, exactly as the generated Kotlin text has them

# (id, text received from the earlier layers, replacement). Each `before` occurs exactly once in VideoCard.kt.
EDITS = (
    ('ux-card-menu-imports',
     'import com.android.purebilibili.core.ui.components.AppDropdownMenu\n'
     'import com.android.purebilibili.core.ui.components.AppDropdownMenuItem\n',
     'import ' + UI + 'DesktopCardContextMenu as AppDropdownMenu\n'
     'import ' + UI + 'DesktopCardContextMenuItem as AppDropdownMenuItem\n'),
    ('ux-card-modifier-imports',
     'import com.android.purebilibili.core.ui.adaptive.adaptiveCardHoverEffect\n',
     'import com.android.purebilibili.core.ui.adaptive.adaptiveCardHoverEffect\n'
     'import ' + UI + 'desktopCardHandCursor\n'
     'import ' + UI + 'desktopCardSecondaryClick\n'),
    ('ux-card-secondary-click-and-hand-cursor',
     '            .adaptiveCardHoverEffect(shape = adaptiveHoverShape)\n',
     '            .adaptiveCardHoverEffect(shape = adaptiveHoverShape)\n'
     '            .desktopCardSecondaryClick(enabled = hasOverflowMenu) { pressOffset ->\n'
     '                openDismissMenu(cardCoordsRef.value, pressOffset)\n'
     '            }\n'
     '            .desktopCardHandCursor()\n'),
    ('ux-card-overflow-target',
     '                            .size(AppChromeSizeTokens.MinimumTouchTarget)\n'
     '                            .semantics { contentDescription = "更多操作" }\n'
     '                            .onGloballyPositioned { coordinates ->\n'
     '                                menuButtonCoordsRef.value = coordinates\n'
     '                            }\n',
     '                            .size(' + UI + 'desktopCardOverflowTarget(AppChromeSizeTokens.MinimumTouchTarget))\n'
     '                            .semantics { contentDescription = "更多操作" }\n'
     '                            .onGloballyPositioned { coordinates ->\n'
     '                                menuButtonCoordsRef.value = coordinates\n'
     '                            }\n'),
    ('ux-card-dismiss-label-default',
     '    dismissMenuText: String = "' + DISMISS_ORIGINAL_KOTLIN + '", //  [新增] 自定义长按菜单删除文案\n',
     '    dismissMenuText: String = ' + UI + 'desktopCardMenuLabel(original = "' + DISMISS_ORIGINAL_KOTLIN + '", plain = "'
     + DISMISS_PLAIN + '"), //  [新增] 自定义长按菜单删除文案\n'),
    ('ux-card-watch-later-label',
     '                                "' + WATCH_LATER_ORIGINAL + '",\n',
     '                                ' + UI + 'desktopCardMenuLabel(original = "' + WATCH_LATER_ORIGINAL + '", plain = "'
     + WATCH_LATER_PLAIN + '"),\n'),
    ('ux-card-unfavorite-label',
     '                                "' + UNFAVORITE_ORIGINAL + '",\n',
     '                                ' + UI + 'desktopCardMenuLabel(original = "' + UNFAVORITE_ORIGINAL + '", plain = "'
     + UNFAVORITE_PLAIN + '"),\n'),
)

# Slice K3: the keyboard focus of the cover. Same rules as EDITS: each `before` occurs exactly once in the text the
# layer receives (after EDITS), and each edit keeps its anchor and adds lines.
COVER_EDITS = (
    ('ux-card-cover-focus-imports',
     'import androidx.compose.ui.platform.testTag\n',
     'import androidx.compose.ui.platform.testTag\n'
     'import ' + UI + 'desktopHomeCoverFocusRing\n'
     'import ' + UI + 'desktopHomeCoverFocusTarget\n'),
    ('ux-card-cover-focus-state',
     '    val adaptiveHoverShape = AppShapes.container(ContainerLevel.Card)\n',
     '    val adaptiveHoverShape = AppShapes.container(ContainerLevel.Card)\n'
     '    val homeCoverFocus = ' + UI + 'rememberDesktopHomeCoverFocus()\n'),
    ('ux-card-cover-focus-target',
     '                .testTag("home_video_cover")\n',
     '                .testTag("home_video_cover")\n'
     '                .desktopHomeCoverFocusTarget(\n'
     '                    state = homeCoverFocus,\n'
     '                    cornerRadius = cardCornerRadius,\n'
     '                    onOpen = { triggerCardClick() },\n'
     '                    onMenu = if (hasOverflowMenu) {\n'
     '                        { openDismissMenu(coverCoordsRef.value, null) }\n'
     '                    } else {\n'
     '                        null\n'
     '                    },\n'
     '                )\n'),
    ('ux-card-cover-focus-ring',
     '            .onGloballyPositioned { coordinates ->\n'
     '                cardCoordsRef.value = coordinates\n'
     '            }\n'
     '    ) {\n',
     '            .onGloballyPositioned { coordinates ->\n'
     '                cardCoordsRef.value = coordinates\n'
     '            }\n'
     '            .desktopHomeCoverFocusRing(homeCoverFocus)\n'
     '    ) {\n'),
)
# Slice K3b: the three line card content of spec 4 Home item 3 (spec v2 line 100). Same rules as EDITS: each `before`
# occurs exactly once in the text the layer receives (after EDITS and COVER_EDITS), and every change reads the
# card_interactions flag through the single local homeCardThreeLine, so the OFF flag renders the vendored card unchanged.
LAYOUT_EDITS = (
    ('ux-card-layout-flag-and-corner',
     '    val cardCornerRadius = AppShapes.containerCornerDp(ContainerLevel.Card)\n',
     '    val homeCardThreeLine = ' + UI + 'desktopCardInteractionsOn()\n'
     '    val cardCornerRadius = if (homeCardThreeLine) 12.dp else AppShapes.containerCornerDp(ContainerLevel.Card)\n'),
    ('ux-card-layout-duration-on-cover',
     '    val showDurationOnCover = homeDurationStyle == HomeDurationStyle.OVERLAY_TEXT_ONLY\n',
     '    val showDurationOnCover = homeDurationStyle == HomeDurationStyle.OVERLAY_TEXT_ONLY ||\n'
     '        (homeCardThreeLine && homeDurationStyle == HomeDurationStyle.OUTSIDE_COVER)\n'),
    ('ux-card-layout-duration-outside',
     '    val showDurationOutside = homeDurationStyle == HomeDurationStyle.OUTSIDE_COVER\n',
     '    val showDurationOutside = !homeCardThreeLine && homeDurationStyle == HomeDurationStyle.OUTSIDE_COVER\n'),
    ('ux-card-layout-stats-on-cover',
     '    val scrollLitePolicy = remember(compactStatsOnCover) {\n'
     '        resolveVideoCardScrollLiteVisualPolicy(\n'
     '            scrollLiteModeEnabled = false,\n'
     '            compactStatsOnCover = compactStatsOnCover\n'
     '        )\n'
     '    }\n',
     '    val scrollLitePolicy = remember(compactStatsOnCover, homeCardThreeLine) {\n'
     '        resolveVideoCardScrollLiteVisualPolicy(\n'
     '            scrollLiteModeEnabled = false,\n'
     '            compactStatsOnCover = compactStatsOnCover || homeCardThreeLine\n'
     '        )\n'
     '    }\n'),
    ('ux-card-layout-publish-time',
     '    remember(showPublishTime, video.pubdate) {\n'
     '        if (!showPublishTime) {\n'
     '            "" to false\n'
     '        } else {\n'
     '            resolveCompactPublishTimeRowText(pubdate = video.pubdate) to false\n'
     '        }\n'
     '    }.let { (text, emphasize) ->\n',
     '    remember(showPublishTime, video.pubdate, homeCardThreeLine) {\n'
     '        if (!showPublishTime) {\n'
     '            "" to false\n'
     '        } else if (homeCardThreeLine) {\n'
     '            resolveCompactPublishTimeRowText(pubdate = video.pubdate).removePrefix("发布于 ") to false\n'
     '        } else {\n'
     '            resolveCompactPublishTimeRowText(pubdate = video.pubdate) to false\n'
     '        }\n'
     '    }.let { (text, emphasize) ->\n'),
    ('ux-card-layout-shell-shape',
     '        val cardShellShape = AppShapes.container(ContainerLevel.Card)\n',
     '        val cardShellShape = if (homeCardThreeLine) {\n'
     '            androidx.compose.foundation.shape.RoundedCornerShape(cardCornerRadius)\n'
     '        } else {\n'
     '            AppShapes.container(ContainerLevel.Card)\n'
     '        }\n'),
    ('ux-card-layout-cover-16-9',
     '                .aspectRatio(coverAspectRatio)\n'
     '                .clip(coverShape)\n',
     '                .aspectRatio(if (homeCardThreeLine) 16f / 9f else coverAspectRatio)\n'
     '                .clip(coverShape)\n'),
    ('ux-card-layout-title-style',
     '            style = contentTypography.title.copy(\n'
     '                color = adaptiveContentColors.titleColor\n'
     '            ),\n',
     '            style = contentTypography.title.copy(\n'
     '                color = adaptiveContentColors.titleColor\n'
     '            ).let { title ->\n'
     '                if (homeCardThreeLine) {\n'
     '                    title.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)\n'
     '                } else {\n'
     '                    title\n'
     '                }\n'
     '            },\n'),
    ('ux-card-layout-owner-time-parameter',
     '    onUpClick: ((Long) -> Unit)?,\n'
     '    modifier: Modifier = Modifier\n'
     ') {\n'
     '    val contentTypography = feedContentTypography()\n',
     '    onUpClick: ((Long) -> Unit)?,\n'
     '    modifier: Modifier = Modifier,\n'
     '    threeLineTime: String? = null\n'
     ') {\n'
     '    val contentTypography = feedContentTypography()\n'),
    ('ux-card-layout-owner-leading-condition',
     '        nameLeadingContent = if (isFollowing || video.isFollowed || video.recommendationReason.isNotBlank()) {\n',
     '        nameLeadingContent = if (isFollowing || video.isFollowed ||\n'
     '            (threeLineTime == null && video.recommendationReason.isNotBlank())) {\n'),
    ('ux-card-layout-owner-no-reason-badge',
     '                    VideoRecommendationBadge(video.recommendationReason)\n',
     '                    if (threeLineTime == null) {\n'
     '                        VideoRecommendationBadge(video.recommendationReason)\n'
     '                    }\n'),
    ('ux-card-layout-owner-time-inline',
     '        maxLines = Int.MAX_VALUE,\n'
     '        overflow = TextOverflow.Visible,\n'
     '        metaMaxLines = Int.MAX_VALUE,\n'
     '        metaOverflow = TextOverflow.Visible,\n'
     '        modifier = ownerModifier\n'
     '    )\n'
     '}\n',
     '        maxLines = if (threeLineTime != null) 1 else Int.MAX_VALUE,\n'
     '        overflow = if (threeLineTime != null) TextOverflow.Ellipsis else TextOverflow.Visible,\n'
     '        metaMaxLines = Int.MAX_VALUE,\n'
     '        metaOverflow = TextOverflow.Visible,\n'
     '        inlineTrailingContent = threeLineTime?.takeIf { it.isNotBlank() }?.let { time ->\n'
     '            {\n'
     '                AppText(\n'
     '                    text = "· " + time,\n'
     '                    style = contentTypography.author,\n'
     '                    color = metadataColors.publishTimeColor,\n'
     '                    maxLines = 1,\n'
     '                    softWrap = false,\n'
     '                    modifier = Modifier.testTag("home_video_owner_time")\n'
     '                )\n'
     '            }\n'
     '        },\n'
     '        modifier = ownerModifier\n'
     '    )\n'
     '}\n'),
    ('ux-card-layout-owner-time-call',
     '            onUpClick = onUpClick,\n'
     '            modifier = resolveVideoCardMetadataRowModifier()\n'
     '        )\n',
     '            onUpClick = onUpClick,\n'
     '            modifier = resolveVideoCardMetadataRowModifier(),\n'
     '            threeLineTime = publishTimeRowText.takeIf { homeCardThreeLine }\n'
     '        )\n'),
    ('ux-card-layout-no-publish-row',
     '            publishTimeText = publishTimeRowText,\n'
     '            emphasizePublishTime = emphasizePublishTime,\n',
     '            publishTimeText = if (homeCardThreeLine) "" else publishTimeRowText,\n'
     '            emphasizePublishTime = emphasizePublishTime,\n'),
)
# Slice K3d: the hover of the card and its quick actions. Same rules as EDITS: each `before` occurs exactly once in the
# text the layer receives (after EDITS, COVER_EDITS and LAYOUT_EDITS). The flag is read through homeCardThreeLine (K3b), and
# the OFF branch of the root keeps the vendored hover expression verbatim.
HOVER_EDITS = (
    ('ux-card-hover-imports',
     'import ' + UI + 'desktopHomeCoverFocusTarget\n',
     'import ' + UI + 'desktopHomeCoverFocusTarget\n'
     'import ' + UI + 'desktopHomeCardHoverSurface\n'),
    ('ux-card-hover-state',
     '    val homeCoverFocus = ' + UI + 'rememberDesktopHomeCoverFocus()\n',
     '    val homeCoverFocus = ' + UI + 'rememberDesktopHomeCoverFocus()\n'
     '    val homeCardHover = ' + UI + 'rememberDesktopHomeCardHover()\n'),
    ('ux-card-hover-surface',
     '            .adaptiveCardHoverEffect(shape = adaptiveHoverShape)\n',
     '            .then(\n'
     '                if (homeCardThreeLine) {\n'
     '                    Modifier.desktopHomeCardHoverSurface(homeCardHover, cornerRadius = cardCornerRadius)\n'
     '                } else {\n'
     '                    Modifier.adaptiveCardHoverEffect(shape = adaptiveHoverShape)\n'
     '                }\n'
     '            )\n'),
    ('ux-card-hover-quick-actions',
     '            }\n'
     '            }\n'
     '\n'
     '        }\n'
     '        \n'
     '        val infoSurfaceShape = remember(cardCornerRadius) {\n',
     '            }\n'
     '            }\n'
     '            ' + UI + 'DesktopHomeCardQuickActions(\n'
     '                state = homeCardHover,\n'
     '                coverFocus = homeCoverFocus,\n'
     '                onWatchLater = onWatchLater,\n'
     '                onMore = if (hasOverflowMenu) {\n'
     '                    { anchor -> openDismissMenu(anchor, null) }\n'
     '                } else {\n'
     '                    null\n'
     '                },\n'
     '                watchLaterLabel = "' + WATCH_LATER_PLAIN + '",\n'
     '                moreLabel = "' + QUICK_MORE_PLAIN + '",\n'
     '                modifier = Modifier.align(Alignment.TopEnd),\n'
     '            )\n'
     '\n'
     '        }\n'
     '        \n'
     '        val infoSurfaceShape = remember(cardCornerRadius) {\n'),
)
# Slice K3e: the copy link of the card (spec 4, menu item 5). Same rules as EDITS: each `before` occurs exactly once in the
# text the layer receives (after all of the above). The row is inside the existing menu, and homeCardCopyLink stays null while
# the flag is OFF, so nothing of this slice renders then.
COPY_EDITS = (
    ('ux-card-copy-link-state',
     '    val homeCardHover = ' + UI + 'rememberDesktopHomeCardHover()\n',
     '    val homeCardHover = ' + UI + 'rememberDesktopHomeCardHover()\n'
     '    val homeCardClipboard = com.bilipai.desktop.appearance.LocalDesktopTextClipboard.current\n'
     '    val homeCardCopyLink = if (homeCardThreeLine) {\n'
     '        ' + UI + 'desktopHomeVideoLink(video.bvid)\n'
     '    } else {\n'
     '        null\n'
     '    }\n'
     '    val copyHomeCardLink: (() -> Boolean)? = if (homeCardCopyLink == null) {\n'
     '        null\n'
     '    } else {\n'
     '        { ' + UI + 'desktopHomeCopyVideoLink(homeCardClipboard, homeCardCopyLink) }\n'
     '    }\n'),
    ('ux-card-copy-link-key',
     '                    onOpen = { triggerCardClick() },\n'
     '                    onMenu = if (hasOverflowMenu) {\n',
     '                    onOpen = { triggerCardClick() },\n'
     '                    onCopy = copyHomeCardLink,\n'
     '                    onMenu = if (hasOverflowMenu) {\n'),
    ('ux-card-copy-link-menu-row',
     '\n                // 不感兴趣 (放第一位，方便操作) -> 改回下方\n',
     '\n                // 复制链接 (Ctrl C)\n'
     '                if (copyHomeCardLink != null) {\n'
     '                    AppDropdownMenuItem(\n'
     '                        text = {\n'
     '                            AppText(\n'
     '                                "复制链接（Ctrl C）",\n'
     '                                color = MaterialTheme.colorScheme.onSurface\n'
     '                            )\n'
     '                        },\n'
     '                        onClick = {\n'
     '                            showDismissMenu = false\n'
     '                            copyHomeCardLink?.invoke()\n'
     '                        }\n'
     '                    )\n'
     '                }\n'
     '\n                // 不感兴趣 (放第一位，方便操作) -> 改回下方\n'),
)
# Slice K3f: one separator line in front of 不感兴趣 (spec 4: "分隔线后放不感兴趣"). Same rules as EDITS: the `before`
# occurs exactly once in the text this layer receives (after all of the above). The line is the kit divider and paints
# nothing while the flag is OFF, so the vendored menu keeps its three rows and the flag-on menu keeps the grouping.
# The guard keeps a card without that row, or with no visible row above it, free of a lone line: it names the three
# rows the menu can draw above 不感兴趣, in their order, and it is the only place that knows them.
SEPARATOR_EDITS = (
    ('ux-card-menu-separator-row',
     '\n                // 不感兴趣 (放第一位，方便操作) -> 改回下方\n',
     '\n                // 分隔线：把常用操作和「不感兴趣」分开 (K3f)\n'
     '                if (onDismiss != null && (onWatchLater != null || onUnfavorite != null || copyHomeCardLink != null)) {\n'
     '                    ' + UI + 'DesktopCardMenuSeparator()\n'
     '                }\n'
     '\n                // 不感兴趣 (放第一位，方便操作) -> 改回下方\n'),
)
# Slice K3i: 收藏 and 小窗播放 of the card menu (spec 4, menu items 3 and 4). Same rules as EDITS: each `before` occurs
# exactly once in the text this layer receives (after all of the above). Both actions are null while the flag is OFF or
# outside the Root Home page, so nothing of this slice renders then. miniHomeCard is composable (the card owns its pending
# mini wait), so it is read unconditionally at the top of the card, next to the copy link.
ACTION_EDITS = (
    ('ux-card-home-actions-state',
     '        { ' + UI + 'desktopHomeCopyVideoLink(homeCardClipboard, homeCardCopyLink) }\n'
     '    }\n',
     '        { ' + UI + 'desktopHomeCopyVideoLink(homeCardClipboard, homeCardCopyLink) }\n'
     '    }\n'
     '    val homeCardActionsPort = ' + UI + 'LocalDesktopHomeCardActions.current\n'
     '    val homeCardActions = if (homeCardThreeLine) homeCardActionsPort else null\n'
     '    val favoriteHomeCard: (() -> Unit)? = ' + UI + 'desktopHomeCardFavorite(homeCardActions, video)\n'
     '    val miniHomeCard: (() -> Unit)? = ' + UI + 'desktopHomeCardMini(homeCardActions, video)\n'),
    ('ux-card-home-actions-menu-rows',
     '\n                // 复制链接 (Ctrl C)\n',
     '\n                // 收藏 (原云端收藏夹抽屉, K3i)\n'
     '                if (favoriteHomeCard != null) {\n'
     '                    AppDropdownMenuItem(\n'
     '                        text = {\n'
     '                            AppText(\n'
     '                                "收藏",\n'
     '                                color = MaterialTheme.colorScheme.onSurface\n'
     '                            )\n'
     '                        },\n'
     '                        onClick = {\n'
     '                            showDismissMenu = false\n'
     '                            favoriteHomeCard?.invoke()\n'
     '                        }\n'
     '                    )\n'
     '                }\n'
     '\n                // 小窗播放 (现有播放器小窗, K3i)\n'
     '                if (miniHomeCard != null) {\n'
     '                    AppDropdownMenuItem(\n'
     '                        text = {\n'
     '                            AppText(\n'
     '                                "小窗播放",\n'
     '                                color = MaterialTheme.colorScheme.onSurface\n'
     '                            )\n'
     '                        },\n'
     '                        onClick = {\n'
     '                            showDismissMenu = false\n'
     '                            miniHomeCard?.invoke()\n'
     '                        }\n'
     '                    )\n'
     '                }\n'
     '\n                // 复制链接 (Ctrl C)\n'),
)
ALL_EDITS = EDITS + COVER_EDITS + LAYOUT_EDITS + HOVER_EDITS + COPY_EDITS + SEPARATOR_EDITS + ACTION_EDITS

# After the layer none of these vendored lines may remain in the file: the menu imports and the overflow box size line.
GONE = (
    'import com.android.purebilibili.core.ui.components.AppDropdownMenu\n',
    'import com.android.purebilibili.core.ui.components.AppDropdownMenuItem\n',
    '                            .size(AppChromeSizeTokens.MinimumTouchTarget)\n                            .semantics { contentDescription = "更多操作" }\n',
)
# Names the layer introduces into the generated text. None may exist in the text it receives.
INTRODUCED = ('DesktopCardContextMenu', 'DesktopCardContextMenuItem', 'desktopCardSecondaryClick', 'desktopCardHandCursor',
              'desktopCardMenuLabel', 'desktopCardOverflowTarget')
COVER_INTRODUCED = ('desktopHomeCoverFocusTarget', 'desktopHomeCoverFocusRing', 'rememberDesktopHomeCoverFocus', 'homeCoverFocus')
LAYOUT_INTRODUCED = ('desktopCardInteractionsOn', 'homeCardThreeLine', 'threeLineTime', 'home_video_owner_time')
HOVER_INTRODUCED = ('desktopHomeCardHoverSurface', 'rememberDesktopHomeCardHover', 'homeCardHover', 'DesktopHomeCardQuickActions')
COPY_INTRODUCED = ('desktopHomeVideoLink', 'desktopHomeCopyVideoLink', 'homeCardClipboard', 'homeCardCopyLink',
                   'copyHomeCardLink')
SEPARATOR_INTRODUCED = ('DesktopCardMenuSeparator',)
ACTION_INTRODUCED = ('LocalDesktopHomeCardActions', 'desktopHomeCardFavorite', 'desktopHomeCardMini', 'homeCardActions',
                     'favoriteHomeCard', 'miniHomeCard')


def sha(text):
    return hashlib.sha256(text.encode('utf-8')).hexdigest()


def need(condition, message):
    if not condition:
        raise AssertionError(message)


def _once(text, before, after, label):
    need(text.count(before) == 1, 'Home card anchor count changed: %s (%d)' % (label, text.count(before)))
    result = text.replace(before, after, 1)
    need(result.count(after) == 1 and result.replace(after, before, 1) == text, 'Home card inverse failed: ' + label)
    return result


def apply_home_card(repo, name, text, audits):
    """The text of one emitted file with the Home card edits, or the text itself for any file the layer does not own."""
    if name != CARD:
        return text
    for introduced in INTRODUCED + COVER_INTRODUCED + LAYOUT_INTRODUCED + HOVER_INTRODUCED + COPY_INTRODUCED + SEPARATOR_INTRODUCED + ACTION_INTRODUCED:
        need(introduced not in text, 'Home card layer already applied or name taken: %s in %s' % (introduced, name))
    received = text
    for label, before, after in ALL_EDITS:
        text = _once(text, before, after, label)
        audits.append(dict(id=label, beforeSha256=sha(before), afterSha256=sha(after), inverseRestoresInput=True))
    for vendored in GONE:
        need(vendored not in text, 'A vendored Home card line remains in %s: %s' % (name, vendored.strip().splitlines()[0]))
    need(reverse(name, text) == received, 'Home card layer is not reversible: ' + name)
    return text


def reverse(name, text):
    """The text before the layer: the edits undone in the opposite order, each found exactly once."""
    need(name == CARD, 'Home card layer does not own ' + name)
    for label, before, after in reversed(ALL_EDITS):
        need(text.count(after) == 1, 'Home card replacement count changed: %s (%d)' % (label, text.count(after)))
        text = text.replace(after, before, 1)
    return text
