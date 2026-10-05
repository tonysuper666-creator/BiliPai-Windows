"""Fixed full v029 History/List source, bounded Windows adaptation.

Recap is pending real RSS ports; no empty header or request stub is installed.
Existing sole producer applies its original environment/platform adapters after
the counted omissions below. No API/state algorithm is rewritten here.
"""
from pathlib import Path
import difflib,hashlib,json,os
ROOT=Path(__file__).resolve().parents[1]/'upstream-slices/v029-brand-history'
COMMIT='a4b77f894d0a2dd26c0b9fc144b8adb88ac05480'
MANIFEST_SHA256='974e742b2c8bf9cb0c00880433e1ba918622fae16ef7e3da364783f448927926'
PINS={'app/src/main/java/com/android/purebilibili/feature/list/ListViewModel.kt': {'path': 'app/src/main/java/com/android/purebilibili/feature/list/ListViewModel.kt', 'upstreamCommit': 'a4b77f894d0a2dd26c0b9fc144b8adb88ac05480', 'gitBlob': 'b16b278d54b29e512f98fb1cf8bd07e38d861b37', 'bytes': 67599, 'sha256Bytes': 'a5ca3f58b081d907a928418aab33bb880683f99acdf426403314fd4b614f5f70', 'sourceUrl': 'https://raw.githubusercontent.com/jay3-yy/BiliPai/a4b77f894d0a2dd26c0b9fc144b8adb88ac05480/app/src/main/java/com/android/purebilibili/feature/list/ListViewModel.kt'}, 'app/src/main/java/com/android/purebilibili/feature/list/CommonListScreen.kt': {'path': 'app/src/main/java/com/android/purebilibili/feature/list/CommonListScreen.kt', 'upstreamCommit': 'a4b77f894d0a2dd26c0b9fc144b8adb88ac05480', 'gitBlob': 'f3ca9bafa474a07e753c9e328c3b9d9fe74cab10', 'bytes': 178540, 'sha256Bytes': 'e5676f371841e43225e8f88d71bcd62137df22bb13bca4aeb378a4fc4d73c5cd', 'sourceUrl': 'https://raw.githubusercontent.com/jay3-yy/BiliPai/a4b77f894d0a2dd26c0b9fc144b8adb88ac05480/app/src/main/java/com/android/purebilibili/feature/list/CommonListScreen.kt'}, 'app/src/main/java/com/android/purebilibili/feature/common/ListLoadError.kt': {'path': 'app/src/main/java/com/android/purebilibili/feature/common/ListLoadError.kt', 'upstreamCommit': 'a4b77f894d0a2dd26c0b9fc144b8adb88ac05480', 'gitBlob': 'eb7f6c04af7910b7a5c4ee4b0b494c33a4a028bd', 'bytes': 1150, 'sha256Bytes': '6fc1d252d9d1d02eff83cf1d9eabd6ff0a645c4876aab81fa225148119a31a32', 'sourceUrl': 'https://raw.githubusercontent.com/jay3-yy/BiliPai/a4b77f894d0a2dd26c0b9fc144b8adb88ac05480/app/src/main/java/com/android/purebilibili/feature/common/ListLoadError.kt'}}
PREFIX='app/src/main/java/com/android/purebilibili/'
SUPPORTED={'feature/list/ListViewModel','feature/list/CommonListScreen','feature/common/ListLoadError'}
def wide(p):
    s=str(p.absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s) if os.name=='nt' else p
def sha(raw):return hashlib.sha256(raw).hexdigest()
def strict_json(pairs):
    result={}
    for k,v in pairs:
        if k in result:raise ValueError('duplicate history manifest key')
        result[k]=v
    return result
def load_raw(rel):
    if rel not in SUPPORTED:raise ValueError('unsupported history source path')
    root=wide(ROOT);raw_manifest=(root/'manifest.json').read_bytes()
    if sha(raw_manifest)!=MANIFEST_SHA256:raise ValueError('history manifest pin mismatch')
    m=json.loads(raw_manifest,object_pairs_hook=strict_json)
    files={Path(here,f).relative_to(root).as_posix() for here,_,names in os.walk(root) for f in names}
    if files!=set(PINS)|{'manifest.json'}:raise ValueError('history raw file-set mismatch')
    rows={r['path']:r for r in m['files']}
    if len(rows)!=len(m['files']) or rows!=PINS:raise ValueError('history raw manifest rows mismatch')
    data={}
    for p,pin in PINS.items():
        raw=(root/p).read_bytes()
        if len(raw)!=pin['bytes'] or sha(raw)!=pin['sha256Bytes']:raise ValueError('history raw pin mismatch: '+p)
        if hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest()!=pin['gitBlob']:raise ValueError('history raw Git blob mismatch')
        data[p]=raw.decode('utf-8')
    return data[PREFIX+rel+'.kt']
