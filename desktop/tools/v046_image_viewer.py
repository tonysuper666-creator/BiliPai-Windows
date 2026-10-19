"""v0.4.6 image viewer on the existing dynamic card producer.

ZoomableImage.kt, ZoomableImageGesturePolicy.kt and the new ZoomableImageMotionPolicy.kt are emitted verbatim from
the archive (the vendored direct copies of the first two are excluded in prepareUpstreamSources), together with the
two original JVM suites that exercise them. This brings 0.3.4-beta.2 (exit drag in root coordinates, UNDECIDED
gesture mode) and 0.4.3 (continuous double-tap zoom, drag inertia, system reduced motion).

The whole v0.4.6 ImagePreviewDialog.kt is pinned once in the archive for the Windows preview chain. This slice
consumes only its pager span: the Windows renderer, which the same producer derives from the 0.2.5 dialog by many
platform substitutions, takes the verbatim v0.4.6 userScrollEnabled lines (the pager must not restart its scroll
node when a vertical dismissal starts) and gives the preview the Windows reduced-motion signal. The other v0.4.6
dialog hunks (AppContentColors, AppHorizontalPager, publicContent) are not applied here.

Nothing is compiled or executed here.
"""
from pathlib import Path
import hashlib
import json
import os
import stat

from v044_favorite_detail import inverse_proof

COMMIT = 'd6bf8b1655ae910999860bf13e65c8fcea5b345b'
ARCHIVE = 'desktop/upstream-slices/v046-image-viewer'
MANIFEST_SHA256 = '98a098e9fa4e275a8a35d7f8e982aa1203e22207b095a532ca7781aa36e56480'
PACKAGE = 'com.android.purebilibili.feature.dynamic.components'
DIRECTORY = 'com/android/purebilibili/feature/dynamic/components/'
ZOOMABLE = 'ZoomableImage.kt'
GESTURE = 'ZoomableImageGesturePolicy.kt'
MOTION = 'ZoomableImageMotionPolicy.kt'
DIALOG = 'ImagePreviewDialog.kt'
MOTION_TEST = 'ZoomableImageMotionPolicyTest.kt'
GESTURE_TEST = 'ImagePreviewGesturePolicyTest.kt'
MAIN_FILES = (ZOOMABLE, GESTURE, MOTION)
TEST_FILES = (MOTION_TEST, GESTURE_TEST)
EXPECTED = set(MAIN_FILES) | set(TEST_FILES) | {DIALOG}
TEST_CASES = {MOTION_TEST: 5, GESTURE_TEST: 8}
COMPONENTS = 'app/src/main/java/com/android/purebilibili/feature/dynamic/components/'
TEST_COMPONENTS = 'app/src/test/java/com/android/purebilibili/feature/dynamic/components/'
UPSTREAM_PATHS = {
    ZOOMABLE: COMPONENTS + ZOOMABLE,
    GESTURE: COMPONENTS + GESTURE,
    MOTION: COMPONENTS + MOTION,
    DIALOG: COMPONENTS + DIALOG,
    MOTION_TEST: TEST_COMPONENTS + MOTION_TEST,
    GESTURE_TEST: TEST_COMPONENTS + GESTURE_TEST,
}
# The vendored direct-mode copies that these archive files replace. The motion policy is new and has no copy.
REPLACED_CANONICAL = {name: UPSTREAM_PATHS[name] for name in (ZOOMABLE, GESTURE)}
# Top-level declarations of the carried files; each token must appear exactly once.
DECLARATIONS = {
    ZOOMABLE: ('internal const val ZOOMABLE_IMAGE_TAG = "zoomable_image"', '\nfun ZoomableImage(\n'),
    GESTURE: ('internal enum class ZoomableImageGestureMode {', 'internal fun resolveZoomableImageGestureMode('),
    MOTION: ('internal fun resolveZoomableImagePanBounds(', 'internal fun clampZoomableImageOffset(',
             'internal fun resolveZoomableImageFlingVelocity('),
    DIALOG: ('\nfun ImagePreviewDialog(\n', 'fun ImagePreviewOverlayHost(', 'private object ImagePreviewOverlayController',
             'private fun ImagePreviewOverlayContent('),
}
PROOF_NAME = 'v046-image-viewer-proof.json'
TESTS_PROOF_NAME = 'v046-image-viewer-tests-proof.json'

