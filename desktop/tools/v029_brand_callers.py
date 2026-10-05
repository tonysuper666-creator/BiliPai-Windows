"""Selected fixed v029 brand caller deltas on complete, already adapted v025 bodies.

This is an additional reversible presentation adaptation, not a source catalog
replacement. Requests, query selection, page state and owner ports stay original.
"""
from pathlib import Path
import hashlib, json, os, re

ROOT = Path(__file__).resolve().parents[1] / 'upstream-slices/v029-brand-callers'
MANIFEST_SHA256 = '49de40b4333702a80d5cf75729aebc2580433a3347803ed7fd52a2adf808a731'
RAW_PINS = {'v025/app/src/main/java/com/android/purebilibili/feature/search/SearchScreen.kt': {'path': 'v025/app/src/main/java/com/android/purebilibili/feature/search/SearchScreen.kt', 'sourcePath': 'app/src/main/java/com/android/purebilibili/feature/search/SearchScreen.kt', 'upstreamCommit': '79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40', 'gitBlob': 'b048012d6e40f6733dfde6e4ed8555142f1c53fa', 'bytes': 228832, 'sha256Bytes': 'd5e95624fa21466c605516bb7b517c8265349a32795c9e9da65344f9ed9dd603', 'sourceUrl': 'https://raw.githubusercontent.com/jay3-yy/BiliPai/79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40/app/src/main/java/com/android/purebilibili/feature/search/SearchScreen.kt'}, 'v025/app/src/main/java/com/android/purebilibili/feature/list/CommonListScreen.kt': {'path': 'v025/app/src/main/java/com/android/purebilibili/feature/list/CommonListScreen.kt', 'sourcePath': 'app/src/main/java/com/android/purebilibili/feature/list/CommonListScreen.kt', 'upstreamCommit': '79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40', 'gitBlob': 'f01065ca999bd7d73ff6fcd82b73b2c508c4e4c9', 'bytes': 172189, 'sha256Bytes': 'f64e32dbbc823cbe49a7d4e7de1c4f66fd162ae78ff62ba5e0dfd66995ecd486', 'sourceUrl': 'https://raw.githubusercontent.com/jay3-yy/BiliPai/79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40/app/src/main/java/com/android/purebilibili/feature/list/CommonListScreen.kt'}, 'v029/app/src/main/java/com/android/purebilibili/feature/search/SearchScreen.kt': {'path': 'v029/app/src/main/java/com/android/purebilibili/feature/search/SearchScreen.kt', 'sourcePath': 'app/src/main/java/com/android/purebilibili/feature/search/SearchScreen.kt', 'upstreamCommit': 'a4b77f894d0a2dd26c0b9fc144b8adb88ac05480', 'gitBlob': 'c2d56636da1978719bb9f62117437ce7810c0e27', 'bytes': 227666, 'sha256Bytes': '4460e81628fdb54a013f09b147e7bdcaeeddb6f2ee23ff375b9c5825f4b92745', 'sourceUrl': 'https://raw.githubusercontent.com/jay3-yy/BiliPai/a4b77f894d0a2dd26c0b9fc144b8adb88ac05480/app/src/main/java/com/android/purebilibili/feature/search/SearchScreen.kt'}, 'v029/app/src/main/java/com/android/purebilibili/feature/list/CommonListScreen.kt': {'path': 'v029/app/src/main/java/com/android/purebilibili/feature/list/CommonListScreen.kt', 'sourcePath': 'app/src/main/java/com/android/purebilibili/feature/list/CommonListScreen.kt', 'upstreamCommit': 'a4b77f894d0a2dd26c0b9fc144b8adb88ac05480', 'gitBlob': 'f3ca9bafa474a07e753c9e328c3b9d9fe74cab10', 'bytes': 178540, 'sha256Bytes': 'e5676f371841e43225e8f88d71bcd62137df22bb13bca4aeb378a4fc4d73c5cd', 'sourceUrl': 'https://raw.githubusercontent.com/jay3-yy/BiliPai/a4b77f894d0a2dd26c0b9fc144b8adb88ac05480/app/src/main/java/com/android/purebilibili/feature/list/CommonListScreen.kt'}}
BASE025 = '79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40'
SOURCE029 = 'a4b77f894d0a2dd26c0b9fc144b8adb88ac05480'
BASE_PREFIX = 'app/src/main/java/com/android/purebilibili/'