def selected_source(rel):
    original=load_raw(rel);body=original;edits=[]
    def replace(before,after,label):
        nonlocal body
        if body.count(before)!=1:raise ValueError('history counted adaptation not unique: '+label)
        at=body.index(before);body=body[:at]+after+body[at+len(before):];edits.append(dict(index=at,before=before,after=after,label=label))
    if rel=='feature/list/ListViewModel':
        replace('    internal val recapSnapshots = mutableMapOf<PersonalRecapWindow, HistoryRecapSnapshot>()\n','','recap-cache-needs-real-RSS-owner')
    elif rel=='feature/list/CommonListScreen':
        replace('    val personalRecapEnabled = if (historyViewModel != null) {\n        SettingsManager.getSubscriptionRecapEnabled(LocalContext.current)\n            .collectAsStateWithLifecycle(initialValue = false).value\n    } else false\n','','recap-preference-not-mounted-without-real-ports')
        start=body.index('                            val recapHeader: (@Composable () -> Unit)? = if (')
        end=body.index('                            CommonListContent(',start)
        replace(body[start:end],'','recap-request-and-header-not-mounted')
        replace('                                headerContent = recapHeader,\n','','no-empty-recap-header-callback')
        replace('                        useLookaheadBounds = onHistoryLongDelete == null || onHistoryDissolveComplete == null,\n','','existing-Windows-shared-layout-component-has-no-new-lookahead-parameter')
    inverse=body
    for e in reversed(edits):
        at=e['index'];assert inverse[at:at+len(e['after'])]==e['after'];inverse=inverse[:at]+e['before']+inverse[at+len(e['after']):]
    assert inverse==original
    path=PREFIX+rel+'.kt';pin=PINS[path]
    row=dict(path='desktop/upstream-slices/v029-brand-history/'+path,upstreamCommit=COMMIT,sha256LfUtf8=sha(original.encode()),rawSha256Bytes=pin['sha256Bytes'],gitBlob=pin['gitBlob'],historySourceAdaptation=dict(edits=edits,count=len(edits),fullRawInverseExact=True,afterSha256LfUtf8=sha(body.encode()),recapPending=True))
    return body,row
def generated_audit(rel,body):
    """Verify every existing platform adaptation plus omission to the full raw.

This immutable ledger describes actual output. The selected-source omissions
above remain named, counted, and separately invertible.
"""
    original=load_raw(rel);cursor=original;delta=0;edits=[]
    old_lines=original.splitlines(keepends=True);new_lines=body.splitlines(keepends=True)
    offsets=[0]
    for line in old_lines:offsets.append(offsets[-1]+len(line))
    for kind,a,b,c,d in difflib.SequenceMatcher(None,old_lines,new_lines,autojunk=False).get_opcodes():
        if kind=='equal':continue
        at=offsets[a]+delta;before=''.join(old_lines[a:b]);after=''.join(new_lines[c:d])
        assert cursor[at:at+len(before)]==before
        cursor=cursor[:at]+after+cursor[at+len(before):]
        edits.append(dict(index=at,before=before,after=after))
        delta+=len(after)-len(before)
    assert cursor==body
    for e in reversed(edits):
        at=e['index'];assert cursor[at:at+len(e['after'])]==e['after'];cursor=cursor[:at]+e['before']+cursor[at+len(e['after']):]
    assert cursor==original
    return dict(fullRawInverseExact=True,upstreamCommit=COMMIT,rawManifestSha256Bytes=MANIFEST_SHA256,originalRawSha256Bytes=sha(original.encode()),generatedSha256LfUtf8=sha(body.encode()),countedOutputAdaptations=len(edits),edits=edits,scope='Named source omissions plus unchanged existing sole producer environment/Android aliases. Complete v029 original business/renderer bodies inverse exactly; recap actual owner/header pending.')
