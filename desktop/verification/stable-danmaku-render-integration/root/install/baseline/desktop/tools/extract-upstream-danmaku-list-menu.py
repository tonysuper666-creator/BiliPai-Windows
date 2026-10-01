from pathlib import Path
import hashlib,json,re,subprocess,sys

def sha(s): return hashlib.sha256(s.encode('utf-8')).hexdigest()
def write(p,s): p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s,encoding='utf-8',newline='\n')
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
def function(text,name,indent='    ',occurrence=0):
 mask=masked(text);matches=list(re.finditer('(?m)^'+indent+r'(?:(?:private|internal|suspend|inline)\s+)*fun(?:\s*<[^>]*>)?\s+'+name+r'\s*\(',mask));assert len(matches)>occurrence,name;m=matches[occurrence]
 a=mask.index('(',m.start());b=balanced(mask,a);c=mask.index('{',b);d=balanced(mask,c,'{','}')
 return text[m.start():d],m.start(),d
def adapt(text,before,after,rows):
 assert text.count(before)==1,(before,text.count(before));rows.append(dict(before=before,after=after));return text.replace(before,after)

COMMIT='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
BASE='app/src/main/java/com/android/purebilibili/'
PATHS=[BASE+x for x in ['feature/video/ui/components/DanmakuPoolSheet.kt','feature/video/ui/components/DanmakuContextMenu.kt','feature/video/viewmodel/VideoPlaybackViewModel.kt','feature/video/danmaku/DanmakuParser.kt','feature/video/danmaku/WeightedTextData.kt','feature/video/danmaku/DanmakuManager.kt','feature/video/danmaku/DanmakuConfig.kt','data/repository/DanmakuRepository.kt','core/store/SettingsManager.kt','feature/video/screen/VideoDetailOverlayHost.kt','feature/video/ui/section/VideoPlayerSection.kt']]+['danmaku-engine/src/main/java/com/android/purebilibili/danmaku/engine/DanmakuModels.kt']

def class_body(s,name,indent=''):
 m=re.search(r'(?m)^'+indent+r'(?:(?:internal|private|open|data|enum)\s+)*class '+name+r'\b',masked(s));assert m,name
 mask=masked(s);a=mask.index('{',m.start());b=balanced(mask,a,'{','}');return s[m.start():b]
def drop_logs(body,patches):
 while True:
  mask=masked(body);m=re.search(r'(?:com\.android\.purebilibili\.core\.util\.Logger|android\.util\.Log)\.[dwei]\s*\(',mask)
  if not m:return body
  a=body.rfind('\n',0,m.start())+1;b=balanced(mask,mask.index('(',m.start()))
  assert not body[a:m.start()].strip();before=body[a:b]+'\n';assert body.count(before)==1
  body=adapt(body,before,'',patches)

