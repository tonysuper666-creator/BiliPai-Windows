from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import hashlib,importlib.util,json,re,subprocess,sys,difflib
sys.dont_write_bytecode=True;sys.stdout.reconfigure(encoding='utf-8')
import argparse
cli=argparse.ArgumentParser(description="Complete fixed-tag MusicPlayerContent and original lyrics/artwork/import/history UI")
cli.add_argument('--repo',type=Path,required=True);cli.add_argument('--output',type=Path,required=True);cli.add_argument('--standalone',action='store_true')
args=cli.parse_args();REPO=args.repo.resolve();LANE=args.output.resolve()
FEATURE='stable-music-player-original'
SOURCE_PINS={'app/src/main/java/com/android/purebilibili/feature/audio/lyrics/halcyon/AppleMusicLyricsView.kt': {'sha256LF': 'deeaee2422f18c6f5bad05186235eccb900fe77e48d864751be0545f9974ff67', 'gitBlob': '37d768fa8543c9fc1d1df8a310f9af6a7dd85e86'}, 'app/src/main/java/com/android/purebilibili/feature/audio/lyrics/halcyon/AppleMusicLyricLine.kt': {'sha256LF': '1114516a2105245c0b108e2d4616636cbca4c56b9635105420962acadf2730f7', 'gitBlob': '5a047bd369af89371bb721d35eda79286dcdf5a5'}, 'app/src/main/java/com/android/purebilibili/feature/audio/lyrics/halcyon/AppleMusicKaraokeText.kt': {'sha256LF': '41765edebf44f4719b1e46e7c1efc5a82281d0d4db8eb18fe4e1c6ae28af1180', 'gitBlob': 'f4705e979d7874dd0515ff6a6550a33f22072f32'}, 'app/src/main/java/com/android/purebilibili/feature/audio/lyrics/halcyon/AppleMusicInterludes.kt': {'sha256LF': '625a065e021dcde08f9b1a0eb9595c17a33e893913e241a3b9b804cd5f852114', 'gitBlob': '587c89a36b91f18aefcfd293be6944152f1a5f57'}, 'app/src/main/java/com/android/purebilibili/feature/audio/lyrics/halcyon/PlayerMiniLyrics.kt': {'sha256LF': '92299e6f66c8e3bf25ac0866eda05f364dcdf72d3f9d61a40d86bffaecefd98b', 'gitBlob': '72ff85e59ddb1752dbec1741d4ba9acacc0fed6f'}, 'app/src/main/java/com/android/purebilibili/feature/audio/lyrics/halcyon/HalcyonLyricSettings.kt': {'sha256LF': 'e6d0fee9d480937bd863d70da646ac256c9f4021c78b6b02d08ee1e90d97eeda', 'gitBlob': '798dd4186a6d182847619689d6f0917110676f71'}, 'app/src/main/java/com/android/purebilibili/feature/audio/lyrics/halcyon/PlayerLyricPerspective.kt': {'sha256LF': '31a860459d16f51b3c7d94147f0852a7ba7a62d06b073a7d8a8b8c373a908a59', 'gitBlob': 'e353957fc8edd02f74afa74b5f3ff27fc274ab6b'}, 'app/src/main/java/com/android/purebilibili/feature/audio/lyrics/halcyon/PlayerPalette.kt': {'sha256LF': 'dba4432bc86d36792ec75e67681bec25e512b6290471a6c66b68d1b8c210e43b', 'gitBlob': '8a18bc830fc7e0fb72d5809484144c8859a8349b'}, 'app/src/main/java/com/android/purebilibili/feature/audio/lyrics/halcyon/PlayerBlurBackground.kt': {'sha256LF': 'fbc253f246e873b3c8131d4fa4328d1932e63eb4e931b720264999718c8d8c84', 'gitBlob': 'db9dea7aa6f8819bdc0fadbac0c14089e390b3bc'}, 'app/src/main/java/com/android/purebilibili/feature/audio/lyrics/halcyon/AppleCoverFlowBackground.kt': {'sha256LF': '466ae7e5dff23b97d4d3cddf7718855d57947c1998bae26a5570490f088b0599', 'gitBlob': 'cfc4a8bb54b1e066c2b1d077b64e03c4726e6930'}, 'app/src/main/java/com/android/purebilibili/feature/audio/screen/MusicPlayerVisualPolicy.kt': {'sha256LF': '4d76fac7122d0e371fd8710eab280f20921e6ce164a01b9773dfd3cadbfebb1b', 'gitBlob': 'd997011ca540580e1b698369dae847220aeb84e8'}, 'app/src/main/java/com/android/purebilibili/feature/audio/screen/MusicTopControlDragPolicy.kt': {'sha256LF': '8eb95f92c79b22446cdedc05a67566c328e3a01df750c58abbd144816e366d07', 'gitBlob': 'be50bc68387633cf26e4e2d269c64f7ce4eeb248'}, 'app/src/main/java/com/android/purebilibili/feature/audio/screen/MusicWavyProgressPolicy.kt': {'sha256LF': 'd310349e0081260e95353694f47ee498277db6da4622dac33eab5a36b0999ab3', 'gitBlob': 'e6558f6a0affbe6881ffbeef875cf0f51c9c4142'}, 'app/src/main/java/com/android/purebilibili/feature/audio/screen/MusicWavySlider.kt': {'sha256LF': 'd6de77651bbf9109b7e0f27dd72084e4b466f61d77d09e1a5f37f6121ca54e3f', 'gitBlob': '89d602295d5373c3e2bb33ae99e1cb4a02c6bdfa'}, 'app/src/main/java/com/android/purebilibili/feature/audio/screen/Music3DCoverFlow.kt': {'sha256LF': 'fea16146c8b3ff2ecca919e31f177330d59ff973703972eba6a162aac2b23392', 'gitBlob': 'b54fbb2b17c75dd338e514d53a78a29f0dbaf8e7'}, 'app/src/main/java/com/android/purebilibili/core/store/SettingsManager.kt': {'sha256LF': '5799bb8802992594ae9494b48d6357ee00ecc7be03d97ed0dcb5fede7774328c', 'gitBlob': '5d24281dc2152c1e2113ab3476418f61261cd15a'}, 'app/src/main/java/com/android/purebilibili/core/store/PlayHistoryStore.kt': {'sha256LF': '571161606f2dc9c287fcbdfc43699b46fca9e67384969598e38cbd4170efcf17', 'gitBlob': 'bf0af37abeaef05a92cac860259376157b756d87'}, 'app/src/main/java/com/android/purebilibili/core/store/LocalPlaylistStore.kt': {'sha256LF': '40ee1a329c6a725f52f1450b7686d1e66eab320cf31acd9721d5e8bace414fdd', 'gitBlob': '651cf856083e2373e2bf793bac5c9506acf98c0b'}, 'app/src/main/java/com/android/purebilibili/data/repository/ExternalPlaylistRepository.kt': {'sha256LF': '3fe9556d6701f34e7f0eb1731ad005acca7f2ef2f278f5bf3fc571911ae09288', 'gitBlob': 'c84d1b014268b9663eb65db51d2bb042357d6a51'}, 'app/src/main/java/com/android/purebilibili/feature/audio/screen/PlayHistorySheet.kt': {'sha256LF': '85639fbe680f6f56868bf7b58126af26b921721ba876b670213344f57f6f2694', 'gitBlob': '079e23e919f0a1881423ea8945081af33b3c207c'}, 'app/src/main/java/com/android/purebilibili/feature/audio/screen/ExternalPlaylistImportDialog.kt': {'sha256LF': '26cb46a8d744f16eb448c3242ade46d4edc9363c7109fdaf7b86d07c3fd91c97', 'gitBlob': '1420b9a7c22f114894e4a56506c20cdd47f0339b'}, 'app/src/main/java/com/android/purebilibili/feature/audio/screen/MusicPlayerContent.kt': {'sha256LF': '2a200f2661989021073992af6fbe041a8a973dd8e9a1e221f8935283aebb538d', 'gitBlob': 'e22e16772a64eac70e27918f724471f7752a4859'}}
DIRECT={p for p in SOURCE_PINS if any(p.endswith('/'+n+'.kt')for n in ['MusicPlayerVisualPolicy','MusicTopControlDragPolicy','MusicWavyProgressPolicy','HalcyonLyricSettings'])}
manifest=json.loads((REPO/'desktop/upstream-sources.json').read_text(encoding='utf-8'))
assert manifest['upstreamCommit']=='79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40'

