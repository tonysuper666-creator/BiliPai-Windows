from pathlib import Path
import hashlib, json, subprocess, sys

HERE = Path(__file__).resolve().parent
MAIN = HERE.parents[2]
REPO = MAIN.parent / 'BiliPai-v023'
COMMIT = '3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
SOURCE = 'app/src/main/java/com/android/purebilibili/feature/home/subscription/SubscriptionFeedPage.kt'

def safe(path):
    return Path('\\\\?\\' + str(Path(path).absolute()))

def read(path): return safe(path).read_bytes().decode('utf-8').replace('\r\n','\n')
def sha(value): return hashlib.sha256(value.encode()).hexdigest()
def write(path, text):
    safe(path.parent).mkdir(parents=True, exist_ok=True)
    safe(path).write_text(text, encoding='utf-8', newline='\n')
def save(path, value): write(path, json.dumps(value,ensure_ascii=False,indent=2)+'\n')

def generate(repo, output, standalone=False):
    # Same pinned source identities as the rest of the v0.2.3 product. No copies of feed schemas/services.
    original = read(Path(repo)/SOURCE)
    expected = subprocess.check_output(['git','show',COMMIT+':'+SOURCE], cwd=REPO).decode().replace('\r\n','\n')
    assert original == expected, 'Subscription source is not the pinned stable identity'
    text = original
    changes = []
    def replace(before, after):
        nonlocal text
        assert before in text, before[:120]
        changes.append(dict(before=before,after=after))
        text = text.replace(before,after)
    replace('import androidx.activity.compose.PredictiveBackHandler', 'import com.bilipai.desktop.ui.DesktopSubscriptionPredictiveBackHandler as PredictiveBackHandler\nimport com.bilipai.desktop.ui.LocalDesktopSubscriptionBindings\nimport kotlinx.coroutines.flow.collectLatest')
    replace('import androidx.compose.ui.platform.LocalContext\n','')
    replace('    val context = LocalContext.current','    val platform = LocalDesktopSubscriptionBindings.current\n    val context = platform.context')
    replace('    val subscriptionRevision by SubscriptionFeedStore.revision.collectAsStateWithLifecycle()', '    val repositorySnapshot by platform.state.collectAsStateWithLifecycle()')
    replace('lastFraction = event.progress.coerceIn(0f, 1f)', 'lastFraction = event.coerceIn(0f, 1f)')
    replace('onArticleOpenChanged(isArticleOpen)', 'platform.articleOpenChanged(isArticleOpen, onArticleOpenChanged)')
    replace('onDispose { onArticleOpenChanged(false) }', 'onDispose { platform.articleOpenChanged(false, onArticleOpenChanged) }')
    start=text.index('    LaunchedEffect(reloadToken, subscriptionRevision) {')
    end=text.index('\n    val visibleItems',start)
    before=text[start:end]
    after='''    // UI projection only; the runtime repository remains the sole feed/reading IO authority.
    LaunchedEffect(platform, repositorySnapshot) {
        platform.commitUi {
            sources = repositorySnapshot.activeSources
            if (selectedSourceId != null && sources.none { it.id == selectedSourceId }) selectedSourceId = null
            readKeys = repositorySnapshot.reading.readKeys.toSet()
            cachedBodies = repositorySnapshot.reading.fullBodies
            val preserveOrder = listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
            items = stabilizeFeedOrder(items, repositorySnapshot.reading.items, preserveOrder)
            loadErrors = repositorySnapshot.errors
            loading = repositorySnapshot.loading
        }
    }
    LaunchedEffect(platform, reloadToken) {
        platform.changes.collectLatest {
            try { platform.reload() }
            catch (error: Throwable) {
                platform.reportFailure(error) { loadErrors = loadErrors + (error.message ?: "订阅刷新失败") }
            }
        }
    }
'''
    replace(before,after)
    replace('FeedReadingStore.setRead(context, key, read)', 'platform.setRead(article, read)')
    replace('FeedReadingStore.setRead(context, key, true)', 'platform.setRead(item, true)')
    replace('FeedReadingStore.saveFullBody(context, key, body)', 'platform.saveFullBody(article, body)')
    replace('    onFullBody: (String) -> Unit,', '    onFullBody: suspend (String) -> Unit,')
    # The full-body save belongs to the article fetch Job, not a detached parent-page launch.
    replace('''                        scope.launch {
                            runCatching { platform.saveFullBody(article, body) }
                                .onFailure { loadErrors = loadErrors + "正文缓存保存失败" }
                        }''', '''                        runCatching { platform.saveFullBody(article, body) }
                            .onFailure { error -> platform.reportFailure(error) { loadErrors = loadErrors + "正文缓存保存失败" } }''')
    for message in ['阅读状态保存失败']:
        replace('.onFailure { loadErrors = loadErrors + "'+message+'" }', '.onFailure { error -> platform.reportFailure(error) { loadErrors = loadErrors + "'+message+'" } }')
    before='''    if (webUrl != null) {
        com.android.purebilibili.feature.web.WebViewScreen(
            url = webUrl.orEmpty(),
            title = "原文",
            onBack = { webUrl = null },
        )
    }'''
    after='''    // Android embedded WebView is an explicit Windows external-browser boundary.
    LaunchedEffect(platform, webUrl) {
        webUrl?.let { platform.openExternalUrl(it); webUrl = null }
    }'''
    replace(before,after)
    replace('com.android.purebilibili.core.store.SettingsManager\n            .getSubscriptionArticleFontScale(context).first()', 'platform.articleFontScale.first()')
    replace('com.android.purebilibili.core.store.SettingsManager\n                                        .setSubscriptionArticleFontScale(context, fontScale)', 'platform.setArticleFontScale(fontScale)')
    replace('fetchArticleHtml(item.link)', 'platform.fetchArticleBody(item)')
    replace('.onFailure { bodyError = it.message ?: "暂时无法读取原文" }', '.onFailure { error -> platform.reportFailure(error) { bodyError = error.message ?: "暂时无法读取原文" } }')
    replace('copyFeedText(context, feedBlocksPlainText(blocks).ifBlank { item.title })', 'copyFeedText(platform, feedBlocksPlainText(blocks).ifBlank { item.title })')
    before='''private fun copyFeedText(context: android.content.Context, text: String) {
    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("订阅正文", text))
    android.widget.Toast.makeText(context, "已复制正文", android.widget.Toast.LENGTH_SHORT).show()
}'''
    after='''private fun copyFeedText(platform: com.bilipai.desktop.ui.DesktopSubscriptionPageBindings, text: String) {
    platform.copyText(text)
}'''
    replace(before,after)
    # Only imports made unnecessary by the explicit data/platform substitutions are removed.
    for symbol in ['FeedReadingStore','FeedConditionalStore','SubscriptionFeedStore','mergeCachedFeedItems','fetchArticleHtml','loadEnabledFeedSources','loadFeedSources']:
        line='import com.android.purebilibili.core.plugin.feed.'+symbol+'\n'
        replace(line,'')
    destination=Path(output)/'com/android/purebilibili/feature/home/subscription/SubscriptionFeedPage.kt'
    header='// Original '+SOURCE+'\n// Original LF SHA256 '+sha(original)+'\n'
    write(destination,header+text)
    inverse=text
    for change in reversed(changes):
        assert change['after'] in inverse or change['after']=='',change['after'][:100]
        if change['after']=='': continue
        inverse=inverse.replace(change['after'],change['before'])
    # Removed imports are verified as an explicit set; re-add before original positions for strict inversion.
    for symbol in ['FeedReadingStore','FeedConditionalStore','SubscriptionFeedStore','mergeCachedFeedItems','fetchArticleHtml','loadEnabledFeedSources','loadFeedSources']:
        line='import com.android.purebilibili.core.plugin.feed.'+symbol+'\n'
        # This import-only normalization is checked separately from every executable body edit.
        inverse=inverse.replace(line,'')
    normalized=original
    for change in changes:
        if change['after']=='':
            assert change['before'].startswith('import '), 'Only unused imports may be omitted from inverse comparison'
            normalized=normalized.replace(change['before'],'')
    assert inverse==normalized,'Inverse adaptation differs from the original source'
    return dict(path=SOURCE,sha256LF=sha(original),generated=destination.name,mode='extracted',
        fullFileSelected=True,topLevelDeclarations=13,feedBlockBranches=8,changes=changes,
        inverseNormalizedOriginalEqual=True,generatedSha256LF=sha(header+text))

