"""v0.4.6 sheet motion and controlled dismiss on the Windows-adapted AppSheetComponents body.

The predecessor pin is the adapt_sheet body. The two-line producer header is added later by emit().
"""
from pathlib import Path
import hashlib
import json

from v044_favorite_detail import inverse_proof

COMMIT = 'd6bf8b1655ae910999860bf13e65c8fcea5b345b'
ARCHIVE = 'desktop/upstream-slices/v046-sheet-motion'
MANIFEST_SHA256 = '42178c5a93e84af2d914ac68069ff0401cb743167ddf5e0aeeeb4682ead1985f'
PREDECESSOR_SHA256 = '75c5d144d1b343bc4774569950a7e8501f94c4635b2d941c5a79e97ce5f3cbc6'
EXPECTED = {'AppSheetComponents.kt'}
OUTPUT_RELATIVE = 'com/android/purebilibili/core/ui/DesktopOriginalDetailAppSheetComponents.kt'
PROOF_NAME = 'v046-sheet-motion-proof.json'

# v0.4.6 inserts after `if (hingeSafeRegions != null || centered)`.
# adapt_sheet rewrote that condition to `if (centered)`. Same inserted lines, hinge region stays gone.
# The two miuix dismiss pairs and the material dismiss/back sites are widened so each anchor is exact-once.
MECHANICAL_REANCHORS = (
    dict(label='centered-dialog-visible-guard',
         v046Anchor='if (hingeSafeRegions != null || centered)',
         windowsAnchor='if (centered) { Dialog(',
         decision='mechanical re-anchor; the hinge region is not restored'),
    dict(label='window-dialog-controlled-dismiss',
         v046Anchor='onDismissRequest = { show = false }, onDismissFinished = { latestDismiss() }',
         windowsAnchor='WindowDialog maxWidth/largeScreen/insideMargin plus that pair',
         decision='widened exact-once anchor; both miuix sites take the same v0.4.6 text'),
    dict(label='window-bottom-sheet-controlled-dismiss',
         v046Anchor='onDismissRequest = { show = false }, onDismissFinished = { latestDismiss() }',
         windowsAnchor='WindowBottomSheet show/modifier/backgroundColor/insideMargin plus that pair',
         decision='widened exact-once anchor; both miuix sites take the same v0.4.6 text'),
    dict(label='material-on-dismiss-request',
         v046Anchor='onDismissRequest = onDismissRequest',
         windowsAnchor='onDismissRequest = onDismissRequest followed by modifier = modifier',
         decision='the centered Dialog keeps onDismissRequest; only ModalBottomSheet changes'),
    dict(label='material-back-handler',
         v046Anchor='ModalSheetNavigationHost(dismissOnBackPress, onDismissRequest)',
         windowsAnchor='rememberSheetBlurMotionModifier(blurBehind) then that call',
         decision='the centered Dialog host keeps onDismissRequest; only the material host changes'),
)

