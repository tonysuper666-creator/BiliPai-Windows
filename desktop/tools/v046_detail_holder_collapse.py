"""Detail holder collapse family, the part with a Windows reproduction (roadmap slice plr-holder-collapse-family, slice #55).

The roadmap entry is a phone-style family: eight upstream entries over the video detail state holder, the inline collapse state,
the auto-pause policy, the vertical priority pager gesture and the tablet layout (0.3.0 .. d6bf8b16). Its first step is
"reproduce or skip": for every entry decide whether the Windows tree reproduces the problem, and carry only those. The decisions
are recorded in DECISIONS (and in the archive manifest); this module carries the three parts that DO reproduce on Windows and
leaves everything that needs the v0.3 pause-hide model of the phone detail for a follow-up slice.

CARRIED (Windows reproduction, all text taken from the v0.4.6 blobs of d6bf8b1655ae910999860bf13e65c8fcea5b345b):

  Fullscreen exit scroll recovery (v042-fullscreen-exit-scroll-recovery, upstream e5965775 / 4c3ec3e1 on top of the 0.3.2 block)
      VideoDetailInlineScrollRecovery.kt   new in v0.4.2, emitted verbatim by the holder producer.
      VideoDetailScreenStateHolder.kt      the v0.4.6 LaunchedEffect that calls it when fullscreen ends (one counted insertion
                                           before the local back target; the pinned tree has no predecessor of that block, so the
                                           whole v0.4.6 block is the hunk). The retained inline lists and pager keep their saved
                                           positions across fullscreen; a scroll mutation of the old attachment must not block the
                                           next click or scroll after the nodes are attached again.
  Tablet secondary pane tab synchronisation (v046-collapse-height-shared, the TabletVideoLayout half of upstream 11a7bae7)
      TabletVideoLayout.kt                 the wide-window detail layout of Windows (TabletSecondaryContent, reached through
                                           LargeScreenVideoLayout and TabletVideoInfoPane): the pager state is keyed by the video,
                                           the saved tab is clamped, requests drive the pager once through selectSecondaryTab
                                           and only the SETTLED page is persisted. Seven counted hunks, each `after` a verbatim
                                           slice of the v0.4.6 TabletVideoLayout.kt of the v046-comment-list archive.
  Compact comment titles (post046-shared-collapse-detail-layouts, the title half of upstream d6bf8b16)
      TabletVideoLayout.kt, TabletCinemaLayout.kt   compactTypography = true on the comment sort header (verbatim hunks).
      DesktopOriginalVideoContentSection.kt         compactSortHeader = true on the two VideoCommentTab calls (verbatim hunks) and
                                                    compactTypography = true on the inline CommentListHeader, which is what
                                                    VideoCommentFloatingHeader(compactTitleTypography = true) forwards in v0.4.6.
      The definers (CommentListHeader/CommentSortHeader/VideoCommentTab/VideoCommentFloatingHeader parameters) and the other hosts
      (comment sheet host, Bangumi panel) already carry the v0.4.6 shape.

NOT CARRIED (see DECISIONS): the pause-hide model and the shared collapse height of the phone detail, the auto-pause decision, the
touch-only vertical priority pager arbitration, and the geometry parts that the Windows tree already satisfies.

The hunks are COUNTED, not hash pinned: the producer text they run on is the output of many Windows adaptations, so each anchor
must occur exactly once and each layer records an inverse proof; every `after` is a verbatim slice of a v0.4.6 blob (checked by
the source test; the holder block against the git object, the tablet and content text against the v046-comment-list archive).
"""
from pathlib import Path
import hashlib
import json
import os

from v044_favorite_detail import inverse_proof

