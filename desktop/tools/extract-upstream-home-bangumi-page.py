from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import argparse,json,hashlib,re
PINS={'app/src/main/java/com/android/purebilibili/feature/bangumi/HomeBangumiTabPage.kt': '40489c068b77bac5136d31209f1d6f25952d34831760ccf7b1feda9c56a52b98', 'app/src/main/java/com/android/purebilibili/feature/bangumi/BangumiHubViewModel.kt': '5d6b6d24c41e7072b5f1e7107c65c632db7926fbf7da1cc35c61551bdf2a2045', 'app/src/main/java/com/android/purebilibili/feature/bangumi/BangumiHubContent.kt': 'de00b9fc1ba0910d4b778c8d76e1f72237c74042fffb6ef14a3dadf2711604b3', 'app/src/main/java/com/android/purebilibili/feature/bangumi/BangumiHubSkeletons.kt': 'b9eef7947aa323c64188aaaf3cec36cafe840e3ad6753227739858acef6ec7f7', 'app/src/main/java/com/android/purebilibili/feature/bangumi/BangumiHubBlurPolicy.kt': 'ee8ac2498ab5de0f46aa01b81ab8f2bae11e7775ebea5b04b6220388206bfe8c', 'app/src/main/java/com/android/purebilibili/feature/bangumi/policy/BangumiHubPolicy.kt': 'c136016b778e72104b097752ef8d0e5ad29ec59d27b2af20a551f088230cd5b5', 'app/src/main/java/com/android/purebilibili/feature/bangumi/policy/MyFollowPolicy.kt': '830651749a67b75417ed2f8d9ee3525d155bd3ccbcd8ada42572110500e15d73', 'app/src/main/java/com/android/purebilibili/feature/bangumi/policy/BangumiUiPolicy.kt': '7483af137b9c00e8b48343a09705f9584b0b7c5528d85c528ed35a334682f285', 'app/src/main/java/com/android/purebilibili/feature/bangumi/ui/list/BangumiListComponents.kt': 'cb81ae57da657c0281db32f8765fd73d63c0827fd3bf361fe3e0c34d1f5c0f74', 'app/src/main/java/com/android/purebilibili/data/repository/BangumiRepository.kt': '7a7f195be5ba108aaa3e9b05d7069e9c981183fa7a79fae751b8f0da25714c73'}
BASE='app/src/main/java/com/android/purebilibili/'
def sha(s):return hashlib.sha256(s.encode('utf-8')).hexdigest()
def balanced(s,start):
 depth=0;quote=None;escape=False;line=False;block=False;i=start
 while i<len(s):
  c=s[i];n=s[i:i+2]
  if line:
   if c=='\n':line=False
  elif block:
   if n=='*/':block=False;i+=1
  elif quote:
   if escape:escape=False
   elif c=='\\':escape=True
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
 m=re.search(r'(?m)^ *(?:(?:internal|private) )?(?:suspend )?fun (?:\w+\.)?'+re.escape(name)+r'\(',s);assert m,name
 start=m.start();annotation=s.rfind('@Composable',0,start)
 if annotation>=0 and s[annotation:start].strip()=='@Composable':start=annotation
 opening=s.index('{',m.end());return s[start:balanced(s,opening)]