HUNKS = (
    ('animation-core-imports',
     'import androidx.compose.animation.slideOutVertically\n',
     'import androidx.compose.animation.slideOutVertically\n'
     'import androidx.compose.animation.core.tween\n'
     'import androidx.compose.animation.core.FiniteAnimationSpec\n'
     'import androidx.compose.animation.core.spring\n'),
    ('sheet-value-import',
     'import androidx.compose.material3.SheetState\n',
     'import androidx.compose.material3.SheetState\nimport androidx.compose.material3.SheetValue\n'),
    ('runtime-effect-imports',
     'import androidx.compose.runtime.CompositionLocalProvider\n',
     'import androidx.compose.runtime.CompositionLocalProvider\n'
     'import androidx.compose.runtime.LaunchedEffect\n'
     'import androidx.compose.runtime.rememberCoroutineScope\n'
     'import androidx.compose.runtime.snapshotFlow\n'),
    ('coroutines-imports',
     'import com.android.purebilibili.core.ui.motion.AppMotionTokens\n',
     'import com.android.purebilibili.core.ui.motion.AppMotionTokens\n'
     'import kotlinx.coroutines.launch\n'
     'import kotlinx.coroutines.flow.collect\n'),
    ('reduced-motion-parameter',
     'internal fun resolveAdaptiveBottomSheetMotionSpec(\n    uiStyle: AppUiStyle,\n): AdaptiveBottomSheetMotionSpec {\n',
     'internal fun resolveAdaptiveBottomSheetMotionSpec(\n    uiStyle: AppUiStyle,\n'
     '    reducedMotion: Boolean = false,\n): AdaptiveBottomSheetMotionSpec {\n'),
    ('reduced-motion-early-return',
     '): AdaptiveBottomSheetMotionSpec {\n    val tokens = resolveAndroidNativeChromeTokens(uiStyle)\n',
     '): AdaptiveBottomSheetMotionSpec {\n'
     '    if (reducedMotion) return AdaptiveBottomSheetMotionSpec(120, 90, 120, 90)\n'
     '    val tokens = resolveAndroidNativeChromeTokens(uiStyle)\n'),
    ('controlled-dismiss-kdoc',
     ' * Hinge-safe presentation keeps the existing region host to avoid spanning a fold.\n */\n',
     ' * Hinge-safe presentation keeps the existing region host to avoid spanning a fold.\n'
     ' * Controlled callers keep this composable mounted when [visible] becomes false;\n'
     ' * [onDismissRequest] reports completion after native hiding finishes.\n */\n'),
    ('content-color-default',
     '    contentColor: Color = MaterialTheme.colorScheme.onSurface,\n',
     '    contentColor: Color = AppContentColors.primaryText(),\n'),
    ('visible-and-on-dismiss-started-parameters',
     '    backgroundBlurBehind: Boolean = true,\n    content: @Composable ColumnScope.() -> Unit\n',
     '    backgroundBlurBehind: Boolean = true,\n'
     '    visible: Boolean = true,\n'
     '    onDismissStarted: () -> Unit = {},\n'
     '    content: @Composable ColumnScope.() -> Unit\n'),
    ('controlled-dismiss-preamble',
     ') {\n    val uiStyle = LocalAppUiStyle.current\n    val miuix = uiStyle == AppUiStyle.MIUIX\n',
     ') {\n'
     '    // Controlled callers keep this host in composition while native sheets finish hiding.\n'
     '    // The default remains visible; native theme geometry, material and motion are unchanged.\n'
     '    var retained by remember { mutableStateOf(visible) }\n'
     '    val latestDismiss by rememberUpdatedState(onDismissRequest)\n'
     '    val latestDismissStarted by rememberUpdatedState(onDismissStarted)\n'
     '    LaunchedEffect(visible) {\n'
     '        if (visible) retained = true\n'
     '    }\n'
     '    val finishDismiss: () -> Unit = {\n'
     '        if (retained) {\n'
     '            retained = false\n'
     '            latestDismiss()\n'
     '        }\n'
     '    }\n'
     '    if (!visible && !retained) return\n'
     '    val uiStyle = LocalAppUiStyle.current\n'
     '    val miuix = uiStyle == AppUiStyle.MIUIX\n'),
    ('miuix-show-follows-visible',
     '        val latestDismiss by rememberUpdatedState(onDismissRequest)\n',
     '        LaunchedEffect(visible) { show = visible }\n'),
    ('window-dialog-controlled-dismiss',
     '                maxWidth = layoutSpec.maxWidthDp.dp,\n'
     '                largeScreen = true,\n'
     '                insideMargin = DpSize(0.dp, 0.dp),\n'
     '                onDismissRequest = { show = false },\n'
     '                onDismissFinished = { latestDismiss() },\n',
     '                maxWidth = layoutSpec.maxWidthDp.dp,\n'
     '                largeScreen = true,\n'
     '                insideMargin = DpSize(0.dp, 0.dp),\n'
     '                onDismissRequest = { latestDismissStarted(); show = false },\n'
     '                onDismissFinished = { if (!show) finishDismiss() },\n'),
    ('window-bottom-sheet-controlled-dismiss',
     '            WindowBottomSheet(\n'
     '                show = show,\n'
     '                modifier = modifier,\n'
     '                backgroundColor = resolvedColor,\n'
     '                insideMargin = DpSize(0.dp, 0.dp),\n'
     '                onDismissRequest = { show = false },\n'
     '                onDismissFinished = { latestDismiss() },\n',
     '            WindowBottomSheet(\n'
     '                show = show,\n'
     '                modifier = modifier,\n'
     '                backgroundColor = resolvedColor,\n'
     '                insideMargin = DpSize(0.dp, 0.dp),\n'
     '                onDismissRequest = { latestDismissStarted(); show = false },\n'
     '                onDismissFinished = { if (!show) finishDismiss() },\n'),
    ('centered-dialog-visible-guard',
     '    if (centered) {\n        Dialog(\n',
     '    if (centered) {\n'
     '        // This branch already uses a plain centered/hinge-safe dialog, not a native sheet.\n'
     '        LaunchedEffect(visible) { if (!visible) finishDismiss() }\n'
     '        if (!visible) return\n'
     '        Dialog(\n'),
    ('material-controlled-dismiss-block',
     '    ModalBottomSheet(\n',
     '    val scope = rememberCoroutineScope()\n'
     '    var wasClosing by remember { mutableStateOf(false) }\n'
     '    LaunchedEffect(sheetState) {\n'
     '        // Covers native scrim/swipe dismissal as well as the explicit back handler below.\n'
     '        snapshotFlow { sheetState.isVisible && sheetState.targetValue == SheetValue.Hidden }\n'
     '            .collect { closing -> if (closing) latestDismissStarted() }\n'
     '    }\n'
     '    LaunchedEffect(visible) {\n'
     '        if (!visible) {\n'
     '            wasClosing = true\n'
     '            sheetState.hide()\n'
     '            if (!sheetState.isVisible) finishDismiss()\n'
     '        } else if (wasClosing) {\n'
     '            wasClosing = false\n'
     '            sheetState.show()\n'
     '        }\n'
     '    }\n'
     '    val requestMaterialDismiss: () -> Unit = {\n'
     '        latestDismissStarted()\n'
     '        scope.launch {\n'
     '            sheetState.hide()\n'
     '            if (!sheetState.isVisible) finishDismiss()\n'
     '        }\n'
     '    }\n'
     '    ModalBottomSheet(\n'),
    ('material-on-dismiss-request',
     '        onDismissRequest = onDismissRequest,\n        modifier = modifier,\n',
     '        onDismissRequest = finishDismiss,\n        modifier = modifier,\n'),
    ('material-back-handler',
     '        val blurMotionModifier = rememberSheetBlurMotionModifier(blurBehind)\n'
     '        ModalSheetNavigationHost(dismissOnBackPress, onDismissRequest) {\n',
     '        val blurMotionModifier = rememberSheetBlurMotionModifier(blurBehind)\n'
     '        ModalSheetNavigationHost(dismissOnBackPress, requestMaterialDismiss) {\n'),
    ('motion-members',
     '    val contentExitFadeDurationMillis: Int,\n)\n',
     '    val contentExitFadeDurationMillis: Int,\n'
     '    val reducedMotion: Boolean = false,\n'
     '    val dragSettleSpec: FiniteAnimationSpec<Float> = spring(dampingRatio = 1f, stiffness = 600f),\n'
     ')\n'),
    ('remember-motion-reduced-source',
     '    return remember(uiStyle) {\n        val motionSpec = resolveAdaptiveBottomSheetMotionSpec(uiStyle)\n',
     '    // Only custom overlay consumers use this contract. Native MD3/Miuix window\n'
     '    // sheets above keep their own motion, geometry and theme defaults.\n'
     '    val reducedMotion = LocalSystemReduceMotion.current || !LocalComponentMotionEnabled.current\n'
     '    return remember(uiStyle, reducedMotion) {\n'
     '        val motionSpec = resolveAdaptiveBottomSheetMotionSpec(uiStyle, reducedMotion)\n'),
    ('remember-motion-fade-specs',
     '            scrimEnter = bottomSheetScrimEnterTransition(uiStyle),\n'
     '            scrimExit = bottomSheetScrimExitTransition(uiStyle),\n'
     '            contentEnter = bottomSheetContentEnterTransition(uiStyle),\n'
     '            contentExit = bottomSheetContentExitTransition(uiStyle),\n',
     '            scrimEnter = if (reducedMotion) fadeIn(tween(120)) else bottomSheetScrimEnterTransition(uiStyle),\n'
     '            scrimExit = if (reducedMotion) fadeOut(tween(90)) else bottomSheetScrimExitTransition(uiStyle),\n'
     '            contentEnter = if (reducedMotion) fadeIn(tween(120)) else bottomSheetContentEnterTransition(uiStyle),\n'
     '            contentExit = if (reducedMotion) fadeOut(tween(90)) else bottomSheetContentExitTransition(uiStyle),\n'),
    ('remember-motion-member-arguments',
     '            contentExitFadeDurationMillis = motionSpec.contentExitFadeDurationMillis,\n        )\n',
     '            contentExitFadeDurationMillis = motionSpec.contentExitFadeDurationMillis,\n'
     '            reducedMotion = reducedMotion,\n'
     '            dragSettleSpec = if (reducedMotion) tween(0) else spring(dampingRatio = 1f, stiffness = 600f),\n'
     '        )\n'),
)

