"""Fixed full v030 share source, using the existing sole share/API producers.

The existing stable platform recipe is transplanted onto the complete newer raw
source. Every resulting replacement records its actual offset and full inverse.
No raw hash, canonical source identity or original UI body is silently replaced.
"""
from pathlib import Path
import difflib
import hashlib
import json

COMMIT = '0e2206a85e288ba08f361cc636fab0710c2b9ab8'
SLICE = 'desktop/upstream-slices/v030-video-dynamic-share'
ROOT = Path(__file__).resolve().parent.parent / 'upstream-slices/v030-video-dynamic-share'
PINS = {
 'VideoShareSheet.kt': ('668fc2bfdbada0ed1b515f0e968b75ee8ac7a65d40d9b8d51fd24ac84294381d', '7b678ef67f93f986d69497b191bf2d9b4b751b5f'),
 'VideoSharePolicy.kt': ('b5c051390305b5084a583331997664bad2b089465f8c1f6c0c72b611a7287070', '2aad50f69f6cdc3b8895ac28a4b6e48a0870b3bc'),
 'VideoShareToDynamicDialog.kt': ('11154580c088dd3344892240aaa40890dd2ae2557c2b923f1b48fed83b0cb16d', 'b35a6217c49068c11ada3f896a07c6d88edde489'),
 'VideoDynamicShareRepository.kt': ('a760f172b83314a83b0192573598d4dbb64e1605c53456355b44d8b3960724d1', 'c24ce1d78896516d70eccf5e49b7dbcbca63b031'),
 'DynamicCreateModels.kt': ('ea6d1959a53c7aa53250432a7d404c4bae60d70ce5f45bcbca38f33b8b2f1868', 'a725af09ea44104353e5d88e920b04be881ef86f'),
}

SOURCE_PATHS = {name: ('core-data/src/main/java/com/android/purebilibili/data/model/response/' if name == 'DynamicCreateModels.kt' else
    'app/src/main/java/com/android/purebilibili/data/repository/' if name == 'VideoDynamicShareRepository.kt' else
    'app/src/main/java/com/android/purebilibili/feature/video/share/') + name for name in PINS}

def wide(path):
    path = Path(path).absolute()
    return Path('\\\\?\\' + str(path)) if __import__('os').name == 'nt' else path

def sha(value):
    return hashlib.sha256(value.encode() if isinstance(value, str) else value).hexdigest()

def sources(repo):
    root = ROOT
    manifest = json.loads(wide(root / 'manifest.json').read_bytes())
    assert manifest['upstreamCommit'] == COMMIT and len(manifest['sources']) == len(PINS)
    result = {}
    for row in manifest['sources']:
        name = row['file']
        assert name in PINS and name not in result
        assert row['path'] == SOURCE_PATHS[name]
        raw = wide(root / name).read_bytes()
        blob = hashlib.sha1(('blob ' + str(len(raw)) + '\0').encode() + raw).hexdigest()
        assert (sha(raw), blob) == PINS[name] == (row['sha256'], row['gitBlob'])
        assert len(raw) == row['bytes'] and b'\r' not in raw and not raw.startswith(b'\xef\xbb\xbf')
        result[name] = raw.decode('utf8')
    assert set(result) == set(PINS)
    return result

class Edits:
    def __init__(self, original):
        self.original = self.body = original
        self.rows = []
    def at(self, pos, before, after):
        assert self.body[pos:pos + len(before)] == before
        self.body = self.body[:pos] + after + self.body[pos + len(before):]
        self.rows.append(dict(offset=pos, before=before, after=after))
    def once(self, before, after):
        assert before and self.body.count(before) == 1, (before, self.body.count(before))
        self.at(self.body.index(before), before, after)
    def proof(self):
        restored = self.body
        for row in reversed(self.rows):
            pos = row['offset']; after = row['after']
            assert restored[pos:pos + len(after)] == after
            restored = restored[:pos] + row['before'] + restored[pos + len(after):]
        assert restored == self.original
        name = next(name for name, pin in PINS.items() if pin[0] == sha(self.original))
        return dict(upstreamCommit=COMMIT, sourcePath=SOURCE_PATHS[name], gitBlob=PINS[name][1],
                    rawSha256=sha(self.original), generatedSha256=sha(self.body),
                    fullSourceInverseVerified=True, edits=self.rows)