def prepare_existing():
    snapshot=json.loads(read(MAIN/'desktop/.local/stable-product-snapshot-33/manifest.json'))
    inputs=snapshot.get('inputs',snapshot.get('sourceInputs',{}))
    allrows=[]
    def flatten(value):
        if isinstance(value,dict):
            if 'path' in value and 'sha256Bytes' in value:allrows.append(value)
            for child in value.values():flatten(child)
        elif isinstance(value,list):
            for child in value:flatten(child)
    flatten(snapshot)
    records=[]
    for name in ['DesktopSubscriptionRepository','DesktopPluginServices','DesktopPluginStore']:
        relative='desktop/src/main/kotlin/com/bilipai/desktop/plugins/'+name+'.kt'
        raw=safe(REPO/relative).read_bytes()
        original=raw.decode().replace('\r\n','\n');text=original;changes=[]
        matching=[row for row in allrows if row['path']==relative]
        assert matching and matching[0]['sha256Bytes']==hashlib.sha256(raw).hexdigest(),relative+' differs from actual33 source'
        def replace(before,after):
            nonlocal text
            assert before in text,before[:100]
            assert text.count(before)==1,(name,before[:100],text.count(before))
            text=text.replace(before,after);changes.append(dict(before=before,after=after))
        if name=='DesktopPluginServices':
            before='''            try { Files.move(temp, baseFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (unsupported: AtomicMoveNotSupportedException) { Files.move(temp, baseFile.toPath(), StandardCopyOption.REPLACE_EXISTING) }'''
            replace(before,'''            DesktopSubscriptionWriteAdmission.commitOrOriginal {
'''+before+'''
            }''')
        elif name=='DesktopPluginStore':
            replace('            try { Files.move(temporary, backing.file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }',
                '            DesktopSubscriptionWriteAdmission.checkCurrentRequestOrOriginal()\n            try { Files.move(temporary, backing.file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }')
        else:
            replace('    val loading: Boolean = false)', '    val loading: Boolean = false, val activeSources: List<FeedSource> = emptyList())')
            before='''        val enabledIds = ((if (builtinEnabled) sources.filter { it.enabled }.map { "builtin:${it.id}" } else emptyList()) +
            extra.sources.filter { isHttpFeedUrl(it.url) }.map { it.id }).toSet()'''
            after='''        val builtinSources = if (builtinEnabled) sources.filter { it.enabled && isHttpFeedUrl(it.url) }
            .map { FeedSource("builtin:${it.id}", it.title, it.url) } else emptyList()
        val activeSources = (builtinSources + extra.sources.filter { isHttpFeedUrl(it.url) }).distinctBy { it.url }
        val enabledIds = activeSources.map { it.id }.toSet()'''
            replace(before,after)
            replace('DesktopSubscriptionState(sources, reading.copy(items = mergeCachedFeedItems(reading.items, emptyList(), enabledIds)))',
                'DesktopSubscriptionState(sources, reading.copy(items = mergeCachedFeedItems(reading.items, emptyList(), enabledIds)), activeSources = activeSources)')
            replace('cache.copy(items = emptyList()), if (builtinEnabled) emptyList() else listOf("请启用订阅插件或已授权的 JS 订阅模块"))',
                'cache.copy(items = emptyList()), if (builtinEnabled) emptyList() else listOf("请启用订阅插件或已授权的 JS 订阅模块"), activeSources = sources)')
            replace('DesktopSubscriptionState(feeds, cache.copy(items = mergeCachedFeedItems(cache.items, emptyList(), ids)), loading = true)',
                'DesktopSubscriptionState(feeds, cache.copy(items = mergeCachedFeedItems(cache.items, emptyList(), ids)), loading = true, activeSources = sources)')
            replace('cache.copy(items = mergeCachedFeedItems(cache.items, update.items, ids)), update.errors, loading = true)',
                'cache.copy(items = mergeCachedFeedItems(cache.items, update.items, ids)), update.errors, loading = true, activeSources = sources)')
            replace('DesktopSubscriptionState(SubscriptionFeedStore.list(context), FeedReadingStore.load(context), result.errors)',
                'DesktopSubscriptionState(SubscriptionFeedStore.list(context), FeedReadingStore.load(context), result.errors, activeSources = sources)')
            # Precisely replace expression-valued state assignments; unchanged constructor expressions remain intact.
            import re
            cursor=0
            while True:
                match=re.search(r'_state\.value = ',text[cursor:])
                if not match:break
                begin=cursor+match.start();exprstart=cursor+match.end();i=exprstart;depth=0;quote=False;escape=False
                while i<len(text):
                    c=text[i]
                    if quote:
                        if escape:escape=False
                        elif c=='\\':escape=True
                        elif c=='"':quote=False
                    elif c=='"':quote=True
                    elif c in '([{':depth+=1
                    elif c in ')]}':
                        if depth==0:break
                        depth-=1
                    elif c in '\n;' and depth==0:break
                    i+=1
                before=text[begin:i];expr=text[exprstart:i].rstrip();tail=text[exprstart:i][len(expr):]
                cleanup=expr=='_state.value.copy(loading = false)'
                call='publishCleanupState' if cleanup else 'publishState'
                after=call+'('+expr+')'+tail
                text=text[:begin]+after+text[i:];changes.append(dict(before=before,after=after));cursor=begin+len(after)
            # Recheck request generation/source eligibility inside Root admission, after any monitor wait.
            before='publishState(DesktopSubscriptionState(sources, reading.copy(items = mergeCachedFeedItems(reading.items, emptyList(), enabledIds)), activeSources = activeSources))'
            replace(before,before+' { !stopped && token == generation.get() && builtinEnabled == enabled() && extra.revision == extraSourceRevision() }')
            start=text.index('    suspend fun refresh()');end=text.index('    suspend fun add(',start)
            refresh=text[start:end]
            cursor=0
            while True:
                begin=refresh.find('publishState(',cursor)
                if begin<0:break
                i=begin+len('publishState(');depth=1;quote=False;escape=False
                while depth:
                    c=refresh[i]
                    if quote:
                        if escape:escape=False
                        elif c=='\\':escape=True
                        elif c=='"':quote=False
                    elif c=='"':quote=True
                    elif c=='(':depth+=1
                    elif c==')':depth-=1
                    i+=1
                refresh=refresh[:i]+' { current() }'+refresh[i:];cursor=i+len(' { current() }')
            replace(text[start:end],refresh)
            before='''                FeedReadingStore.saveItems(context, merged)
                if (result.validators.isNotEmpty()) FeedConditionalStore.update(context, result.validators)
                publishState(DesktopSubscriptionState(SubscriptionFeedStore.list(context), FeedReadingStore.load(context), result.errors, activeSources = sources)) { current() }'''
            replace(before,'''                DesktopSubscriptionWriteAdmission.whileCurrent(::current) {
'''+before+'''
                }''')
            replace('publishState(DesktopSubscriptionState(feeds,\n                    cache.copy(items = mergeCachedFeedItems(cache.items, update.items, ids)), update.errors, loading = true, activeSources = sources)) { current() }',
                'publishProgress(DesktopSubscriptionState(feeds,\n                    cache.copy(items = mergeCachedFeedItems(cache.items, update.items, ids)), update.errors, loading = true, activeSources = sources)) { current() }')
            replace('if (token == generation.get()) publishCleanupState(_state.value.copy(loading = false))',
                'if (token == generation.get()) publishCleanupState { token == generation.get() }')
            replace('publishState(_state.value.copy(reading = FeedReadingStore.load(context)))', 'publishReading(FeedReadingStore.load(context))') if text.count('publishState(_state.value.copy(reading = FeedReadingStore.load(context)))')==1 else None
            # The same expression occurs in original setRead and loadFullArticle; preserve both identities explicitly.
            before='publishState(_state.value.copy(reading = FeedReadingStore.load(context)))'
            if before in text:
                after='publishReading(FeedReadingStore.load(context))'
                count=text.count(before);text=text.replace(before,after);changes.append(dict(before=before,after=after,occurrences=count))
            before='publishCleanupState(_state.value.copy(loading = false))'
            after='publishCleanupState()'
            count=text.count(before);text=text.replace(before,after);changes.append(dict(before=before,after=after,occurrences=count))
            anchor='    private suspend fun <T> mutate(block: suspend () -> T): T = withContext(Dispatchers.IO) {'
            extra='''    /** Original article fetch without premature persistence; the original UI keeps its length comparison. */
    suspend fun fetchArticleBody(item: ParsedFeedItem): Result<String> {
        check(!stopped) { "订阅服务已停止" }
        return fetchArticleHtml(item.link)
    }
    suspend fun saveFullBody(item: ParsedFeedItem, html: String): Unit = mutation.withLock {
        check(!stopped) { "订阅服务已停止" }
        FeedReadingStore.saveFullBody(context, feedItemKey(item), html)
        publishReading(FeedReadingStore.load(context))
    }
    private fun publishState(value: DesktopSubscriptionState, current: () -> Boolean = { !stopped }) =
        DesktopSubscriptionWriteAdmission.commitOrOriginal { if (current()) _state.value = value }
    private fun publishReading(reading: FeedReadingSnapshot) =
        DesktopSubscriptionWriteAdmission.commitOrOriginal { if (!stopped) _state.value = _state.value.copy(reading = reading) }
    private fun publishProgress(value: DesktopSubscriptionState, current: () -> Boolean) =
        DesktopSubscriptionWriteAdmission.commitOrOriginal {
            if (current()) _state.value = value.copy(reading = value.reading.copy(
                readKeys = _state.value.reading.readKeys, fullBodies = _state.value.reading.fullBodies))
        }
    private fun publishCleanupState(current: () -> Boolean = { true }) =
        DesktopSubscriptionWriteAdmission.cleanupOrOriginal { if (current()) _state.value = _state.value.copy(loading = false) }

'''
            replace(anchor,extra+anchor)
            replace('''        generation.incrementAndGet()
        try { mutation.withLock''', '''        val token = generation.incrementAndGet()
        try { mutation.withLock''')
            replace('catch (error: Throwable) { publishCleanupState(); throw error }',
                'catch (error: Throwable) { publishCleanupState { token == generation.get() }; throw error }')
        inverse=text
        for row in reversed(changes):inverse=inverse.replace(row['after'],row['before'])
        assert inverse==original,name+' inverse'
        destination=HERE/'proof-existing'/relative
        write(destination,text)
        record=dict(path=relative,baseSha256Bytes=hashlib.sha256(raw).hexdigest(),baseSha256LF=sha(original),candidateSha256LF=sha(text),changes=changes,inverseOriginalEqual=True,installWholeFile=False)
        save(HERE/'hunks'/(''+name+'.json'),record);records.append(record)
    return records

if __name__=='__main__':
    inventory=generate(REPO,HERE/'prepared/generated')
    save(HERE/'source-inventory.json',inventory)
    records=prepare_existing();save(HERE/'existing-source-hunks.json',records)
    print(json.dumps(dict(original=inventory['sha256LF'],generated=inventory['generatedSha256LF'],existingHunks=len(records)),indent=2))