COMMIT = 'd6bf8b1655ae910999860bf13e65c8fcea5b345b'
ARCHIVE = 'desktop/upstream-slices/v046-detail-holder-collapse'
MANIFEST_SHA256 = '0c54a94a740600a2b947908bd998a64e16495efef46fd29654e9503904c5a8aa'
PROOF_DIRECTORY = 'v046-detail-holder-collapse-proofs'
APP = 'app/src/main/java/com/android/purebilibili/'
SCREEN = APP + 'feature/video/screen/'
TEST_SCREEN = 'app/src/test/java/com/android/purebilibili/feature/video/screen/'

RECOVERY_FILE = 'VideoDetailInlineScrollRecovery.kt'
RECOVERY_TEST_FILE = 'VideoDetailInlineScrollRecoveryTest.kt'
EXPECTED = {RECOVERY_FILE, RECOVERY_TEST_FILE}
UPSTREAM_PATHS = {RECOVERY_FILE: SCREEN + RECOVERY_FILE, RECOVERY_TEST_FILE: TEST_SCREEN + RECOVERY_TEST_FILE}
RECOVERY_EMIT_REL = 'com/android/purebilibili/feature/video/screen/' + RECOVERY_FILE
RECOVERY_TEST_CASES = 2
# desktop-owned verbatim copy of the upstream unit test (the holder producer has no test root of its own)
RECOVERY_TEST_COPY = ('desktop/src/test/kotlin/com/android/purebilibili/feature/video/screen/' + RECOVERY_TEST_FILE)

HOLDER_PATH = SCREEN + 'VideoDetailScreenStateHolder.kt'
HOLDER_UPSTREAM_BLOB = 'a21412fcdaf7895c4edea200c0234de5aa82bed2'
HOLDER_EMIT_REL = 'com/android/purebilibili/feature/video/screen/VideoDetailScreenStateHolder.kt'
TABLET_PATH = SCREEN + 'TabletVideoLayout.kt'
CINEMA_PATH = SCREEN + 'TabletCinemaLayout.kt'
CONTENT_PATH = SCREEN + 'VideoContentSection.kt'
FLOATING_HEADER_PATH = APP + 'feature/video/ui/components/VideoCommentFloatingHeader.kt'
COMMENT_LIST_ARCHIVE = 'desktop/upstream-slices/v046-comment-list'

HOLDER_ANCHOR = '    val localBackTarget = resolveVideoDetailLocalBackTarget(\n'
HOLDER_BLOCK = '''    // Fullscreen removes the inline scrollable nodes while retaining their states. A scroll
    // mutation from the old attachment must not suppress clicks/scrolls after reattachment.
    // stopScroll cancels the old mutation without changing the saved item/page position.
    var inlineContentWasFullscreen by remember(currentBvid) {
        mutableStateOf(isFullscreenMode || isPortraitFullscreen)
    }
    LaunchedEffect(currentBvid, isFullscreenMode, isPortraitFullscreen) {
        val fullscreenNow = isFullscreenMode || isPortraitFullscreen
        val returningToInline = inlineContentWasFullscreen && !fullscreenNow
        inlineContentWasFullscreen = fullscreenNow
        com.android.purebilibili.core.util.Logger.d("VideoInputTrace") {
            "presentation_changed fullscreen=$fullscreenNow returning=$returningToInline " +
                "introScrolling=${introListState.isScrollInProgress} commentScrolling=${commentListState.isScrollInProgress} " +
                "pagerScrolling=${videoContentPagerState.isScrollInProgress} phase=$continuousPlayerPhase"
        }
        if (returningToInline) {
            stopVideoDetailInlineScrolls(
                introListState = introListState,
                commentListState = commentListState,
                pagerState = videoContentPagerState,
            )
            com.android.purebilibili.core.util.Logger.d(
                "VideoDetailScreen",
                "Inline input restored after fullscreen: introScrolling=${introListState.isScrollInProgress} " +
                    "commentScrolling=${commentListState.isScrollInProgress} pagerScrolling=${videoContentPagerState.isScrollInProgress}",
            )
        }
    }

'''
HOLDER_BLOCK_SHA256 = 'a33082bb4b42f59fee585ab40f46b82ac750ece00accda48a6b7a3f4231c76f2'