def _boundary(old, new, pos, *, end=False):
    """Translate exact token boundaries without consuming adjacent new members."""
    old_lines = old.splitlines(keepends=True); new_lines = new.splitlines(keepends=True)
    old_at = [0]; new_at = [0]
    for line in old_lines: old_at.append(old_at[-1] + len(line))
    for line in new_lines: new_at.append(new_at[-1] + len(line))
    candidates = []
    for tag, ai, bi, ci, di in difflib.SequenceMatcher(None, old_lines, new_lines, autojunk=False).get_opcodes():
        a, b, c, d = old_at[ai], old_at[bi], new_at[ci], new_at[di]
        if a <= pos <= b:
            if tag == 'equal':
                mapped = c + pos - a
                if a < pos < b: return mapped
                candidates.append(mapped)
            elif pos == a or pos == b:
                if pos == a: candidates.append(c)
                if pos == b: candidates.append(d)
            else:
                for inner, x, y, u, v in difflib.SequenceMatcher(None, old[a:b], new[c:d], autojunk=False).get_opcodes():
                    relative = pos - a
                    if x <= relative <= y:
                        if inner == 'equal':
                            if x < relative < y: return c + u + relative - x
                            candidates.append(c + u + relative - x)
                        elif relative == x or relative == y:
                            if relative == x: candidates.append(c + u)
                            if relative == y: candidates.append(c + v)
                if not candidates: raise AssertionError(('A platform edit cuts a changed raw member', tag, pos, a, b))
    if candidates: return min(candidates) if end else max(candidates)
    raise AssertionError(('Unmapped original boundary', pos))

def advance_share(repo, spec, original, old_generated):
    name = Path(spec['source']).name
    if name not in ('VideoShareSheet.kt', 'VideoSharePolicy.kt'):
        return old_generated, None
    raw = sources(repo)[name]
    changes = Edits(raw)
    old = original
    for row in spec['operations']:
        pos = row['offset']; before = row['before']; after = row['after']
        assert old[pos:pos + len(before)] == before
        start = _boundary(old, changes.body, pos)
        end = _boundary(old, changes.body, pos + len(before), end=True)
        actual_before = changes.body[start:end]
        if name == 'VideoSharePolicy.kt' and 'internal enum class VideoShareTarget {' in after:
            after = after.replace('BILIBILI_FRIENDS,', 'BILIBILI_FRIENDS,BILIBILI_DYNAMIC,')
        if name == 'VideoShareSheet.kt' and 'copyPlainTextToClipboard(context' in before:
            assert 'VideoShareFeedbackEvents.prepared(payload.bvid)' in actual_before
            after = after.replace('                                    context.showFeedback',
                                  '                                    shareScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { context.sharePrepared(payload.bvid) }\n                                    context.showFeedback')
        changes.at(start, actual_before, after)
        old = old[:pos] + row['after'] + old[pos + len(before):]
    assert old == old_generated
    if name == 'VideoShareSheet.kt':
        # Installed Android app-icon probing/AndroidView is absent from the
        # existing Windows vector/text renderer; preserve the original item fill.
        changes.once('.background(if (appIcon == null) item.backgroundColor else Color.Transparent)',
                     '.background(item.backgroundColor)')
        changes.once('import com.android.purebilibili.core.store.TokenManager\n', '')
        changes.once('TokenManager.csrfCache.isNullOrBlank()', '!context.canShareToDynamic()')
        changes.once('                                        Toast.makeText(context, "请先登录后分享到动态", Toast.LENGTH_SHORT).show()',
                     '                                        context.showFeedback("请先登录后分享到动态")')
        # The existing share presentation delta still adapts the following branch.
        # Adapt the new branch here, with bounded publication and IO outside it.
        changes.once('''                                    switchingSheet = true
                                    shareScope.launch {
                                        hideVideoShareSheet(sheetState)
                                        showDynamicComposer = true
''', '''                                    val dynamicAdmitted = context.withAdmission { switchingSheet = true }
                                    if (!dynamicAdmitted) return@VideoShareSheetItemView
                                    shareScope.launch {
                                        hideVideoShareSheet(sheetState)
                                        context.withAdmission { showDynamicComposer = true }
''')
        changes.once('if (count > 0) VideoShareFeedbackEvents.prepared(payload.bvid)',
                     'if (count > 0) shareScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { context.sharePrepared(payload.bvid) }')
        assert 'VideoShareFeedbackEvents' not in changes.body
    return changes.body, changes.proof()