# The Windows renderer body that the card producer holds right before this slice's layer (LF SHA-256).
RENDERER_PREDECESSOR_SHA256 = '240017afd2eeb1646b877c7e24a38df2cf301c8921eafaeff0626adf27454bd6'
# The pager span of the dialog: the 0.2.5 text that the renderer still holds, and the v0.4.6 text of the archive.
PAGER_START = '                    beyondViewportPageCount = 1,  // 预加载相邻页面\n'
PAGER_END = '                    key = { images.getOrElse(it) { "" } }\n'
PAGER_BEFORE_SHA256 = '7462b073fda3bae0d2a456b087fb1fb75b25b265c842439f396f8e14fc1de104'
PAGER_AFTER_SHA256 = '56086a886d7b5110a0489a50bf14eb1df90bf3c4d46cf79b2bc41c6ee7246c00'
# Windows reports the system animation setting (SPI_GETCLIENTAREAANIMATION) through one Root binding. The Android
# local that ZoomableImage reads defaults to false and nothing on Windows provides it, so the preview provides it.
PROVIDER_BEFORE = 'CompositionLocalProvider(com.bilipai.desktop.ui.LocalDesktopDynamicCardBindings provides request.platform) {\n'
PROVIDER_AFTER = (
    'CompositionLocalProvider(\n'
    '                    com.bilipai.desktop.ui.LocalDesktopDynamicCardBindings provides request.platform,\n'
    '                    // Upstream zoom motion follows the system reduced-motion setting. Windows reports it as\n'
    '                    // SPI_GETCLIENTAREAANIMATION through the one Root binding; the local defaults to false.\n'
    '                    com.android.purebilibili.core.ui.LocalSystemReduceMotion provides\n'
    '                        com.bilipai.desktop.ui.rememberDesktopDynamicReduceMotion(),\n'
    '                ) {\n')


def sha(value):
    return hashlib.sha256(value.encode('utf8') if isinstance(value, str) else value).hexdigest()


def need(condition, message):
    if not condition:
        raise ValueError(message)


def fixed(repo):
    root = Path(repo) / ARCHIVE
    need(root.is_dir() and not root.is_symlink(), 'Missing original image viewer inputs')
    need({p.name for p in root.iterdir()} == EXPECTED | {'manifest.json', 'LICENSE'},
         'Image viewer input leaves changed')
    manifest_raw = (root / 'manifest.json').read_bytes()
    need(b'\r' not in manifest_raw and sha(manifest_raw) == MANIFEST_SHA256, 'Image viewer manifest changed')
    manifest = json.loads(manifest_raw)
    need((manifest['repository'], manifest['tag'], manifest['commit'], manifest['hashNormalization']) ==
         ('jay3-yy/BiliPai', 'v0.4.6', COMMIT, 'raw'), 'Image viewer source identity changed')
    need(len(manifest['files']) == len(EXPECTED) and {row['filename'] for row in manifest['files']} == EXPECTED,
         'Image viewer source set changed')
    sources, raws = {}, {}
    for row in manifest['files']:
        file = root / row['filename']
        need(file.is_file() and not file.is_symlink() and Path(row['path']).name == file.name,
             'Linked or ambiguous image viewer source')
        need(row['path'] == UPSTREAM_PATHS[file.name], 'Image viewer upstream path changed: ' + file.name)
        leaf = file.read_bytes()
        need(b'\r' not in leaf and len(leaf) == row['bytes'] and sha(leaf) == row['sha256'],
             'Image viewer bytes changed: ' + file.name)
        need(hashlib.sha1(b'blob ' + str(len(leaf)).encode() + b'\0' + leaf).hexdigest() == row['gitBlob'],
             'Image viewer Git blob changed: ' + file.name)
        text = leaf.decode('utf8')
        need(text.encode('utf8') == leaf and text.endswith('\n'), 'Image viewer text changed: ' + file.name)
        need(('package ' + PACKAGE + '\n') in text[:600], 'Image viewer package changed: ' + file.name)
        sources[file.name] = text
        raws[file.name] = leaf
    for name, tokens in DECLARATIONS.items():
        for token in tokens:
            need(sources[name].count(token) == 1, 'Image viewer declaration changed: ' + token.strip())
    for name, cases in TEST_CASES.items():
        need(sources[name].count('@Test') == cases, 'Image viewer test count changed: ' + name)
    start, end = _pager_span(sources[DIALOG], 'v0.4.6 dialog')
    need(sha(sources[DIALOG][start:end]) == PAGER_AFTER_SHA256, 'Image viewer v0.4.6 pager span changed')
    license_file = root / 'LICENSE'
    need(not license_file.is_symlink() and sha(license_file.read_bytes()) == manifest['license']['sha256'],
         'Image viewer license changed')
    return sources, raws, manifest


def validate_outputs(repo, *outputs):
    fixed(repo)
    root = (Path(repo) / ARCHIVE).resolve()
    for output in outputs:
        if output is None:
            continue
        target = Path(output).resolve()
        need(not (target.is_relative_to(root) or root.is_relative_to(target)),
             'Output overlaps original image viewer inputs')


def restore(value, proof):
    """Apply an inverse_proof backwards; the proof and every hunk are checked on the way."""
    need(sha(value) == proof['afterSHA256'], 'Image viewer output differs from its receipt')
    lines = value.splitlines(keepends=True)
    for hunk in reversed(proof['hunks']):
        first, last = hunk['afterLines']
        need(''.join(lines[first:last]) == hunk['after'], 'Image viewer inverse span changed')
        lines[first:last] = hunk['before'].splitlines(keepends=True)
    result = ''.join(lines)
    need(sha(result) == proof['beforeSHA256'], 'Image viewer predecessor inverse failed')
    return result


def _replace_once(body, old, new, label):
    need(body.count(old) == 1, 'Image viewer anchor changed: ' + label)
    return body.replace(old, new, 1)