FORBIDDEN = (
    'LocalView',
    'LocalConfiguration',
    'import android.',
    'hingeSafeRegions',
    'HingeSafeOverlayHost',
    'findViewTreeNavigationEventDispatcherOwner',
    'LocalHingeSafeOverlayRegions',
    'decorFitsSystemWindows',
)
WINDOWS_HOST = (
    '    DesktopCommentDialogNavigationHost {\n'
    '        val backState = rememberNavigationEventState(NavigationEventInfo.None)\n'
    '        NavigationBackHandler(state=backState,isBackEnabled=dismissOnBackPress,onBackCompleted=onDismissRequest)\n'
    '        content()\n'
    '    }\n'
)


def sha(value):
    return hashlib.sha256(value.encode('utf8') if isinstance(value, str) else value).hexdigest()


def need(condition, message):
    if not condition:
        raise ValueError(message)


def fixed(repo):
    root = Path(repo)/ARCHIVE
    need(root.is_dir() and not root.is_symlink(), 'Missing original sheet motion inputs')
    need({p.name for p in root.iterdir()} == EXPECTED | {'manifest.json', 'LICENSE'},
         'Sheet motion input leaves changed')
    raw = (root/'manifest.json').read_bytes()
    need(sha(raw) == MANIFEST_SHA256, 'Sheet motion manifest changed')
    manifest = json.loads(raw)
    need((manifest['repository'], manifest['tag'], manifest['commit'], manifest['hashNormalization']) ==
         ('jay3-yy/BiliPai', 'v0.4.6', COMMIT, 'raw'), 'Sheet motion source identity changed')
    need(len(manifest['files']) == 1 and {row['filename'] for row in manifest['files']} == EXPECTED,
         'Sheet motion source set changed')
    sources = {}
    for row in manifest['files']:
        file = root/row['filename']
        need(file.is_file() and not file.is_symlink() and Path(row['path']).name == file.name,
             'Linked or ambiguous sheet motion source')
        leaf = file.read_bytes()
        need(b'\r' not in leaf and len(leaf) == row['bytes'] and sha(leaf) == row['sha256'],
             'Sheet motion bytes changed: '+file.name)
        need(hashlib.sha1(b'blob '+str(len(leaf)).encode()+b'\0'+leaf).hexdigest() == row['gitBlob'],
             'Sheet motion Git blob changed: '+file.name)
        sources[file.name] = leaf.decode('utf8')
    license_file = root/'LICENSE'
    need(not license_file.is_symlink() and sha(license_file.read_bytes()) == manifest['license']['sha256'],
         'Sheet motion license changed')
    pinned = manifest['beforeFinalOutputs'][OUTPUT_RELATIVE]['sha256']
    need(len(pinned) == 64 and pinned == PREDECESSOR_SHA256, 'Sheet motion predecessor pin changed')
    return sources, manifest


