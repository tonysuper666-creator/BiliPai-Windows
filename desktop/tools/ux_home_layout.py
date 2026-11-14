"""Desktop Home density layer on the generated Home text (desktop UX program, slice H1, spec 3.6).

The sole Home producer (extract-upstream-home-page.py) calls apply_home_layout once per emitted file, after every
other layer, through one hook line (the v043_home_recommendation_badges pattern). The layer touches two generated
files and nothing else:

  DesktopOriginalHomeScreen.kt  six exact-once replacements
    * the feed content width and the delegation: one value, desktopHomeDelegated, is declared in front of the width. It
      says whether the vendored layout keeps the feed (the single-column display mode, or a stored column count, spec 3.6:
      a pinned count and the single-column mode delegate to the original function). It is handed to the feed width, to
      both card layouts and to the category page, so a delegated feed is the vendored feed in its width, columns and
      cards. minOf(windowSizeClass.widthDp, resolveHomeFeedMaxContentWidth()) becomes
      desktopHomeFeedContentWidth(windowSizeClass.widthDp, delegated = desktopHomeDelegated), the width the rail leaves
      (held at the vendored cap when the feed is delegated);
    * the grid column count: the remember( header of gridColumns gains the home_layout flag as a key (read in
      composable scope by desktopHomeFeedLayoutKey()) and resolveHomeFeedGridColumns( becomes
      desktopResolveHomeFeedGridColumns( with layout = desktopHomeLayout first and the vendored named arguments after;
    * the card layout: both resolveHomeFeedCardLayout call sites go through desktopResolveHomeFeedCardLayout, which
      reads the flag and the feed width itself (so the remember( block of the first site is replaced whole) and gets
      the delegation;
    * the category page call: HomeCategoryPageContent gets desktopHomeDelegated = desktopHomeDelegated;
  HomeCategoryPage.kt           two exact-once replacements, the new defaulted parameter desktopHomeDelegated of
                                HomeCategoryPageContent and the third card layout call site, which passes it on.

The wrappers live in desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopHomeFeedLayout.kt and run the vendored
function itself when home_layout is CLASSIC. This is a desktop-owned layer: there is no upstream archive, no manifest
and no pinned predecessor hash. Every anchor is counted on the text it receives and must occur exactly once, so a
change of an earlier layer (v029 load, v042 search, v043 native cards and recommendation badges) that moves one of
these lines fails closed with AssertionError instead of editing nothing or editing twice. Each edit is recorded as an
audit row (the v043 shape) and is reversible: reverse() restores the received text byte for byte.

HomeHeader.kt (slice K3c, spec 2.3) is a third owned file with its own edit table, HEADER_EDITS, kept apart from the
eight H1 edits above. The desktop shell already offers the profile, the account switch and the settings in the rail,
so with home_layout DESKTOP the generated header drops its avatar and its right action (settings or inbox) in both
branches that draw them, the folded tabs row (bottom bar search) and the search row, and the search row keeps one
search entry centred in the width it gets, at most 560 dp. Every edit inserts text around vendored lines and leaves
them as they are: the flag desktopHomeHeaderDeduped is read once at the top of HomeHeader through the existing
desktopHomeFeedLayoutKey(), the avatar and the right action (with the spacer next to each) sit in
if (!desktopHomeHeaderDeduped) blocks, and the search dock modifier chooses between the centred desktop width and
the vendored .weight(1f).widthIn(max = ...) chain, which stays the CLASSIC expression word for word.

Gradle input tracking (hunk RH3b): the module is an input of both Home tasks in desktop/build.gradle.kts, because the
generated text changes when this file does.
"""
import hashlib

SCREEN = 'DesktopOriginalHomeScreen.kt'
CATEGORY = 'HomeCategoryPage.kt'
WRAPPERS = 'com.bilipai.desktop.ui.'

