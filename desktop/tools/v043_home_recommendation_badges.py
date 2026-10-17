"""Original recommendation badge/author slot and exact hooks in retained card producers.

The three complete shared sources have one existing full-card producer. Each
caller hook comes from the fixed whole original card, retains its Windows
platform bindings and is reversible. Canonical v025 sources stay unchanged.
"""
from pathlib import Path
import difflib
import hashlib
import json
from v025_source_paths import canonical_source
import v043_home_native_cards as native_cards

COMMIT = '35e2fac8a3870a2227a323e19f29f1e93d7e591c'
ARCHIVE = 'desktop/upstream-slices/v043-home-recommendation-badges'
MANIFEST_SHA256 = '2f84b9d058010331081cc4c0c101ca52e9a932f5408c4fbfb7b8f2236a1c67ee'
PINS = [{'path': 'app/src/main/java/com/android/purebilibili/feature/home/components/cards/VideoRecommendationBadge.kt',
  'archiveFile': 'VideoRecommendationBadge.kt',
  'bytes': 1412,
  'sha256': '3be56014c1ebf01e6a9236347d895f40aacdf2c780a9b1005eda524a4751e3d9',
  'gitBlob': '1e21389f9c5381ed72c79512d7b5db265e2b7106',
  'referenceOnly': False},
 {'path': 'app/src/main/java/com/android/purebilibili/core/ui/components/UpBadgeName.kt',
  'archiveFile': 'UpBadgeName.kt',
  'bytes': 6214,
  'sha256': '24b59fdf75cbeddd017bf41e6b7bc799c88550374ebe3521f578afa92923e672',
  'gitBlob': '2716bf2df5740fb7cb887fa6cd1529e2d11152aa',
  'referenceOnly': False},
 {'path': 'app/src/main/java/com/android/purebilibili/core/ui/transition/VideoCardSourceSnapshot.kt',
  'archiveFile': 'VideoCardSourceSnapshot.kt',
  'bytes': 6735,
  'sha256': 'cc6683cda448d47948c0aad84ba1c7712642a7ad636d53a2a7b7167fe3456166',
  'gitBlob': 'fb23bace655c4def332e8aeba11819ecf6f31994',
  'referenceOnly': False},
 {'path': 'app/src/main/java/com/android/purebilibili/feature/home/components/cards/StoryVideoCard.kt',
  'archiveFile': 'StoryVideoCard.kt',
  'bytes': 25298,
  'sha256': '4afa0ad9b8fc1e16fae25b4ee3f5871fb23e9dacef218e4795867e2fc497a8f8',
  'gitBlob': '5cf4cb7096958c1edbae99e37285e398e9cc58cc',
  'referenceOnly': True}]
PREDECESSORS = {'UpBadgeName.kt': '3e1201e5abd23916a836f16e9a2130f6d0b43c38f87ef5473240c55fb663e2d6', 'VideoCardSourceSnapshot.kt': '6dea5fc155bbf1908bb8a89bc5dbcf5880ce9ca63d4c4a340298bfe791e39a48'}
REVIEWED_DELTAS = {'UpBadgeName.kt': [['replace',
                     49,
                     50,
                     49,
                     52,
                     '2d47956b6856d631ce4ef71c3d01ecc80bb1a01ddc94c8bfb8d185de180bf111',
                     '4be9ca366a35edc8cd39aa89cef41c7e7799776de6eeaed15198618290aba986'],
                    ['insert',
                     64,
                     64,
                     66,
                     71,
                     'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
                     '9739c34981917a233993430aac5bd5170cd47548d59615e3850387a55decadd2']],
 'VideoCardSourceSnapshot.kt': [['insert',
                                 4,
                                 4,
                                 4,
                                 5,
                                 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
                                 '77bcf981b52737eefd0691ce472984a94f43832fa42f0f95d707ff91de827fa9'],
                                ['insert',
                                 90,
                                 90,
                                 91,
                                 92,
                                 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
                                 '26f382d0f99c029b30d28f884cbd9aa71f86cc403c747b82e7021c8e03158124'],
                                ['insert',
                                 101,
                                 101,
                                 103,
                                 105,
                                 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
                                 '35341f746d39d05bd0621ed58412d6825cec52d1a18a460439460b6a8d6c5ffe']]}
APP = 'app/src/main/java/com/android/purebilibili/'
REPLACED = (APP+'core/ui/components/UpBadgeName.kt', APP+'core/ui/transition/VideoCardSourceSnapshot.kt')


def sha(raw):
    return hashlib.sha256(raw.encode('utf-8') if isinstance(raw, str) else raw).hexdigest()


def need(condition, message):
    if not condition:
        raise ValueError(message)


def verified_raw(repo):
    root = Path(repo).resolve()/ARCHIVE
    need(root.is_dir() and not root.is_symlink(), 'Missing or linked v043 badge archive')
    leaves = {'manifest.json', *(row['archiveFile'] for row in PINS)}
    need({p.name for p in root.iterdir()} == leaves, 'Unexpected v043 badge archive leaves')
    for name in leaves:
        need((root/name).is_file() and not (root/name).is_symlink(), 'Linked/non-file badge input: '+name)
    raw = (root/'manifest.json').read_bytes()
    need(sha(raw) == MANIFEST_SHA256, 'v043 badge manifest pin changed')
    manifest = json.loads(raw)
    need((manifest['schemaVersion'], manifest['repository'], manifest['tag'], manifest['commit'], manifest['files']) ==
         (1, 'jay3-yy/BiliPai', 'v0.4.3', COMMIT, PINS), 'v043 badge manifest identity changed')
    result = {}
    for row in PINS:
        raw = (root/row['archiveFile']).read_bytes()
        need(len(raw) == row['bytes'] and sha(raw) == row['sha256'], 'v043 badge bytes changed: '+row['archiveFile'])
        need(hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest() == row['gitBlob'],
             'v043 badge Git blob changed: '+row['archiveFile'])
        result[row['archiveFile']] = raw
    return result