def validate_outputs(repo, *outputs):
    fixed(repo)
    root = (Path(repo)/ARCHIVE).resolve()
    for output in outputs:
        if output is None:
            continue
        target = Path(output).resolve()
        need(not (target.is_relative_to(root) or root.is_relative_to(target)),
             'Output overlaps original sheet motion inputs')


def restore(value, proof):
    need(sha(value) == proof['afterSHA256'], 'Sheet motion final output differs from its receipt')
    lines = value.splitlines(keepends=True)
    for hunk in reversed(proof['hunks']):
        first, last = hunk['afterLines']
        need(''.join(lines[first:last]) == hunk['after'], 'Sheet motion inverse span changed')
        lines[first:last] = hunk['before'].splitlines(keepends=True)
    result = ''.join(lines)
    need(sha(result) == proof['beforeSHA256'], 'Sheet motion predecessor inverse failed')
    return result


def _replace_once(body, old, new, label):
    need(body.count(old) == 1, 'Sheet motion anchor changed: '+label)
    return body.replace(old, new, 1)


def _motion_members_once(body):
    start = body.index('data class AppBottomSheetMotion(')
    end = body.index('\n)\n', start)
    params = body[start:end + 1]
    need(params.count('\n    val reducedMotion: Boolean = false,\n') == 1, 'reducedMotion member drifted')
    need(params.count('\n    val dragSettleSpec: FiniteAnimationSpec<Float> = spring(dampingRatio = 1f, stiffness = 600f),\n') == 1,
         'dragSettleSpec member drifted')
    need(body.count('val reducedMotion:') == 1 and body.count('val dragSettleSpec:') == 1,
         'Sheet motion members are not declared once')