def dialog(repo):
    edits = Edits(sources(repo)['VideoShareToDynamicDialog.kt'])
    edits.once('import android.widget.Toast\n', '')
    edits.once('import androidx.compose.ui.platform.LocalContext\n', '')
    edits.once('import com.android.purebilibili.data.repository.VideoDynamicShareRepository\n', '')
    edits.once('    val context = LocalContext.current',
               '    val context = com.bilipai.desktop.ui.LocalDesktopVideoShareBindings.current')
    edits.once('onDismissRequest = { if (!posting) onDismiss() },',
               'onDismissRequest = { if (!posting) context.withAdmission { onDismiss() } },')
    edits.once('onValueChange = { if (!posting) { text = it.take(2000); error = null } },',
               'onValueChange = { if (!posting) context.withAdmission { text = it.take(2000); error = null } },')
    edits.once('AppDialogAction(onClick = { if (!posting) onDismiss() })',
               'AppDialogAction(onClick = { if (!posting) context.withAdmission { onDismiss() } })')
    edits.once('''                    posting = true
                    error = null
''', '''                    if (!context.withAdmission { posting = true; error = null }) return@AppDialogAction
''')
    edits.once('VideoDynamicShareRepository.share(payload.bvid, text)', 'context.shareToDynamic(payload.bvid, text)')
    edits.once('''                                    VideoShareFeedbackEvents.prepared(payload.bvid)
                                    Toast.makeText(context, "已分享到动态", Toast.LENGTH_SHORT).show()
                                    onDismiss()
''', '''                                    context.sharePrepared(payload.bvid)
                                    context.withAdmission {
                                        context.showFeedback("已分享到动态")
                                        onDismiss()
                                    }
''')
    edits.once('onFailure = { error = it.message ?: "分享失败，请重试" },',
               'onFailure = { if (it is kotlinx.coroutines.CancellationException) throw it\n                                    context.withAdmission { error = it.message ?: "分享失败，请重试" } },')
    edits.once('                            posting = false', '                            context.withAdmission { posting = false }')
    return edits.body, edits.proof()

def repository(repo):
    edits = Edits(sources(repo)['VideoDynamicShareRepository.kt'])
    edits.once('import com.android.purebilibili.core.network.NetworkModule\n',
               'import com.android.purebilibili.core.network.BilibiliApi\nimport com.android.purebilibili.core.network.DynamicApi\n')
    edits.once('import com.android.purebilibili.core.store.TokenManager\n', '')
    edits.once('import kotlinx.coroutines.withContext\n',
               'import kotlinx.coroutines.withContext\nimport kotlinx.coroutines.currentCoroutineContext\nimport kotlinx.coroutines.ensureActive\n')
    edits.once('object VideoDynamicShareRepository {', 'internal object DesktopOriginalVideoDynamicShareRepository {')
    edits.once('''    suspend fun share(bvid: String, text: String): Result<String> = withContext(Dispatchers.IO) {
        try {
''', '''    suspend fun share(api: BilibiliApi, dynamicApi: DynamicApi, csrfProvider: () -> String,
        bvid: String, text: String, isCurrent: () -> Boolean,
        admit: ((() -> Unit) -> Boolean)): Result<String> = withContext(Dispatchers.IO) {
        val requestContext = currentCoroutineContext()
        fun current() {
            requestContext.ensureActive()
            if (!isCurrent()) throw CancellationException("视频动态分享来源已退役")
        }
        fun admitted() {
            current()
            if (!admit { current() }) throw CancellationException("视频动态分享许可已退役")
            current()
        }
        try {
            admitted()
''')
    edits.once('TokenManager.csrfCache.orEmpty()', 'csrfProvider()')
    edits.once('NetworkModule.api.getVideoInfo(bvid)', 'api.getVideoInfo(bvid).also { current() }')
    edits.once('''            val response = NetworkModule.dynamicApi.createFeedDynamic(csrf = csrf, body = request)
''', '''            admitted()
            val response = dynamicApi.createFeedDynamic(csrf = csrf, body = request)
            admitted()
''')
    edits.once('''        } catch (failure: Exception) {
            Result.failure(failure)
''', '''        } catch (failure: Exception) {
            current()
            Result.failure(failure)
''')
    return edits.body, edits.proof()

def _write(output, target, body):
    path = wide(Path(output) / target); path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(body.encode('utf8'))
    return path

def emit_additions(repo, output):
    files = []; proof = []
    for target, adapter in (
        ('com/android/purebilibili/feature/video/share/VideoShareToDynamicDialog.kt', dialog),
        ('com/android/purebilibili/data/repository/DesktopOriginalVideoDynamicShareRepository.kt', repository)):
        body, record = adapter(repo)
        files.append(_write(output, target, body)); proof.append(dict(target=target, **record))
    _write(output, 'v030-video-dynamic-share-additions-proof.json', json.dumps(dict(
        upstreamCommit=COMMIT, outputs=proof), ensure_ascii=True, indent=2) + '\n')
    return files

def emit_models(repo, output):
    body = sources(repo)['DynamicCreateModels.kt']
    target = 'com/android/purebilibili/data/model/response/DynamicCreateModels.kt'
    path = _write(output, target, body)
    _write(output, 'v030-video-dynamic-model-proof.json', json.dumps(dict(upstreamCommit=COMMIT,
        target=target, sourcePath=SOURCE_PATHS['DynamicCreateModels.kt'], gitBlob=PINS['DynamicCreateModels.kt'][1], rawSha256=sha(body), generatedSha256=sha(body),
        fullSourceInverseVerified=True, edits=[]), ensure_ascii=True, indent=2) + '\n')
    return path
