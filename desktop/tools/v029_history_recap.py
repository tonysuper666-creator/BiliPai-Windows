"""Complete fixed v029 recap bodies, reused by the existing sole producers.

Canonical catalog pins remain unchanged. Physical raw bytes are never normalized
to pass a pin; each counted platform delta has a complete original inverse.
"""
from pathlib import Path
import difflib,hashlib,json,os
ROOT=Path(__file__).resolve().parents[1]/'upstream-slices/v029-history-recap'
COMMIT='a4b77f894d0a2dd26c0b9fc144b8adb88ac05480'
MANIFEST_SHA256='238f0c595c54f85241e81105da29b40c3a532872dcecdacb4e015e8dd08ec365'
PINS={'app/src/main/java/com/android/purebilibili/core/plugin/feed/FeedReadingStore.kt': {'path': 'app/src/main/java/com/android/purebilibili/core/plugin/feed/FeedReadingStore.kt', 'upstreamCommit': 'a4b77f894d0a2dd26c0b9fc144b8adb88ac05480', 'gitBlob': '37564f4a9ec0863ab0bd24194855c0c1f9f82a49', 'sha256Bytes': '7c0b7727e49f9d152f0310b7391528854f85984bc36a71f12700d142c588a8d6', 'bytes': 5750, 'sourceUrl': 'https://raw.githubusercontent.com/jay3-yy/BiliPai/a4b77f894d0a2dd26c0b9fc144b8adb88ac05480/app/src/main/java/com/android/purebilibili/core/plugin/feed/FeedReadingStore.kt'}, 'app/src/main/java/com/android/purebilibili/core/plugin/feed/FeedSourceCatalog.kt': {'path': 'app/src/main/java/com/android/purebilibili/core/plugin/feed/FeedSourceCatalog.kt', 'upstreamCommit': 'a4b77f894d0a2dd26c0b9fc144b8adb88ac05480', 'gitBlob': '4d652016426859fcc7087b3e5c2f179c8bdb012e', 'sha256Bytes': 'e5063972eb7f5ecaf1c68056e75fa57c84b15ad91f89ef5b1c21cfba370ef7ca', 'bytes': 4074, 'sourceUrl': 'https://raw.githubusercontent.com/jay3-yy/BiliPai/a4b77f894d0a2dd26c0b9fc144b8adb88ac05480/app/src/main/java/com/android/purebilibili/core/plugin/feed/FeedSourceCatalog.kt'}, 'app/src/main/java/com/android/purebilibili/feature/home/subscription/SubscriptionFeedPage.kt': {'path': 'app/src/main/java/com/android/purebilibili/feature/home/subscription/SubscriptionFeedPage.kt', 'upstreamCommit': 'a4b77f894d0a2dd26c0b9fc144b8adb88ac05480', 'gitBlob': '46713d0db12a5a20cd2d10b3a297cbf0f6134f66', 'sha256Bytes': 'df0b6a9fb178b1a1f124af594b5d5d8ccd216286db11eb75e6fe8c6168e6cf84', 'bytes': 79781, 'sourceUrl': 'https://raw.githubusercontent.com/jay3-yy/BiliPai/a4b77f894d0a2dd26c0b9fc144b8adb88ac05480/app/src/main/java/com/android/purebilibili/feature/home/subscription/SubscriptionFeedPage.kt'}, 'app/src/main/java/com/android/purebilibili/core/store/SettingsManager.kt': {'path': 'app/src/main/java/com/android/purebilibili/core/store/SettingsManager.kt', 'upstreamCommit': 'a4b77f894d0a2dd26c0b9fc144b8adb88ac05480', 'gitBlob': '0e3ba91eda50c77195930bd9ffc2b447a557e702', 'sha256Bytes': 'def520af9293c76711364e216186d1fddcc90e0d2810d7f42170c2b74cef3865', 'bytes': 405563, 'sourceUrl': 'https://raw.githubusercontent.com/jay3-yy/BiliPai/a4b77f894d0a2dd26c0b9fc144b8adb88ac05480/app/src/main/java/com/android/purebilibili/core/store/SettingsManager.kt'}, 'app/src/main/java/com/android/purebilibili/feature/settings/ui/SettingsSections.kt': {'path': 'app/src/main/java/com/android/purebilibili/feature/settings/ui/SettingsSections.kt', 'upstreamCommit': 'a4b77f894d0a2dd26c0b9fc144b8adb88ac05480', 'gitBlob': '4ce530cad0845b325ffa89cff08b46ac03c18664', 'sha256Bytes': '46a495b02f231c230ff36da7a5164ec5fe47dd59555cae80ff6868f82a48b2a8', 'bytes': 106620, 'sourceUrl': 'https://raw.githubusercontent.com/jay3-yy/BiliPai/a4b77f894d0a2dd26c0b9fc144b8adb88ac05480/app/src/main/java/com/android/purebilibili/feature/settings/ui/SettingsSections.kt'}, 'app/src/main/java/com/android/purebilibili/feature/list/HistoryRecapCard.kt': {'path': 'app/src/main/java/com/android/purebilibili/feature/list/HistoryRecapCard.kt', 'upstreamCommit': 'a4b77f894d0a2dd26c0b9fc144b8adb88ac05480', 'gitBlob': '6b03b623227971badc4ee58f5836726f4d4a1440', 'sha256Bytes': '0cd8a074a85fff5c4fb91085cbc3baa4252f273f92c6a26289d23aeb54349a09', 'bytes': 17567, 'sourceUrl': 'https://raw.githubusercontent.com/jay3-yy/BiliPai/a4b77f894d0a2dd26c0b9fc144b8adb88ac05480/app/src/main/java/com/android/purebilibili/feature/list/HistoryRecapCard.kt'}, 'app/src/main/java/com/android/purebilibili/feature/list/PersonalRecapPolicy.kt': {'path': 'app/src/main/java/com/android/purebilibili/feature/list/PersonalRecapPolicy.kt', 'upstreamCommit': 'a4b77f894d0a2dd26c0b9fc144b8adb88ac05480', 'gitBlob': 'bd47649e07acb7bd276d9eea34fea8e49602f574', 'sha256Bytes': '39ed16d7fcc0e00ea79d5cab5eb2c5852132685281ecef3b73b8e0718240268e', 'bytes': 6260, 'sourceUrl': 'https://raw.githubusercontent.com/jay3-yy/BiliPai/a4b77f894d0a2dd26c0b9fc144b8adb88ac05480/app/src/main/java/com/android/purebilibili/feature/list/PersonalRecapPolicy.kt'}, 'app/src/main/java/com/android/purebilibili/feature/list/PersonalRecapCharts.kt': {'path': 'app/src/main/java/com/android/purebilibili/feature/list/PersonalRecapCharts.kt', 'upstreamCommit': 'a4b77f894d0a2dd26c0b9fc144b8adb88ac05480', 'gitBlob': '6825abea1522058fc65a015f69b5cbb69bc5d96f', 'sha256Bytes': '22220577365a82f2a19d3e0c77e872f09cd9d5867bd26f22e2df339bacd92aac', 'bytes': 7190, 'sourceUrl': 'https://raw.githubusercontent.com/jay3-yy/BiliPai/a4b77f894d0a2dd26c0b9fc144b8adb88ac05480/app/src/main/java/com/android/purebilibili/feature/list/PersonalRecapCharts.kt'}, 'app/src/main/java/com/android/purebilibili/feature/list/CommonListScreen.kt': {'path': 'app/src/main/java/com/android/purebilibili/feature/list/CommonListScreen.kt', 'upstreamCommit': 'a4b77f894d0a2dd26c0b9fc144b8adb88ac05480', 'gitBlob': 'f3ca9bafa474a07e753c9e328c3b9d9fe74cab10', 'sha256Bytes': 'e5676f371841e43225e8f88d71bcd62137df22bb13bca4aeb378a4fc4d73c5cd', 'bytes': 178540, 'sourceUrl': 'https://raw.githubusercontent.com/jay3-yy/BiliPai/a4b77f894d0a2dd26c0b9fc144b8adb88ac05480/app/src/main/java/com/android/purebilibili/feature/list/CommonListScreen.kt'}, 'app/src/main/java/com/android/purebilibili/feature/list/ListViewModel.kt': {'path': 'app/src/main/java/com/android/purebilibili/feature/list/ListViewModel.kt', 'upstreamCommit': 'a4b77f894d0a2dd26c0b9fc144b8adb88ac05480', 'gitBlob': 'b16b278d54b29e512f98fb1cf8bd07e38d861b37', 'sha256Bytes': 'a5ca3f58b081d907a928418aab33bb880683f99acdf426403314fd4b614f5f70', 'bytes': 67599, 'sourceUrl': 'https://raw.githubusercontent.com/jay3-yy/BiliPai/a4b77f894d0a2dd26c0b9fc144b8adb88ac05480/app/src/main/java/com/android/purebilibili/feature/list/ListViewModel.kt'}}
PREFIX='app/src/main/java/com/android/purebilibili/'
def wide(p):
 s=str(p.absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s) if os.name=='nt' else p