# TabletVideoLayout.kt: TabletSecondaryContent. before = the pinned Windows text, after = the v0.4.6 text.
TABLET_HUNKS = (
    dict(
        label='tablet import: the Flow collect extension of the settled page effect',
        before='import com.android.purebilibili.navigation.animatePagerSelection\n',
        after='import com.android.purebilibili.navigation.animatePagerSelection\nimport kotlinx.coroutines.flow.collect\n',
    ),
    dict(
        label='tablet pager state keyed by the video and clamped to the tab list',
        before=(
            '    val pagerState = rememberPagerState(\n'
            '        initialPage = selectedTab,\n'
            '        pageCount = { tabs.size }\n'
            '    )\n'
        ),
        after=(
            '    val pagerState = key(success.info.bvid, fixedTab, relatedTabFirst) {\n'
            '        rememberPagerState(\n'
            '            initialPage = selectedTab.coerceIn(0, tabs.lastIndex),\n'
            '            pageCount = { tabs.size },\n'
            '        )\n'
            '    }\n'
        ),
    ),
    dict(
        label='tablet requests drive the pager once and only the settled page is persisted',
        before=(
            '    LaunchedEffect(selectedTab) {\n'
            '        if (pagerState.currentPage != selectedTab) {\n'
            '            animatePagerSelection(pagerState, selectedTab)\n'
            '        }\n'
            '    }\n'
            '    LaunchedEffect(pagerState.currentPage) {\n'
            '        if (selectedTab != pagerState.currentPage) {\n'
            '            selectedTab = pagerState.currentPage\n'
            '        }\n'
            '    }\n'
        ),
        after=(
            '    // All material modes share this synchronization: requests drive the pager once;\n'
            '    // only its settled page is persisted. Intermediate pages never become new requests.\n'
            '    fun selectSecondaryTab(index: Int) {\n'
            '        scope.launch { animatePagerSelection(pagerState, index) }\n'
            '    }\n'
            '    LaunchedEffect(pagerState) {\n'
            '        snapshotFlow { pagerState.settledPage }.collect { settledPage ->\n'
            '            selectedTab = settledPage\n'
            '        }\n'
            '    }\n'
        ),
    ),
    dict(
        label='tablet requested tab goes through the pager',
        before='            if (index >= 0) selectedTab = index\n',
        after='            if (index >= 0) selectSecondaryTab(index)\n',
    ),
    dict(
        label='tablet comment thread selects the comment tab through the pager',
        before='                ?.let { selectedTab = it }\n',
        after='                ?.let { selectSecondaryTab(it) }\n',
    ),
    dict(
        label='tablet collapsed pane buttons select through the pager',
        before=(
            '                    selectedTab = index\n'
            '                    onPaneModeChange(TabletSecondaryPaneMode.COMPACT)\n'
        ),
        after=(
            '                    selectSecondaryTab(index)\n'
            '                    onPaneModeChange(TabletSecondaryPaneMode.COMPACT)\n'
        ),
    ),
    dict(
        label='tablet tab row selects through the pager',
        before=(
            '                    onSelected = { index ->\n'
            '                        scope.launch { animatePagerSelection(pagerState, index) }\n'
            '                    },\n'
        ),
        after='                    onSelected = ::selectSecondaryTab,\n',
    ),
    dict(
        label='tablet comment sort header uses the compact typography',
        before=(
            '                                onSearchClick = { showCommentSearchSheet = true },\n'
            '                            )\n'
        ),
        after=(
            '                                onSearchClick = { showCommentSearchSheet = true },\n'
            '                                compactTypography = true,\n'
            '                            )\n'
        ),
    ),
)
CINEMA_HUNKS = (
    dict(
        label='cinema comment sort header uses the compact typography',
        before=(
            '                onSearchClick = { showCommentSearchSheet = true },\n'
            '            )\n'
        ),
        after=(
            '                onSearchClick = { showCommentSearchSheet = true },\n'
            '                compactTypography = true,\n'
            '            )\n'
        ),
    ),
)
CONTENT_HUNKS = (
    dict(
        label='content inline comment tab uses the compact sort header',
        before=(
            '                        showSortControlInHeader = true,\n'
            '                        showHeader = !immersiveVideoContentChromeEnabled,\n'
        ),
        after=(
            '                        showSortControlInHeader = true,\n'
            '                        // 详情页评论标题在玻璃开/关时统一使用紧凑字阶；仅影响这一处调用。\n'
            '                        compactSortHeader = true,\n'
            '                        showHeader = !immersiveVideoContentChromeEnabled,\n'
        ),
        # the v0.4.6 text continues with the glass comment about the sibling overlay; Windows keeps its own next argument
        verbatim_part=(
            '                        showSortControlInHeader = true,\n'
            '                        // 详情页评论标题在玻璃开/关时统一使用紧凑字阶；仅影响这一处调用。\n'
            '                        compactSortHeader = true,\n'
        ),
    ),
    dict(
        label='content landscape comment panel list uses the compact sort header',
        before=(
            '                            lightweightCommentRendering = false,\n'
            '                        )\n'
        ),
        after=(
            '                            lightweightCommentRendering = false,\n'
            '                            compactSortHeader = true,\n'
            '                        )\n'
        ),
        verbatim_part=(
            '                            lightweightCommentRendering = false,\n'
            '                            compactSortHeader = true,\n'
            '                        )\n'
        ),
    ),
    dict(
        label='content inline floating comment title uses the compact typography',
        before=(
            '                    CommentListHeader(\n'
            '                        count = replyCount,\n'
            '                        title = "${sortMode.label}评论",\n'
            '                        modifier = Modifier.align(Alignment.TopStart),\n'
            '                    )\n'
        ),
        after=(
            '                    CommentListHeader(\n'
            '                        count = replyCount,\n'
            '                        title = "${sortMode.label}评论",\n'
            '                        // v0.4.6 VideoCommentFloatingHeader forwards compactTitleTypography = true to this parameter.\n'
            '                        compactTypography = true,\n'
            '                        modifier = Modifier.align(Alignment.TopStart),\n'
            '                    )\n'
        ),
        windows_adaptation=True,
    ),
)
FLOATING_FORWARD = 'compactTypography = compactTitleTypography,'
FLOATING_CALL_ARGUMENT = 'compactTitleTypography = true,'