COMMIT='79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40';BASE='app/src/main/java/com/android/purebilibili/'
OUT=LANE;SOURCES={};CHANGES={};OUTPUTS=[]
def safe(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix)else prefix+s)
def sha(t):return hashlib.sha256(t.encode()if isinstance(t,str)else t).hexdigest()
def write(p,t):safe(p).parent.mkdir(parents=True,exist_ok=True);safe(p).write_bytes(t.encode()if isinstance(t,str)else t)
def module(name,p):
 spec=importlib.util.spec_from_file_location(name,p);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
parser=module('music_tokens',REPO/'desktop/tools/sync-upstream.py');selector=module('music_select',REPO/'desktop/tools/extract-appearance-platform.py')
section=module('music_section',REPO/'desktop/tools/extract-upstream-video-player-section-full.py');section.parser=parser
def read(rel):
 p=BASE+rel
 if p not in SOURCES:
  raw=subprocess.check_output(['git','show',COMMIT+':'+p],cwd=REPO);assert raw==safe(_desktop_canonical_source(REPO, p)).read_bytes().replace(b'\r\n',b'\n'),p
  assert sha(raw)==SOURCE_PINS[p]['sha256LF']
  assert subprocess.check_output(['git','rev-parse',COMMIT+':'+p],cwd=REPO,text=True).strip()==SOURCE_PINS[p]['gitBlob']
  if not args.standalone:
   rows=[r for r in manifest['sources']if r['path']==p]
   assert len(rows)==1 and rows[0]['sha256']==SOURCE_PINS[p]['sha256LF'] and FEATURE in rows[0]['features'],p

  SOURCES[p]={'text':raw.decode(),'sha256LF':sha(raw),'gitBlob':subprocess.check_output(['git','rev-parse',COMMIT+':'+p],cwd=REPO,text=True).strip()};write(LANE/'original-retained'/(p+'.txt'),raw)
 return SOURCES[p]['text']