def sha(raw):return hashlib.sha256(raw).hexdigest()
def strict(pairs):
 result={}
 for k,v in pairs:
  if k in result:raise ValueError('duplicate recap manifest key')
  result[k]=v
 return result
def load_raw(rel):
 path=PREFIX+rel+'.kt'
 if path not in PINS:raise ValueError('unsupported recap raw source')
 root=wide(ROOT);manifest=(root/'manifest.json').read_bytes()
 if sha(manifest)!=MANIFEST_SHA256:raise ValueError('recap manifest pin mismatch')
 m=json.loads(manifest,object_pairs_hook=strict)
 files={Path(here,name).relative_to(root).as_posix() for here,_,names in os.walk(root) for name in names}
 if files!=set(PINS)|{'manifest.json'}:raise ValueError('recap raw file-set mismatch')
 rows={row['path']:row for row in m['files']}
 if len(rows)!=len(m['files']) or rows!=PINS or m['upstreamCommit']!=COMMIT:raise ValueError('recap manifest rows changed')
 data={}
 for p,pin in PINS.items():
  raw=(root/p).read_bytes()
  if len(raw)!=pin['bytes'] or sha(raw)!=pin['sha256Bytes']:raise ValueError('recap raw pin mismatch: '+p)
  if hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest()!=pin['gitBlob']:raise ValueError('recap Git blob mismatch')
  data[p]=raw.decode('utf-8')
 return data[path]