# The eight roadmap entries and what this slice did with each. kind: carried | partial | already-carried | not-needed | deferred
DECISIONS = (
    dict(entry='v030-detail-chrome-hidden-paused-collapsed', kind='deferred',
         reason='needs hidePausedPlayerForComments (the v0.3 pause-hide model: isPlaybackPausedForInlineLayout, '
                'shouldHidePausedInlinePlayerForComments, resolveInlinePlayerCollapsedViewportHeightDp), which the pinned '
                'v0.2.5 holder and PortraitDetailPresentationPolicy do not have; carried by the follow-up phone-detail slice'),
    dict(entry='v030-detail-glass-gated-chrome-blur', kind='already-carried',
         reason='the retained v0.3.4/v0.3.6 VideoContentSection already has chromeBlurActive = !isPlayerCollapsed || '
                'liquidGlassEnabled and the glass tab row condition; no change'),
    dict(entry='v042-detail-cover-169-skeleton', kind='not-needed',
         reason='Windows keeps the v0.2.5 geometry (preferLayoutWidth16x9Inline, never the 0.3.x 16:10 detour) and the skeleton '
                'is always animated (animated = true), which is the v0.4.6 result whenever card transitions are off (root '
                'decision D1); the 16:10 to generic rename belongs to the phone-detail follow-up'),
    dict(entry='v042-fullscreen-exit-scroll-recovery', kind='carried',
         reason='VideoDetailInlineScrollRecovery.kt verbatim plus the v0.4.6 holder block that calls it'),
    dict(entry='v042-compact-comment-autopause', kind='deferred',
         reason='no Windows reproduction: compactInlinePlayerForCommentTab is true only on foldable cover windows, so the '
                'pinned auto-pause decision already equals resolvePlayerCollapsedForAutoPause; the policy functions arrive '
                'with the phone-detail follow-up'),
    dict(entry='v042-glass-pager-vertical-priority', kind='deferred',
         reason='a touch or pen gesture arbitration (the desktop inputs are the mouse wheel and the tab row; inferred from '
                'Compose behaviour, not exercised here), and it needs the Miuix branch of VerticalPriorityPagerGesture '
                '(another upstream commit), glassTabFlingBehavior and tabSelectionAnimationSpec of the glass tab rail '
                'slice; the sensor orientation hunk is phone only'),
    dict(entry='v046-collapse-height-shared', kind='partial',
         reason='the TabletVideoLayout half of upstream 11a7bae7 (secondary pane tab synchronisation) is carried; the shared '
                'collapse height of the phone detail (holder, PortraitDetailPresentationPolicy, VideoDetailPhoneContent, '
                'VideoDetailInlineCollapseState) is deferred'),
    dict(entry='post046-shared-collapse-detail-layouts', kind='partial',
         reason='the compact comment title half of upstream d6bf8b16 is carried; the collapse semantics half '
                '(layoutReconciled, shared viewport height) is deferred with the phone-detail follow-up'),
)
FOLLOW_UPS = (
    'plr-holder-collapse-core: the phone detail pause-hide model and shared collapse height (entries 1, 5, 7, 8 collapse half): '
    'PlayerCollapseAutoPausePolicy and VideoDetailInlineCollapseState as whole v0.4.6 files (direct mode, exclude), '
    'PortraitDetailPresentationPolicy v0.4.6 (renames resolveLandscapeDetailPlayerContentHeightPx), the holder collapse region '
    '(about 380 lines), VideoDetailPhoneContent without onIntroScrollThresholdChange, VideoContentSection',
    'the danmaku tap capture of upstream 11a7bae7 (PlayerGestureHandler.captureSingleTap, VideoPlayerSection) and the danmaku '
    'click listener DisposableEffect of VideoDetailPlayerSettingsOverlayAdapter are not assigned to any roadmap entry',
    'the touch-only vertical priority arbitration (entry 6) with the Miuix branch of VerticalPriorityPagerGesture, together '
    'with the glass tab rail slice',
)


