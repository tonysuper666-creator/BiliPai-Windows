"""Fixed v030 live stream declarations on the existing sole API/media producers."""
from pathlib import Path
import difflib
import hashlib
import importlib.util
import json
import os
import re
import textwrap

COMMIT = '0e2206a85e288ba08f361cc636fab0710c2b9ab8'
BASE = 'app/src/main/java/com/android/purebilibili/'
REPOSITORY = BASE + 'data/repository/LiveRepository.kt'
POLICY = BASE + 'feature/live/LivePlaybackPolicy.kt'
TEST = 'app/src/test/java/com/android/purebilibili/feature/live/LivePlaybackPolicyTest.kt'
API = 'core-data/src/main/java/com/android/purebilibili/core/network/ApiClient.kt'
MODEL = 'core-data/src/main/java/com/android/purebilibili/data/model/response/LiveModels.kt'
PINS = {
    REPOSITORY: ('0c69a011fa66f0c9829b066aa4fa149a865c996c', '554ca3c32522c88148144c4221879701657aab296519ea0da4162661039d4857', 56832),
    POLICY: ('934b8669be7c7dc09e93b10b211be30a8466b912', '451dc433f3776053ec09f010e0b5926e4806d8b6eb3e62fc711bd6a36d61398e', 8312),
    TEST: ('a766c1240ad0d2395a2b295382679c28bd09db98', 'e4e6075af50f86a3037f5ebaa0681cc603122d87d64f60b0080b9b69307919a0', 16757),
    API: ('76c1f1dcc3e99579b0684bc6f5d8d2e248697570', '1ed0da47deb801a5a2f6f2a63e6bf2e9e8674bb861e573b45956f1bd5ed2febb', 151172),
    MODEL: ('30e2200509bae1c1e5d325adc1205cf354910eec', '95ef6390a7f370a470b17d79aadfa87145aa0003dd5334e5d2becd1e4529b160', 14794),
}
ARCHIVE = Path(__file__).resolve().parent.parent / 'upstream-slices/v030-live-stream'

def safe(path):
    value = os.path.abspath(str(path))
    return Path('\\\\?\\' + value) if os.name == 'nt' and not value.startswith('\\\\?\\') else Path(value)

def sha(data):
    return hashlib.sha256(data).hexdigest()

def fixed_sources():
    manifest = json.loads(safe(ARCHIVE / 'manifest.json').read_bytes())
    if manifest.get('schemaVersion') != 1 or manifest.get('fixedUpstreamCommit') != COMMIT or manifest.get('hashNormalization') != 'raw':
        raise ValueError('Unknown fixed live-stream manifest')
    rows = manifest.get('files', [])
    if len(rows) != len(PINS) or {r['path'] for r in rows} != set(PINS):
        raise ValueError('Incomplete fixed live-stream inputs')
    result = {}
    for row in rows:
        path = row['path']
        blob, digest, size = PINS[path]
        expected = dict(path=path, gitBlob=blob, sha256Bytes=digest, bytes=size,
                        url='https://github.com/jay3-yy/BiliPai/blob/' + COMMIT + '/' + path)
        if row != expected:
            raise ValueError('Fixed live-stream identity changed: ' + path)
        data = safe(ARCHIVE / path).read_bytes()
        if len(data) != size or sha(data) != digest or hashlib.sha1(b'blob ' + str(size).encode() + b'\0' + data).hexdigest() != blob:
            raise ValueError('Fixed live-stream raw bytes changed: ' + path)
        # Verify the physical raw blob first; generated Kotlin uses LF text.
        result[path] = data.decode('utf8').replace('\r\n', '\n')
    return result

def whole_proof(before, after):
    left = before.splitlines(keepends=True); right = after.splitlines(keepends=True)
    a = [0]; b = [0]
    for line in left: a.append(a[-1] + len(line))
    for line in right: b.append(b[-1] + len(line))
    edits = []
    for kind, start, end, newstart, newend in difflib.SequenceMatcher(None, left, right, autojunk=False).get_opcodes():
        if kind != 'equal':
            edits.append(dict(beforeOffset=a[start], afterOffset=b[newstart],
                              before=before[a[start]:a[end]], after=after[b[newstart]:b[newend]]))
    if replay(before, edits, False) != after or replay(after, edits, True) != before:
        raise ValueError('Live-stream indexed complete inverse failed')
    return dict(beforeSha256LF=sha(before.encode()), afterSha256LF=sha(after.encode()),
                indexedEdits=edits, exactCompleteInverse=True)

