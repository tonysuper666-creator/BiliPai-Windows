"""Authenticated current collection detail on the existing three desktop owners."""
from pathlib import Path
import difflib
import hashlib
import json
import textwrap

COMMIT = '070ab41608213cd981fd7e51df357295f9a537bf'
ARCHIVE = 'desktop/upstream-slices/v044-favorite-detail'
MANIFEST_SHA256 = '21f6536a4a554c824921332ef957fc8bc49c6178ce644821851f8ea294c93861'
EXPECTED = {'SeasonSeriesDetailViewModel.kt', 'SpacePlaybackPolicy.kt', 'CommonListScreen.kt',
    'ListViewModel.kt', 'SpacePlaybackPolicyTest.kt', 'SeasonSeriesDetailViewModelTest.kt',
    'FavoriteModels.kt', 'PlaylistManager.kt'}
THEME = 'com/android/purebilibili/feature/space/'


def sha(value):
    return hashlib.sha256(value.encode('utf8') if isinstance(value, str) else value).hexdigest()


def need(condition, message):
    if not condition:
        raise ValueError(message)


def fixed_sources(repo):
    root = Path(repo)/ARCHIVE
    need(root.is_dir() and not root.is_symlink(), 'Missing or linked favorite detail originals')
    need({p.name for p in root.iterdir()} == EXPECTED | {'manifest.json'}, 'Favorite detail input leaves changed')
    raw = (root/'manifest.json').read_bytes()
    need(sha(raw) == MANIFEST_SHA256, 'Favorite detail manifest changed')
    manifest = json.loads(raw)
    need((manifest['repository'], manifest['tag'], manifest['commit'], manifest['hashNormalization']) ==
         ('jay3-yy/BiliPai', 'v0.4.4', COMMIT, 'raw'), 'Favorite detail source identity changed')
    need(len(manifest['files']) == len(EXPECTED) and {row['filename'] for row in manifest['files']} == EXPECTED,
         'Favorite detail source set changed')
    sources = {}
    for row in manifest['files']:
        file = root/row['filename']
        need(file.is_file() and not file.is_symlink() and Path(row['path']).name == file.name,
             'Linked or ambiguous favorite detail source')
        raw = file.read_bytes()
        need(len(raw) == row['bytes'] and sha(raw) == row['sha256'], 'Favorite detail bytes changed: '+file.name)
        need(hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest() == row['gitBlob'],
             'Favorite detail Git blob changed: '+file.name)
        sources[file.name] = raw.decode('utf8')
    return sources, manifest


def validate_outputs(repo, *outputs):
    archive = (Path(repo)/ARCHIVE).resolve()
    for output in outputs:
        if output is None:
            continue
        target = Path(output).resolve()
        need(not target.is_relative_to(archive) and not archive.is_relative_to(target),
             'Output overlaps favorite detail originals')
    fixed_sources(repo)


def inverse_proof(before, after):
    a, b = before.splitlines(keepends=True), after.splitlines(keepends=True)
    ops = [op for op in difflib.SequenceMatcher(None, a, b, autojunk=False).get_opcodes() if op[0] != 'equal']
    forward, inverse = a[:], b[:]
    for _, i, j, k, l in reversed(ops):
        forward[i:j] = b[k:l]
        inverse[k:l] = a[i:j]
    need(''.join(forward) == after and ''.join(inverse) == before, 'Whole favorite detail inverse failed')
    return dict(beforeSHA256=sha(before), afterSHA256=sha(after), wholeInverseExact=True,
        hunks=[dict(beforeLines=[i,j], afterLines=[k,l], before=''.join(a[i:j]), after=''.join(b[k:l]))
            for _, i, j, k, l in ops])


