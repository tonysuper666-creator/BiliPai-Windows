"""Complete v041 feed import/store/title sources with reversible Windows bindings."""
from pathlib import Path
import hashlib
import json
from v038_listen_viewmodel import block_end

COMMIT = 'd18fb20e31adb11bbec9fae6dfce7d705d556217'
ARCHIVE = 'desktop/upstream-slices/v041-feed-import-title'
MANIFEST_SHA256 = 'ad5739797bdb6b6b5f2c2af0568d612f9d7dbbf64ef769368bae40b2ace6acd8'
BASE_SHA256_LF = {
    'FeedImportParser.kt': '4fe53d795378083418e1ab02e25fb5ac6d40e05c20cf414eee45c0245b3a6de0',
    'SubscriptionFeedStore.kt': '204b5891d005c0cac525ca6fdd328b4534c4f3a6a3966917610eee528d3b2785',
    'FeedTitleResolver.kt': '9c98ff6a159c7f4e5a32f18ab8ed5659d860990af500ad603cad4960413290ea',
    'FeedModels.kt': '3276d1a66b8682508a2b17a03d3063368d4a8f94e730e679d8e4728002521b5f',
    'FeedSourceCatalog.kt': '36477d508db469e5556dd12a6712fb64675e60343ecec05cc18d607978571f36',
}

def sha(raw): return hashlib.sha256(raw).hexdigest()

def verified_sources(repo):
    root = Path(repo) / ARCHIVE
    raw = (root / 'manifest.json').read_bytes()
    if sha(raw) != MANIFEST_SHA256: raise ValueError('v041 feed import/title manifest changed')
    manifest = json.loads(raw)
    if (manifest.get('repository'), manifest.get('tag'), manifest.get('commit')) != ('jay3-yy/BiliPai', 'v0.4.1', COMMIT):
        raise ValueError('v041 feed import/title identity changed')
    rows = manifest['files']
    if len(rows) != 5 or {r['archiveFile'] for r in rows} != set(BASE_SHA256_LF):
        raise ValueError('v041 feed import/title file closure changed')
    result = {}
    for row in rows:
        raw = (root / row['archiveFile']).read_bytes()
        blob = hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest()
        if (len(raw), sha(raw), blob) != (row['bytes'], row['sha256'], row['gitBlob']):
            raise ValueError('v041 feed import/title bytes changed: ' + row['archiveFile'])
        result[row['path']] = raw.decode('utf-8').replace('\r\n', '\n')
    return result

