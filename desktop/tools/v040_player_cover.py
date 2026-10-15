"""Current original cover policies and caller, projected into the sole player producer."""
from pathlib import Path
import hashlib
import json
import re

COMMIT = '415d01ec5bc3ac82521a9ac47bcc449b8e202460'
BASE_COMMIT = '8b47769d22c0d5bc88ae5248cee4aa04ff10d62c'
ARCHIVE = 'desktop/upstream-slices/v040-player-cover'
MANIFEST_SHA256 = '835d7ed7458ad40761e84452e95b5e3c8b9e6a78ee3d99829349950b3ebeefbc'
NAMES = ('resolveVideoPlayerCoverMotionSpec', 'resolveVideoPlayerEntryPresentationSpec')
OLD_HASHES = {
    NAMES[0]: 'e05dc1913894adeb550ee6d039ebb11e926a86fa8fe21dbe55ba65e247f7a34c',
    NAMES[1]: 'e4c8c2d3ee7cc04d04fc4a2a526aa87ac7168d88daad2b12aa5bc9846d41dc08',
}
NEW_HASHES = {
    NAMES[0]: 'bdd301edb68eb3c16b0d1a43789ef77323e8b7600ca16ee5b65e65890f4eede3',
    NAMES[1]: 'a74ed174dc491fa447dcdbae0c780f8062649a9783f4a4e4e94149538cb6d7ee',
}
OLD_CALLER_SHA256 = '296d6db182982949420f08ae8dcaa14bd7e604c5645830cd0d40ce7ac7ac9bfc'
NEW_CALLER_SHA256 = 'b142dd2cf41fe0beada9158dbdc74bc78727d1544c59d547b882c6e176db4508'
OLD_CONTAINER_SHA256 = 'b18475d37ee39760e5474342b7eae227345abf9851e193ab3b11d3f7c26e97f5'
NEW_CONTAINER_SHA256 = 'c440bebf98392b4505c77185fecc87ab10484394b941f40550234ac7b91c6c74'
ORIGINAL_TEST_NAMES = (
    'returnCoverPresentation_usesSharedBoundsDuringForcedReturn',
    'returnCoverPresentation_usesSharedBoundsForManualStart',
    'returnCoverPresentation_doesNotUseSharedBoundsForNormalPlayback',
)


def sha(raw):
    return hashlib.sha256(raw.encode('utf-8') if isinstance(raw, str) else raw).hexdigest()


def fixed_sources(repo):
    root = Path(repo) / ARCHIVE
    raw = (root / 'manifest.json').read_bytes()
    if sha(raw) != MANIFEST_SHA256:
        raise ValueError('Unreviewed player-cover manifest')
    manifest = json.loads(raw)
    if manifest['fixedUpstreamCommit'] != COMMIT or {
        row['filename'] for row in manifest['files']
    } != {'VideoPlayerSectionPolicy.kt', 'VideoPlayerSectionPolicyTest.kt',
          'VideoDetailCoverContentScale.kt', 'VideoPlayerSection.kt'}:
        raise ValueError('Unreviewed player-cover source identity')
    result = {}
    for row in manifest['files']:
        raw = (root / row['filename']).read_bytes()
        blob = hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest()
        if len(raw) != row['bytes'] or sha(raw) != row['sha256Bytes'] or blob != row['gitBlob']:
            raise ValueError('Modified fixed player-cover source: ' + row['path'])
        result[row['filename']] = raw
    return result


def register_identity(repo, filename, sources):
    raw = fixed_sources(repo)[filename]
    manifest = json.loads((Path(repo) / ARCHIVE / 'manifest.json').read_bytes())
    row = next(row for row in manifest['files'] if row['filename'] == filename)
    local = ARCHIVE + '/' + filename
    text = raw.decode('utf-8').replace('\r\n', '\n')
    identity = dict(text=text, upstreamCommit=COMMIT, originalPath=row['path'],
        sha256LF=sha(text), gitBlob=row['gitBlob'], localPath=local,
        sourceManifestSha256=MANIFEST_SHA256)
    sources[local] = identity
    return text, {key: value for key, value in identity.items() if key != 'text'}


def declaration(text, name, protocol):
    matches = list(re.finditer(r'(?m)^[ \t]*(?:internal )?fun ' + re.escape(name) + r'\(', text))
    if len(matches) != 1:
        raise ValueError('Expected one complete player-cover declaration: ' + name)
    start = matches[0].start()
    masked = protocol.masked(text)
    opening = masked.index('(', start)
    after_parameters = protocol.balanced(masked, opening, '(', ')')
    opening = masked.index('{', after_parameters)
    end = protocol.balanced(masked, opening, '{', '}')
    return text[start:end + (text[end:end + 1] == '\n')]