def replace(t,b,a,label,all=False):
 n=t.count(b);assert n>0 and(all or n==1),(CURRENT,label,n)
 # Each exact offset is relative to the preceding operation. Reverse replay restores
 # the whole original body, including platform-only blocks and imports.
 indices=[m.start()for m in re.finditer(re.escape(b),t)]
 for i in indices[::-1]if all else indices:
  CHANGES.setdefault(CURRENT,[]).append({'label':label,'before':b,'after':a,'index':i});t=t[:i]+a+t[i+len(b):]
 return t
def between(t,b,e,a,label):
 i=t.index(b);j=t.index(e,i);return replace(t,t[i:j],a,label)
def emit(rel,t,original,selected=None):
 p=OUT/'com/android/purebilibili'/rel
 # Receipt/proof may select DIRECT bodies, but Sync is their sole production copy.
 if BASE+original not in DIRECT or args.standalone:write(p,t)
 OUTPUTS.append({'path':str(p),'sha256LF':sha(t),'original':BASE+original,'selected':selected})
HAL='feature/audio/lyrics/halcyon/'
for name in ['AppleMusicLyricsView','AppleMusicLyricLine','AppleMusicKaraokeText','AppleMusicInterludes','PlayerMiniLyrics','HalcyonLyricSettings','PlayerLyricPerspective','PlayerPalette','PlayerBlurBackground','AppleCoverFlowBackground']:
 CURRENT=HAL+name+'.kt';t=read(CURRENT)
 for b,a in {
 'import android.graphics.Bitmap':'import com.bilipai.desktop.ui.DesktopMusicRaster as Bitmap',
 'import android.graphics.Color as AndroidColor':'import com.bilipai.desktop.ui.DesktopMusicRasterColor as AndroidColor',
 'import android.graphics.Canvas':'import com.bilipai.desktop.ui.DesktopMusicRasterCanvas as Canvas',
 'import android.graphics.ColorMatrixColorFilter':'import com.bilipai.desktop.ui.DesktopMusicRasterColorFilter as ColorMatrixColorFilter',
 'import android.graphics.ColorMatrix\n':'import com.bilipai.desktop.ui.DesktopMusicRasterColorMatrix as ColorMatrix\n',
 'import android.graphics.Matrix':'import com.bilipai.desktop.ui.DesktopMusicRasterMatrix as Matrix',
 'import android.graphics.Paint':'import com.bilipai.desktop.ui.DesktopMusicRasterPaint as Paint',
 'import androidx.compose.ui.graphics.asImageBitmap':'import com.bilipai.desktop.ui.asImageBitmap',
 'import androidx.compose.ui.platform.LocalContext\n':'',
 'import androidx.compose.ui.platform.LocalConfiguration':'import com.bilipai.desktop.ui.DesktopHomeCardWindowMetrics as LocalConfiguration',
 }.items():
  if b in t:t=replace(t,b,a,'Actual Windows backend/import '+b)
 if name=='PlayerLyricPerspective':
  t=replace(t,'configuration.orientation ==\n        android.content.res.Configuration.ORIENTATION_LANDSCAPE','configuration.screenWidthDp > configuration.screenHeightDp','Actual enclosing window layout, not fabricated Android orientation')
  t=replace(t,'configuration.smallestScreenWidthDp','minOf(configuration.screenWidthDp, configuration.screenHeightDp)','Actual enclosing window dimensions')
 if name=='AppleCoverFlowBackground':
  t=replace(t,'    val context = LocalContext.current\n    val densityDpi = context.resources.displayMetrics.densityDpi\n    val settingsManager = remember(context) { HalcyonLyricSettings }','    val densityDpi = (androidx.compose.ui.platform.LocalDensity.current.density * 160f).roundToInt()\n    val settingsManager = remember { HalcyonLyricSettings }','Original DPI sampling threshold maps actual Compose pixel density; original stock settings retained')
  t=replace(t,'    val canvas = Canvas(frame)','    val canvas = Canvas(frame)\n    try {','Dispose actual Graphics2D even when original frame computation fails')
  t=replace(t,'    if (frame !== blurred && frame !== result) frame.recycle()\n    return result\n}','    if (frame !== blurred && frame !== result) frame.recycle()\n    return result\n    } finally { canvas.close() }\n}','Dispose actual Graphics2D after original blur/crop algorithm')
 emit(CURRENT,t,CURRENT)
