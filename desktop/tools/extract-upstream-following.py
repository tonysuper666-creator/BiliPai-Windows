from v025_source_paths import canonical_source as _desktop_canonical_source
#!/usr/bin/env python3
from pathlib import Path
import argparse,hashlib,importlib.util,json,os,subprocess
PIN='79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40'
BASE='app/src/main/java/com/android/purebilibili/'
def safe(p):
 s=os.path.abspath(p);prefix=chr(92)*2+'?'+chr(92)
 return Path(s if os.name!='nt' or s.startswith(prefix) else prefix+s)
def write(p,s):safe(p).parent.mkdir(parents=True,exist_ok=True);safe(p).write_text(s,encoding='utf-8',newline='\n')
def generate(repo,output,standalone=False):
 repo=Path(repo);out=Path(output);rows=[]
 def source(rel):
  path=BASE+rel+'.kt';b=subprocess.check_output(['git','show',PIN+':'+path],cwd=repo).replace(b'\r\n',b'\n')
  s=safe(_desktop_canonical_source(repo, path)).read_text(encoding='utf-8').replace('\r\n','\n');assert b==s.encode(),path
  rows.append(dict(path=path,upstreamCommit=PIN,sha256LfUtf8=hashlib.sha256(b).hexdigest()));return s
 def emit(rel,s,direct=False):
  p='com/android/purebilibili/'+rel+'.kt';rows[-1]['output']=dict(path=p,mode='direct' if direct else 'selected',sha256LfUtf8=hashlib.sha256(s.encode()).hexdigest())
  if not direct or standalone:write(out/p,s)
 for rel in ['feature/following/FollowingGroupPolicy','feature/following/FollowingLayoutPolicy','feature/following/FollowingUserPresentationPolicy']:emit(rel,source(rel),True)
 s=source('core/store/FollowingCacheStore')
 s=s.replace('import android.content.Context','import com.android.purebilibili.feature.following.DesktopFollowingCacheContext as Context')
 emit('core/store/FollowingCacheStore',s)
 s=source('feature/following/FollowingListScreen')
 for line in ['import androidx.lifecycle.ViewModel\n','import androidx.lifecycle.viewModelScope\n','import androidx.lifecycle.viewmodel.compose.viewModel\n','import com.android.purebilibili.core.network.NetworkModule\n','import com.android.purebilibili.data.repository.ActionRepository\n']:s=s.replace(line,'')
 s=s.replace('import androidx.compose.ui.platform.LocalContext','import coil3.compose.LocalPlatformContext as LocalContext').replace('import androidx.lifecycle.compose.collectAsStateWithLifecycle','import androidx.compose.runtime.collectAsState as collectAsStateWithLifecycle')
 s=s.replace('class FollowingListViewModel : ViewModel() {','class FollowingListViewModel(environment: DesktopFollowingEnvironment) : DesktopFollowingScopedOwner(environment) {')
 s=s.replace('viewModel: FollowingListViewModel = viewModel()','viewModel: FollowingListViewModel')
 s=s.replace('NetworkModule.api.getFollowings(','environment.getFollowings(').replace('NetworkModule.appContext ?: return false','environment.cacheContext').replace('NetworkModule.appContext ?: return','environment.cacheContext').replace('ActionRepository.','environment.actions.')
 # Retire task admission, not a second following/list/cache authority. Original page/filter,
 # debounce, retry, fallback and group request algorithms are kept intact.
 s=s.replace('    private var currentMid: Long = 0','    @Volatile private var requestGeneration = 0L\n    @Volatile private var cacheWriteGeneration = 0L\n    private var firstPageJob: Job? = null\n    private suspend fun checkRequest(generation: Long) {\n        kotlinx.coroutines.currentCoroutineContext().ensureActive()\n        environment.assertOwned()\n        if (generation != requestGeneration) throw CancellationException("Following request retired")\n    }\n\n    private var currentMid: Long = 0')
 s=s.replace('import kotlinx.coroutines.Job','import kotlinx.coroutines.Job\nimport kotlinx.coroutines.ensureActive')
 s=s.replace('        currentMid = mid\n','        environment.assertOwned()\n        val generation = ++requestGeneration\n        firstPageJob?.cancel()\n        currentMid = mid\n',1)
 s=s.replace('        viewModelScope.launch {\n            if (!restoredFromCache)', '        firstPageJob = viewModelScope.launch {\n            if (!restoredFromCache)',1)
 s=s.replace('                val response = environment.getFollowings(mid, pn = 1, ps = 50)','                val response = environment.getFollowings(mid, pn = 1, ps = 50)\n                checkRequest(generation)')
 s=s.replace('        loadRemainingPagesJob = viewModelScope.launch {','        val generation = requestGeneration\n        loadRemainingPagesJob = viewModelScope.launch {',1)
 s=s.replace('                    val response = environment.getFollowings(mid, pn = page, ps = pageSize)','                    val response = environment.getFollowings(mid, pn = page, ps = pageSize)\n                    checkRequest(generation)')
 s=s.replace('                            currentUsers = mergeFollowingUsersOffMain(currentUsers, newUsers)','                            currentUsers = mergeFollowingUsersOffMain(currentUsers, newUsers)\n                            checkRequest(generation)')
 s=s.replace('                        val mergedUsers = mergeFollowingUsersOffMain(currentUsers, allUsers)','                        val mergedUsers = mergeFollowingUsersOffMain(currentUsers, allUsers)\n                        checkRequest(generation)')
 s=s.replace('                // 加载完成','                checkRequest(generation)\n                // 加载完成')
 s=s.replace('            } catch (e: Exception) {\n                // 后台加载失败暂不干扰主流程','            } catch (e: CancellationException) {\n                throw e\n            } catch (e: Exception) {\n                checkRequest(generation)\n                // 后台加载失败暂不干扰主流程')
 s=s.replace('                loadRemainingPagesJob = null','                if (loadRemainingPagesJob === kotlinx.coroutines.currentCoroutineContext()[Job]) loadRemainingPagesJob = null')
 s=s.replace('        val context = environment.cacheContext\n        val snapshotUsers', '        val generation = requestGeneration\n        val writeGeneration = ++cacheWriteGeneration\n        val context = environment.cacheContext.forRequest { generation == requestGeneration && writeGeneration == cacheWriteGeneration }\n        val snapshotUsers')
 # Logger must not emit arbitrary response text. Preserve failure handling, local type-only log.
 spec=importlib.util.spec_from_file_location('following_parser',repo/'desktop/tools/extract-upstream-dynamic-reply-protocol.py');parser=importlib.util.module_from_spec(spec);spec.loader.exec_module(parser)
 spec=importlib.util.spec_from_file_location('following_fav',repo/'desktop/tools/extract-upstream-favorites.py');fav=importlib.util.module_from_spec(spec);spec.loader.exec_module(fav);fav.parser=parser
 s=fav.drop_logs(s)
 from v029_brand_success import following_delta
 s,businessAudit=following_delta(s);rows[-1]['windowsBrandSuccessAdaptation']=businessAudit
 emit('feature/following/FollowingListScreen',s)
 return rows
if __name__=='__main__':
 cli=argparse.ArgumentParser();cli.add_argument('--repo',type=Path,required=True);cli.add_argument('--output',type=Path,required=True);cli.add_argument('--standalone',action='store_true');a=cli.parse_args();print(json.dumps(generate(a.repo,a.output,a.standalone),ensure_ascii=False,indent=2))