# (id, text received from the earlier layers, replacement). Each `before` occurs exactly once in its file.
EDITS = {
    SCREEN: (
        ('ux-home-feed-content-width',
         '    val contentWidth = if (windowSizeClass.isExpandedScreen) {\n'
         '        minOf(windowSizeClass.widthDp, resolveHomeFeedMaxContentWidth())\n',
         '    val desktopHomeDelegated = ' + WRAPPERS + 'desktopHomeFeedColumnsDelegated(\n'
         '        displayMode = displayMode,\n'
         '        fixedColumnCount = resolveHomeFeedStoredColumnCount(\n'
         '            widthSizeClass = windowSizeClass.widthSizeClass,\n'
         '            compactColumnCount = homeSettings.gridColumnCountCompact,\n'
         '            defaultColumnCount = homeSettings.gridColumnCount,\n'
         '        ),\n'
         '    )\n'
         '    val contentWidth = if (windowSizeClass.isExpandedScreen) {\n'
         '        ' + WRAPPERS + 'desktopHomeFeedContentWidth(windowSizeClass.widthDp, delegated = desktopHomeDelegated)\n'),
        ('ux-home-grid-columns-key',
         '    val gridColumns = remember(\n        contentWidth,\n',
         '    val desktopHomeLayout = ' + WRAPPERS + 'desktopHomeFeedLayoutKey()\n'
         '    val gridColumns = remember(\n        desktopHomeLayout,\n        contentWidth,\n'),
        ('ux-home-grid-columns-delegate',
         '        resolveHomeFeedGridColumns(\n            contentWidthDp = contentWidth.value.toInt(),\n',
         '        ' + WRAPPERS + 'desktopResolveHomeFeedGridColumns(\n            layout = desktopHomeLayout,\n'
         '            contentWidthDp = contentWidth.value.toInt(),\n'),
        ('ux-home-card-layout-remembered',
         '    val homeFeedCardLayout = remember(\n'
         '        homeFeedCardStyle,\n'
         '        effectiveGridColumns,\n'
         '        windowSizeClass.widthSizeClass,\n'
         '    ) {\n'
         '        resolveHomeFeedCardLayout(\n'
         '            style = homeFeedCardStyle,\n'
         '            gridColumns = effectiveGridColumns,\n'
         '            widthSizeClass = windowSizeClass.widthSizeClass,\n'
         '        )\n'
         '    }\n',
         '    val homeFeedCardLayout = ' + WRAPPERS + 'desktopResolveHomeFeedCardLayout(\n'
         '        style = homeFeedCardStyle,\n'
         '        gridColumns = effectiveGridColumns,\n'
         '        widthSizeClass = windowSizeClass.widthSizeClass,\n'
         '        delegated = desktopHomeDelegated,\n'
         '    )\n'),
        ('ux-home-card-layout-feed-box',
         '                            val homeFeedCardLayout = resolveHomeFeedCardLayout(\n'
         '                                style = homeFeedCardStyle,\n'
         '                                gridColumns = effectiveGridColumns,\n'
         '                                widthSizeClass = windowSizeClass.widthSizeClass,\n'
         '                            )\n',
         '                            val homeFeedCardLayout = ' + WRAPPERS + 'desktopResolveHomeFeedCardLayout(\n'
         '                                style = homeFeedCardStyle,\n'
         '                                gridColumns = effectiveGridColumns,\n'
         '                                widthSizeClass = windowSizeClass.widthSizeClass,\n'
         '                                delegated = desktopHomeDelegated,\n'
         '                            )\n'),
        ('ux-home-category-delegated-argument',
         '                                 HomeCategoryPageContent(\n'
         '                                     category = category,\n',
         '                                 HomeCategoryPageContent(\n'
         '                                     desktopHomeDelegated = desktopHomeDelegated,\n'
         '                                     category = category,\n'),
    ),
    CATEGORY: (
        ('ux-home-category-delegated-parameter',
         '    longPressCallback: ((VideoItem) -> Unit)? = null, // [Feature] Long Press\n'
         '    displayMode: Int,\n',
         '    longPressCallback: ((VideoItem) -> Unit)? = null, // [Feature] Long Press\n'
         '    desktopHomeDelegated: Boolean = false,\n'
         '    displayMode: Int,\n'),
        ('ux-home-card-layout-category',
         '    val cardLayout = remember(homeFeedCardStyle, gridColumns, widthSizeClass) {\n'
         '        resolveHomeFeedCardLayout(\n'
         '            style = homeFeedCardStyle,\n'
         '            gridColumns = gridColumns,\n'
         '            widthSizeClass = widthSizeClass,\n'
         '        )\n'
         '    }\n',
         '    val cardLayout = ' + WRAPPERS + 'desktopResolveHomeFeedCardLayout(\n'
         '        style = homeFeedCardStyle,\n'
         '        gridColumns = gridColumns,\n'
         '        widthSizeClass = widthSizeClass,\n'
         '        delegated = desktopHomeDelegated,\n'
         '    )\n'),
    ),
}