def sha(value):
    return hashlib.sha256(value.encode('utf8') if isinstance(value, str) else value).hexdigest()


def need(condition, message):
    if not condition:
        raise ValueError(message)


def once(text, before, after, label):
    need(text.count(before) == 1, 'Detail holder collapse anchor changed: ' + label + ' (' + str(text.count(before)) + ')')
    need(after not in text, 'Detail holder collapse hunk already applied: ' + label)
    return text.replace(before, after, 1)


def fixed(repo):
    root = Path(repo)/ARCHIVE
    need(root.is_dir() and not root.is_symlink(), 'Missing detail holder collapse inputs')
    need({p.name for p in root.iterdir()} == EXPECTED | {'manifest.json', 'LICENSE'}, 'Detail holder collapse input leaves changed')
    raw = (root/'manifest.json').read_bytes()
    need(sha(raw) == MANIFEST_SHA256, 'Detail holder collapse manifest changed')
    manifest = json.loads(raw)
    need((manifest['repository'], manifest['tag'], manifest['commit'], manifest['hashNormalization']) ==
         ('jay3-yy/BiliPai', 'v0.4.6', COMMIT, 'raw'), 'Detail holder collapse source identity changed')
    need(len(manifest['files']) == len(EXPECTED) and {r['filename'] for r in manifest['files']} == EXPECTED,
         'Detail holder collapse source set changed')
    sources = {}
    for row in manifest['files']:
        file = root/row['filename']
        need(file.is_file() and not file.is_symlink() and row['path'] == UPSTREAM_PATHS[row['filename']],
             'Detail holder collapse leaf changed: ' + row['filename'])
        raw_leaf = file.read_bytes()
        need(len(raw_leaf) == row['bytes'] and sha(raw_leaf) == row['sha256'], 'Detail holder collapse leaf bytes changed')
        need(hashlib.sha1(b'blob '+str(len(raw_leaf)).encode()+b'\0'+raw_leaf).hexdigest() == row['gitBlob'],
             'Detail holder collapse git blob changed')
        sources[row['filename']] = raw_leaf.decode('utf8')
    licence = root/'LICENSE'
    need(not licence.is_symlink() and sha(licence.read_bytes()) == manifest['license']['sha256'], 'Detail holder collapse LICENSE changed')
    return sources, manifest


