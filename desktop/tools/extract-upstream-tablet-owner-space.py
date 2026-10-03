"""Complete original SpaceViewModel initial-load call graph, with required captured Windows request ports."""
from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import argparse,hashlib,importlib.util,json,re,subprocess,textwrap
PIN='79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40';BASE='app/src/main/java/com/android/purebilibili/'
SOURCES=[BASE+'feature/space/SpaceViewModel.kt',BASE+'data/repository/ActionRepository.kt','core-data/src/main/java/com/android/purebilibili/core/network/ApiClient.kt',BASE+'feature/space/SpaceLoadPolicy.kt']
def module(name,path):
 spec=importlib.util.spec_from_file_location(name,path);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
def members(parser,src):
 tokens=parser.kotlin_tokens(src);depth=0;parens=0;brackets=0;starts=[];result={}
 for i,t in enumerate(tokens):
  if depth==0 and parens==0 and brackets==0 and t[0]in ('fun','val','var','class','object','init','companion'):starts.append(i)
  depth+=(t[0]=='{')-(t[0]=='}')
  parens+=(t[0]=='(')-(t[0]==')');brackets+=(t[0]=='[')-(t[0]==']')
 for q,i in enumerate(starts):
  kind=tokens[i][0];limit=starts[q+1]if q+1<len(starts)else len(tokens)
  begin=src.rfind('\n',0,tokens[i][1])+1;end=limit-1
  if kind=='fun':
   op=next(j for j in range(i+1,limit)if tokens[j][0]=='(');name=tokens[op-1][0];level=1;j=op
   while level:j+=1;level+=(tokens[j][0]=='(')-(tokens[j][0]==')')
   ob=next(k for k in range(j+1,limit)if tokens[k][0]in ('=','{'))
   brace=ob if tokens[ob][0]=='{'else next((k for k in range(ob+1,limit)if tokens[k][0]=='{'),None)
   if brace is not None:
    level=1;end=brace
    while level:end+=1;level+=(tokens[end][0]=='{')-(tokens[end][0]=='}')
  elif kind in ('class','object','init','companion'):
   name=tokens[i+1][0]if kind in ('class','object')else kind
   brace=next((k for k in range(i+1,limit)if tokens[k][0]=='{'),None)
   if brace is not None:
    level=1;end=brace
    while level:end+=1;level+=(tokens[end][0]=='{')-(tokens[end][0]=='}')
   elif kind=='class':
    op=next((k for k in range(i+1,limit)if tokens[k][0]=='('),None)
    if op is not None:
     level=1;end=op
     while level:end+=1;level+=(tokens[end][0]=='(')-(tokens[end][0]==')')
  else:name=tokens[i+1][0]
  value=src[begin:tokens[end][2]].rstrip()
  # Non-braced properties end before the next declaration's modifiers/comments.
  if kind in ('val','var'):
   value=re.sub(r'\n\s*(?://[^\n]*\n\s*)*(?:(?:private|public|internal|override|suspend)\s*)+$','',value).rstrip()
  result.setdefault(name,[]).append(dict(kind=kind,text=value))
 return result
def classbody(parser,source,name):
 tokens=parser.kotlin_tokens(source);s,e=parser.kotlin_structure(tokens,'class',name);op=next(i for i in range(s,e)if tokens[i][0]=='{');return source[tokens[op][2]:tokens[e][1]]
def select_graph(allmembers,roots):
 methods={n:'\n\n'.join(v['text']for v in values)for n,values in allmembers.items()if values[0]['kind']=='fun'}
 chosen=set();pending=list(roots)
 while pending:
  n=pending.pop()
  if n in chosen:continue
  chosen.add(n);pending +=[m for m in methods if m not in chosen and re.search(r'\b'+re.escape(m)+r'\s*\(',methods[n])]
 return {n:methods[n]for n in methods if n in chosen}