def replay(value, edits, inverse):
    for edit in reversed(edits):
        pos = edit['afterOffset' if inverse else 'beforeOffset']
        before = edit['after' if inverse else 'before']; after = edit['before' if inverse else 'after']
        if value[pos:pos + len(before)] != before:
            raise ValueError('Live-stream indexed adaptation mismatch')
        value = value[:pos] + after + value[pos + len(before):]
    return value

def counted(value, before, after, label, edits, count=1):
    if value.count(before) != count:
        raise ValueError('Counted live-stream adaptation changed: ' + label)
    edits.append(dict(label=label, before=before, after=after, count=count))
    return value.replace(before, after, count)

def selected_function(repo, source, name):
    spec = importlib.util.spec_from_file_location('live_source_structure', repo / 'desktop/tools/sync-upstream.py')
    parser = importlib.util.module_from_spec(spec); spec.loader.exec_module(parser)
    match = re.search(r'(?m)^\s*(?:(?:internal|private|suspend)\s+)*fun\s+' + re.escape(name) + r'\s*\(', source)
    if match is None: raise ValueError('Missing fixed live function: ' + name)
    tokens = parser.kotlin_tokens(source)
    start = next(i for i, token in enumerate(tokens) if token[1] >= match.start())
    opening = next(i for i in range(start, len(tokens)) if tokens[i][0] == '{')
    depth = 1; end = opening
    while depth:
        end += 1; depth += (tokens[end][0] == '{') - (tokens[end][0] == '}')
    return textwrap.dedent(source[match.start():tokens[end][2]]).strip()

def api_method(source):
    match = re.search(r'    @GET\("https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo"\)\n    suspend fun getLivePlayUrl\([\s\S]*?\): LivePlayUrlResponse', source)
    if match is None: raise ValueError('Missing complete live API declaration')
    return match.group()

def model_declaration(source):
    match = re.search(r'@Serializable\ndata class LivePlayUrlData\([\s\S]*?\n\)', source)
    if match is None: raise ValueError('Missing complete live response declaration')
    return match.group()

def write_proof(output, name, path, before, after, extra=None):
    record = dict(fixedUpstreamCommit=COMMIT, originalPath=path, rawPin=PINS[path],
                  completeBody=whole_proof(before, after))
    if extra: record.update(extra)
    target = safe(output / name); target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(record, ensure_ascii=True, indent=2) + '\n', encoding='utf8', newline='\n')

def api_delta(joined, repo, output):
    from v025_source_paths import canonical_source
    original = canonical_source(repo, API).read_text(encoding='utf8').replace('\r\n', '\n')
    before = api_method(original); after = api_method(fixed_sources()[API])
    edits = []; adapted = counted(joined, before, after, 'same full signed QueryMap request', edits)
    write_proof(output, 'v030-live-api-source-proof.json', API, joined, adapted,
                dict(canonicalHashPreserved=sha(original.encode()), completeSelectedDeclaration=after, countedAdaptations=edits))
    return adapted

def emit_models(repo, output):
    from v025_source_paths import canonical_source
    original = canonical_source(repo, MODEL).read_text(encoding='utf8').replace('\r\n', '\n')
    edits = []; selected = model_declaration(fixed_sources()[MODEL])
    after = counted(original, model_declaration(original), selected,
                    'whole v030 play-url metadata; unrelated list schema unchanged', edits)
    target = safe(output / 'com/android/purebilibili/data/model/response/LiveModels.kt')
    target.parent.mkdir(parents=True, exist_ok=True); target.write_text(after, encoding='utf8', newline='\n')
    write_proof(output, 'v030-live-model-source-proof.json', MODEL, original, after,
                dict(completeSelectedDeclaration=selected, countedAdaptations=edits,
                     canonicalModelHashPreserved=sha(original.encode()), otherDeclarationsUnchanged=True))
    return target

def stream_policy(source):
    before = source; edits = []
    for name in ['androidx.media3.common.PlaybackException', 'androidx.media3.common.Player',
                 'com.android.purebilibili.feature.video.ui.components.VideoAspectRatio']:
        source = counted(source, 'import ' + name + '\n', '', 'omit unconsumed Android player import', edits)
    for name in ['resolveLivePlaybackErrorRecovery', 'shouldRecoverUnexpectedLiveEnd', 'resolveLiveViewportAspectRatio']:
        pattern = r'(?m)^internal fun ' + name + r'\('
        start = re.search(pattern, source).start()
        next_decl = re.search(r'(?m)^(?:internal|private) (?:fun|class|data class|sealed|enum)', source[start + 1:])
        end = start + 1 + next_decl.start() if next_decl else len(source)
        source = counted(source, source[start:end], '', 'omit unconsumed platform-only ' + name, edits)
    return source, edits, whole_proof(before, source)