for name in ['MusicPlayerVisualPolicy','MusicTopControlDragPolicy','MusicWavyProgressPolicy']:
 CURRENT='feature/audio/screen/'+name+'.kt';emit(CURRENT,read(CURRENT),CURRENT)
for name in ['MusicWavySlider','Music3DCoverFlow']:
 CURRENT='feature/audio/screen/'+name+'.kt';t=read(CURRENT)
 if name=='MusicWavySlider':
  t=replace(t,'import android.os.SystemClock\n','','Monotonic existing JVM clock')
  t=replace(t,'SystemClock.elapsedRealtime()','System.nanoTime() / 1_000_000L','Monotonic existing JVM clock',True)
 emit(CURRENT,t,CURRENT)
CURRENT='core/store/SettingsManager.kt';settings=read(CURRENT)
settingsInner=settings[settings.index('object SettingsManager {')+len('object SettingsManager {'):settings.rfind('}')]
enum=selector.declarations(parser,settingsInner,['MusicLyricsUiStyle'])
body,names=section.member_closure(settingsInner,['getMusicLyricsUiStyle','setMusicLyricsUiStyle','getStartupAutoPlayEnabledSync','getAudioNowPlayingBarEnabled'])
if 'MusicLyricsUiStyle' in names:enum=''
emit('core/store/DesktopOriginalMusicUiSettings.kt','''package com.android.purebilibili.core.store
import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext as Context
import com.bilipai.desktop.ui.playerIntPreferencesKey as intPreferencesKey
import com.bilipai.desktop.ui.playerBooleanPreferencesKey as booleanPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
object DesktopOriginalMusicUiSettings {
'''+enum+'\n'+body+'\n}\n',CURRENT,{'members':names,'enum':['MusicLyricsUiStyle']})
CURRENT='core/store/PlayHistoryStore.kt';t=read(CURRENT);selected=selector.declarations(parser,t,['PlayHistoryEntry','PlayLastSession'])
emit('core/store/DesktopOriginalPlayHistoryModels.kt','package com.android.purebilibili.core.store\nimport kotlinx.serialization.Serializable\n'+selected+'\n',CURRENT,{'declarations':['PlayHistoryEntry','PlayLastSession']})
CURRENT='core/store/LocalPlaylistStore.kt';t=read(CURRENT);selected=selector.declarations(parser,t,['LocalPlaylistItem','LocalPlaylist'])
emit('core/store/DesktopOriginalLocalPlaylistModels.kt','package com.android.purebilibili.core.store\nimport kotlinx.serialization.Serializable\n'+selected+'\n',CURRENT,{'declarations':['LocalPlaylistItem','LocalPlaylist']})
# The existing model producers remain sole owners. Emit the complete original
# history/local-playlist objects separately, replacing only their object name
# and consumed Android Context/preferences imports with the same Root Store view.
for originalName, desktopName in [('PlayHistoryStore', 'DesktopOriginalAudioHistoryStore'),
                                  ('LocalPlaylistStore', 'DesktopOriginalLocalPlaylistStore')]:
 CURRENT='core/store/'+originalName+'.kt';t=read(CURRENT)
 selected=selector.declarations(parser,t,[originalName])
 originalObject=selected
 declaration='object '+originalName+' {'; adapted='object '+desktopName+' {'
 assert selected.count(declaration)==1
 selected=selected.replace(declaration,adapted,1)
 assert selected.replace(adapted,declaration,1)==originalObject
 header='package com.android.purebilibili.core.store\n'
 header+='import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext as Context\n'
 header+='import com.bilipai.desktop.ui.playerStringPreferencesKey as stringPreferencesKey\n'
 header+='import kotlinx.coroutines.flow.Flow\nimport kotlinx.coroutines.flow.map\n'
 header+='import kotlinx.serialization.encodeToString\nimport kotlinx.serialization.json.Json\n'
 emit('core/store/'+desktopName+'.kt',header+selected+'\n',CURRENT,
      {'declarations':[originalName], 'fullObjectBody':True, 'originalObjectSHA256LF':sha(originalObject),
       'onlyObjectIdentifierRenamed':{'before':originalName,'after':desktopName}, 'exactWholeObjectInverse':True})
