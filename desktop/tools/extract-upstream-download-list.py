"""Complete fixed v0.2.3 download list; Windows owner/queue/path/Coil seams only."""
from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import argparse,hashlib,importlib.util,json,subprocess,textwrap

COMMIT='79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40'
BASE='app/src/main/java/com/android/purebilibili/'
SOURCE_PINS={
 BASE+'feature/download/DownloadListScreen.kt':'0c2f1aa19895a2fa0f566055f84f6e172fd6c9629ac34d064c3379e0588c2895',
 BASE+'feature/download/DownloadStoragePolicy.kt':'a5b874db17777ade210027e7b517fe9a292a94fe117844d0ed139f9ce2d3f74c',
 BASE+'core/store/SettingsManager.kt':'5799bb8802992594ae9494b48d6357ee00ecc7be03d97ed0dcb5fede7774328c',
 BASE+'feature/download/DownloadTaskPresentationPolicy.kt':'b7a3849710941b82fbba529d6f5f8f6e9d824a73ed75b2203f05f213ac89d54d',
}
def safe(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def write(p,s):
 p=safe(p);p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s,encoding='utf-8',newline='\n')
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
def load(p,n):
 spec=importlib.util.spec_from_file_location(n,p);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m

def generate(repo,output,standalone=False):
 repo=Path(repo).resolve();output=Path(output).resolve();original={};records=[];deltas={}
 manifest=json.loads(read(repo/'desktop/upstream-sources.json'))
 assert manifest['upstreamCommit']==COMMIT,'Target identity changed'
 for path,pin in SOURCE_PINS.items():
  s=read(_desktop_canonical_source(repo, path));assert sha(s)==pin,path
  blob=subprocess.check_output(['git','show',COMMIT+':'+path],cwd=repo).decode('utf-8').replace('\r\n','\n')
  assert s==blob,'Working original differs from fixed Git blob: '+path
  if not standalone:
   rows=[row for row in manifest['sources'] if row['path']==path]
   assert len(rows)==1 and rows[0]['sha256']==pin,'Sole fixed registry identity missing: '+path
   assert 'stable-download-list-original' in rows[0]['features'],'Required selected-source feature missing: '+path
  original[path]=s;write(output/'original-retained'/(path+'.txt'),s)
 parser=load(repo/'desktop/tools/sync-upstream.py','download_list_parser')
 media=load(repo/'desktop/tools/extract-upstream-media.py','download_list_media')
 appearance=load(repo/'desktop/tools/extract-appearance-platform.py','download_list_declarations')
 def edit(s,before,after,edits,count=1):
  assert s.count(before)==count,repr(before)
  starts=[];at=0
  for i in range(count):
   at=s.index(before,at);starts.append(at+i*(len(after)-len(before)));at+=len(before)
  edits.append({'before':before,'after':after,'count':count,'afterOffsets':starts});return s.replace(before,after)
 def emit(path,s,destination,edits,whole):
  write(output/destination,s)
  if whole:
   recovered=s
   for e in reversed(edits):
    for at in reversed(e['afterOffsets']):
     assert recovered[at:at+len(e['after'])]==e['after']
     recovered=recovered[:at]+e['before']+recovered[at+len(e['after']):]
   assert recovered==original[path],'Exact reverse does not restore fixed original'
   rebuilt=original[path]
   for e in edits:rebuilt=rebuilt.replace(e['before'],e['after'])
   assert rebuilt==s,'Unrecorded source delta'
  records.append({'source':path,'originalSha256LF':sha(original[path]),'output':destination,'preparedSha256LF':sha(s),'wholeOriginalFile':whole,'exactReversePass':whole,'deltaCount':len(edits)})
  deltas[path]=edits
 direct=BASE+'feature/download/DownloadTaskPresentationPolicy.kt'
 directOutput='com/android/purebilibili/feature/download/DownloadTaskPresentationPolicy.kt'
 if standalone:emit(direct,original[direct],directOutput,[],True)
 else:safe(output/directOutput).unlink(missing_ok=True)
 path=BASE+'feature/download/DownloadListScreen.kt';s=original[path];edits=[]
 for a,b in [
  ('import android.widget.Toast\n',''),
  ('import com.android.purebilibili.core.ui.animation.jiggleOnDissolve','import com.bilipai.desktop.ui.jiggleOnDissolve'),
  ('com.android.purebilibili.core.ui.animation.MaybeDissolvableVideoCard(','com.bilipai.desktop.ui.DesktopReplyDissolvableContainer('),
  ('com.android.purebilibili.core.ui.animation.DissolveAnimationPreset.','com.bilipai.desktop.ui.DissolveAnimationPreset.'),
  ('import androidx.compose.ui.platform.LocalContext','import coil3.compose.LocalPlatformContext as LocalContext\nimport com.bilipai.desktop.ui.LocalDesktopOriginalDownloadListBindings\nimport com.bilipai.desktop.ui.desktopOriginalDownloadListScreenWidthDp'),
  ('import com.android.purebilibili.core.store.SettingsManager','import com.android.purebilibili.core.store.DesktopOriginalDownloadListSettings as SettingsManager'),
  ('import com.android.purebilibili.core.util.NetworkUtils\n',''),
  ('import androidx.lifecycle.compose.collectAsStateWithLifecycle','import androidx.compose.runtime.collectAsState as collectAsStateWithLifecycle'),
  ('onOfflineVideoClick: (String) -> Unit = {}','onOfflineVideoClick: (String) -> Unit'),
  ('    val context = LocalContext.current','    val context = LocalContext.current\n    val bindings = LocalDesktopOriginalDownloadListBindings.current'),
  ('androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.toFloat()','desktopOriginalDownloadListScreenWidthDp()'),
  ('DownloadManager.tasks','bindings.tasks'),
  ('resolveDisplayedDownloadLocation(','bindings.displayedDownloadLocation('),
  ('androidx.compose.ui.platform.LocalContext.current','LocalContext.current'),
 ]:s=edit(s,a,b,edits)
 for name in ['getDownloadPath','getDownloadExportTreeUri','getDefaultDownloadPath']:
  s=edit(s,'SettingsManager.'+name+'(context)','SettingsManager.'+name+'(bindings.context)',edits)
 for a,b in [('NetworkUtils.isNetworkAvailable(context)','bindings.isNetworkAvailable()'),('initialValue =','initial ='),('DownloadManager.pauseDownload','bindings.pauseDownload'),('DownloadManager.startDownload','bindings.startDownload'),('DownloadManager.removeTask','bindings.removeTask')]:
  count=s.count(a);assert count>0;s=edit(s,a,b,edits,count)
 a='''                                        Toast.makeText(
                                            context,
                                            "本地文件不可用，已切换在线播放",
                                            Toast.LENGTH_SHORT
                                        ).show()'''
 s=edit(s,a,'                                        bindings.showFeedback("本地文件不可用，已切换在线播放")',edits)
 assert 'Toast.' not in s and 'import android.' not in s and 'DownloadManager.' not in s
 emit(path,s,'com/android/purebilibili/feature/download/DownloadListScreen.kt',edits,True)
 path=BASE+'feature/download/DownloadStoragePolicy.kt';s=original[path];edits=[]
 s=edit(s,'import com.android.purebilibili.core.util.decodeUrlComponentCompat','import java.net.URLDecoder.decode as decodeUrlComponentCompat',edits)
 emit(path,s,'com/android/purebilibili/feature/download/DownloadStoragePolicy.kt',edits,True)
 path=BASE+'core/store/SettingsManager.kt';manager=original[path][original[path].index('object SettingsManager {')+len('object SettingsManager {'):original[path].rfind('}')]
 names=['KEY_DOWNLOAD_PATH','KEY_DOWNLOAD_EXPORT_TREE_URI','getDownloadPath','getDownloadExportTreeUri','getDefaultDownloadPath']
 body=textwrap.dedent(appearance.declarations(parser,manager,names));originalBody=body
 old=media.function(original[path],'getDefaultDownloadPath',parser)
 new='''fun getDefaultDownloadPath(context: Context): String {
    return com.bilipai.desktop.download.DesktopDownloadManager.defaultDownloadRoot().toAbsolutePath().normalize().toString()
}'''
 assert old in body;body=body.replace(old,new)
 assert body.replace(new,old)==originalBody,'Settings selected body failed exact inverse'
 s='''package com.android.purebilibili.core.store
import com.bilipai.desktop.plugins.DesktopPluginContext as Context
import com.bilipai.desktop.plugins.stringPreferencesKey
import com.bilipai.desktop.settings.dynamicSettingsDataStore as settingsDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Original canonical getters on Root's same settings namespace. No second SettingsManager/Store. */
object DesktopOriginalDownloadListSettings {
'''+textwrap.indent(body,'    ')+'\n}\n'
 emit(path,s,'com/android/purebilibili/core/store/DesktopOriginalDownloadListSettings.kt',[{'before':old,'after':new,'count':1}],False)
 write(output/'source-receipt.json',json.dumps({'fixedCommit':COMMIT,'sources':records,'soleDirectReference':direct,'standalone':standalone,'exactDeltas':deltas,'selectedSettingsDeclarations':names,'selectedSettingsOriginalSha256LF':sha(originalBody),'selectedSettingsAdaptedSha256LF':sha(body),'selectedSettingsExactInversePass':True,'canonicalGetterBodiesUnchanged':True,'allThreeListDeclarationsRetained':['DownloadListScreen','formatDownloadStorageBytes','DownloadTaskItem'],'newHttpClient':False,'newTaskModelOrStore':False},ensure_ascii=False,indent=2))
 return records

if __name__=='__main__':
 cli=argparse.ArgumentParser(description=__doc__);cli.add_argument('--repo',type=Path,required=True);cli.add_argument('--output',type=Path,required=True);cli.add_argument('--standalone',action='store_true')
 args=cli.parse_args();print('Generated',len(generate(args.repo,args.output,args.standalone)),'original download list sources')
