from pathlib import Path
import hashlib,json,re,subprocess,sys,importlib.util
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
BASE='app/src/main/java/com/android/purebilibili/'
COMMIT='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
paths=[BASE+p for p in ['feature/bangumi/HomeBangumiTabPage.kt','feature/bangumi/BangumiHubViewModel.kt','feature/bangumi/BangumiHubContent.kt','feature/bangumi/BangumiHubSkeletons.kt','feature/bangumi/BangumiHubBlurPolicy.kt','feature/bangumi/policy/BangumiHubPolicy.kt','feature/bangumi/policy/MyFollowPolicy.kt','feature/bangumi/policy/BangumiUiPolicy.kt','feature/bangumi/ui/list/BangumiListComponents.kt','data/repository/BangumiRepository.kt']]
def sha(b):return hashlib.sha256(b).hexdigest()
source={};pins={}
for p in paths:
    b=(REPO/p).read_bytes().replace(b'\r\n',b'\n');assert b==subprocess.check_output(['git','show',COMMIT+':'+p],cwd=REPO).replace(b'\r\n',b'\n');source[p]=b.decode('utf-8');pins[p]=sha(b)
producer='''from pathlib import Path
import argparse,json,hashlib,re
PINS=__PINS__
BASE='app/src/main/java/com/android/purebilibili/'
def sha(s):return hashlib.sha256(s.encode('utf-8')).hexdigest()
def balanced(s,start):
 depth=0;quote=None;escape=False;line=False;block=False;i=start
 while i<len(s):
  c=s[i];n=s[i:i+2]
  if line:
   if c=='\\n':line=False
  elif block:
   if n=='*/':block=False;i+=1
  elif quote:
   if escape:escape=False
   elif c=='\\\\':escape=True
   elif c==quote:quote=None
  elif n=='//':line=True;i+=1
  elif n=='/*':block=True;i+=1
  elif c in ('"',"'"):quote=c
  elif c=='{':depth+=1
  elif c=='}':
   depth-=1
   if depth==0:return i+1
  i+=1
 raise ValueError('unbalanced declaration')
def fn(s,name):
 m=re.search(r'(?m)^ *(?:(?:internal|private) )?(?:suspend )?fun (?:\\w+\\.)?'+re.escape(name)+r'\\(',s);assert m,name
 start=m.start();annotation=s.rfind('@Composable',0,start)
 if annotation>=0 and s[annotation:start].strip()=='@Composable':start=annotation
 opening=s.index('{',m.end());return s[start:balanced(s,opening)]
def main():
 ap=argparse.ArgumentParser();ap.add_argument('--repo',required=True);ap.add_argument('--output',required=True);a=ap.parse_args()
 root=Path(a.repo);out=Path(a.output);raw={}
 for path,pin in PINS.items():
  s=(root/path).read_text(encoding='utf-8');assert sha(s)==pin,path;raw[path]=s
 rows=[]
 def emit(origin,s,name,changes=(),selected=None):
  package=re.search(r'(?m)^package (\\S+)',raw[origin])[1]
  p=out/package.replace('.','/')/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s,encoding='utf-8',newline='\\n')
  rows.append(dict(origin=origin,path=p.relative_to(out).as_posix(),sha256LF=sha(s),adaptations=changes,selected=selected))
 for origin in PINS:
  if origin.endswith('BangumiRepository.kt') or origin.endswith('BangumiListComponents.kt') or origin.endswith('BangumiUiPolicy.kt'):continue
  s=raw[origin];changes=[]
  def adapt(before,after,count=1):
   nonlocal s
   assert s.count(before)==count,(origin,before,s.count(before),count);s=s.replace(before,after);changes.append(dict(before=before,after=after,count=count))
  if origin.endswith('HomeBangumiTabPage.kt'):
   adapt('import androidx.compose.ui.platform.LocalContext','import com.bilipai.desktop.ui.LocalDesktopHomeEnvironment as LocalContext')
   adapt('import com.android.purebilibili.core.store.SettingsManager','// Windows: use the same original persisted Home settings projection')
   adapt('import com.android.purebilibili.feature.download.DownloadManager','// Windows: existing Root owned image-save consumer')
   adapt('fun HomeBangumiTabPage(','internal fun HomeBangumiTabPage(')
   adapt('viewModel: BangumiHubViewModel = viewModel(),','viewModel: BangumiHubViewModel = com.bilipai.desktop.ui.LocalDesktopBangumiHubViewModel.current,')
   adapt('SettingsManager.getShowPgcTimeline(context)','context.settings.homeSettings.map { it.showPgcTimeline }')
   adapt('DownloadManager.saveImageToGallery(context, url, title)','context.saveCover(url, title)')
   adapt('import kotlinx.coroutines.launch','import kotlinx.coroutines.launch\\nimport kotlinx.coroutines.flow.map')
  if origin.endswith('BangumiHubViewModel.kt'):
   adapt('import androidx.lifecycle.viewModelScope','// Windows: supplied captured-entry scope')
   adapt('import com.android.purebilibili.data.repository.BangumiRepository','// Windows: full selected original raw repository methods on the shared owned APIs')
   adapt('import com.android.purebilibili.core.store.TokenManager','// Windows: the existing actual SessionStore identity gate')
   adapt('class BangumiHubViewModel : ViewModel() {','internal class BangumiHubViewModel(private val desktopEnvironment: com.bilipai.desktop.ui.DesktopBangumiHubEnvironment) : ViewModel() {')
   adapt('MutableStateFlow(BangumiHubUiState())','com.bilipai.desktop.ui.DesktopHomeOwnedMutableStateFlow(BangumiHubUiState(), desktopEnvironment.commitIfCurrent)')
   adapt('!TokenManager.sessDataCache.isNullOrBlank()','desktopEnvironment.isLoggedIn()')
   adapt('viewModelScope','desktopEnvironment.scope',s.count('viewModelScope'))
   adapt('BangumiRepository.','desktopEnvironment.repository.',s.count('BangumiRepository.'))
  reverse=s
  for r in reversed(changes):
   assert reverse.count(r['after'])==r['count'];reverse=reverse.replace(r['after'],r['before'])
  assert reverse==raw[origin]
  emit(origin,s,Path(origin).name,changes)
 origin=BASE+'feature/bangumi/policy/BangumiUiPolicy.kt';s=raw[origin]
 header=s[:s.index('/**')].replace('import android.content.pm.ActivityInfo','// This consumer selects the original cover-badge policy; player orientation belongs to the player port.')
 emit(origin,header+fn(s,'resolveBangumiCoverBadgeColors')+'\\n','DesktopOriginalBangumiBadgePolicy.kt',selected=['resolveBangumiCoverBadgeColors'])
 origin=BASE+'feature/bangumi/ui/list/BangumiListComponents.kt';s=raw[origin]
 header=s[:s.index('/**')];header='\\n'.join(l for l in header.splitlines() if not l.startswith('import ') or any(x in l for x in ['.*','AppText','AppSurface','AppShapes','AppSurfaceTokens','ContainerLevel','Alignment','Modifier','clip','Color','FontWeight','.dp','.sp','resolveBangumiCoverBadgeColors']))
 emit(origin,header+'\\n'+fn(s,'BangumiBadge')+'\\n','DesktopOriginalBangumiBadge.kt',selected=['BangumiBadge'])
 origin=BASE+'data/repository/BangumiRepository.kt';methods=['getTimeline','unfollowBangumi','updateBangumiFollowStatus','updateBangumiFollowStatuses','getBangumiIndexConditions','getBangumiIndexPage','searchBangumi','getMyFollowBangumi'];parts=[];changes=[]
 for name in methods:
  s=fn(raw[origin],name);original=s;delta=[]
  def adapt(before,after,count=1):
   nonlocal s
   assert s.count(before)==count,(name,before,s.count(before));s=s.replace(before,after);delta.append(dict(before=before,after=after,count=count))
  if 'TokenManager.csrfCache' in s:adapt('TokenManager.csrfCache','csrf()')
  if 'TokenManager.midCache' in s:adapt('TokenManager.midCache','mid()')
  if 'val navApi = NetworkModule.api' in s:adapt('val navApi = NetworkModule.api','val navApi = ownedNavApi')
  if 'val searchApi = NetworkModule.searchApi' in s:adapt('val searchApi = NetworkModule.searchApi','val searchApi = ownedSearchApi')
  for line in s.splitlines():
   if 'android.util.Log.e' in line:adapt(line,'            // Existing optional protocol failure is returned through Result; no credential-bearing platform log.')
  reverse=s
  for r in reversed(delta):reverse=reverse.replace(r['after'],r['before'])
  assert reverse==original
  parts.append(s);changes.append(dict(method=name,originalSha256LF=sha(original),adaptedSha256LF=sha(s),adaptations=delta,inverseByteEqual=True))
 header='package com.android.purebilibili.data.repository\\nimport com.android.purebilibili.data.model.response.*\\nimport com.android.purebilibili.core.network.*\\nimport kotlinx.coroutines.Dispatchers\\nimport kotlinx.coroutines.withContext\\n'
 declaration='internal class DesktopOriginalBangumiHubRepository(private val api:BangumiApi, private val ownedNavApi:BilibiliApi, private val ownedSearchApi:SearchApi, private val csrf:()->String?,private val mid:()->Long?) {\\n'
 emit(origin,header+declaration+'\\n\\n'.join(parts)+'\\n}\\n','DesktopOriginalBangumiHubRepository.kt',changes,methods)
 (out/'bangumi-page-producer-inventory.json').write_text(json.dumps(dict(upstreamCommit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',pins=PINS,emitted=rows,originalInverseTransformsVerified=True),indent=2)+'\\n',encoding='utf-8')
 print('Complete original Home Bangumi Hub emitted')
if __name__=='__main__':main()
'''.replace('__PINS__',repr(pins))
p=HERE/'prepared/tools/extract-upstream-home-bangumi-page.py';p.parent.mkdir(parents=True,exist_ok=True);p.write_text(producer,encoding='utf-8',newline='\n')
subprocess.run([sys.executable,str(p),'--repo',str(REPO),'--output',str(HERE/'generated')],check=True)
print(json.dumps(dict(sourcePins=len(pins),producerSHA256=sha(p.read_bytes()))))