def _wide(path):
    value=str(path.absolute())
    return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value) if os.name=='nt' else path

def _sha(raw): return hashlib.sha256(raw).hexdigest()
def _unique_json(pairs):
    result={}
    for key,value in pairs:
        if key in result: raise ValueError('duplicate brand caller manifest key')
        result[key]=value
    return result

def _sources():
    root=_wide(ROOT)
    raw_manifest=(root/'manifest.json').read_bytes()
    if _sha(raw_manifest)!=MANIFEST_SHA256: raise ValueError('brand caller manifest pin mismatch')
    manifest=json.loads(raw_manifest, object_pairs_hook=_unique_json)
    expected=set(RAW_PINS)|{'manifest.json'}
    actual={Path(p).relative_to(root).as_posix() for here,_,files in os.walk(root) for f in files for p in [Path(here)/f]}
    if actual!=expected: raise ValueError('unexpected brand caller raw file set')
    rows={row['path']:row for row in manifest['files']}
    if set(rows)!=set(RAW_PINS) or len(rows)!=len(manifest['files']): raise ValueError('brand caller manifest rows mismatch')
    result={}
    for path,pin in RAW_PINS.items():
        row=rows[path];raw=(root/path).read_bytes()
        if row!=pin or len(raw)!=pin['bytes'] or _sha(raw)!=pin['sha256Bytes']: raise ValueError('brand caller raw pin mismatch: '+path)
        if hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest()!=pin['gitBlob']: raise ValueError('brand caller Git blob mismatch')
        result[path]=raw.decode('utf-8')
    return result