def emit_media(repo, output, test_output=None):
    sources = fixed_sources()
    from v025_source_paths import canonical_source
    canonical_prelude = canonical_source(repo, REPOSITORY).read_text(encoding='utf8').replace('\r\n', '\n')
    if selected_function(repo, canonical_prelude, 'hasPlayableLiveUrl') != selected_function(repo, sources[REPOSITORY], 'hasPlayableLiveUrl'):
        raise ValueError('Existing original hasPlayableLiveUrl prelude changed')
    query = selected_function(repo, sources[REPOSITORY], 'buildLivePlayUrlQuery')
    request = selected_function(repo, sources[REPOSITORY], 'getLivePlayUrlWithQuality')
    original = request; edits = []
    request = counted(request, 'suspend fun getLivePlayUrlWithQuality(\n',
        'internal suspend fun requestOriginalDesktopLiveStream(\n    api: com.android.purebilibili.core.network.BilibiliApi,\n    signWithWbi: suspend (Map<String, String>) -> Map<String, String>,\n',
        'stateless actual existing API and signer request ports', edits)
    request = counted(request, 'val realRoomId = resolveRealRoomId(roomId)', 'val realRoomId = roomId',
        'roomId already resolved and checked by existing Windows liveRoom', edits)
    anchor = 'val resp = api.getLivePlayUrl(signWithWbi(buildLivePlayUrlQuery(realRoomId, qn, onlyAudio)))'
    request = counted(request, anchor, anchor + '\n        if (resp.code in setOf(-101, -352, -412, -403)) {\n            return@withContext Result.failure(com.bilipai.desktop.data.BiliApiException(resp.code, resp.message))\n        }',
        'preserve existing Windows permission denial without legacy fallback', edits)
    body = 'package com.android.purebilibili.data.repository\n\nimport com.android.purebilibili.data.model.response.*\nimport kotlinx.coroutines.CancellationException\nimport kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.withContext\n\n' + query + '\n\n' + request + '\n'
    target = safe(output / 'com/android/purebilibili/data/repository/DesktopOriginalLiveStreamRequest.kt')
    target.parent.mkdir(parents=True, exist_ok=True); target.write_text(body, encoding='utf8', newline='\n')
    write_proof(output, 'v030-live-request-source-proof.json', REPOSITORY, sources[REPOSITORY], body,
        dict(queryOriginalExact=query, requestSelectionOriginal=original, requestSelectionAdapted=request,
             selectedRequestInverse=whole_proof(original, request), countedAdaptations=edits,
             bodySelectionOnly=True, existingHasPlayableLiveUrlPreludeConsumer=True))
    policy, adaptations, inverse = stream_policy(sources[POLICY])
    policy_target = safe(output / 'com/android/purebilibili/feature/live/DesktopOriginalLiveStreamPolicy.kt')
    policy_target.parent.mkdir(parents=True, exist_ok=True); policy_target.write_text(policy, encoding='utf8', newline='\n')
    write_proof(output, 'v030-live-policy-source-proof.json', POLICY, sources[POLICY], policy,
                dict(countedAdaptations=adaptations, exactCandidateAndAdvanceBodies=True))
    if test_output is not None: emit_tests(test_output, sources[TEST])
    return [target, policy_target]

def emit_tests(output, source=None):
    source = source if source is not None else fixed_sources()[TEST]
    original = source; edits = []
    for name in ['androidx.media3.common.PlaybackException', 'androidx.media3.common.Player',
                 'com.android.purebilibili.feature.video.ui.components.VideoAspectRatio']:
        source = counted(source, 'import ' + name + '\n', '', 'omit tests for platform-only methods', edits)
    annotations = list(re.finditer(r'(?m)^    @Test\n', source))
    for index in reversed(range(len(annotations))):
        start = annotations[index].start()
        if index + 1 < len(annotations): end = annotations[index + 1].start()
        else: end = source.index('    private fun playbackData(', start)
        method = source[start:end]
        if any(name in method for name in ['resolveLiveViewportAspectRatio(', 'resolveLivePlaybackErrorRecovery(', 'shouldRecoverUnexpectedLiveEnd(']):
            source = source[:start] + source[end:]
    target = safe(output / 'com/android/purebilibili/feature/live/LivePlaybackPolicyTest.kt')
    target.parent.mkdir(parents=True, exist_ok=True); target.write_text(source, encoding='utf8', newline='\n')
    write_proof(output, 'v030-live-original-test-source-proof.json', TEST, original, source,
                dict(originalTestCount=original.count('@Test'), retainedTestCount=source.count('@Test'),
                     removedOnlyUnconsumedPlatformMethods=True))
    return target
