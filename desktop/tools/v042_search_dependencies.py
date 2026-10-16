"""Complete current Search/glass dependencies on their existing desktop owners."""
from pathlib import Path
import hashlib
import importlib.util
import json
import re

from v029_comment_search import whole_proof
from v041_search_all import declaration, replace_once

COMMIT = '5c34a82418b33bf88acea01784bb3c6b179d6491'
ARCHIVE = 'desktop/upstream-slices/v042-search-dependencies'
MANIFEST_SHA256 = '137aaa9f5504fc45fc634677c0be23ebc9e063ea02319c9fc40b864cfa0c288a'
APP = 'app/src/main/java/com/android/purebilibili/'
DS = 'design-system/src/main/java/com/android/purebilibili/'
SHARED_PATHS = {APP + 'core/ui/components/AppLiquidAwareTabRow.kt', *(
    APP + 'feature/home/components/' + name for name in (
        'FloatingBottomBar.kt', 'FloatingBottomBarGeometry.kt',
        'BottomBarMatchedLiquidChrome.kt', 'FloatingDockChrome.kt'))}
FOUNDATIONS = {DS + 'core/ui/AppTabTypography.kt', DS + 'core/ui/AppThemeConfig.kt'}
NATIVE_TABS = DS + 'core/ui/components/AppSegmentedControl.kt'
NATIVE_RENDERERS = {DS + 'core/ui/renderer/' + name for name in (
    'material3/AppMaterial3SegmentedControl.kt', 'miuix/AppMiuixSegmentedControl.kt')}
CONTENT_COLORS = DS + 'core/ui/AppContentColors.kt'
SEARCH_MOTION = APP + 'feature/search/SearchMotionBudgetPolicy.kt'
SCROLL_HIDE = APP + 'core/ui/BottomBarScrollHidePolicy.kt'
TOP_BAR = APP + 'feature/home/components/TopBar.kt'


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def fixed_sources(repo):
    root = Path(repo) / ARCHIVE
    raw = (root / 'manifest.json').read_bytes()
    if sha(raw) != MANIFEST_SHA256:
        raise ValueError('Unreviewed current Search dependency manifest')
    manifest = json.loads(raw)
    if manifest['upstreamCommit'] != COMMIT or manifest['hashNormalization'] != 'raw':
        raise ValueError('Unknown current Search dependency identity')
    if {p.name for p in root.iterdir()} != {'manifest.json', *(r['filename'] for r in manifest['files'])}:
        raise ValueError('Unexpected current Search dependency archive leaf')
    sources = {}
    for row in manifest['files']:
        raw = (root / row['filename']).read_bytes()
        oid = hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest()
        if row['filename'] != Path(row['path']).name or row['path'] in sources:
            raise ValueError('Ambiguous current Search dependency')
        if len(raw) != row['bytes'] or sha(raw) != row['sha256Bytes'] or oid != row['gitBlob']:
            raise ValueError('Changed current Search dependency: ' + row['path'])
        sources[row['path']] = raw
    return sources, manifest


def identity(repo, path):
    _, manifest = fixed_sources(repo)
    row = next(row for row in manifest['files'] if row['path'] == path)
    return dict(pinnedCommit=COMMIT, originalPath=path, path=ARCHIVE + '/' + row['filename'],
        sha256Bytes=row['sha256Bytes'], gitBlobOid=row['gitBlob'], bytes=row['bytes'])


def shared_source(repo, path, previous):
    from v042_optical_dependencies import selected_source
    return selected_source(repo,path,fixed_sources(repo)[0][path].decode('utf-8') if path in SHARED_PATHS else previous)


def shared_identity(repo, path):
    from v042_optical_dependencies import identity as optical_identity
    return optical_identity(repo,path) or (identity(repo,path) if path in SHARED_PATHS else None)


def native_tabs_source(repo, path, previous):
    from v042_optical_dependencies import selected_source
    return selected_source(repo,path,fixed_sources(repo)[0][path].decode('utf-8') if path in NATIVE_RENDERERS | {NATIVE_TABS} else previous)


def retained_foundation(repo, path, raw, row, commit, manifest):
    if path not in FOUNDATIONS:
        return raw, row, commit, manifest
    sources, current = fixed_sources(repo)
    return sources[path], next(r for r in current['files'] if r['path'] == path), COMMIT, MANIFEST_SHA256