def main():
 ap=argparse.ArgumentParser();ap.add_argument('--repo',required=True);ap.add_argument('--output',required=True);a=ap.parse_args()
 root=Path(a.repo);value=str(Path(a.output).absolute());prefix=chr(92)*2+'?'+chr(92);out=Path(value if value.startswith(prefix) else prefix+value);raw={}
 for path,pin in PINS.items():
  s=(_desktop_canonical_source(root, path)).read_text(encoding='utf-8');assert sha(s)==pin,path;raw[path]=s
 rows=[]
 def emit(origin,s,name,changes=(),selected=None):
  package=re.search(r'(?m)^package (\S+)',raw[origin])[1]
  p=out/package.replace('.','/')/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s,encoding='utf-8',newline='\n')
  rows.append(dict(origin=origin,path=p.relative_to(out).as_posix(),sha256LF=sha(s),adaptations=changes,selected=selected))
 for origin in PINS:
  if origin.endswith('BangumiRepository.kt') or origin.endswith('BangumiListComponents.kt') or origin.endswith('BangumiUiPolicy.kt'):continue
  s=raw[origin];changes=[]
  def adapt(before,after,count=1):
   nonlocal s
   assert s.count(before)==count,(origin,before,s.count(before),count);s=s.replace(before,after);changes.append(dict(before=before,after=after,count=count))
  if origin.endswith('HomeBangumiTabPage.kt'):
   adapt('import androidx.compose.ui.platform.LocalContext','import com.bilipai.desktop.ui.LocalDesktopHomeEnvironment as LocalContext')
   adapt('import androidx.lifecycle.viewmodel.compose.viewModel','// Windows: required retained local supplies the original Hub VM')
   adapt('import com.android.purebilibili.core.store.SettingsManager','// Windows: use the same original persisted Home settings projection')
   adapt('import com.android.purebilibili.feature.download.DownloadManager','// Windows: existing Root owned image-save consumer')
   adapt('fun HomeBangumiTabPage(','internal fun HomeBangumiTabPage(')
   adapt('viewModel: BangumiHubViewModel = viewModel(),','viewModel: BangumiHubViewModel = com.bilipai.desktop.ui.LocalDesktopBangumiHubViewModel.current,')
   adapt('SettingsManager.getShowPgcTimeline(context)','context.settings.homeSettings.map { it.showPgcTimeline }')
   adapt('DownloadManager.saveImageToGallery(context, url, title)','context.saveCover(url, title)')
   adapt('import kotlinx.coroutines.launch','import kotlinx.coroutines.launch\nimport kotlinx.coroutines.flow.map')
  if origin.endswith('BangumiHubViewModel.kt'):
   adapt('import androidx.lifecycle.viewModelScope','// Windows: supplied captured-entry scope')
   adapt('import androidx.lifecycle.ViewModel','// Windows: entry lifetime owns this original transient VM')
   adapt('import com.android.purebilibili.data.repository.BangumiRepository','// Windows: full selected original raw repository methods on the shared owned APIs')
   adapt('import com.android.purebilibili.core.store.TokenManager','// Windows: the existing actual SessionStore identity gate')
   adapt('class BangumiHubViewModel : ViewModel() {','internal class BangumiHubViewModel(private val desktopEnvironment: com.bilipai.desktop.ui.DesktopBangumiHubEnvironment) {')
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
 emit(origin,header+fn(s,'resolveBangumiCoverBadgeColors')+'\n','DesktopOriginalBangumiBadgePolicy.kt',selected=['resolveBangumiCoverBadgeColors'])
 origin=BASE+'feature/bangumi/ui/list/BangumiListComponents.kt';s=raw[origin]
 header=s[:s.index('/**')];header='\n'.join(l for l in header.splitlines() if not l.startswith('import ') or any(x in l for x in ['.*','AppText','AppSurface','AppShapes','AppSurfaceTokens','ContainerLevel','Alignment','Modifier','clip','Color','FontWeight','.dp','.sp','resolveBangumiCoverBadgeColors']))
 emit(origin,header+'\n'+fn(s,'BangumiBadge')+'\n','DesktopOriginalBangumiBadge.kt',selected=['BangumiBadge'])
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
 header='package com.android.purebilibili.data.repository\nimport com.android.purebilibili.data.model.response.*\nimport com.android.purebilibili.core.network.*\nimport kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.withContext\n'
 declaration='internal class DesktopOriginalBangumiHubRepository(private val api:BangumiApi, private val ownedNavApi:BilibiliApi, private val ownedSearchApi:SearchApi, private val csrf:()->String?,private val mid:()->Long?) {\n'
 emit(origin,header+declaration+'\n\n'.join(parts)+'\n}\n','DesktopOriginalBangumiHubRepository.kt',changes,methods)
 (out/'bangumi-page-producer-inventory.json').write_text(json.dumps(dict(upstreamCommit='79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40',pins=PINS,emitted=rows,originalInverseTransformsVerified=True),indent=2)+'\n',encoding='utf-8')
 print('Complete original Home Bangumi Hub emitted')
if __name__=='__main__':main()