def _pager_span(text, label):
    need(text.count(PAGER_START) == 1 and text.count(PAGER_END) == 1, 'Image viewer pager anchors changed: ' + label)
    start = text.index(PAGER_START)
    end = text.index(PAGER_END)
    need(start < end and text[start:end].count('\n') <= 6, 'Image viewer pager span changed: ' + label)
    return start, end


def advance_preview_renderer(repo, before):
    """Windows preview renderer: the v0.4.6 pager span and the Windows reduced-motion signal."""
    sources, _raws, _manifest = fixed(repo)
    need(sha(before.replace('\r\n', '\n')) == RENDERER_PREDECESSOR_SHA256, 'Image viewer renderer predecessor changed')
    start, end = _pager_span(before, 'renderer')
    need(sha(before[start:end]) == PAGER_BEFORE_SHA256, 'Image viewer renderer pager span changed')
    dialog = sources[DIALOG]
    dialog_start, dialog_end = _pager_span(dialog, 'v0.4.6 dialog')
    # The replacement is a verbatim slice of the archived v0.4.6 dialog, never retyped text.
    after = before[:start] + dialog[dialog_start:dialog_end] + before[end:]
    after = _replace_once(after, PROVIDER_BEFORE, PROVIDER_AFTER, 'reduced-motion provider')
    need('userScrollEnabled = !isDismissing && !isPreparingDismiss &&\n' in after,
         'Image viewer pager no longer follows the v0.4.6 scroll rule')
    need('userScrollEnabled = !isVerticalDismissDragging' not in after, 'Image viewer pager still restarts on drag')
    proof = inverse_proof(before, after)
    proof.update(upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256, source=UPSTREAM_PATHS[DIALOG],
                 sourceSHA256=sha(dialog), predecessorSHA256=RENDERER_PREDECESSOR_SHA256,
                 pagerBeforeSHA256=PAGER_BEFORE_SHA256, pagerAfterSHA256=PAGER_AFTER_SHA256,
                 reducedMotionSignal='LocalSystemReduceMotion provides rememberDesktopDynamicReduceMotion()',
                 jvmTestsExecuted=False, mainCompiled=False, runtimeExecuted=False)
    return after, proof


def _target(root, relative):
    base = Path(root).resolve()
    target = base / relative
    # A leaf symlink must stay unresolved; resolve() would follow it and write through it.
    if not target.is_symlink():
        target = target.resolve()
        need(target.is_relative_to(base), 'Image viewer target escapes the output root: ' + relative)
    return target


def _require_regular_or_absent(target, relative):
    try:
        info = os.lstat(target)
    except FileNotFoundError:
        return
    need(stat.S_ISREG(info.st_mode), 'Image viewer target is not a regular file: ' + relative)


def _write_json(path, value):
    Path(path).write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf8', newline='\n')


def _write_verbatim(output, name, raw):
    relative = DIRECTORY + name
    target = _target(output, relative)
    _require_regular_or_absent(target, relative)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(raw)
    need(target.read_bytes() == raw, 'Image viewer readback differs: ' + name)
    return target


def emit_main(repo, output, renderer_proof=None):
    """Write the three main files verbatim and the receipt; return the generated Kotlin paths."""
    validate_outputs(repo, output)
    _sources, raws, _manifest = fixed(repo)
    targets = []
    for name in MAIN_FILES:
        relative = DIRECTORY + name
        target = _target(output, relative)
        _require_regular_or_absent(target, relative)
        targets.append((name, target))
    written = [_write_verbatim(output, name, raws[name]) for name, _target_path in targets]
    _write_json(Path(output) / PROOF_NAME, dict(
        upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256,
        files={name: dict(source=UPSTREAM_PATHS[name], relative=DIRECTORY + name, emittedVerbatim=True,
                          sha256=sha(raws[name]), replacesCanonical=REPLACED_CANONICAL.get(name))
               for name in MAIN_FILES},
        dialog=dict(source=UPSTREAM_PATHS[DIALOG], sha256=sha(raws[DIALOG]), pinnedWhole=True, emitted=False,
                    consumedSpan='pager userScrollEnabled', pagerAfterSHA256=PAGER_AFTER_SHA256),
        renderer=renderer_proof,
        jvmTestsExecuted=False, mainCompiled=False, runtimeExecuted=False))
    return written


def emit_tests(repo, output):
    """Write the two original JVM suites unchanged (they already use kotlin.test)."""
    validate_outputs(repo, output)
    _sources, raws, _manifest = fixed(repo)
    for name in TEST_FILES:
        relative = DIRECTORY + name
        _require_regular_or_absent(_target(output, relative), relative)
    written = [_write_verbatim(output, name, raws[name]) for name in TEST_FILES]
    _write_json(Path(output) / TESTS_PROOF_NAME, dict(
        upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256,
        tests={name: dict(source=UPSTREAM_PATHS[name], cases=TEST_CASES[name], sha256=sha(raws[name]),
                          emittedVerbatim=True) for name in TEST_FILES},
        jvmTestsExecuted=False))
    return written