def inverse(body,edits):
 for e in reversed(edits):
  at=e['index']
  if body[at:at+len(e['after'])]!=e['after']:raise ValueError('recap inverse guard failed')
  body=body[:at]+e['before']+body[at+len(e['after']):]
 return body
def full_audit(original,body):
 cursor=original;delta=0;edits=[];old=original.splitlines(keepends=True);new=body.splitlines(keepends=True);offsets=[0]
 for line in old:offsets.append(offsets[-1]+len(line))
 for kind,a,b,c,d in difflib.SequenceMatcher(None,old,new,autojunk=False).get_opcodes():
  if kind=='equal':continue
  at=offsets[a]+delta;before=''.join(old[a:b]);after=''.join(new[c:d]);assert cursor[at:at+len(before)]==before
  cursor=cursor[:at]+after+cursor[at+len(before):];delta+=len(after)-len(before);edits.append(dict(index=at,before=before,after=after))
 assert cursor==body and inverse(body,edits)==original
 return dict(count=len(edits),edits=edits,fullRawInverseExact=True,generatedSha256LfUtf8=sha(body.encode()))
def record(rel,original,body):
 pin=PINS[PREFIX+rel+'.kt']
 return dict(path='desktop/upstream-slices/v029-history-recap/'+pin['path'],upstreamCommit=COMMIT,
  sha256LfUtf8=sha(original.encode()),rawSha256Bytes=pin['sha256Bytes'],gitBlob=pin['gitBlob'],recapGeneratedAdaptation=full_audit(original,body))
def adapt(rel,edits):
 original=load_raw(rel);body=original;ledger=[]
 for before,after,label in edits:
  if body.count(before)!=1:raise ValueError('recap counted adaptation not unique: '+label)
  at=body.index(before);body=body[:at]+after+body[at+len(before):];ledger.append(dict(index=at,before=before,after=after,label=label))
 assert inverse(body,ledger)==original
 row=record(rel,original,body);row['recapGeneratedAdaptation']['countedPlatformEdits']=ledger
 return original,body,row
def feed_store_source():
 return adapt('core/plugin/feed/FeedReadingStore',[
  ('import android.content.Context','import com.bilipai.desktop.plugins.DesktopPluginContext as Context','same existing runtime Context'),
  ('import android.util.AtomicFile','import com.bilipai.desktop.plugins.DesktopPluginAtomicFile as AtomicFile','same existing AtomicFile/admission')])