def ui_source(repo, before):
    sources, manifest = fixed_sources(repo)
    review = manifest['reviewedUiAdvance']
    need(sha(before) == review['predecessorSelectedSHA256'], 'Favorite detail UI predecessor changed')
    reference = sources['CommonListScreen.kt']
    body = before
    for edit in review['edits']:
        original, at, label = edit['originalText'], edit['originalOffset'], edit['label']
        need(reference[at:at+len(original)] == original and sha(original) == edit['originalSHA256'],
             'Favorite detail UI original segment changed')
        if label in ('original-detail-capabilities-and-observable-management', 'original-detail-same-page-retry-callback'):
            expected = edit['before']+original
        elif label == 'whole-original-detail-toolbar-at-existing-layout-depth':
            expected = textwrap.indent(textwrap.dedent(original), ' '*edit['existingLayoutIndentSpaces'])
        elif label == 'original-new-items-pagination-trigger-on-existing-caller-API':
            expected = original.replace(', allowAutomaticLoadMore', '')
        else:
            expected = original
        need(edit['after'] == expected, 'Unreviewed favorite detail UI adaptation')
        at = edit['offset']
        need(body[at:at+len(edit['before'])] == edit['before'] and body.count(edit['before']) == 1,
             'Favorite detail UI anchor changed: '+label)
        body = body[:at]+edit['after']+body[at+len(edit['before']):]
    need(sha(body) == review['selectedSHA256'], 'Favorite detail UI selected digest changed')
    restored = body
    for edit in reversed(review['edits']):
        at = edit['offset']
        need(restored[at:at+len(edit['after'])] == edit['after'], 'Favorite detail UI inverse anchor changed')
        restored = restored[:at]+edit['before']+restored[at+len(edit['after']):]
    need(restored == before, 'Favorite detail UI complete predecessor inverse failed')
    return body, dict(upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256,
        wholePredecessorInverseExact=True, originalSegments=len(review['edits']),
        originalToolbarReindentedOnly=True, beforeSHA256=sha(before), afterSHA256=sha(body))


def selected_source(repo, relative, before, row):
    if relative != 'feature/space/SeasonSeriesDetailViewModel':
        return before, row
    sources, manifest = fixed_sources(repo)
    name = 'SeasonSeriesDetailViewModel.kt'
    need(sha(before) == manifest['reviewedTransitions'][name]['predecessorSHA256'],
         'Favorite detail ViewModel predecessor changed')
    original = sources[name]
    reference = next(r for r in manifest['files'] if r['filename'] == name)
    return original, dict(path=ARCHIVE+'/'+name, upstreamCommit=COMMIT,
        sha256LfUtf8=sha(original), rawSha256Bytes=reference['sha256'], gitBlob=reference['gitBlob'],
        v044FavoriteDetailSourceAdvance=inverse_proof(before, original))


def ui_source_after_platform(repo, before):
    """Apply after strict History, brand, cover and role gates without changing them."""
    from v029_brand_history import selected_source as legacy_source
    baseline = legacy_source('feature/list/CommonListScreen')[0]
    _, baseline_proof = ui_source(repo, baseline)
    review = fixed_sources(repo)[1]['reviewedUiAdvance']
    need(sha(before) == review['finalProducerPredecessorSHA256'],
         'Final existing favorite owner predecessor changed')
    body = before
    for edit in review['edits']:
        need(body.count(edit['before']) == 1, 'Final favorite detail UI anchor changed')
        body = body.replace(edit['before'], edit['after'], 1)
    for imported in review['requiredOriginalImports']:
        line = imported['text']
        reference = fixed_sources(repo)[0]['CommonListScreen.kt']
        offset = imported['originalOffset']
        need(reference[offset:offset+len(line)] == line and sha(line) == imported['originalSHA256'],
             'Original favorite detail import changed')
        marker = 'package com.android.purebilibili.feature.list\n'
        need(body.count(marker) == 1 and line not in body, 'Favorite detail import owner changed')
        body = body.replace(marker, marker+'\n'+line, 1)
    need(sha(body) == review['finalProducerSelectedSHA256'], 'Final favorite detail UI digest changed')
    return body, dict(baseline_proof, allPriorOwnerSourceGatesRetained=True,
        actualFinalOwnerToCurrent=inverse_proof(before, body))


