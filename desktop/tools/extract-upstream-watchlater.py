#!/usr/bin/env python3
"""Full stable WatchLater UI/VM/repository, with owned Windows Root ports only."""
from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import argparse,hashlib,importlib.util,json,os,re,subprocess
PIN='79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40'
BASE='app/src/main/java/com/android/purebilibili/'
def safe(p):
 p=os.path.abspath(p);prefix=chr(92)*2+'?'+chr(92)
 return Path(p if os.name!='nt' or p.startswith(prefix) else prefix+p)
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def write(p,s):safe(p).parent.mkdir(parents=True,exist_ok=True);safe(p).write_text(s,encoding='utf-8',newline='\n')
def generate(repo,output,standalone=False):
 global R,OUT,STANDALONE,parser,fav,rows,P
 R=Path(repo).resolve();OUT=Path(output).resolve();STANDALONE=standalone;P=PIN
 spec=importlib.util.spec_from_file_location('watch_parser',R/'desktop/tools/extract-upstream-dynamic-reply-protocol.py');parser=importlib.util.module_from_spec(spec);spec.loader.exec_module(parser)
 spec=importlib.util.spec_from_file_location('watch_favorites',R/'desktop/tools/extract-upstream-favorites.py');fav=importlib.util.module_from_spec(spec);spec.loader.exec_module(fav);fav.parser=parser
 rows=[]

 def source(rel):
  path=BASE+rel+'.kt';sourcePath=_desktop_canonical_source(R,path);path=sourcePath.relative_to(R).as_posix();s=read(sourcePath);b=subprocess.check_output(['git','show',P+':'+path],cwd=R).replace(b'\r\n',b'\n');assert s.encode()==b
  rows.append({'path':path,'commit':P,'sha256LfUtf8':hashlib.sha256(b).hexdigest()});return s
 def emit(rel,s,direct=False):
  path=OUT/'com/android/purebilibili'/(rel+'.kt')
  if not direct or STANDALONE:write(path,s)
  rows[-1]['output']={'path':path.relative_to(OUT).as_posix(),'sha256LfUtf8':hashlib.sha256(s.encode()).hexdigest(),'mode':'direct' if direct else 'selected'}
 for rel in ['feature/watchlater/WatchLaterPlaybackPolicy','feature/watchlater/WatchLaterLayoutPolicy']:
  emit(rel,source(rel),True)
 s=source('data/repository/WatchLaterRepository')
 s=s.replace('import com.android.purebilibili.core.network.NetworkModule\n','').replace('import com.android.purebilibili.core.network.WbiKeyManager\n','').replace('import com.android.purebilibili.core.store.TokenManager\n','')
 s=s.replace('object WatchLaterRepository {','class DesktopOriginalWatchLaterRepository(private val environment: com.android.purebilibili.feature.watchlater.DesktopWatchLaterEnvironment) {').replace('private const val PAGE_SIZE','private val PAGE_SIZE')
 s=s.replace('NetworkModule.api','environment.api').replace('WbiKeyManager.getWbiKeys()','environment.wbiKeys(false)').replace('WbiKeyManager.refreshKeys()','environment.wbiKeys(true)').replace('TokenManager.csrfCache','environment.csrf()').replace('TokenManager.midCache','environment.currentMid()')
 emit('data/repository/DesktopOriginalWatchLaterRepository',s)
 s=source('feature/watchlater/WatchLaterScreen')
 s='\n'.join(line for line in s.splitlines() if not any(line.startswith(x) for x in ['import android.','import androidx.lifecycle.AndroidViewModel','import androidx.lifecycle.viewModelScope','import androidx.lifecycle.viewmodel.compose.viewModel','import com.android.purebilibili.core.network.NetworkModule','import com.android.purebilibili.core.coroutines.AppScope','import com.android.purebilibili.core.store.SettingsManager','import com.android.purebilibili.data.repository.FavoriteRepository','import com.android.purebilibili.data.repository.WatchLaterRepository']))+'\n'
 s=s.replace('application: Application','environment: DesktopWatchLaterEnvironment').replace('AndroidViewModel(application)','DesktopWatchLaterScopedOwner(environment)')
 s=s.replace('WatchLaterRepository.','environment.watchLater.').replace('FavoriteRepository.','environment.favorite.').replace('com.android.purebilibili.core.store.TokenManager.csrfCache','environment.csrf()').replace('com.android.purebilibili.core.store.TokenManager.midCache','environment.currentMid()').replace('NetworkModule.api','environment.api').replace('AppScope.ioScope','environment.ioScope')
 s=fav.toast(s).replace('e.printStackTrace()','environment.reportFailure(e)')
 s=s.replace('import androidx.lifecycle.compose.collectAsStateWithLifecycle','import androidx.compose.runtime.collectAsState as collectAsStateWithLifecycle').replace('initialValue =','initial =')
 s=s.replace('import androidx.compose.ui.platform.LocalContext','import coil3.compose.LocalPlatformContext as LocalContext\nimport com.bilipai.desktop.ui.LocalDesktopWatchLaterBindings').replace('import androidx.compose.ui.platform.LocalConfiguration','import com.bilipai.desktop.ui.LocalDesktopFavoriteViewport as LocalConfiguration')
 s=s.replace('    val context = LocalContext.current','    val context = LocalContext.current\n    val platform = LocalDesktopWatchLaterBindings.current')
 s=s.replace('SettingsManager.getHomeSettings(context)','platform.homeSettings').replace('SettingsManager.getAppNavigationSettings(context)','platform.navigationSettings').replace('com.android.purebilibili.core.store.HomeSettings()','platform.initialHomeSettings').replace('com.android.purebilibili.core.store.AppNavigationSettings()','platform.initialNavigationSettings')
 s=s.replace('viewModel: WatchLaterViewModel = viewModel(),','viewModel: WatchLaterViewModel,')
 s=s.replace('import com.android.purebilibili.core.ui.blur.rememberRecoverableHazeState\n','').replace('import com.android.purebilibili.core.ui.blur.shouldAllowRenderEffectBackedHazeEffect\n','').replace('import com.android.purebilibili.core.ui.blur.hazeSourceCompat','import dev.chrisbanes.haze.hazeSource')
 s=s.replace('shouldAllowRenderEffectBackedHazeEffect(Build.VERSION.SDK_INT)','platform.renderEffectsSupported').replace('rememberRecoverableHazeState()','remember { HazeState() }').replace('.hazeSourceCompat(','.hazeSource(')
 s=s.replace('import com.android.purebilibili.core.ui.animation.DissolveAnimationPreset','import com.bilipai.desktop.ui.DissolveAnimationPreset').replace('import com.android.purebilibili.core.ui.animation.MaybeDissolvableVideoCard','import com.bilipai.desktop.ui.DesktopReplyDissolvableContainer as MaybeDissolvableVideoCard').replace('import com.android.purebilibili.core.ui.animation.jiggleOnDissolve','import com.bilipai.desktop.ui.jiggleOnDissolve')
 # The three original external-playlist admissions retain the original items/start index;
 # concrete current Root queue bridge applies SEQUENTIAL and opens video/audio atomically.
 needle='com.android.purebilibili.feature.video.player.PlaylistManager.setExternalPlaylist('
 assert s.count(needle)==3, ('latest original three queue admissions', s.count(needle))
 while needle in s:
  a=s.index(needle);b=parser.balanced(parser.masked(s),s.index('(',a))
  args=s[s.index('(',a)+1:b-1]
  audio='"全部听"' in s[max(0,a-1300):a] or (a < s.index('private fun WatchLaterVideoCard') and 'resolveWatchLaterPlayAllStartTarget' in s[b:b+300])
  # First menu action is audio; card click and floating action are video.
  boolval='true' if 'label = "全部听"' in s[max(0,a-1300):a] else 'false'
  s=s[:a]+'platform.openQueue(externalPlaylist.playlistItems, externalPlaylist.startIndex, '+boolval+', displayedItems)'+s[b:]
  old=re.search(r'com\.android\.purebilibili\.feature\.video\.player\.PlaylistManager\s*\.setPlayMode\(com\.android\.purebilibili\.feature\.video\.player\.PlayMode\.SEQUENTIAL\)',s[a:]);assert old
  start=a+old.start();end=a+old.end();s=s[:start]+s[end:]
 s=s.replace('homeSettings = homeSettings,\n            topChromePolicy','homeSettings = platform.filterAppearance(homeSettings),\n            topChromePolicy').replace('androidx.activity.compose.BackHandler','com.android.purebilibili.core.ui.LocalNavigationBackHandler')
 emit('feature/watchlater/WatchLaterScreen',s)
 return rows

if __name__=="__main__":
 cli=argparse.ArgumentParser();cli.add_argument("--repo",type=Path,required=True);cli.add_argument("--output",type=Path,required=True);cli.add_argument("--standalone",action="store_true");args=cli.parse_args()
 print(json.dumps(generate(args.repo,args.output,args.standalone),ensure_ascii=False,indent=2))