HEADER = 'HomeHeader.kt'
DEDUPED = 'desktopHomeHeaderDeduped'
_OFF = 'if (!' + DEDUPED + ') {\n'
_END = '} // ' + DEDUPED + '\n'
_AVATAR_TAP = 'performHomeTopBarTap(haptic = haptic, onClick = onAvatarClick)'
_GAP = 'Spacer(modifier = Modifier.width(resolveHomeTopEdgeControlGap(topChromePolicy)))\n'
_DOCK_WIDTH = ('                                    .weight(1f)\n'
               '                                    .widthIn(max = AppSpacingTokens.TripleExtraLarge * 13 + AppSpacingTokens.Large)\n')

# HomeHeader.kt (K3c): (id, text received, replacement), each `before` exactly once. Only insertions around vendored text.
HEADER_EDITS = (
    ('ux-home-header-flag',
     ') {\n    val topChromePolicy = rememberAppTopChromePolicy()\n',
     ') {\n    val ' + DEDUPED + ' = ' + WRAPPERS + 'desktopHomeFeedLayoutKey() ==\n'
     '        com.bilipai.desktop.appearance.DesktopWindowsUxHomeLayout.DESKTOP\n'
     '    val topChromePolicy = rememberAppTopChromePolicy()\n'),
    ('ux-home-header-folded-avatar-open',
     '                Box(\n                    modifier = Modifier\n                        .size(48.dp)\n'
     '                        .clickable { ' + _AVATAR_TAP + ' }\n',
     '                ' + _OFF +
     '                Box(\n                    modifier = Modifier\n                        .size(48.dp)\n'
     '                        .clickable { ' + _AVATAR_TAP + ' }\n'),
    ('ux-home-header-folded-avatar-close-action-open',
     '                Box(modifier = Modifier.weight(1f)) {\n                    renderTabs()\n                }\n',
     '                ' + _END +
     '                Box(modifier = Modifier.weight(1f)) {\n                    renderTabs()\n                }\n'
     '                ' + _OFF),
    ('ux-home-header-folded-action-close',
     '            }\n        } else {\n            renderTabs()\n        }\n',
     '                ' + _END +
     '            }\n        } else {\n            renderTabs()\n        }\n'),
    ('ux-home-header-search-avatar-open',
     '                        ) {\n                            Box(\n                                modifier = Modifier\n'
     '                                    .size(resolveHomeTopAvatarOuterSize())\n',
     '                        ) {\n                            ' + _OFF +
     '                            Box(\n                                modifier = Modifier\n'
     '                                    .size(resolveHomeTopAvatarOuterSize())\n'),
    ('ux-home-header-search-avatar-close-dock-width',
     '                            ' + _GAP + '\n'
     '                            BottomBarMatchedReusableLiquidDock(\n'
     '                                shape = CircleShape,\n'
     '                                modifier = Modifier\n' + _DOCK_WIDTH,
     '                            ' + _GAP +
     '                            ' + _END + '\n'
     '                            BottomBarMatchedReusableLiquidDock(\n'
     '                                shape = CircleShape,\n'
     '                                modifier = Modifier\n'
     '                                    .then(\n'
     '                                        if (' + DEDUPED + ') {\n'
     '                                            Modifier\n'
     '                                                .weight(1f)\n'
     '                                                .wrapContentWidth(Alignment.CenterHorizontally)\n'
     '                                                .widthIn(max = 560.dp)\n'
     '                                                .fillMaxWidth()\n'
     '                                        } else {\n'
     '                                            Modifier\n' +
     ''.join('            ' + line + '\n' for line in _DOCK_WIDTH.splitlines()) +
     '                                        }\n'
     '                                    )\n'),
    ('ux-home-header-search-action-open',
     '                            ' + _GAP + '\n                            val topRightActionButtonSize = ',
     '                            ' + _OFF +
     '                            ' + _GAP + '\n                            val topRightActionButtonSize = '),
    ('ux-home-header-search-action-close',
     '                                }\n                            }\n                        }\n                    }\n\n'
     '                    if (!hideTopTabs && topLayoutOrder == HomeTopLayoutOrder.SEARCH_THEN_TABS) {\n',
     '                                }\n                            }\n                            ' + _END +
     '                        }\n                    }\n\n'
     '                    if (!hideTopTabs && topLayoutOrder == HomeTopLayoutOrder.SEARCH_THEN_TABS) {\n'),
)
# Names the header edits introduce. None may exist in the HomeHeader text the layer receives.
HEADER_INTRODUCED = (DEDUPED, 'desktopHomeFeedLayoutKey', 'DesktopWindowsUxHomeLayout')