def bind_viewmodel(repo, body):
    """Root retires/recreates its page owner; its guarded API rejects late responses."""
    def replace(before, after, count=1):
        nonlocal body
        need(body.count(before) == count, 'Favorite detail platform boundary changed: '+before)
        body = body.replace(before, after)
    replace('import com.android.purebilibili.core.store.TokenManager\n', '')
    replace('TokenManager.midCache', 'environment.currentMid()', 3)
    replace('accountRequests.launch', 'viewModelScope.launch', 4)
    # Android's inherited account callback becomes an ordinary complete method.
    # The existing Root replaces the whole entry on credential/epoch changes.
    replace('override fun onAccountChanged()', 'fun onAccountChanged()')
    replace('import kotlinx.coroutines.CancellationException\n',
            'import kotlinx.coroutines.CancellationException\nimport kotlinx.coroutines.launch\n')
    # Root creates this retained VM and supplies its route in the same call.
    # An Android-style constructor load can race that init on a queued dispatcher,
    # publishing an empty first page or duplicating the real first request.
    # Keep the complete original request algorithms; init(route) is their sole
    # initial activation point at the existing platform ownership boundary.
    replace('    init {\n        loadData()\n    }\n', '')
    return body


def viewmodel_proof(repo, body):
    original = fixed_sources(repo)[0]['SeasonSeriesDetailViewModel.kt']
    return dict(upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256,
        originalToPlatform=inverse_proof(original, body),
        existingRootScopeAndApiReused=True, credentialChangeRetiresWholeRootEntry=True,
        rootRouteInitIsSoleInitialLoad=True, redundantConstructorLoadRemoved=True,
        parallelAccountOrRepositoryOwnerAdded=False)


def playlist_item(source):
    marker = '@Serializable\ndata class PlaylistItem('
    need(source.count(marker) == 1, 'PlaylistItem declaration boundary changed')
    start = source.index(marker)
    end = source.index('\n)', start)+2
    return source[start:end]


def playlist_source(repo, queue):
    original = fixed_sources(repo)[0]['PlaylistManager.kt']
    before, after = playlist_item(queue), playlist_item(original)
    need(sha(before) == 'db39fec1cbb3d4a7e1da02d33c34a81d2250a5fbc00f9fc96de9937874133042',
         'PlaylistItem predecessor changed')
    need(sha(after) == '255eb932cd9fbbb332c23ac39b5af6e064871e6e6e4aa130bd24eab3eee0ba3e',
         'Current complete PlaylistItem declaration changed')
    need(queue.count(before) == 1, 'Duplicate original PlaylistItem owner')
    selected = queue.replace(before, after, 1)
    return selected, dict(upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256,
        completeAnnotatedOriginalDeclaration=True, soleExistingPlaylistOwnerPreserved=True,
        managerOrQueueAlgorithmsReplaced=False, originalDeclarationSHA256=sha(after),
        retainedQueueToCurrent=inverse_proof(queue, selected))


def emit_policy(repo, output, tests, predecessor):
    validate_outputs(repo, output, tests)
    sources, manifest = fixed_sources(repo)
    name = 'SpacePlaybackPolicy.kt'
    need(sha(predecessor) == manifest['reviewedTransitions'][name]['predecessorSHA256'],
         'Space playback policy predecessor changed')
    target = Path(output)/THEME/name
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(sources[name], encoding='utf8', newline='\n')
    if tests is not None:
        target = Path(tests)/THEME/'SpacePlaybackPolicyTest.kt'
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(sources[target.name], encoding='utf8', newline='\n')
    proof = dict(upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256,
        wholeOriginalPolicyEmitted=True, originalPolicyCases=17 if tests is not None else 0,
        originalAndroidViewModelTestsExecuted=False, sourceQualified=False, mainCompiled=False,
        retainedToCurrent=inverse_proof(predecessor, sources[name]))
    (Path(output)/'v044-favorite-detail-policy-proof.json').write_text(
        json.dumps(proof, ensure_ascii=False, indent=2)+'\n', encoding='utf8', newline='\n')
    return proof