CURRENT='data/repository/ExternalPlaylistRepository.kt';t=read(CURRENT)
inner=t[t.index('object ExternalPlaylistRepository {')+len('object ExternalPlaylistRepository {'):t.rfind('}')]
names=['Source','ExternalTrack','ExternalPlaylistMeta','MatchedVideo','MatchOutcome','ImportCheckpoint','parsePlaylistInput','extractId','buildSearchQueryForManualMatch']
selected=selector.declarations(parser,inner,names)
emit('data/repository/DesktopOriginalExternalPlaylistSchema.kt','package com.android.purebilibili.data.repository\nimport kotlinx.serialization.Serializable\nobject DesktopOriginalExternalPlaylistSchema {\n'+selected+'\n}\n',CURRENT,{'declarations':names})
# Original HTTP parsing, encryption, checkpoint storage and matching are selected
# once. Their canonical schemas above and the original Search policies are borrowed.
domainNames=['SerializableMatchOutcome','json','importCheckpointKey','loadImportCheckpoint',
             'saveImportCheckpoint','clearImportCheckpoint','StoredImportCheckpoint',
             'BLACKLIST_ZONES','DURATION_TOLERANCE_SEC','MATCH_DELAY_MS','fetchPlaylist',
             'fetchQqPlaylist','fetchNeteasePlaylist','eapiEncrypt','matchTrack','matchTracks']
domain=selector.declarations(parser,inner,domainNames);originalDomain=domain;domainChanges=[]
for before,after in [
 ('NetworkModule.okHttpClient.newCall(request).execute().use { response ->',
  'responsePort.withResponse(request) { response ->'),
 ('SearchRepository.search(keyword = query, page = 1)',
  'searchRepository.search(keyword = query, page = 1)'),
 ('private const val ', 'private val ')]:
 positions=[m.start()for m in re.finditer(re.escape(before),domain)]
 assert len(positions)==(2 if before.startswith(('NetworkModule','private const'))else 1)
 for index in reversed(positions):
  domainChanges.append({'index':index,'before':before,'after':after})
  domain=domain[:index]+after+domain[index+len(before):]
restored=domain
for change in reversed(domainChanges):
 index=change['index'];assert restored[index:index+len(change['after'])]==change['after']
 restored=restored[:index]+change['before']+restored[index+len(change['after']):]
assert restored==originalDomain
domainHeader='''package com.android.purebilibili.data.repository
import com.android.purebilibili.data.repository.DesktopOriginalExternalPlaylistSchema.Source
import com.android.purebilibili.data.repository.DesktopOriginalExternalPlaylistSchema.ExternalTrack
import com.android.purebilibili.data.repository.DesktopOriginalExternalPlaylistSchema.ExternalPlaylistMeta
import com.android.purebilibili.data.repository.DesktopOriginalExternalPlaylistSchema.MatchedVideo
import com.android.purebilibili.data.repository.DesktopOriginalExternalPlaylistSchema.MatchOutcome
import com.android.purebilibili.data.repository.DesktopOriginalExternalPlaylistSchema.ImportCheckpoint
import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext as Context
import com.bilipai.desktop.ui.playerStringPreferencesKey as stringPreferencesKey
import com.bilipai.desktop.ui.DesktopOriginalExternalPlaylistResponsePort
import com.android.purebilibili.core.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
internal class DesktopOriginalExternalPlaylistDomain(
    private val responsePort: DesktopOriginalExternalPlaylistResponsePort,
    private val searchRepository: DesktopOriginalCapturedVideoSearch,
) {
'''
emit('data/repository/DesktopOriginalExternalPlaylistDomain.kt',domainHeader+domain+
     '\n    suspend fun searchVideo(keyword: String) = searchRepository.search(keyword = keyword)\n}\n',CURRENT,
     {'declarations':domainNames,'originalSelectedSHA256LF':sha(originalDomain),
      'adaptations':domainChanges,'exactSelectedBodyInverse':True})