def advance(repo, before):
    sources, _manifest = fixed(repo)
    need(sha(before) == PREDECESSOR_SHA256, 'AppSheetComponents predecessor changed')
    original = sources['AppSheetComponents.kt']
    need(original.startswith('package com.android.purebilibili.core.ui\n') and original.endswith('\n'),
         'AppSheetComponents original file shape changed')
    current = before
    for label, old, new in HUNKS:
        current = _replace_once(current, old, new, label)
    need(current.endswith('\n') and '\r' not in current, 'Sheet motion output shape changed')
    for token in FORBIDDEN:
        need(token not in current, 'Windows adaptation lost: '+token)
    need(current.count(WINDOWS_HOST) == 1, 'Windows navigation host changed')
    need(current.count('val configuration = LocalWindowSizeClass.current') == 1, 'Windows size class changed')
    need(current.count('layout.findRootCoordinates().size.height') == 1, 'Windows root coordinates changed')
    need(current.count('neverEqualPolicy()') == 1, 'Windows coordinate policy changed')
    _motion_members_once(current)
    proof = dict(upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256,
                 predecessorSHA256=PREDECESSOR_SHA256,
                 predecessorIsAdaptSheetBodyWithoutProducerHeader=True,
                 windowsAdaptationsRetained=True,
                 appliedHunks=[label for label, _old, _new in HUNKS],
                 mechanicalReanchors=list(MECHANICAL_REANCHORS),
                 stoppedHunks=[],
                 originalToWindows=inverse_proof(original, current),
                 retainedToCurrent=inverse_proof(before, current))
    need(restore(current, proof['originalToWindows']) == original, 'AppSheetComponents original inverse failed')
    need(restore(current, proof['retainedToCurrent']) == before, 'AppSheetComponents retained inverse failed')
    return current, proof