def validate_outputs(repo, *outputs):
    fixed(repo)
    root = (Path(repo)/ARCHIVE).resolve()
    for output in outputs:
        if output is None:
            continue
        candidate = Path(output).resolve()
        need(not (candidate.is_relative_to(root) or root.is_relative_to(candidate)), 'Detail holder collapse output overlaps retained source')


def restore(value, proof):
    """Applies an inverse receipt backwards (same contract as the other v046 layers)."""
    need(sha(value) == proof['afterSHA256'], 'Detail holder collapse final output differs from its receipt')
    lines = value.splitlines(keepends=True)
    for hunk in reversed(proof['hunks']):
        first, last = hunk['afterLines']
        need(''.join(lines[first:last]) == hunk['after'], 'Detail holder collapse inverse span changed')
        lines[first:last] = hunk['before'].splitlines(keepends=True)
    result = ''.join(lines)
    need(sha(result) == proof['beforeSHA256'], 'Detail holder collapse predecessor inverse failed')
    return result


def wide(path):
    """Windows MAX_PATH: the holder producer writes its own outputs through the same extended prefix."""
    text = str(Path(path).absolute())
    prefix = chr(92)*2+'?'+chr(92)
    return Path(text if text.startswith(prefix) or os.name != 'nt' else prefix+text)


def save(repo, output, name, value):
    target = wide(Path(output)/PROOF_DIRECTORY/(name+'.json'))
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(value, ensure_ascii=False, indent=2)+'\n', encoding='utf8', newline='\n')


def apply_hunks(text, hunks):
    rows = []
    for hunk in hunks:
        text = once(text, hunk['before'], hunk['after'], hunk['label'])
        need(text.count(hunk['after']) == 1, 'Detail holder collapse hunk is not unique after applying: ' + hunk['label'])
        rows.append(dict(label=hunk['label'], before=hunk['before'], after=hunk['after'], count=1))
    return text, rows


def comment_list_sources(repo):
    from v046_comment_list import fixed as comment_list_fixed
    return comment_list_fixed(repo)[0]


def check_provenance(repo, leaf, hunks, label):
    """Every verbatim `after` is one slice of the v0.4.6 leaf of the v046-comment-list archive."""
    source = comment_list_sources(repo)[leaf]
    for hunk in hunks:
        part = hunk.get('verbatim_part', hunk['after'])
        if hunk.get('windows_adaptation'):
            continue
        need(source.count(part) >= 1, label + ' hunk is not v0.4.6 text: ' + hunk['label'])
    return source


def advance_holder(repo, before, output):
    fixed(repo)
    need(sha(HOLDER_BLOCK) == HOLDER_BLOCK_SHA256, 'Detail holder collapse block pin changed')
    need('inlineContentWasFullscreen' not in before and 'stopVideoDetailInlineScrolls' not in before,
         'Detail holder collapse block already present')
    after = once(before, HOLDER_ANCHOR, HOLDER_BLOCK+HOLDER_ANCHOR, 'fullscreen exit scroll recovery block')
    need(after.count(HOLDER_BLOCK) == 1, 'Detail holder collapse block is not unique')
    save(repo, output, 'holder-proof', dict(upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256,
        upstreamPath=HOLDER_PATH, upstreamBlob=HOLDER_UPSTREAM_BLOB, blockSHA256=HOLDER_BLOCK_SHA256,
        anchor=HOLDER_ANCHOR, blockLines=HOLDER_BLOCK.count('\n'), wholeBlockIsV046=True,
        noPredecessorInPinnedTree=True, retainedToCurrent=inverse_proof(before, after)))
    return after