def compose_source(repo, path, baseline):
    name = Path(path).name
    if name not in BASE_SHA256_LF or sha(baseline.encode()) != BASE_SHA256_LF[name]:
        raise ValueError('Canonical v025 feed source changed: ' + path)
    original = verified_sources(repo)[path]
    body = original
    edits = []
    def replace(before, after):
        nonlocal body
        if body.count(before) != 1: raise ValueError('Whole v041 feed binding anchor changed: ' + name)
        at = body.index(before)
        edits.append(dict(offset=at, before=before, after=after))
        body = body[:at] + after + body[at + len(before):]

    if name in {'SubscriptionFeedStore.kt', 'FeedTitleResolver.kt', 'FeedSourceCatalog.kt'}:
        replace('import android.content.Context\n',
                'import com.bilipai.desktop.plugins.DesktopPluginContext as Context\n')
    if name == 'SubscriptionFeedStore.kt':
        replace('import android.util.AtomicFile\n',
                'import com.bilipai.desktop.plugins.DesktopPluginAtomicFile as AtomicFile\n')
        replace('com.android.purebilibili.core.util.Logger.w(',
                'com.bilipai.desktop.plugins.DesktopPluginLog.w(')
        replace('''                val current = read(context)
                val feed = SavedSubscriptionFeed(
                    id = uniqueFeedId(trimmedUrl, current.map { it.id }.toSet()),
                    title = title.trim().ifBlank { trimmedUrl },
                    url = trimmedUrl,
                )''', '''                val current = read(context)
                val existing = current.firstOrNull { it.url == trimmedUrl }
                val normalizedTitle = title.trim().ifBlank { trimmedUrl }
                val feed = existing?.copy(title = normalizedTitle) ?: SavedSubscriptionFeed(
                    id = uniqueFeedId(trimmedUrl, current.map { it.id }.toSet()),
                    title = normalizedTitle,
                    url = trimmedUrl,
                )''')
        marker = '    suspend fun updateTitle(context: Context, id: String, title: String) {'
        if body.count(marker) != 1: raise ValueError('Original title update declaration changed')
        at = body.index(marker)
        before = body[at:block_end(body, body.index('{', at))]
        replace(before, '''    suspend fun updateTitle(context: Context, id: String, expectedUrl: String,
        expectedTitle: String, title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty() || trimmed == expectedUrl) return
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val current = read(context)
                fun eligible(feed: SavedSubscriptionFeed): Boolean =
                    feed.id == id && feed.url == expectedUrl && feed.title == expectedTitle &&
                        needsTitleResolution(feed) && feed.title != trimmed
                if (current.none(::eligible)) return@withContext
                write(context, current.map { if (eligible(it)) it.copy(title = trimmed) else it })
            }
        }
    }''')
        replace('保留坏文件以便排查，返回空并给出显式日志。', '保留坏文件并中止读取，禁止后续写入覆盖。')
        replace('订阅列表文件解析失败，暂按空列表处理: ${error.message}', '订阅列表文件解析失败，已保留原文件: ${error.message}')
        replace('''            emptyList()
        }
    }

    private fun file''', '''            throw error
        }
    }

    private fun file''')
    elif name == 'FeedTitleResolver.kt':
        replace('SubscriptionFeedStore.updateTitle(context, feed.id, title)',
                'SubscriptionFeedStore.updateTitle(context, feed.id, feed.url, feed.title, title)')

    restored = body
    for edit in reversed(edits):
        at = edit['offset']
        if restored[at:at + len(edit['after'])] != edit['after']:
            raise ValueError('v041 feed inverse anchor differs')
        restored = restored[:at] + edit['before'] + restored[at + len(edit['after']):]
    if restored != original: raise ValueError('v041 complete feed source inverse failed')
    if 'import android.' in body: raise ValueError('Unbound original platform dependency')
    return body, dict(upstreamCommit=COMMIT, source=path,
        originalSHA256LF=sha(original.encode()), emittedSHA256LF=sha(body.encode()),
        canonicalBaselineSHA256LF=BASE_SHA256_LF[name], completeOriginalInverse=True,
        allRemainingOriginalBytesUnchanged=True, adaptations=edits,
        actualProducerWired=False, mainCompiled=False, jvmTestsExecuted=False,
        repositoryLifecycleWired=False, applicationExecuted=False)

def complete_original_feed_regression_source(repo):
    from v041_feed_images import original_regression_sources
    path = 'app/src/test/java/com/android/purebilibili/core/plugin/feed/FeedDocumentParserTest.kt'
    original = original_regression_sources(repo)[path]
    before = 'assertEquals("散列", imported.first { it.url == "https://example.com/feed" }.group)'
    after = 'assertEquals("", imported.first { it.url == "https://example.com/feed" }.group)'
    if original.count('@Test') != 19 or original.count(before) != 1:
        raise ValueError('Complete v041 feed regression source changed')
    at = original.index(before)
    body = original[:at] + after + original[at + len(before):]
    if body[:at] + before + body[at + len(after):] != original:
        raise ValueError('Original feed test expectation inverse failed')
    return body, dict(upstreamCommit=COMMIT, source=path, originalTests=19,
        emittedCompleteOriginalTests=19, deferredOriginalTests=0,
        adaptedExpectationCount=1, originalRawArchivePreserved=True,
        completeOriginalInverse=True, remainingOriginalBytesUnchanged=True,
        edits=[dict(offset=at, before=before, after=after,
            reason='Root-level OPML outline has no folder: the original parser and this same test round trip both require empty group.')],
        jvmTestsExecuted=False, mainCompiled=False, runtimeExecuted=False)