def generate(repo:Path,output:Path,standalone=False):
 source={};identities=[];emitted=[]
 for path in PATHS:
  blob=subprocess.check_output(['git','show',COMMIT+':'+path],cwd=repo).decode('utf-8').replace('\r\n','\n')
  assert (repo/path).read_text(encoding='utf-8').replace('\r\n','\n')==blob,path
  source[path]=blob;identities.append(dict(path=path,pinnedCommit=COMMIT,sha256LF=sha(blob),gitBlob=subprocess.check_output(['git','rev-parse',COMMIT+':'+path],cwd=repo,text=True).strip()))
 def emit(path,text,origin,mode,patches=None,original=None):
  write(output/path,text);row=dict(path=path,origin=origin,mode=mode,sha256LF=sha(text),adaptations=patches or [])
  if original is not None:
   reverse=text
   for p in reversed(patches or []):
    assert p['after'] and reverse.count(p['after'])==1
    reverse=reverse.replace(p['after'],p['before'])
   assert reverse==original,path;row['reverseNormalizedOriginalByteEqual']=True
  emitted.append(row)
 # Full original list, search, sort, row and every dialog branch. No hand-written fallback renderer.
 s=source[PATHS[0]];rows=[]
 for line in ['import android.content.ClipData\n','import android.content.ClipboardManager\n','import android.content.Context\n','import android.widget.Toast\n']:
  s=adapt(s,line,'// Windows port: '+line.rstrip()+'\n',rows)
 s=adapt(s,'import androidx.compose.ui.platform.LocalContext','import com.bilipai.desktop.ui.LocalDesktopDanmakuBindings as LocalContext',rows)
 before='''                                Toast.makeText(
                                    context,
                                    "已跳转至 ${FormatUtils.formatDuration(item.showAtTime)}",
                                    Toast.LENGTH_SHORT
                                ).show()'''
 s=adapt(s,before,'                                context.showFeedback("已跳转至 ${FormatUtils.formatDuration(item.showAtTime)}")',rows)
 before='''                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("danmaku", item.text.orEmpty())
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(context, "已复制弹幕内容", Toast.LENGTH_SHORT).show()'''
 s=adapt(s,before,'                                    context.copyText(item.text.orEmpty(), "danmaku")\n                                    context.showFeedback("已复制弹幕内容")',rows)
 s=adapt(s,'.clickable(onClick = onItemClick)', '.combinedClickable(onClick = onItemClick, onLongClick = onLongClick)',rows)
 s=adapt(s,'import androidx.compose.foundation.clickable','import androidx.compose.foundation.clickable\nimport androidx.compose.foundation.combinedClickable',rows)
 emit('com/android/purebilibili/feature/video/ui/components/DanmakuPoolSheet.kt',s,PATHS[0],'selected-full-renderer',rows,source[PATHS[0]])
 # Complete menu/report/recall/copy/select-text/timestamp/block UI and policies.
 s=source[PATHS[1]];rows=[]
 s=adapt(s,'import androidx.compose.ui.platform.LocalContext','import com.bilipai.desktop.ui.LocalDesktopDanmakuBindings as LocalContext',rows)
 s=adapt(s,'import com.android.purebilibili.core.ui.common.copyPlainTextToClipboard','import com.bilipai.desktop.ui.copyDesktopDanmakuText as copyPlainTextToClipboard',rows)
 s=adapt(s,'import com.android.purebilibili.core.ui.common.TextSelectionBottomSheet','import com.bilipai.desktop.ui.DesktopDanmakuTextSelection as TextSelectionBottomSheet',rows)
 emit('com/android/purebilibili/feature/video/ui/components/DanmakuContextMenu.kt',s,PATHS[1],'selected-full-renderer',rows,source[PATHS[1]])
 # Complete original renderer-neutral model. Only irrelevant platform bitmap/font carriers use their actual AWT types.
 original=source[PATHS[-1]];model=original[:original.index('\ndata class DanmakuWindow(')]
 rows=[];s=adapt(model,'import android.graphics.Bitmap','import java.awt.image.BufferedImage as Bitmap',rows)
 s=adapt(s,'import android.graphics.Typeface','import java.awt.Font as Typeface',rows)
 s=adapt(s,'import android.graphics.Path','// Android Path is outside this selected standard-item class.',rows)
 emit('com/android/purebilibili/danmaku/engine/DanmakuItem.kt',s,PATHS[-1],'selected-complete-schema',rows,model)
 weighted=source[PATHS[4]]
 if standalone:emit('com/android/purebilibili/feature/video/danmaku/WeightedTextData.kt',weighted,PATHS[4],'direct',[],weighted)
 parser=source[PATHS[3]];methods=[function(parser,n)[0] for n in ['createTextDataFromProto','formatDanmakuTextWithCount','createTextData','mapLayerType']]
 # Expose the original private factories to the Windows source projection only; bodies remain byte-identical.
 methods[0]=methods[0].replace('private fun createTextDataFromProto','fun createTextDataFromProto');methods[2]=methods[2].replace('private fun createTextData(','fun createTextData(')
 manager=source[PATHS[5]];m=re.search(r'(?m)^internal fun resolveDanmakuClickUserHash[^\n]*',manager);assert m
 policies=[m.group(0),function(manager,'resolveDanmakuClickIsSelf','')[0]]
 config=source[PATHS[6]];constant=re.search(r'(?m)^private const val BILIBILI_STANDARD_DANMAKU_FONT_SIZE[^\n]*',config)
 if not constant:constant=re.search(r'(?m)^(?:internal )?const val BILIBILI_STANDARD_DANMAKU_FONT_SIZE[^\n]*',config)
 assert constant
 policies += [constant.group(0),function(config,'resolveBilibiliDanmakuFontScale','')[0]]
 body='''package com.android.purebilibili.feature.video.danmaku
import com.android.purebilibili.danmaku.engine.*

'''+ '\n\n'.join(policies)+'\n\ninternal object DesktopOriginalDanmakuItemParser {\n'+'\n\n'.join(methods)+'\n}\n'
 emit('com/android/purebilibili/feature/video/danmaku/DesktopOriginalDanmakuItemParser.kt',body,PATHS[3],'selected-original-factories-and-click-policies')
 repo_source=source[PATHS[7]];state=class_body(repo_source,'DanmakuThumbupState') if '{' in repo_source[repo_source.index('internal data class DanmakuThumbupState'):repo_source.index('internal data class DanmakuCloudSyncSettings')] else repo_source[repo_source.index('internal data class DanmakuThumbupState'):repo_source.index('internal data class DanmakuCloudSyncSettings')].strip()
 resolve=function(repo_source,'resolveDanmakuThumbupState','')[0]
 body='package com.android.purebilibili.data.repository\nimport com.android.purebilibili.data.model.response.DanmakuThumbupStatsItem\n\n'+state+'\n\n'+resolve+'\n'
 emit('com/android/purebilibili/data/repository/DesktopOriginalDanmakuThumbupPolicy.kt',body,PATHS[7],'selected-original-schema-and-policy')
 methods=[];methodrows=[]
 for name in ['getDanmakuThumbupState','recallDanmaku','likeDanmaku','reportDanmaku']:
  raw,start,end=function(repo_source,name);patch=[];s=drop_logs(raw,patch)
  if 'com.android.purebilibili.core.store.TokenManager.csrfCache' in s:s=adapt(s,'com.android.purebilibili.core.store.TokenManager.csrfCache','readCsrf()',patch)
  s=adapt(s,'        try {','        kotlinx.coroutines.currentCoroutineContext().ensureActive()\n        assertOwned()\n        try {',patch)
  # API returns before any success/error receipt. The original parameters and all server error mappings remain unchanged.
  mask=masked(s);m=re.search(r'\bval response = api\.',mask);assert m
  opening=mask.index('(',m.end());closing=balanced(mask,opening);before=s[closing:closing+1]
  assert before=='\n',name
  s=s[:closing]+'\n            kotlinx.coroutines.currentCoroutineContext().ensureActive()\n            assertOwned()'+s[closing:]
  s=adapt(s,'        } catch (e: Exception) {','        } catch (cancelled: kotlinx.coroutines.CancellationException) {\n            throw cancelled\n        } catch (e: Exception) {\n            assertOwned()',patch)
  methods.append(s);methodrows.append(dict(member=name,originalStartLine=repo_source[:start].count('\n')+1,originalBodySha256LF=sha(raw),adaptedBodySha256LF=sha(s),changes='Only platform logs/identity injection and caller cancellation/owner guards. Request fields and response code/message rules unchanged.'))
 body='''package com.android.purebilibili.data.repository
import com.android.purebilibili.core.network.BilibiliApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Same owned Root API and CSRF. No Retrofit/client/cookie/account construction. */
internal class DesktopOriginalDanmakuProtocol(
    private val api:BilibiliApi,
    private val readCsrf:()->String?,
    private val assertOwned:()->Unit,
) {
'''+ '\n\n'.join(methods)+'\n}\n'
 emit('com/android/purebilibili/data/repository/DesktopOriginalDanmakuProtocol.kt',body,PATHS[7],'selected-complete-protocol',methodrows)
 settings=source[PATHS[8]];scope=class_body(settings,'DanmakuSettingsScope')
 emit('com/android/purebilibili/core/store/DanmakuSettingsScope.kt','package com.android.purebilibili.core.store\n\n'+scope+'\n',PATHS[8],'selected-complete-original-scope')
 # Select only the original persisted block-rule contract. Full settings panel/cloud/smart occlusion is explicitly outside this slice.
 read_policy=function(settings,'readScopedDanmakuPreference')[0].replace('Preferences.Key<T>','DesktopPreferenceKey<T>').replace('preferences: Preferences','preferences: DesktopPreferenceSnapshot')
 key_builder=function(settings,'buildScopedDanmakuKeyName')[0]
 key=function(settings,'keyDanmakuBlockRules')[0] if False else '    private fun keyDanmakuBlockRules(scope: DanmakuSettingsScope) =\n        stringPreferencesKey(buildScopedDanmakuKeyName(scope, "block_rules"))'
 getter=function(settings,'getDanmakuBlockRulesRaw')[0] if False else settings[settings.index('    fun getDanmakuBlockRulesRaw('):settings.index('    fun getDanmakuBlockRules(')].strip()
 setter=function(settings,'setDanmakuBlockRulesRaw')[0]
 setter=setter.replace('context: Context,\n','').replace('        context.settingsDataStore.edit { preferences ->\n            preferences[keyDanmakuBlockRules(scope)] = normalized\n        }','        writeOriginalScopedValue(keyDanmakuBlockRules(scope), normalized)')
 getter=getter.replace('context: Context,\n','').replace('context.settingsDataStore.data','store.snapshot("settings")')
 body='''package com.bilipai.desktop.settings
import com.android.purebilibili.core.store.DanmakuSettingsScope
import com.android.purebilibili.feature.video.danmaku.*
import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.JsonPrimitive

/** Reads/writes the one Root global settings backing. Scope is portrait/landscape, never MID. */
internal class DesktopDanmakuBlockPreferences(
    private val store:DesktopPluginStore,
    private val withOwnedAdmission:((()->Unit)->Boolean),
) {
    init {store.requireObjectNamespace("settings")}
    private val KEY_DANMAKU_BLOCK_RULES=stringPreferencesKey("danmaku_block_rules")
    private suspend fun writeOriginalScopedValue(key:DesktopPreferenceKey<String>,value:String) {
        val caller=currentCoroutineContext()
        withContext(Dispatchers.IO) {
            if(!withOwnedAdmission {
                store.updateFromSnapshot("settings") { caller.ensureActive(); mapOf(key.name to JsonPrimitive(value)) }
            }) throw CancellationException("Danmaku settings owner retired")
        }
    }
'''+key_builder+'\n'+key+'\n'+read_policy+'\n'+getter+'\n'+setter+'\n}\n'
 emit('com/bilipai/desktop/settings/DesktopDanmakuBlockPreferences.kt',body,PATHS[8],'selected-original-key-getter-normalization')
 vm=source[PATHS[2]];state=vm[vm.index('    data class DanmakuMenuState('):vm.index('    private val _danmakuMenuState')].rstrip()
 methods=[];memberrows=[]
 for name in ['showDanmakuMenu','hideDanmakuMenu','refreshDanmakuThumbupState','recallDanmaku','likeDanmaku','reportDanmaku']:
  raw,start,end=function(vm,name,occurrence=1 if name in ('likeDanmaku','reportDanmaku') else 0);s=raw
  s=s.replace('com.android.purebilibili.data.repository.DanmakuRepository','environment.actions')
  if name=='showDanmakuMenu':
   s=s.replace('        val supportsVote =','        environment.assertOwned()\n        ++menuGeneration\n        val supportsVote =')
  elif name=='hideDanmakuMenu':
   s=s.replace('        _danmakuMenuState.value =','        ++menuGeneration\n        _danmakuMenuState.value =')
  elif name=='refreshDanmakuThumbupState':
   s=s.replace('        viewModelScope.launch {','''        val request=++statsGeneration
        statsRequests[dmid]=request
        val menuRequest=menuGeneration
        statsJobs.remove(dmid)?.cancel()
        val loading=viewModelScope.launch {
            try {''')
   s=s.replace('                .onSuccess { thumbupState ->','''                .also { currentCoroutineContext().ensureActive(); environment.assertOwned(); if(request!=statsRequests[dmid])return@launch }
                .onSuccess { thumbupState ->''')
   s=s.replace('if (!current.visible || current.dmid != dmid)','if (menuRequest!=menuGeneration || !current.visible || current.dmid != dmid)')
   s=s[:-1].rstrip();assert s.endswith('}');s=s[:-1]+'''            } finally {
                if (request==statsRequests[dmid] && menuRequest==menuGeneration && environment.isOwned()) {
                    _danmakuMenuState.update { current -> if(!current.visible||current.dmid!=dmid)current else current.copy(voteLoading=false) }
                }
            }
        }
        if(!loading.isCompleted)statsJobs[dmid]=loading
    }'''
  elif name=='likeDanmaku':
   s=s.replace('        _danmakuMenuState.update { current ->','''        environment.assertOwned()
        val menuRequest=menuGeneration
        val request=++likeGeneration
        if(_danmakuMenuState.value.visible && _danmakuMenuState.value.dmid==dmid)menuLikeRequest=request
        statsJobs.remove(dmid)?.cancel(); statsRequests[dmid]=++statsGeneration
        _danmakuMenuState.update { current ->''',1)
   s=s.replace('        viewModelScope.launch {','        viewModelScope.launch {',1) # First launch is the original invalid-CID feedback branch.
   # Guard the actual mutation coroutine, preserving the original confirmed update and all messages.
   marker='''        viewModelScope.launch {
            environment.actions''';assert s.count(marker)==1
   s=s.replace(marker,'''        viewModelScope.launch {
            try {
            environment.actions''')
   s=s.replace('                .onSuccess {','                .also { currentCoroutineContext().ensureActive(); environment.assertOwned() }\n                .onSuccess {',1)
   s=s.replace('if (!current.visible || current.dmid != dmid)','if (menuRequest!=menuGeneration || !current.visible || current.dmid != dmid)')
   s=s[:-1].rstrip();assert s.endswith('}');s=s[:-1]+'''            } finally {
                if(request==menuLikeRequest && menuRequest==menuGeneration && environment.isOwned() && ownerJob.isActive) {
                    _danmakuMenuState.update { current -> if(!current.visible||current.dmid!=dmid)current else current.copy(voteLoading=false) }
                }
            }
        }
    }'''
  else:
   s=s.replace('        viewModelScope.launch {\n            environment.actions','        environment.assertOwned()\n        viewModelScope.launch {\n            environment.actions')
   s=s.replace('                .onSuccess {','                .also { currentCoroutineContext().ensureActive(); environment.assertOwned() }\n                .onSuccess {',1)
  s=s.replace('environment.assertOwned()','assertOwned()')
  s=s.replace('environment.isOwned())','environment.isOwned() && ownerJob.isActive)')
  if name=='likeDanmaku':methods.append(function(vm,name)[0].replace('        if (dmid <= 0L)', '        assertOwned()\n        if (dmid <= 0L)'))
  if name=='reportDanmaku':methods.append(function(vm,name)[0])
  methods.append(s);memberrows.append(dict(member=name,originalStartLine=vm[:start].count('\n')+1,originalBodySha256LF=sha(raw),adaptedBodySha256LF=sha(s)))
 body='''package com.android.purebilibili.feature.video.viewmodel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import com.bilipai.desktop.ui.DesktopDanmakuSessionEnvironment

/** Original menu and list share one confirmed liked-ID set; no replacement PlaybackVM/list/cache. */
internal class DesktopOriginalDanmakuSession(private val environment:DesktopDanmakuSessionEnvironment):AutoCloseable {
    private val ownerJob=SupervisorJob(environment.scope.coroutineContext[Job])
    private val viewModelScope=CoroutineScope(environment.scope.coroutineContext+ownerJob)
    private val currentCid:Long get()=environment.cid
    private var menuGeneration=0L
    private var statsGeneration=0L
    private var likeGeneration=0L
    private var menuLikeRequest=0L
    private val statsJobs=mutableMapOf<Long,Job>()
    private val statsRequests=mutableMapOf<Long,Long>()
    private fun toast(message:String) {if(environment.isOwned()&&ownerJob.isActive)environment.showFeedback(message)}
    private fun assertOwned(){environment.assertOwned();if(!ownerJob.isActive)throw CancellationException("Danmaku session closed")}
    private val _danmakuMenuState=MutableStateFlow(DanmakuMenuState())
    val danmakuMenuState=_danmakuMenuState.asStateFlow()
    private val _likedDanmakuIds=MutableStateFlow<Set<Long>>(emptySet())
    val likedDanmakuIds=_likedDanmakuIds.asStateFlow()
    override fun close(){++menuGeneration;++statsGeneration;++likeGeneration;ownerJob.cancel();statsJobs.clear();statsRequests.clear()}
'''+state+'\n\n'+'\n\n'.join(methods)+'\n}\n'
 emit('com/android/purebilibili/feature/video/viewmodel/DesktopOriginalDanmakuSession.kt',body,PATHS[2],'selected-original-menu-session',memberrows)
 inventory=dict(upstreamCommit=COMMIT,sources=identities,emitted=emitted,standalone=standalone,
  directReferences=[dict(path=PATHS[4],sha256LF=sha(weighted))],
  pending=['Full DanmakuSettingsPanel/cloud/filter import/smart occlusion','Transparent native overlay pointer/hit testing','Actual Root list/settings entry mounting and native seek callback','Root original TextSelectionBottomSheet delegate under existing owned card provider'],
  originalDefectAdaptation='DanmakuPoolItemRow ignored supplied onLongClick; only clickable->combinedClickable plus import changed.',
  singleAuthority='Root API/Operations/Repository/accountEpoch/player sourceVersion/Overlay rawDocument/global PluginStore remain sole authorities')
 save(output/'source-inventory.json',inventory);return inventory

if __name__=='__main__':
 import argparse
 ap=argparse.ArgumentParser();ap.add_argument('--repo',type=Path,required=True);ap.add_argument('--output',type=Path,required=True);ap.add_argument('--standalone',action='store_true')
 a=ap.parse_args();generate(a.repo,a.output,a.standalone)