def emit_recovery(repo, output):
    sources, _ = fixed(repo)
    target = wide(Path(output)/RECOVERY_EMIT_REL)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(sources[RECOVERY_FILE], encoding='utf8', newline='\n')
    save(repo, output, 'recovery-proof', dict(upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256,
        wholeOriginal=True, originalSHA256=sha(sources[RECOVERY_FILE]), emitted=RECOVERY_EMIT_REL))
    return target


def advance_layout(repo, before, output, label):
    """label: TabletVideoLayout | TabletCinemaLayout."""
    fixed(repo)
    need(label in {'TabletVideoLayout', 'TabletCinemaLayout'}, 'Unknown tablet layout')
    if label == 'TabletVideoLayout':
        hunks, leaf = TABLET_HUNKS, 'TabletVideoLayout.kt'
    else:
        hunks, leaf = CINEMA_HUNKS, 'TabletCinemaLayout.kt'
    source = check_provenance(repo, leaf, hunks, label)
    after, rows = apply_hunks(before, hunks)
    save(repo, output, label+'-proof', dict(upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256,
        upstreamPath=(TABLET_PATH if label == 'TabletVideoLayout' else CINEMA_PATH), hunks_applied=rows,
        retainedToCurrent=inverse_proof(before, after)))
    return after


def advance_content(repo, before, output):
    fixed(repo)
    source = check_provenance(repo, 'VideoContentSection.kt', CONTENT_HUNKS, 'content')
    floating = comment_list_sources(repo)['VideoCommentFloatingHeader.kt']
    need(floating.count(FLOATING_FORWARD) == 1, 'Floating header forwarding changed')
    need(source.count(FLOATING_CALL_ARGUMENT) == 1, 'v0.4.6 content section no longer passes the compact title argument')
    after, rows = apply_hunks(before, CONTENT_HUNKS)
    save(repo, output, 'content-proof', dict(upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256,
        upstreamPath=CONTENT_PATH, hunks_applied=rows, floatingHeaderForwarding=FLOATING_FORWARD,
        windowsAdaptedHunks=[h['label'] for h in CONTENT_HUNKS if h.get('windows_adaptation')],
        retainedToCurrent=inverse_proof(before, after)))
    return after


def manifest_extra():
    """The slice specific manifest keys (make-archive --extra-json)."""
    return dict(
        schemaVersion=1,
        selection='VideoDetailInlineScrollRecovery.kt (new in v0.4.2) and its unit test; the holder block, the tablet secondary '
                  'pane hunks and the compact title hunks are counted slices of blobs that other archives or the git objects hold',
        purpose='roadmap slice plr-holder-collapse-family (#55): reproduce-or-skip over eight holder, collapse and gesture entries',
        holderBlock=dict(path=HOLDER_PATH, blob=HOLDER_UPSTREAM_BLOB, sha256=HOLDER_BLOCK_SHA256, lines=HOLDER_BLOCK.count('\n')),
        countedHunks=dict(
            holder=1, tablet=len(TABLET_HUNKS), cinema=len(CINEMA_HUNKS), content=len(CONTENT_HUNKS),
            tabletAndCinemaAndContentSource=COMMENT_LIST_ARCHIVE),
        entryDecisions=[dict(d) for d in DECISIONS],
        followUps=list(FOLLOW_UPS),
        currentWindowsParent='609200ab61aa57e0bab258bfe949e6014176bedf',
        fullStableParityQualified=False,
    )
