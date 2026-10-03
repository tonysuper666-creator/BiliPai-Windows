from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import argparse, hashlib, importlib.util, json, subprocess, sys
sys.dont_write_bytecode=True
COMMIT='79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40'
SOURCE='app/src/main/java/com/android/purebilibili/data/repository/StoryRepository.kt'
OUTPUT='com/android/purebilibili/data/repository/DesktopOriginalVideoStoryFeedProtocol.kt'
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
def generate(repo,output,standalone=False):
 repo=Path(repo);output=Path(output)
 blob=subprocess.check_output(['git','-C',str(repo),'show',COMMIT+':'+SOURCE]).decode('utf-8').replace('\r\n','\n')
 assert wide(_desktop_canonical_source(repo, SOURCE)).read_bytes().replace(b'\r\n',b'\n').decode('utf-8-sig')==blob
 path=repo/'desktop/tools/extract-upstream-danmaku-list-menu.py'
 spec=importlib.util.spec_from_file_location('story_original_tokens',path);tokens=importlib.util.module_from_spec(spec);spec.loader.exec_module(tokens)
 original,start,end=tokens.function(blob,'getStoryFeed')
 method=original;edits=[]
 def replace(before,after):
  nonlocal method
  assert method.count(before)==1
  edits.append({'before':before,'after':after});method=method.replace(before,after,1)
 replace('        return try {\n','        currentCoroutineContext().ensureActive(); assertOwned()\n        return try {\n')
 replace('NetworkModule.storyApi.getStoryFeed(', 'api.getStoryFeed(')
 replace('Logger.e(TAG, " 获取故事流失败: code=${response.code}, msg=${response.message}")', 'android.util.Log.e(TAG, " 获取故事流失败: code=${response.code}, msg=${response.message}")')
 replace('            if (response.code == 0 && response.data != null) {','            currentCoroutineContext().ensureActive(); assertOwned()\n            if (response.code == 0 && response.data != null) {')
 replace('        } catch (e: Exception) {','        } catch (cancelled: CancellationException) {\n            throw cancelled\n        } catch (e: Exception) {\n            currentCoroutineContext().ensureActive(); assertOwned()')
 inverse=method
 for edit in reversed(edits):inverse=inverse.replace(edit['after'],edit['before'],1)
 assert inverse==original
 body='''package com.android.purebilibili.data.repository

import com.android.purebilibili.core.network.StoryApi
import com.android.purebilibili.data.model.response.StoryItem
import com.android.purebilibili.core.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Complete original getStoryFeed with the existing captured API and owner gate. */
internal class DesktopOriginalVideoStoryFeedProtocol(
    private val api: StoryApi,
    private val assertOwned: () -> Unit,
) {
    private val TAG = "StoryRepository"

'''+method+'\n}\n'
 target=wide(output/OUTPUT);target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(body.encode())
 return {'targetCommit':COMMIT,'originalPath':SOURCE,'originalSHA256LF':sha(blob),'originalStartLine':blob[:start].count('\n')+1,'originalEndLine':blob[:end].count('\n')+1,'originalBodySHA256LF':sha(original),'adaptedBodySHA256LF':sha(method),'output':OUTPUT,'outputSHA256LF':sha(body),'bodyInverseByteEqual':True,'platformEdits':edits,'originalMethod':original,'outputCount':1,'schemaEmission':False}
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--repo',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--standalone',action='store_true');a=p.parse_args();r=generate(a.repo,a.output,a.standalone);print(json.dumps({k:r[k] for k in ['output','outputSHA256LF','bodyInverseByteEqual','outputCount']}))