def favorite_sources():
 rel='feature/list/PersonalRecapPolicy'
 yield (rel,*adapt(rel,[
  ('import com.android.purebilibili.data.repository.HistoryRepository','import com.android.purebilibili.data.repository.DesktopOriginalHistoryRepository','same actual entry-owned repository type'),
  ('suspend fun fetchPersonalVideoRecapHistory(\n','suspend fun fetchPersonalVideoRecapHistory(\n    repository: DesktopOriginalHistoryRepository,\n    checkRequest: () -> Unit,\n','required repository and captured request check'),
  ('        val result = HistoryRepository.getHistoryList(ps = 30, max = cursorMax, viewAt = cursorViewAt)\n            .getOrThrow()',
   '        checkRequest()\n        val result = repository.getHistoryList(ps = 30, max = cursorMax, viewAt = cursorViewAt)\n            .getOrThrow()\n        checkRequest()','same complete cursor API body with before/after request boundary'),
  ('    suspend fun videoRecap(windowStartMs: Long): PersonalVideoRecapStats {','    suspend fun videoRecap(repository: DesktopOriginalHistoryRepository, checkRequest: () -> Unit, windowStartMs: Long): PersonalVideoRecapStats {','required actual entry port'),
  ('fetchPersonalVideoRecapHistory(windowStartMs)','fetchPersonalVideoRecapHistory(repository, checkRequest, windowStartMs)','same original cursor/aggregation'),
  ('context: android.content.Context','context: com.bilipai.desktop.plugins.DesktopPluginContext','same actual RSS backing')]))
 rel='feature/list/PersonalRecapCharts';original=load_raw(rel);yield (rel,original,original,record(rel,original,original))
 rel='feature/list/HistoryRecapCard'
 yield (rel,*adapt(rel,[
  ('import androidx.compose.ui.platform.LocalContext','import com.bilipai.desktop.ui.requireDesktopPersonalRecapBinding','required actual History entry'),
  ('    val context = LocalContext.current','    val binding = requireDesktopPersonalRecapBinding()\n    val context = binding.context','same actual runtime Context'),
  ('        loading = snapshot == null\n        videoUnavailable = false','        val request = binding.beginRequest()\n        request.publish { loading = snapshot == null; videoUnavailable = false }','capture actual effect caller and request identity'),
  ('            val sources = withContext(Dispatchers.IO) { loadEnabledFeedSources(context) }','            val sources = request.read { withContext(Dispatchers.IO) { loadEnabledFeedSources(context) } }','same original real source reader; await outside admission'),
  ('            val rssStats = PersonalRecapRepository.rssRecap(context, start)','            val rssStats = request.read { PersonalRecapRepository.rssRecap(context, start) }','same original real timestamp reader'),
  ('            val videoStats = PersonalRecapRepository.videoRecap(start)','            val videoStats = request.read { PersonalRecapRepository.videoRecap(binding.history, request::check, start) }','same actual owned History API'),
  ('            snapshotCache?.set(window, refreshed)\n            snapshot = refreshed','            request.publish {\n                snapshotCache?.set(window, refreshed)\n                snapshot = refreshed\n            }','final same caller/account/entry request admission'),
  ('            videoUnavailable = snapshot == null','            request.publish { videoUnavailable = snapshot == null }','retired errors cannot publish'),
  ('            loading = false','            request.cleanup { loading = false }\n            request.finish()','only this request clears its own busy marker')]))
 rel='core/store/SettingsManager';original=load_raw(rel)
 start=original.index('    private val KEY_SUBSCRIPTION_RECAP_ENABLED =')
 key=original[start:original.index('\n\n',start)]
 start=original.index('    fun getSubscriptionRecapEnabled(')
 getter=original[start:original.index('\n\n',start)]
 start=original.index('    suspend fun setSubscriptionRecapEnabled(')
 setter=original[start:original.index('\n\n',start)]
 selected=key+'\n\n'+getter+'\n\n'+setter+'\n'
 edits=[('booleanPreferencesKey("subscription_recap_enabled")','com.bilipai.desktop.ui.playerBooleanPreferencesKey("subscription_recap_enabled")','same original Boolean key/decoder'),
  ('fun getSubscriptionRecapEnabled(context: Context)','fun getSubscriptionRecapEnabled(context: com.bilipai.desktop.plugins.DesktopPluginContext)','same existing Root Context read'),
  ('= context.settingsDataStore.data','= context.store.snapshot("settings")','same original namespace read'),
  ('suspend fun setSubscriptionRecapEnabled(context: Context','suspend fun setSubscriptionRecapEnabled(context: DesktopOriginalPlayerSettingsContext','same existing settings Context/atomic journal')]
 body=selected;ledger=[]
 for before,after,label in edits:
  assert body.count(before)==1;at=body.index(before);body=body[:at]+after+body[at+len(before):];ledger.append(dict(index=at,before=before,after=after,label=label))
 assert inverse(body,ledger)==selected
 generated='package com.bilipai.desktop.ui\n\nimport kotlinx.coroutines.flow.Flow\nimport kotlinx.coroutines.flow.map\n\n/** Original OFF-by-default key/getter/setter; same actual Root backing and write journal. */\ninternal object DesktopPersonalRecapSettings {\n'+body+'}\n'
 row=record(rel,original,generated);row['recapGeneratedAdaptation']['selectedOriginalBodies']=dict(sourceSha256=sha(original.encode()),selectedSha256=sha(selected.encode()),adaptedSelectedSha256=sha(body.encode()),edits=ledger,completeSelectedInverseExact=True)
 yield ('core/store/DesktopPersonalRecapSettings',original,generated,row)
