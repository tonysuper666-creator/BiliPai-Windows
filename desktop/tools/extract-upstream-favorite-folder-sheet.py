"""Sole original favorite drawer producer. No account/client/store/model replacement."""
from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import hashlib, json, re, subprocess, sys

COMMIT='79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40'
BASE='app/src/main/java/com/android/purebilibili/'
PATHS=[BASE+x for x in [
 'feature/video/ui/components/FavoriteFolderSheet.kt',
 'feature/video/viewmodel/FavoriteSaveUiPolicy.kt',
 'feature/video/screen/VideoFavoriteActionPolicy.kt',
 'feature/video/policy/FavoriteFolderIdPolicy.kt',
 'feature/video/viewmodel/VideoPlaybackViewModel.kt',
 'data/repository/ActionRepository.kt',
 'core/store/FavoriteInteractionSettingsStore.kt']]

def sha(s): return hashlib.sha256(s.encode('utf-8')).hexdigest()
def write(p,s):
 value=str(p.absolute());prefix=chr(92)*2+'?'+chr(92)
 p=Path(value if value.startswith(prefix) else prefix+value)
 p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s,encoding='utf-8',newline='\n')
def save(p,s): write(p,json.dumps(s,ensure_ascii=False,indent=2)+'\n')
def masked(text):
 out=list(text);i=0;n=len(text)
 while i<n:
  if text.startswith('//',i): end=text.find('\n',i);end=n if end<0 else end
  elif text.startswith('/*',i):
   end=i+2;depth=1
   while end<n and depth:
    if text.startswith('/*',end):depth+=1;end+=2
    elif text.startswith('*/',end):depth-=1;end+=2
    else:end+=1
  elif text.startswith('"""',i):end=text.find('"""',i+3);assert end>=0;end+=3
  elif text[i] in ('"',"'"):
   quote=text[i];end=i+1
   while end<n:
    if text[end]=='\\':end+=2
    elif text[end]==quote:end+=1;break
    else:end+=1
  else:i+=1;continue
  for j in range(i,end):
   if out[j]!='\n':out[j]=' '
  i=end
 return ''.join(out)
def balanced(mask,start,left='(',right=')'):
 assert mask[start]==left;depth=0
 for i in range(start,len(mask)):
  if mask[i]==left:depth+=1
  elif mask[i]==right:
   depth-=1
   if not depth:return i+1
 raise ValueError('unclosed token')
def function(text,name,indent='    '):
 mask=masked(text);m=re.search('(?m)^'+indent+r'(?:(?:private|internal|suspend|inline)\s+)*fun\s+'+name+r'\s*\(',mask);assert m,name
 a=mask.index('(',m.start());b=balanced(mask,a);c=mask.index('{',b);d=balanced(mask,c,'{','}')
 return text[m.start():d],m.start(),d
def adapt(text,before,after,rows):
 assert text.count(before)==1,(before,text.count(before));rows.append(dict(before=before,after=after));return text.replace(before,after)