def generate(repo,out):
 parser=module('tablet_space_parser',repo/'desktop/tools/sync-upstream.py');original={}
 for path in SOURCES:
  blob=subprocess.check_output(['git','-C',str(repo),'show',PIN+':'+path]).decode('utf-8').replace('\r\n','\n');current=(_desktop_canonical_source(repo, path)).read_text(encoding='utf-8').replace('\r\n','\n')
  if current!=blob:raise ValueError('Pinned source differs: '+path)
  original[path]=blob
 vm=original[SOURCES[0]];body=classbody(parser,vm,'SpaceViewModel');allmembers=members(parser,body);selected=select_graph(allmembers,['loadSpaceInfo'])
 selectedText='\n\n'.join(selected.values());fields=[];pendingText=selectedText+'\n'+allmembers['init'][0]['text']
 selectedFields={'uiState'}
 while True:
  new=[n for n,vs in allmembers.items()if vs[0]['kind']in ('val','var')and n not in selectedFields and re.search(r'\b'+re.escape(n)+r'\b',pendingText)]
  if not new:break
  selectedFields.update(new);pendingText+='\n'+'\n'.join(allmembers[n][0]['text']for n in new)
 for n,values in allmembers.items():
  if n in selectedFields:fields.append(values[0]['text'])
 fields.insert(0,allmembers['SpaceVideoLoadResult'][0]['text'])
 cs=body.index('    private companion object {');ce=body.index('\n    }',cs)+len('\n    }');fields.append(body[cs:ce])
 parts=fields+[allmembers['init'][0]['text']]+list(selected.values());records=[]
 def adapt(text):
  replacements=[('private val spaceApi = NetworkModule.spaceApi','private val spaceApi get() = environment.spaceApi'),('MutableStateFlow','environment.mutableStateFlow'),('ActionRepository.followStateChanges','environment.followStateChanges'),('ActionRepository.','environment.actions.'),('HistoryRepository.','environment.history.'),('FavoriteRepository','environment.favorites'),('NetworkModule.api','environment.api'),('spaceApi.getSpaceAggregate(mid = mid)','environment.getSpaceAggregate(mid)'),('fun loadSpaceInfo(mid: Long) {','override fun loadSpaceInfo(mid: Long) {\n        environment.requireMid(mid)')]
  for a,b in replacements:text=text.replace(a,b)
  # A cancelled request is never converted into an empty response or Error state.
  text=re.sub(r'} catch \((\w+): Exception\) \{',r'} catch (cancelled: CancellationException) { throw cancelled\n        } catch (\1: Exception) {',text)
  text=text.replace('runCatching {','ownedRunCatching {').replace('return@runCatching','return@ownedRunCatching')
  text=text.replace('val uiState = _uiState.asStateFlow()','override val uiState = _uiState.asStateFlow()')
  return text
 for n,text in [('fields','\n\n'.join(fields)),('init',parts[len(fields)])]+list(selected.items()):records.append(dict(name=n,original=text,adapted=adapt(text),sha256Original=hashlib.sha256(text.encode()).hexdigest()))
 imports='''package com.android.purebilibili.feature.space
import androidx.lifecycle.SavedStateHandle
import com.android.purebilibili.core.network.WbiUtils
import com.android.purebilibili.data.model.response.*
import com.bilipai.desktop.ui.DesktopOriginalOwnerUploadsPort
import com.bilipai.desktop.ui.DesktopOriginalTabletOwnerSpaceEnvironment
import com.bilipai.desktop.ui.getSpaceAggregate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
'''
 result=imports+'\ninternal class DesktopOriginalTabletOwnerSpaceLoader(\n    private val environment: DesktopOriginalTabletOwnerSpaceEnvironment,\n    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),\n) : DesktopOriginalOwnerUploadsPort {\n    private val viewModelScope get() = environment.scope\n\n'+ '\n\n'.join(adapt(t)for t in parts)+'\n}\n\nprivate const val SPACE_AUDIO_PAGE_SIZE_CONST = 30\n'
 result=result.replace('private val _uiState = environment.mutableStateFlow','private val _uiState = environment.mutableStateFlow')
 # Every original runCatching fallback survives, while cancellation propagates.
 result += '\nprivate inline fun <T> ownedRunCatching(block: () -> T): Result<T> = try { Result.success(block()) } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Throwable) { Result.failure(failure) }\n'
 actionbody=classbody(parser,original[SOURCES[1]],'ActionRepository')if 'class ActionRepository' in original[SOURCES[1]]else original[SOURCES[1]][original[SOURCES[1]].index('object ActionRepository {')+len('object ActionRepository {'):original[SOURCES[1]].rfind('}')]
 actions=members(parser,actionbody);apieces=[actions[n][0]['text']for n in ('getRelationDetail','checkFollowStatus')]
 at='''package com.bilipai.desktop.ui
import com.android.purebilibili.core.network.BilibiliApi
import kotlinx.coroutines.*
internal class DesktopOriginalTabletOwnerSpaceActions(private val api:BilibiliApi) {
'''+ '\n\n'.join(re.sub(r'} catch \((\w+): Exception\) \{',r'} catch (cancelled: CancellationException) { throw cancelled\n            } catch (\1: Exception) {',t)for t in apieces)+'\n}\n'
 api=original[SOURCES[2]];start=api.index('suspend fun SpaceApi.getSpaceAggregate(');end=api.index('fun buildSpaceAggregateParams(',start);ext=api[start:end].strip()
 adapted=ext.replace('suspend fun SpaceApi.getSpaceAggregate','internal suspend fun DesktopOriginalTabletOwnerSpaceEnvironment.getSpaceAggregate').replace('return getSpaceAggregate(','return spaceApi.getSpaceAggregate(').replace('TokenManager.accessTokenCache','primaryAccessToken()').replace('TokenManager.accessTokenPlatformCache','primaryAccessTokenPlatform()')
 at+='\n'+adapted+'\n';at=at.replace('import kotlinx.coroutines.*','import kotlinx.coroutines.*\nimport com.android.purebilibili.core.network.buildSpaceAggregateParams')
 def emit(pkg,name,text):
  path=out/pkg.replace('.','/')/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(text,encoding='utf-8',newline='\n')
 emit('com.android.purebilibili.feature.space','DesktopOriginalTabletOwnerSpaceLoader.kt',result)
 emit('com.bilipai.desktop.ui','DesktopOriginalTabletOwnerSpaceActions.kt',at)
 proof=dict(pin=PIN,methods=list(selected),fields=list(selectedFields),originalSources=[dict(path=p,sha256LF=hashlib.sha256(s.encode()).hexdigest())for p,s in original.items()],vmDeclarations=records,actionDeclarations=[dict(original=a)for a in apieces],aggregateExtension=dict(original=ext,adapted=adapted))
 (out/'selection-proof.json').write_text(json.dumps(proof,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--repo',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args();generate(a.repo,a.output)
