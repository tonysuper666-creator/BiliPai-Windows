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
def generate(repo,output,standalone=False,test_output=None):
 repo=Path(repo);out=Path(output);rows=[]
 from v044_fans import emit as emit_fans, validate_outputs as validate_fans_outputs
 validate_fans_outputs(repo, out, test_output)
 import v044_following as current_following
 def source(rel):
  path=BASE+rel+'.kt';b=subprocess.check_output(['git','show',PIN+':'+path],cwd=repo).replace(b'\r\n',b'\n')
  s=safe(_desktop_canonical_source(repo, path)).read_text(encoding='utf-8').replace('\r\n','\n');assert b==s.encode(),path
  rows.append(dict(path=path,upstreamCommit=PIN,sha256LfUtf8=hashlib.sha256(b).hexdigest()));return s
 def emit(rel,s,direct=False):
  p='com/android/purebilibili/'+rel+'.kt';rows[-1]['output']=dict(path=p,mode='direct' if direct else 'selected',sha256LfUtf8=hashlib.sha256(s.encode()).hexdigest())
  if not direct or standalone:write(out/p,s)
 for rel in ['feature/following/FollowingGroupPolicy','feature/following/FollowingLayoutPolicy','feature/following/FollowingUserPresentationPolicy']:
  old=source(rel)
  if rel.endswith('/FollowingGroupPolicy'):
   body=current_following.group_source(repo)
   rows[-1].update(upstreamCommit=current_following.COMMIT,sha256LfUtf8=current_following.sha(body),wholeCurrentOriginal=True)
   emit(rel,body)
  else:emit(rel,old,True)
 s=source('core/store/FollowingCacheStore')
 s=s.replace('import android.content.Context','import com.android.purebilibili.feature.following.DesktopFollowingCacheContext as Context')
 emit('core/store/FollowingCacheStore',s)
 source('feature/following/FollowingListScreen')
 spec=importlib.util.spec_from_file_location('following_parser',repo/'desktop/tools/extract-upstream-dynamic-reply-protocol.py');parser=importlib.util.module_from_spec(spec);spec.loader.exec_module(parser)
 spec=importlib.util.spec_from_file_location('following_fav',repo/'desktop/tools/extract-upstream-favorites.py');fav=importlib.util.module_from_spec(spec);spec.loader.exec_module(fav);fav.parser=parser
 body,audit=current_following.bind_screen(repo,fav.drop_logs)
 original=current_following.fixed(repo)['FollowingListScreen.kt']
 rows[-1].update(upstreamCommit=current_following.COMMIT,sha256LfUtf8=current_following.sha(original),
  currentFollowingPlatformBinding=audit,windowsBrandSuccessAdaptation=audit['windowsBrandSuccessAdaptation'])
 from desktop_refresh_adaptation import bind_refresh_launch
 body=bind_refresh_launch(body)
 emit('feature/following/FollowingListScreen',body)
 if test_output is not None:current_following.emit_tests(repo,test_output)
 rows.append(emit_fans(repo, out))
 return rows
if __name__=='__main__':
 # Gradle captures stdout using the Windows locale; JSON escapes preserve the
 # complete Unicode report even when a runner's pipe encoding is cp1252.
 cli=argparse.ArgumentParser();cli.add_argument('--repo',type=Path,required=True);cli.add_argument('--output',type=Path,required=True);cli.add_argument('--standalone',action='store_true');cli.add_argument('--test-output',type=Path);a=cli.parse_args();print(json.dumps(generate(a.repo,a.output,a.standalone,a.test_output),ensure_ascii=True,indent=2))