CURRENT='feature/audio/screen/PlayHistorySheet.kt';t=read(CURRENT)
t=replace(t,'import com.android.purebilibili.core.store.PlayHistoryStore\n','','Required same Root history view')
t=replace(t,'import androidx.compose.ui.platform.LocalContext','import com.bilipai.desktop.ui.LocalDesktopOriginalMusicUiPlatform','Required same Root history view')
t=replace(t,'    val context = LocalContext.current\n    val entries by PlayHistoryStore.recent(context, limit = 50)','    val platform = LocalDesktopOriginalMusicUiPlatform.current\n    val entries by platform.history.recent(limit = 50)','Original full UI reads same existing history actor')
emit(CURRENT,t,CURRENT)
CURRENT='feature/audio/screen/ExternalPlaylistImportDialog.kt';t=read(CURRENT)
t=replace(t,'            decorFitsSystemWindows = false,\n','','Android decor system-bars option maps actual desktop Dialog client area')
t=replace(t,'import androidx.compose.ui.platform.LocalContext','import com.bilipai.desktop.ui.LocalDesktopOriginalMusicUiPlatform','Required same Root import actions')
t=replace(t,'import com.android.purebilibili.data.repository.ExternalPlaylistRepository','import com.android.purebilibili.data.repository.DesktopOriginalExternalPlaylistSchema as ExternalPlaylistRepository','Full original schemas and pure input policy selected once')
t=replace(t,'import com.android.purebilibili.data.repository.SearchRepository\n','','Required same Repository search transport')
t=replace(t,'import com.android.purebilibili.core.store.LocalPlaylistStore\n','','Required same local playlist persistence')
t=replace(t,'    val context = LocalContext.current','    val platform = LocalDesktopOriginalMusicUiPlatform.current\n    val context = platform.context','Required same Root owner, no new Store/HTTP')
for name in ['loadImportCheckpoint','saveImportCheckpoint','clearImportCheckpoint','matchTracks','fetchPlaylist']:
 t=replace(t,'ExternalPlaylistRepository.'+name,'platform.externalPlaylist.'+name,'Same owned protocol/persistence operation '+name,True)
t=replace(t,'LocalPlaylistStore.savePlaylist(context, local)','platform.savePlaylist(local)','Same existing Root local playlist persistence')
t=replace(t,'SearchRepository.search(keyword = manualKeyword)','platform.externalPlaylist.search(keyword = manualKeyword)','Same owned Repository/WBI search')
for originalUi in [
 '''            playlist = checkpoint.playlist
            matchResults = checkpoint.outcomes.map { ExternalPlaylistRepository.MatchOutcome(it.track, it.video) }
            matchTotal = checkpoint.playlist.tracks.size
            matchCompleted = checkpoint.completedCount''',
 '''                matchCompleted = completed
                matchingTrackTitle = outcome.track.title
                matchResults = matchResults.toMutableList().also { list ->
                    if (completed - 1 in list.indices) list[completed - 1] = outcome
                }''',
 '''                                                                manualResults = items.take(8).map {
                                                                    ExternalPlaylistRepository.MatchedVideo(
                                                                        bvid = it.bvid,
                                                                        title = it.title,
                                                                        cover = it.pic,
                                                                        author = it.owner.name,
                                                                        durationSec = it.duration.toLong(),
                                                                    )
                                                                }''',
 '''                                                playlist = fetched
                                                matchResults = fetched.tracks.map {
                                                    ExternalPlaylistRepository.MatchOutcome(it, null)
                                                }
                                                matchCompleted = 0
                                                matchTotal = fetched.tracks.size''',
]:
 indent=re.match(r' *',originalUi).group();body='\n'.join('    '+line for line in originalUi.splitlines())
 t=replace(t,originalUi,indent+'platform.commitUi {\n'+body+'\n'+indent+'}',
           'Only async UI publication enters same caller/entry/account gate; checkpoint IO remains outside')
for before,after in [
 ('            matching = false\n        }\n    }\n\n    fun savePlaylist()',
  '            platform.commitUi { matching = false }\n        }\n    }\n\n    fun savePlaylist()'),
 ('if (fetched.tracks.isEmpty()) fetchError = "歌单为空或为私密歌单"',
  'if (fetched.tracks.isEmpty()) platform.commitUi { fetchError = "歌单为空或为私密歌单" }'),
 ('                                    fetchError = "无法识别链接，请粘贴网易云或 QQ 音乐的完整分享链接"',
  '                                    platform.commitUi { fetchError = "无法识别链接，请粘贴网易云或 QQ 音乐的完整分享链接" }'),
 ('                                        .onFailure { fetchError = it.message ?: "获取歌单失败" }',
  '                                        .onFailure { error -> platform.commitUi { fetchError = error.message ?: "获取歌单失败" } }'),
 ('                                fetching = false\n                            }',
  '                                platform.commitUi { fetching = false }\n                            }'),
 ('                                                        manualSearching = true',
  '                                                        platform.commitUi { manualSearching = true }'),
 ('                                                        manualSearching = false',
  '                                                        platform.commitUi { manualSearching = false }'),
]:t=replace(t,before,after,'Async import UI publishes only inside same Root short admission')
emit(CURRENT,t,CURRENT)
CURRENT='feature/audio/screen/MusicPlayerContent.kt';t=read(CURRENT)
for b,a in {
 'import android.os.Build\n':'', 'import android.provider.Settings\n':'',
 'import androidx.palette.graphics.Palette\n':'', 'import coil3.request.allowHardware\n':'',
 'import androidx.compose.ui.graphics.asAndroidBitmap\n':'', 'import androidx.compose.ui.graphics.asImageBitmap\n':'',
 'import coil3.imageLoader\n':'',
 'import androidx.compose.ui.platform.LocalContext':'import com.bilipai.desktop.ui.LocalDesktopOriginalMusicUiPlatform',
 'import com.android.purebilibili.core.lifecycle.BackgroundManager\n':'',
 'import com.android.purebilibili.core.store.SettingsManager':'import com.android.purebilibili.core.store.DesktopOriginalMusicUiSettings as SettingsManager',
 }.items():
 if b in t:t=replace(t,b,a,'Required actual Windows platform/import '+b)