# Delimiters inside comments and Kotlin literals cannot become extraction bounds.
_TOKEN = re.compile(r'//[^\n]*|/\*[\s\S]*?\*/|"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'')
def _mask(source):
    return _TOKEN.sub(lambda m: ''.join('\n' if c=='\n' else ' ' for c in m.group()),source)

def _one(source,anchor):
    if source.count(anchor)!=1: raise ValueError('brand caller selection not unique: '+anchor[:80])
    return source.index(anchor)

def _balanced(mask,start,opening,closing):
    if mask[start]!=opening: raise ValueError('brand caller expected opening delimiter')
    depth=0
    for pos in range(start,len(mask)):
        if mask[pos]==opening: depth+=1
        elif mask[pos]==closing:
            depth-=1
            if depth==0:return pos+1
    raise ValueError('brand caller unbalanced selection')

def _span(source,anchor,kind='call'):
    start=_one(source,anchor);mask=_mask(source)
    if kind=='function':
        params=mask.index('(',start);params_end=_balanced(mask,params,'(',')')
        body=mask.index('{',params_end);end=_balanced(mask,body,'{','}')
    else:
        opening,closing=('(',')') if kind=='call' else ('{','}')
        pos=mask.index(opening,start);end=_balanced(mask,pos,opening,closing)
    return source[start:end]

def _line(source,anchor):
    at=_one(source,anchor);start=source.rfind('\n',0,at)+1;end=source.find('\n',at)
    return source[start:end if end>=0 else len(source)]

def _pairs(kind,raw):
    rel='feature/search/SearchScreen.kt' if kind=='search' else 'feature/list/CommonListScreen.kt'
    old=raw['v025/'+BASE_PREFIX+rel];new=raw['v029/'+BASE_PREFIX+rel]
    pairs=[]
    def add(label,before,after):
        if before==after: raise ValueError('brand caller adaptation must change actual body')
        if old.count(before)!=1 or new.count(after)!=1: raise ValueError('brand caller raw selection not exact/unique: '+label)
        pairs.append(dict(label=label,before=before,after=after,sourceBeforeSpanSha256=_sha(before.encode()),sourceAfterSpanSha256=_sha(after.encode())))
    if kind=='search':
        anchor='                            SearchNativeMessageState(\n                                title = "搜索失败",'
        add('body-error-retry-and-visible-page',_span(old,anchor),_span(new,anchor))
        anchor='                            if (copy != null) {'
        add('body-empty-selected-query-action',_span(old,anchor,'block'),_span(new,anchor,'block'))
        for field in ['searchResults','upResults','bangumiResults','liveResults','articleResults']:
            anchor='                                    if (!pageResultState.isSearching && pageResultState.'+field+'.isEmpty() && pageResultState.error == null && pageEmptyStateCopy != null) {'
            add('result-empty-'+field,_span(old,anchor,'block'),_span(new,anchor,'block'))
        anchor='@Composable\nprivate fun SearchNativeMessageState('
        add('full-original-native-message-state',_span(old,anchor,'function'),_span(new,anchor,'function'))
    elif kind=='list':
        old_anchor='        Box(modifier = emptyViewportModifier, contentAlignment = Alignment.Center) {\n             AppText("暂无数据", color = MaterialTheme.colorScheme.onSurfaceVariant)'
        new_anchor='            Box(modifier = emptyViewportModifier, contentAlignment = Alignment.Center) {\n                com.android.purebilibili.core.ui.EmptyState(message = "暂无数据", enableEasterEgg = false)'
        add('existing-empty-data-call',_span(old,old_anchor,'block'),_span(new,new_anchor,'block'))
        before=_line(old,'AppText("没有找到相关视频", color = MaterialTheme.colorScheme.onSurfaceVariant)')
        anchor='com.android.purebilibili.core.ui.EmptyState(\n                        message = "没有找到相关视频",'
        after='                '+_span(new,anchor)
        # Only indentation changes around this complete original call; the raw
        # expression itself remains exact and is separately recorded below.
        raw_after=_span(new,anchor)
        if new.count(raw_after)!=1: raise ValueError('list empty raw call not unique')
        pairs.append(dict(label='existing-filtered-empty-call',before=before,after=after,sourceBeforeSpanSha256=_sha(before.encode()),sourceAfterSpanSha256=_sha(raw_after.encode()),indentationPrefix='                ',rawAfter=raw_after))
        anchor='        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {\n            AppText(text = message, color = MaterialTheme.colorScheme.onSurfaceVariant)'
        new_anchor='        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {\n            com.android.purebilibili.core.ui.EmptyState('
        add('existing-folder-empty-query-choice',_span(old,anchor,'block'),_span(new,new_anchor,'block'))
    else: raise ValueError('unsupported brand caller adaptation')
    return pairs

def adapt_brand_callers(source,kind):
    raw=_sources();pairs=_pairs(kind,raw);before=source;edits=[]
    for pair in pairs:
        start=_one(source,pair['before'])
        source=source[:start]+pair['after']+source[start+len(pair['before']):]
        edits.append(dict(index=start,**pair))
    inverse=source
    for edit in reversed(edits):
        at=edit['index']
        if inverse[at:at+len(edit['after'])]!=edit['after']: raise ValueError('brand caller inverse anchor mismatch')
        inverse=inverse[:at]+edit['before']+inverse[at+len(edit['after']):]
    if inverse!=before: raise ValueError('brand caller full adapted-body inverse mismatch')
    # The old full-source adaptations remain upstream of this exact inverse.
    return source,dict(upstreamCommit=SOURCE029,baselineCommit=BASE025,rawManifestSha256Bytes=MANIFEST_SHA256,kind=kind,countedAdaptations=len(edits),beforeSha256LfUtf8=_sha(before.encode()),afterSha256LfUtf8=_sha(source.encode()),fullAdaptedBodyInverseExact=True,edits=edits,pendingListBranches=['new history loadMoreError/retry/retained-header branches require separate state/VM migration'] if kind=='list' else [])
