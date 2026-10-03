from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import argparse,hashlib,importlib.util,json,re,subprocess,sys
sys.dont_write_bytecode=True
COMMIT='79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40'
def sha(v):return hashlib.sha256(v).hexdigest()
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def raw(p):return wide(p).read_bytes().replace(b'\r\n',b'\n')
def save(p,v):p=wide(p);p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(v if isinstance(v,bytes) else v.encode())
def js(p,v):save(p,json.dumps(v,ensure_ascii=False,indent=2)+'\n')
def generate(repo,output,standalone=False):
 REPO=Path(repo);HERE=Path(output)
 spec=importlib.util.spec_from_file_location('original',REPO/'desktop/tools/extract-upstream-danmaku-list-menu.py');e=importlib.util.module_from_spec(spec);spec.loader.exec_module(e)
 path='app/src/main/java/com/android/purebilibili/data/repository/DanmakuRepository.kt'
 original=subprocess.check_output(['git','show',COMMIT+':'+path],cwd=REPO).replace(b'\r\n',b'\n');s=original.decode()
 expect=raw(_desktop_canonical_source(REPO,path));assert expect==original
 save(HERE/'original-retained'/Path(path+'.txt'),original)
 declarations=[];pieces=[]
 for name in ['DANMAKU_VIP_GRADUAL_COLOR_CODE','DANMAKU_UP_IDENTITY_CHECKBOX_TYPE']:
  body=re.search(r'(?m)^internal const val '+name+r'[^\n]*',s).group();pieces.append(body);declarations.append(dict(name=name,originalBody=body,originalSHA=sha(body.encode()),adaptations=[]))
 for name in ['DanmakuPostPayload','AttentionCommandDanmakuPayload']:
  start=re.search(r'(?m)^internal data class '+name+r'\(',s).start();end=e.balanced(e.masked(s),s.index('(',start));body=s[start:end];pieces.append(body);declarations.append(dict(name=name,originalBody=body,originalSHA=sha(body.encode()),adaptations=[]))
 for name in ['buildDanmakuPostPayload','buildAttentionCommandDanmakuPayload']:
  body,_,_=e.function(s,name,indent='');pieces.append(body);declarations.append(dict(name=name,originalBody=body,originalSHA=sha(body.encode()),adaptations=[]))
 # Existing Grade's mapper is file-private. Keep this selected copy file-private,
 # so there is no second package-visible mapper declaration.
 mapper,_,_=e.function(s,'mapSendDanmakuErrorMessage',indent='');pieces.append(mapper.replace('internal fun','private fun',1))
 declarations.append(dict(name='mapSendDanmakuErrorMessage',originalBody=mapper,originalSHA=sha(mapper.encode()),adaptations=[dict(before='internal fun',after='private fun')]))
 methods=[]
 for name in ['sendDanmaku','sendAttentionCommandDanmaku']:
  body,a,b=e.function(s,name);patches=[]
  body=e.adapt(body,'com.android.purebilibili.core.store.TokenManager.csrfCache','readCsrf()',patches)
  body=e.adapt(body,'= withContext(Dispatchers.IO) {','= withContext(Dispatchers.IO) {\n        checkRequest()',patches)
  # The original API fields/arguments/results remain untouched. The exact await
  # point is followed by captured caller/receipt validation before success.
  needle='            if (response.code == 0 && response.data != null) {'
  body=e.adapt(body,needle,'            checkRequest()\n'+needle,patches)
  body=e.adapt(body,'        } catch (e: Exception) {','        } catch (cancelled: kotlinx.coroutines.CancellationException) {\n            throw cancelled\n        } catch (e: Exception) {\n            checkRequest()',patches)
  inverse=body
  for p in reversed(patches):inverse=inverse.replace(p['after'],p['before'],1)
  assert inverse==s[a:b]
  methods.append(body);declarations.append(dict(name=name,originalBody=s[a:b],originalSHA=sha(s[a:b].encode()),sourceLine=s[:a].count('\n')+1,adaptedSHA=sha(body.encode()),adaptations=patches))
 out='package com.android.purebilibili.data.repository\n\nimport com.android.purebilibili.core.network.BilibiliApi\nimport kotlinx.coroutines.*\n\n'+'\n\n'.join(pieces)+'\n\ninternal class DesktopOriginalVideoDanmakuSendProtocol(\n    private val api: BilibiliApi,\n    private val readCsrf: () -> String?,\n    private val assertOwned: () -> Unit,\n) {\n    private suspend fun checkRequest() { currentCoroutineContext().ensureActive(); assertOwned() }\n'+'\n\n'.join(methods)+'\n}\n'
 save(HERE/'com/android/purebilibili/data/repository/DesktopOriginalVideoDanmakuSendProtocol.kt',out)
 js(HERE/'selected-declarations.json',dict(commit=COMMIT,path=path,sourceSHA=sha(original),declarations=declarations))
 return [dict(path='com/android/purebilibili/data/repository/DesktopOriginalVideoDanmakuSendProtocol.kt',origin=path,originalSha256LF=sha(original),sha256LF=sha(out.encode()),mode='selected',generated=True)]
if __name__=='__main__':
 parser=argparse.ArgumentParser();parser.add_argument('--repo',required=True);parser.add_argument('--output',required=True);parser.add_argument('--standalone',action='store_true');args=parser.parse_args();print(json.dumps(generate(Path(args.repo),Path(args.output),args.standalone)))