def generate(repo:Path,output:Path,standalone=False):
 source={};identities=[];emitted=[]
 for path in PATHS:
  blob=subprocess.check_output(['git','show',COMMIT+':'+path],cwd=repo).decode('utf-8').replace('\r\n','\n')
  local=(_desktop_canonical_source(repo, path)).read_text(encoding='utf-8').replace('\r\n','\n');assert local==blob,path
  source[path]=blob;identities.append(dict(path=path,pinnedCommit=COMMIT,sha256LF=sha(blob),gitBlob=subprocess.check_output(['git','rev-parse',COMMIT+':'+path],cwd=repo,text=True).strip()))
 def emit(path,text,origin,mode,patches=None,original=None):
  write(output/path,text);row=dict(path=path,origin=origin,mode=mode,sha256LF=sha(text),adaptations=patches or [])
  if original is not None:
   reverse=text
   for p in reversed(patches or []): assert reverse.count(p['after'])==1;reverse=reverse.replace(p['after'],p['before'])
   assert reverse==original,path
   row['reverseNormalizedOriginalByteEqual']=True
  emitted.append(row)
 sheet=source[PATHS[0]];patch=[]
 adapted=adapt(sheet,'import androidx.compose.ui.platform.LocalConfiguration','import com.bilipai.desktop.ui.LocalDesktopFavoriteFolderViewport as LocalConfiguration',patch)
 emit('com/android/purebilibili/feature/video/ui/components/FavoriteFolderSheet.kt',adapted,PATHS[0],'selected-full-renderer',patch,sheet)
 for i in (1,2,3):
  if standalone:
   emit('com/android/purebilibili/'+PATHS[i][len(BASE):],source[PATHS[i]],PATHS[i],'direct',[],source[PATHS[i]])
 vm=source[PATHS[4]]
 policies=[]
 for name in ('resolveFavoriteFolderMutation','resolveFavoriteFolderDialogTargetAid','shouldSyncFavoriteFolderUiState'):
  policies.append(function(vm,name,indent='')[0])
 declarations=[]
 for name in ('FavoriteFolderMutation','FavoriteFolderSaveEvent'):
  mask=masked(vm);m=re.search(r'(?m)^internal data class '+name+r'\(',mask);assert m
  end=balanced(mask,mask.index('(',m.start()));declarations.append(vm[m.start():end])
 selected='package com.android.purebilibili.feature.video.viewmodel\n\n'+'\n\n'.join(declarations+policies)+'\n'
 emit('com/android/purebilibili/feature/video/viewmodel/FavoriteFolderSessionPolicy.kt',selected,PATHS[4],'selected-policy')
 action=source[PATHS[5]];methods=[];actionpatch=[]
 for name in ('getFavoriteFolders','updateFavoriteFolders'):
  body,_,_=function(action,name)
  original=body
  body=body.replace('TokenManager.midCache','readMid()').replace('TokenManager.csrfCache','readCsrf()')
  body=body.replace('                android.util.Log.e("ActionRepository", "updateFavoriteFolders failed", e)\n','')
  # The unchanged original result algorithm is surrounded by the existing caller's owner guard.
  body=body.replace('        return withContext(Dispatchers.IO) {','        assertOwned()\n        return withContext(Dispatchers.IO) {\n            kotlinx.coroutines.currentCoroutineContext().ensureActive()\n            assertOwned()')
  body=body.replace('                if (response.code == 0) {','                kotlinx.coroutines.currentCoroutineContext().ensureActive()\n                assertOwned()\n                if (response.code == 0) {')
  body=body.replace('            } catch (e: Exception) {','            } catch (cancelled: kotlinx.coroutines.CancellationException) {\n                throw cancelled\n            } catch (e: Exception) {\n                assertOwned()')
  actionpatch.append(dict(member=name,originalBodySha256LF=sha(original),adaptedBodySha256LF=sha(body),originalStartLine=action[:function(action,name)[1]].count('\n')+1))
  methods.append(body)
 protocol='''package com.android.purebilibili.data.repository
import com.android.purebilibili.core.network.BilibiliApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Uses Root's existing owned API. No Retrofit, client, cookies, identity or data store. */
class DesktopOriginalFavoriteFolderProtocol(
    private val api:BilibiliApi,
    private val readMid:()->Long?,
    private val readCsrf:()->String?,
    private val assertOwned:()->Unit,
) {
'''+ '\n\n'.join(methods)+'\n}\n'
 emit('com/android/purebilibili/data/repository/DesktopOriginalFavoriteFolderProtocol.kt',protocol,PATHS[5],'selected-protocol',actionpatch)
 # Session holds the original drawer's transient StateFlows only; Root owns video info/count and account.
 fields=vm[vm.index('    private val _favoriteFolderDialogVisible'):vm.index('    private val _followGroupDialogVisible')].rstrip()
 methods=[];memberrows=[]
 names=('showFavoriteFolderDialog','dismissFavoriteFolderDialog','invalidateFavoriteFolderCache','loadFavoriteFolders','toggleFavoriteFolderSelection','saveFavoriteFolderSelection','applyFavoriteSaveUiState','updateFavoriteUiState','createFavoriteFolder')
 for name in names:
  body,start,end=function(vm,name);original=body
  body=body.replace('(_uiState.value as? VideoPlaybackUiState.Success)?.info?.aid','environment.currentAid()')
  body=body.replace('interactionUseCase.','environment.').replace('com.android.purebilibili.data.repository.ActionRepository.createFavFolder','environment.createFavFolder')
  if name=='toggleFavoriteFolderSelection':
   # Both original overloads must remain; the folder overload follows the Long overload.
   following=vm[end:];second,_,_=function(following,name);body+='\n\n'+second
  if name=='applyFavoriteSaveUiState':
   body='''    private fun applyFavoriteSaveUiState(
        originalFolderIds: Set<Long>,
        selectedFolderIds: Set<Long>
    ) {
        val resolvedState = resolveFavoriteSaveUiState(
            originalFolderIds = originalFolderIds,
            selectedFolderIds = selectedFolderIds,
            currentFavoriteCount = environment.currentFavoriteCount()
        )
        environment.confirmFavoriteSave(resolvedState.isFavorited, resolvedState.favoriteCount)
    }'''
  elif name=='updateFavoriteUiState':
   before='''        _uiState.update { state ->
            if (state is VideoPlaybackUiState.Success) {
                state.copy(isFavorited = selectedFolderIds.isNotEmpty())
            } else {
                state
            }
        }'''
   assert before in body;body=body.replace(before,'        environment.confirmFavoriteLoaded(selectedFolderIds.isNotEmpty())')
  elif name=='invalidateFavoriteFolderCache':
   body=body.replace('        favoriteFoldersBoundAid = null','        ++loadRequestId\n        loadJob?.cancel()\n        _isFavoriteFoldersLoading.value = false\n        favoriteFoldersBoundAid = null')
  elif name=='loadFavoriteFolders':
   body=body.replace('        viewModelScope.launch {','''        val request = ++loadRequestId
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {''')
   body=body.replace('            val result = environment.getFavoriteFolders(aid)','''            val result = environment.getFavoriteFolders(aid)
            currentCoroutineContext().ensureActive()
            if (request != loadRequestId) return@launch
            environment.assertOwned()''')
   body=body.replace('            _isFavoriteFoldersLoading.value = false','''            } finally {
                if (request == loadRequestId) _isFavoriteFoldersLoading.value = false
            }''')
  elif name=='saveFavoriteFolderSelection':
   body=body.replace('        viewModelScope.launch {\n            _isSavingFavoriteFolders.value = true','''        val request = ++saveRequestId
        _isSavingFavoriteFolders.value = true
        saveJob = viewModelScope.launch {
            try {''')
   body=body.replace('            result.onSuccess {','''            currentCoroutineContext().ensureActive()
            if (request != saveRequestId) return@launch
            environment.assertOwned()
            result.onSuccess {''')
   body=body.replace('            _isSavingFavoriteFolders.value = false','''            } finally {
                if (request == saveRequestId) _isSavingFavoriteFolders.value = false
            }''')
  elif name=='createFavoriteFolder':
   body=body.replace('        viewModelScope.launch {','        val createJob = viewModelScope.launch {')
   body=body.replace('            result.onSuccess {','            currentCoroutineContext().ensureActive()\n            environment.assertOwned()\n            result.onSuccess {')
   body=body[:-1]+'''    createJobs.add(createJob)
        createJob.invokeOnCompletion { createJobs.remove(createJob) }
    }'''
  # Every direct action is rejected after the owner has closed; no old-page feedback.
  if name in ('showFavoriteFolderDialog','toggleFavoriteFolderSelection','saveFavoriteFolderSelection','createFavoriteFolder'):
   opening=body.index('{');body=body[:opening+1]+'\n        environment.assertOwned()'+body[opening+1:]
  methods.append(body);memberrows.append(dict(member=name,originalStartLine=vm[:start].count('\n')+1,originalBodySha256LF=sha(original),adaptedBodySha256LF=sha(body)))
 session='''package com.android.purebilibili.feature.video.viewmodel
import com.android.purebilibili.feature.video.policy.resolveFavoriteFolderMediaId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import com.bilipai.desktop.ui.DesktopFavoriteFolderEnvironment

/** Original drawer state only. Root remains the sole video/account/count authority. */
class DesktopOriginalFavoriteFolderSession(private val environment:DesktopFavoriteFolderEnvironment) {
    private val viewModelScope:CoroutineScope get()=environment.scope
    private fun toast(message:String)=environment.showFeedback(message)
    private var loadRequestId=0L
    private var saveRequestId=0L
    private var loadJob:Job?=null
    private var saveJob:Job?=null
    private val createJobs=java.util.concurrent.ConcurrentHashMap.newKeySet<Job>()
    fun close() { environment.close(); ++loadRequestId; ++saveRequestId; loadJob?.cancel(); saveJob?.cancel(); createJobs.forEach { it.cancel() }; createJobs.clear() }
'''+fields+'\n\n'+'\n\n'.join(methods)+'\n}\n'
 emit('com/android/purebilibili/feature/video/viewmodel/DesktopOriginalFavoriteFolderSession.kt',session,PATHS[4],'selected-session',memberrows)
 inventory=dict(upstreamCommit=COMMIT,sources=identities,emitted=emitted,
  requiredDirectReferences=[dict(path=PATHS[i],sha256LF=sha(source[PATHS[i]]),owner='Root sync-upstream direct once') for i in (1,2,3)],
  referenceOnly=[dict(path=PATHS[6],reason='Same Root global settings key; do not emit a second SettingsStore')],
  productionDefault='These are unique declarations, verify actual symbol owners before registry merge',standalone=standalone)
 save(output/'source-inventory.json',inventory)
 return inventory

if __name__=='__main__':
 import argparse
 ap=argparse.ArgumentParser()
 ap.add_argument('--repo',type=Path,required=True)
 ap.add_argument('--output',type=Path,required=True)
 ap.add_argument('--standalone',action='store_true')
 args=ap.parse_args()
 generate(args.repo,args.output,args.standalone)
 print('generated original favorite drawer, policy, protocol and session closure')
