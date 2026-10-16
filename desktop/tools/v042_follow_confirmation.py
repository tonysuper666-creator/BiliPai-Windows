"""Exact v042 follow-query and confirmed-cache methods on the existing protocol."""
from pathlib import Path
import hashlib
import json

COMMIT = '5c34a82418b33bf88acea01784bb3c6b179d6491'
ARCHIVE = 'desktop/upstream-slices/v042-follow-confirmation'
MANIFEST_SHA256 = '9976d04fa221e8dbfd1bcc3b05f8d6647942d9d43e91b4dbc68b32b40c203f3e'
SOURCE_SHA256 = 'c302740f2babaf87de20682bae44714461a07cf5a20d97ebfca9d96f158d38d4'
SOURCE_BLOB = 'eb6671b5641ebfcffed05e2f6229b33ffa093037'


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def fixed_source(repo):
    root = Path(repo) / ARCHIVE
    manifest = (root / 'manifest.json').read_bytes()
    if sha(manifest) != MANIFEST_SHA256 or json.loads(manifest)['fixedUpstreamCommit'] != COMMIT:
        raise ValueError('Unreviewed confirmed-follow source manifest')
    raw = (root / 'ActionRepository.kt').read_bytes()
    oid = hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest()
    if len(raw) != 45228 or sha(raw) != SOURCE_SHA256 or oid != SOURCE_BLOB:
        raise ValueError('Changed original confirmed-follow source')
    return raw.decode('utf-8')


def apply(repo, original, select):
    source = fixed_source(repo)

    def interior(text, declaration):
        start = text.index(declaration) + len(declaration)
        return text[start:text.rfind('}')]

    old_query = select(interior(original, 'object ActionRepository {'), ['queryFollowStatus'])
    new_query = select(interior(source, 'object ActionRepository {'), ['queryFollowStatus'])
    if 'interactionCache.confirmedOperationValue(session, "follow:$mid")' not in new_query:
        raise ValueError('Original query no longer consumes confirmed operations')
    old_cache = select(original, ['VideoInteractionStatusCache'])
    new_cache = select(source, ['VideoInteractionStatusCache'])
    helper = select(interior(new_cache, ') {'), ['confirmedOperationValue'])
    if 'fun confirmedOperationValue(' in old_cache:
        raise ValueError('Existing cache method ownership changed')
    # Select the whole original class, including independent value/pending TTLs.
    # The original fifteen tests use explicit pending TTLs where needed.
    cache = new_cache
    text = original
    edits = []
    for before, after in [(old_query, new_query), (old_cache, cache)]:
        if text.count(before) != 1:
            raise ValueError('Unknown current confirmed-follow projection boundary')
        at = text.index(before)
        edits.append(dict(offset=at, before=before, after=after))
        text = text[:at] + after + text[at + len(before):]
    reverse = text
    for edit in reversed(edits):
        at = edit['offset']
        if reverse[at:at + len(edit['after'])] != edit['after']:
            raise ValueError('Confirmed-follow inverse boundary mismatch')
        reverse = reverse[:at] + edit['before'] + reverse[at + len(edit['after']):]
    if reverse != original:
        raise ValueError('Previous complete interaction source was not retained')
    return text, dict(upstreamCommit=COMMIT, archive=ARCHIVE, sourceSHA256=SOURCE_SHA256,
        sourceManifestSHA256=MANIFEST_SHA256, originalMethods=['queryFollowStatus', 'confirmedOperationValue'],
        exactOriginalMethodSHA256={'queryFollowStatus': sha(new_query.encode()), 'confirmedOperationValue': sha(helper.encode())},
        previousSourceSHA256=sha(original.encode()), projectedSourceSHA256=sha(text.encode()),
        wholePreviousInputInverseRecovered=True, edits=edits, partialReuse=True,
        wholeLatestOriginalCacheRetained=True, originalCacheSHA256=sha(new_cache.encode()),
        defaultKnownValueTtlMs=1800000, defaultPendingOperationTtlMs=60000,
        mainCompiled=False, runtimeExecuted=False)