t=replace(t,'val context = LocalContext.current','val platform = LocalDesktopOriginalMusicUiPlatform.current\n    val context = platform.context','Required same-entry Root platform capture',True)
t=replace(t,'getMusicLyricsUiStyle(LocalContext.current)','getMusicLyricsUiStyle(LocalDesktopOriginalMusicUiPlatform.current.context)','Same required global context in complete private player UI')
t=between(t,'    val systemReduceMotion = remember(context) {','    val musicBackdropSource', '''    val systemReduceMotion by platform.systemReduceMotion.collectAsStateWithLifecycle()
    val isInBackground by platform.isInBackground.collectAsStateWithLifecycle()
    val effectiveReduceMotion = reduceMotion || systemReduceMotion || isInBackground
''','Actual Root system/lifecycle flows')
t=replace(t,'val homeSettings by SettingsManager\n        .getHomeSettings(context)\n        .collectAsStateWithLifecycle(initialValue = HomeSettings())','val homeSettings by platform.homeSettings.collectAsStateWithLifecycle()','Same full global settings current value, no fake default')
t=replace(t,'BackgroundManager.isInBackground','isInBackground','Same actual background state',True)
t=replace(t,'val result = loadMusicArtwork(context.imageLoader, state.coverUrl, context)','val result = loadMusicArtwork(platform, state.coverUrl)','Same owned SingletonImageLoader artwork load')
t=replace(t,'''        artworkBitmap = result?.bitmap
        paletteColor = result?.baseColor ?: themeSurfaceColor
        paletteAccent = result?.accentColor ?: (result?.baseColor ?: themeSurfaceColor)
        playerPalette = result?.palette
            ?: com.android.purebilibili.feature.audio.lyrics.halcyon.PlayerPalette.Default''','''        platform.commitUi {
            artworkBitmap = result?.bitmap
            paletteColor = result?.baseColor ?: themeSurfaceColor
            paletteAccent = result?.accentColor ?: (result?.baseColor ?: themeSurfaceColor)
            playerPalette = result?.palette
                ?: com.android.purebilibili.feature.audio.lyrics.halcyon.PlayerPalette.Default
        }''','Same caller Job/entry admission rejects a superseded cover or retired page before UI publication')
t=replace(t,'''val glassEnabled = resolveMusicLiquidGlassEnabled(
        sdkInt = Build.VERSION.SDK_INT,
        effectsEnabled = liquidGlassEffectsEnabled,
        isAppInBackground = isInBackground,
        reduceMotion = effectiveReduceMotion
    )''','''val glassEnabled = platform.blurEffectsAvailable && liquidGlassEffectsEnabled &&
        !isInBackground && !effectiveReduceMotion''','Map only original Android API capability clause to required real Skia capability; retain remaining original conditions')
t=replace(t,'val coverBitmap = remember(bitmap) { bitmap?.toAndroidBitmapOrNull() }','val coverBitmap = remember(bitmap) { bitmap?.let(com.bilipai.desktop.ui::desktopMusicRasterCopy) }','Owned copy of actual Compose bitmap, never close borrowed cache') if False else t
t=replace(t,'bitmap?.toAndroidBitmapOrNull()','bitmap?.let { com.bilipai.desktop.ui.desktopMusicRasterCopy(it) }','Owned copy of actual Compose bitmap, never close borrowed cache')
t=between(t,'private fun ImageBitmap.toAndroidBitmapOrNull()','@Composable\nprivate fun PlayerPage','', 'Android Bitmap cast is replaced by real owned raster copy')
t=between(t,'    val context = androidx.compose.ui.platform.LocalContext.current','    val inactiveTrackColor', '''    val platform = LocalDesktopOriginalMusicUiPlatform.current
    val actualVolume by platform.volume.collectAsStateWithLifecycle()
    val maxVolume = platform.maximumVolumeStep.also { require(it > 0) }
    var volume by remember(platform) { mutableFloatStateOf(actualVolume) }
    LaunchedEffect(actualVolume) { volume = actualVolume }
''','Actual same Listen/native volume flow replaces Android AudioManager/ContentObserver')
t=replace(t,'''audioManager?.setStreamVolume(
                        android.media.AudioManager.STREAM_MUSIC,
                        (volume * maxVolume).roundToInt(),
                        0
                    )''','platform.setVolumeFraction(volume)','Required same source-owned volume write')