# After the layer none of these vendored calls may remain in the file: every call site went through a wrapper.
GONE = {
    SCREEN: ('resolveHomeFeedMaxContentWidth(', ' resolveHomeFeedGridColumns(', ' resolveHomeFeedCardLayout('),
    CATEGORY: (' resolveHomeFeedCardLayout(',),
}
# Names the layer introduces into the generated text. None may exist in the text it receives.
INTRODUCED = ('desktopHomeLayout', 'desktopHomeDelegated', 'desktopHomeFeedColumnsDelegated', 'desktopHomeFeedContentWidth',
              'desktopHomeFeedLayoutKey', 'desktopResolveHomeFeedGridColumns', 'desktopResolveHomeFeedCardLayout')


def sha(text):
    return hashlib.sha256(text.encode('utf-8')).hexdigest()


def need(condition, message):
    if not condition:
        raise AssertionError(message)


def _once(text, before, after, label):
    need(text.count(before) == 1, 'Home density anchor count changed: %s (%d)' % (label, text.count(before)))
    result = text.replace(before, after, 1)
    need(result.count(after) == 1 and result.replace(after, before, 1) == text, 'Home density inverse failed: ' + label)
    return result


def apply_home_layout(repo, name, text, audits):
    """The text of one emitted file with the Home desktop edits, or the text itself for any file the layer does not own."""
    if name == HEADER:
        return _apply(name, text, audits, HEADER_EDITS, HEADER_INTRODUCED, ())
    edits = EDITS.get(name)
    if edits is None:
        return text
    return _apply(name, text, audits, edits, INTRODUCED, GONE[name])


def _apply(name, text, audits, edits, introduced_names, gone):
    for introduced in introduced_names:
        need(introduced not in text, 'Home density layer already applied or name taken: %s in %s' % (introduced, name))
    received = text
    for label, before, after in edits:
        text = _once(text, before, after, label)
        audits.append(dict(id=label, beforeSha256=sha(before), afterSha256=sha(after), inverseRestoresInput=True))
    for vendored in gone:
        need(vendored not in text, 'A vendored Home layout call remains in %s: %s' % (name, vendored.strip()))
    need(reverse(name, text) == received, 'Home density layer is not reversible: ' + name)
    return text


def reverse(name, text):
    """The text before the layer: the edits undone in the opposite order, each found exactly once."""
    for label, before, after in reversed(HEADER_EDITS if name == HEADER else EDITS[name]):
        need(text.count(after) == 1, 'Home density replacement count changed: %s (%d)' % (label, text.count(after)))
        text = text.replace(after, before, 1)
    return text