def check_output(repo, output):
    archive, output = (Path(repo)/ARCHIVE).resolve(), Path(output).resolve()
    need(not (output.is_relative_to(archive) or archive.is_relative_to(output)), 'Output overlaps fixed badge source')


def advance_original(name, before, after):
    need(sha(before) == PREDECESSORS[name], 'The complete badge predecessor changed: '+name)
    old, new = before.splitlines(keepends=True), after.splitlines(keepends=True)
    ops = [op for op in difflib.SequenceMatcher(None, old, new, autojunk=False).get_opcodes() if op[0] != 'equal']
    reviewed = [[tag, a, b, c, d, sha(b''.join(old[a:b])), sha(b''.join(new[c:d]))]
                for tag, a, b, c, d in ops]
    need(reviewed == REVIEWED_DELTAS[name], 'Unreviewed complete badge source delta: '+name)
    forward, inverse = old[:], new[:]
    for _, a, b, c, d in reversed(ops):
        forward[a:b] = new[c:d]
        inverse[c:d] = old[a:b]
    need(b''.join(forward) == after and b''.join(inverse) == before,
         'Complete badge source inverse failed: '+name)
    return dict(predecessorSha256=sha(before), reviewedHunks=reviewed, completeOriginalInverse=True)


def emit_originals(repo):
    files = verified_raw(repo)
    result = {}
    for row in PINS:
        if row['referenceOnly']:
            continue
        raw, name = files[row['archiveFile']], row['archiveFile']
        proof = dict(mode='raw-v043', source=row['path'], upstreamCommit=COMMIT,
                     sha256=sha(raw), bytes=len(raw), identicalToArchive=True)
        if name in PREDECESSORS:
            before = canonical_source(repo, row['path']).read_bytes().replace(b'\r\n', b'\n')
            proof.update(advance_original(name, before, raw))
        result[row['path'].split('/src/main/java/', 1)[1]] = (raw, proof)
    return result


def original_card(repo, name):
    if name == 'StoryVideoCard.kt':
        return verified_raw(repo)[name].decode('utf-8')
    need(name in {'VideoCard.kt', 'HomeStyleSingleColumnVideoCard.kt'}, 'Unknown recommendation card caller')
    return native_cards.verified_raw(repo)[name].decode('utf-8')


def argument_block(text, marker, next_marker):
    need(text.count(marker) == 1, 'Badge caller start anchor changed: '+marker)
    start = text.index(marker)
    need(text.count(next_marker, start) == 1, 'Badge caller end anchor changed: '+next_marker)
    return text[start:text.index(next_marker, start)]


def replace(text, before, after, label, audits):
    need(text.count(before) == 1, 'Recommendation badge caller anchor changed: '+label)
    result = text.replace(before, after, 1)
    need(result.count(after) == 1 and result.replace(after, before, 1) == text,
         'Recommendation badge caller inverse failed: '+label)
    audits.append(dict(id=label, beforeSha256=sha(before), afterSha256=sha(after), inverseRestoresInput=True))
    return result


def apply_card(repo, name, text, audits):
    if name not in {'VideoCard.kt', 'HomeStyleSingleColumnVideoCard.kt', 'StoryVideoCard.kt'}:
        return text
    source = original_card(repo, name)
    if name in {'VideoCard.kt', 'HomeStyleSingleColumnVideoCard.kt'}:
        indent = '        ' if name == 'VideoCard.kt' else '                '
        next_marker = indent+('leadingContent = if (showUpAvatar' if name == 'VideoCard.kt' else 'nameStyle = contentTypography.author,')
        before = argument_block(text, indent+'inlineTrailingContent = if (isFollowing) {', next_marker)
        after = argument_block(source, indent+'nameLeadingContent = if (isFollowing || video.isFollowed || video.recommendationReason.isNotBlank()) {', next_marker)
        text = replace(text, before, after, 'recommendation-author-leading-'+name, audits)
        before_follow = 'followed = video.isFollowed,' if name == 'VideoCard.kt' else 'followed = isFollowing,'
        text = replace(text, before_follow, 'followed = isFollowing || video.isFollowed,',
                       'recommendation-followed-snapshot-'+name, audits)
    else:
        marker = '                nameStyle = contentTypography.author.copy(fontWeight = FontWeight.Medium),'
        block = argument_block(source, '                nameLeadingContent = if (video.recommendationReason.isNotBlank() || video.isFollowed) {', marker)
        text = replace(text, marker, block+marker, 'recommendation-story-author-leading', audits)
        before = '                maxLines = Int.MAX_VALUE,\n                overflow = TextOverflow.Visible,'
        after = '''                maxLines = 1,
                overflow = TextOverflow.Ellipsis,'''
        need(after in source, 'Original one-line Story author policy changed')
        text = replace(text, before, after, 'recommendation-story-single-line-author', audits)
    before = '                    ownerFaceUrl = video.owner.face,\n'
    after = before+'                    recommendationReason = video.recommendationReason,\n'
    if name == 'StoryVideoCard.kt':
        after += '                    followed = video.isFollowed,\n'
    need(after in source, 'Original recommendation snapshot arguments changed: '+name)
    text = replace(text, before, after, 'recommendation-reason-snapshot-'+name, audits)
    color_import = 'import com.android.purebilibili.core.ui.AppContentColors\n'
    if name != 'StoryVideoCard.kt' and color_import not in text:
        package = 'package com.android.purebilibili.feature.home.components.cards\n'
        text = replace(text, package, package+'\n'+color_import, 'recommendation-color-import-'+name, audits)
    return text