def content_colors_source(repo):
    raw = fixed_sources(repo)[0][CONTENT_COLORS]
    text = raw.decode('utf-8')
    return text, raw, whole_proof(text, text)


def extension_declaration(repo, source, name):
    from v041_search_all import parser
    lexer = parser(repo)
    spec = importlib.util.spec_from_file_location('search_dependency_original_declarations',
        repo / 'desktop/tools/extract-upstream-frosted-audio-renderer.py')
    selected = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(selected)
    values = [body for key, body in selected.declarations(lexer, source) if key == name]
    if len(values) != 1:
        raise ValueError('Ambiguous current chrome extension: ' + name)
    tokens = lexer.kotlin_tokens(values[0])
    return values[0][:tokens[-1][2]].rstrip()


def adapt_home(repo, name, source, audits):
    repo = Path(repo)
    originals, _ = fixed_sources(repo)
    if name == 'HomeCategoryPage.kt':
        # This caller explicitly requested content sizing. The current original
        # makes CONTENT the default; retain that intent through its current API.
        source = replace_once(source, '            contentSizedMiuixNonGlassItems = true,',
            '            equalizeMiuixNonGlassItemWidths = false,')
    if 'fun Modifier.homeTopBottomBarMatchedSurface(' in source:
        before = extension_declaration(repo, source, 'homeTopBottomBarMatchedSurface')
        current = originals[TOP_BAR].decode('utf-8')
        after = extension_declaration(repo, current, 'homeTopBottomBarMatchedSurface')
        source = replace_once(source, before, after)
        audits.append(dict(sourcePath=TOP_BAR, upstreamCommit=COMMIT,
            declaration='homeTopBottomBarMatchedSurface', wholeOriginalToPlatform=whole_proof(after, after),
            retainedToCurrent=whole_proof(before, after), soleExistingHomeOwner=True))
    return source


def emit_search(repo, output, test_output):
    repo = Path(repo)
    originals, _ = fixed_sources(repo)
    output = Path(output)
    motion = originals[SEARCH_MOTION].decode('utf-8')
    target = output / 'com/android/purebilibili/feature/search/SearchMotionBudgetPolicy.kt'
    from v025_source_paths import canonical_source
    previous = canonical_source(Path(repo), SEARCH_MOTION).read_text(encoding='utf-8').replace('\r\n', '\n')
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(motion, encoding='utf-8', newline='\n')
    policy = originals[SCROLL_HIDE].decode('utf-8')
    properties = re.findall(r'(?m)^val NowPlayingBarOverlayAvoidancePadding:[^\n]+\n    androidx\.compose\.ui\.unit\.Dp\([^\n]+\)\n', policy)
    if len(properties) != 1:
        raise ValueError('Original now-playing avoidance property boundary changed')
    selected = properties[0].rstrip()
    selected += '\n\n' + declaration(repo, policy, 'fun', 'rememberNowPlayingBarOverlayVisible', annotation=True)
    header = 'package com.android.purebilibili.core.ui\n\nimport androidx.compose.runtime.Composable\nimport androidx.lifecycle.compose.collectAsStateWithLifecycle\n\n'
    target = output / 'com/android/purebilibili/core/ui/DesktopOriginalSearchNowPlayingAvoidance.kt'
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(header + selected + '\n', encoding='utf-8', newline='\n')
    # All three classes exercise the real imported policy implementations.
    tests = []
    if test_output is not None:
        for path, raw in originals.items():
            if '/test/java/' not in path:
                continue
            target = Path(test_output) / path.split('/test/java/', 1)[1]
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(raw)
            tests.append(path)
    proof = dict(upstreamCommit=COMMIT, completeSearchMotionPolicy=True,
        originalMotionToPlatform=whole_proof(motion, motion), retainedMotionToCurrent=whole_proof(previous, motion),
        avoidanceOriginalToPlatform=whole_proof(selected, selected),
        actualExistingAudioNowPlayingSessionObserved=True, originalTests=tests,
        sourceQualified=False, mainCompiled=False, pixelsValidated=False)
    (output / 'v042-search-dependency-source-proof.json').write_text(
        json.dumps(proof, ensure_ascii=False, indent=2)+'\n', encoding='utf-8', newline='\n')
    return proof