# Remaining private UI functions capture the required local only when their original
# API capability check needs it. No fabricated SDK version is supplied.
t=replace(t,'sdkInt = Build.VERSION.SDK_INT,','sdkInt = Build.VERSION.SDK_INT,','noop') if False else t
t=replace(t,'''resolveMusicLyricsBlurEnabled(
        sdkInt = Build.VERSION.SDK_INT,
        effectsEnabled = blurEffectsEnabled,
        reduceMotion = reduceMotion
    )''','''LocalDesktopOriginalMusicUiPlatform.current.blurEffectsAvailable &&
        blurEffectsEnabled && !reduceMotion''','Actual Skia blur capability with original remaining policy')
t=replace(t,'Build.VERSION.SDK_INT >= 31 && blurRadius.value > 0.dp','LocalDesktopOriginalMusicUiPlatform.current.blurEffectsAvailable && blurRadius.value > 0.dp','Actual Skia blur capability')
t=between(t,'private suspend fun loadMusicArtwork(','internal fun formatMusicTime', '''private suspend fun loadMusicArtwork(
    platform: com.bilipai.desktop.ui.DesktopOriginalMusicUiPlatform,
    coverUrl: String
): MusicArtworkPalette? = try {
    val bitmap = platform.loadOwnedArtwork(coverUrl)
    if (bitmap == null) null else {
    try {
        val palette = com.android.purebilibili.feature.audio.lyrics.halcyon.PlayerPalette.fromCoverBackground(bitmap, light = false)
        MusicArtworkPalette(bitmap.asImageBitmap(), palette.middle, palette.accent, palette)
    } finally { bitmap.recycle() }
    }
} catch (cancelled: kotlinx.coroutines.CancellationException) {
    throw cancelled
} catch (_: Throwable) {
    null // Preserve the original artwork failure palette fallback, never swallow cancellation.
}

''','Required same loader/context/HTTP owned acquisition; original palette algorithm retained, owned pixel temporary disposed')
t=t.replace('import com.bilipai.desktop.ui.LocalDesktopOriginalMusicUiPlatform\n','import com.bilipai.desktop.ui.LocalDesktopOriginalMusicUiPlatform\nimport com.bilipai.desktop.ui.asImageBitmap\n')
emit(CURRENT,t,CURRENT)
for relative,changes in CHANGES.items():
 p=BASE+relative
 if relative=='core/store/SettingsManager.kt':continue
 matches=[o for o in OUTPUTS if o['original']==p]
 assert len(matches)==1,(p,len(matches))
 reverse=safe(matches[0]['path']).read_text(encoding='utf-8')
 # Added extension import is recorded here after all body edits.
 if p.endswith('MusicPlayerContent.kt'):
  reverse=reverse.replace('import com.bilipai.desktop.ui.LocalDesktopOriginalMusicUiPlatform\nimport com.bilipai.desktop.ui.asImageBitmap\n','import com.bilipai.desktop.ui.LocalDesktopOriginalMusicUiPlatform\n')
 for change in changes[::-1]:
  i=change['index'];after=change['after'];assert reverse[i:i+len(after)]==after,(p,change['label'])
  reverse=reverse[:i]+change['before']+reverse[i+len(after):]
 assert reverse==SOURCES[p]['text'],p
for output in OUTPUTS:
 if output['original'] in DIRECT and not args.standalone:continue
 before=SOURCES[output['original']]['text'];after=safe(output['path']).read_text(encoding='utf-8')
 write(LANE/'diffs'/(Path(output['path']).stem+'.diff'),''.join(difflib.unified_diff(before.splitlines(True),after.splitlines(True),fromfile=output['original'],tofile=output['path'])))
write(LANE/'music-source-pins.json',json.dumps({p:{k:v for k,v in d.items()if k!='text'}for p,d in SOURCES.items()},indent=2)+'\n')
write(LANE/'music-adaptations.json',json.dumps(CHANGES,ensure_ascii=False,indent=2)+'\n')
write(LANE/'music-outputs.json',json.dumps(OUTPUTS,indent=2)+'\n')
print(json.dumps({'outputs':len(OUTPUTS),'sources':len(SOURCES),'exactWholeBodyInverse':True}))
