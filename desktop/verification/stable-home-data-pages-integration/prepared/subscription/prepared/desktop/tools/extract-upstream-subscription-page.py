#!/usr/bin/env python3
"""Complete pinned v0.2.3 Subscription UI. Existing runtime is the sole reading IO authority."""
from pathlib import Path
import argparse, hashlib, json
SOURCE = 'app/src/main/java/com/android/purebilibili/feature/home/subscription/SubscriptionFeedPage.kt'
def safe(path):
    value=str(Path(path).absolute())
    return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def read(path): return safe(path).read_bytes().decode('utf-8').replace('\r\n','\n')
def sha(value): return hashlib.sha256(value.encode()).hexdigest()
def write(path,text):
    safe(path.parent).mkdir(parents=True,exist_ok=True)
    safe(path).write_text(text,encoding='utf-8',newline='\n')
def _generate_original_subscription_page(repo, output, standalone=False):
    # Same pinned source identities as the rest of the v0.2.3 product. No copies of feed schemas/services.
    original = read(Path(repo)/SOURCE)
    assert sha(original) == 'c3430358427744da0b3627f73561bfbe3d6519c86b96fcc49734035db5672707', 'Subscription source is not the pinned stable identity'
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

def generate(repo,output,standalone=False):
    row=_generate_original_subscription_page(repo,output,standalone)
    output=Path(output)
    write(output/'subscription-page-producer-inventory.json',json.dumps([row],ensure_ascii=False,indent=2)+'\n')
    return [output/'com/android/purebilibili/feature/home/subscription/SubscriptionFeedPage.kt']
if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--repo',required=True);parser.add_argument('--output',required=True)
    parser.add_argument('--standalone',action='store_true');args=parser.parse_args()
    print('Original Subscription UI outputs',len(generate(args.repo,args.output,args.standalone)))