def project_members(repo, members, sources, protocol):
    current, identity = register_identity(repo, 'VideoPlayerSectionPolicy.kt', sources)
    projected = dict(members)
    changes = []
    for name in NAMES:
        if name not in members:
            raise ValueError('Missing existing player-cover member: ' + name)
        before = declaration(members[name], name, protocol)
        after = declaration(current, name, protocol)
        if sha(before) != OLD_HASHES[name] or sha(after) != NEW_HASHES[name]:
            raise ValueError('Unreviewed player-cover declaration drift: ' + name)
        if members[name].count(before) != 1:
            raise ValueError('Ambiguous existing player-cover member: ' + name)
        projected[name] = members[name].replace(before, after, 1)
        if projected[name].replace(after, before, 1) != members[name]:
            raise ValueError('Player-cover member inverse failed: ' + name)
        changes.append(dict(name=name, before=before, after=after,
            beforeSHA256=sha(before), afterSHA256=sha(after)))
    if any(projected[name] != body for name, body in members.items() if name not in NAMES):
        raise ValueError('Unrelated player-policy member changed')
    return projected, dict(baseFixedCommit=BASE_COMMIT, selectedFixedCommit=COMMIT,
        source=identity, declarations=changes, fullOriginalDeclarationBodies=True,
        everyUnrelatedMemberPreserved=True, fullV040PlayerParityClaimed=False)


def caller_block(text, protocol):
    anchor = '    val coverMotionSpec = remember'
    if text.count(anchor) != 1:
        raise ValueError('Expected one existing cover remember/caller block')
    start = text.index(anchor)
    masked = protocol.masked(text)
    opening = masked.index('(', start)
    after_parameters = protocol.balanced(masked, opening, '(', ')')
    opening = masked.index('{', after_parameters)
    end = protocol.balanced(masked, opening, '{', '}')
    return text[start:end + (text[end:end + 1] == '\n')]


def cover_container_block(text, protocol):
    anchor = '            val coverContainerModifier = if ('
    if text.count(anchor) != 1:
        raise ValueError('Expected one original cover container declaration')
    start = text.index(anchor)
    masked = protocol.masked(text)
    opening = masked.index('(', start)
    after_parameters = protocol.balanced(masked, opening, '(', ')')
    opening = masked.index('{', after_parameters)
    end = protocol.balanced(masked, opening, '{', '}')
    other = re.match(r'\s*else\s*\{', masked[end:])
    if other is None:
        raise ValueError('Original cover container else branch changed')
    end = protocol.balanced(masked, end + other.end() - 1, '{', '}')
    return text[start:end + (text[end:end + 1] == '\n')]


def project_caller(repo, text, sources, protocol):
    current, identity = register_identity(repo, 'VideoPlayerSection.kt', sources)
    before = caller_block(text, protocol)
    after = caller_block(current, protocol)
    if sha(before) != OLD_CALLER_SHA256 or sha(after) != NEW_CALLER_SHA256:
        raise ValueError('Unreviewed player-cover caller drift')
    offset = text.index(before)
    result = text[:offset] + after + text[offset + len(before):]
    edits = [dict(offset=offset, before=before, after=after)]
    before_container = cover_container_block(result, protocol)
    after_container = cover_container_block(current, protocol)
    if sha(before_container) != OLD_CONTAINER_SHA256 or sha(after_container) != NEW_CONTAINER_SHA256:
        raise ValueError('Unreviewed player-cover container drift')
    offset = result.index(before_container)
    result = result[:offset] + after_container + result[offset + len(before_container):]
    edits.append(dict(offset=offset, before=before_container, after=after_container))
    inverse = result
    for edit in reversed(edits):
        offset, after = edit['offset'], edit['after']
        if inverse[offset:offset + len(after)] != after:
            raise ValueError('Player-cover caller inverse boundary changed')
        inverse = inverse[:offset] + edit['before'] + inverse[offset + len(after):]
    if inverse != text:
        raise ValueError('Player-cover caller inverse failed')
    return result, dict(baseFixedCommit=BASE_COMMIT, selectedFixedCommit=COMMIT,
        source=identity, indexedEdits=edits,
        fullCallerBlockOriginal=True, fullCoverContainerDeclarationOriginal=True,
        allOtherOriginalSectionBytesPreserved=True,
        windowsForegroundPortalPreserved=True, androidUnderlayCapabilityNotMapped=True,
        fullV040PlayerParityClaimed=False)


def emit_tests(repo, output, protocol):
    raw = fixed_sources(repo)['VideoPlayerSectionPolicyTest.kt']
    original = raw.decode('utf-8').replace('\r\n', '\n')
    methods = [declaration(original, name, protocol) for name in ORIGINAL_TEST_NAMES]
    text = ('package com.android.purebilibili.feature.video.ui.section\n\n'
        'import org.junit.jupiter.api.Test\nimport kotlin.test.assertEquals\n'
        'import kotlin.test.assertFalse\nimport kotlin.test.assertTrue\n\n'
        'class DesktopV040OriginalPlayerCoverPolicyTest {\n' +
        '\n'.join('    @Test\n' + method for method in methods) + '}\n')
    path = Path(output) / 'com/android/purebilibili/feature/video/ui/section/DesktopV040OriginalPlayerCoverPolicyTest.kt'
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding='utf-8', newline='\n')
    proof = dict(fixedCommit=COMMIT, originalTestSHA256=sha(raw),
        wholeOriginalMethods=[dict(name=name, sha256LF=sha(method))
            for name, method in zip(ORIGINAL_TEST_NAMES, methods)],
        originalMethodsUnmodified=True, onlyWrapperAndTestAnnotationPlatformAdapted=True,
        actualCases=3, jvmExecuted=False)
    (Path(output) / 'v040-player-cover-original-test-proof.json').write_text(
        json.dumps(proof, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
