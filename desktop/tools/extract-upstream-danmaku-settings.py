from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import hashlib,importlib.util,json,re,subprocess,sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent;MAIN=next(p for p in HERE.parents if (p/'.git').exists());STABLE=MAIN.parent/'BiliPai-v023'
COMMIT='79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40';BASE='app/src/main/java/com/android/purebilibili/'
def sha(s):return hashlib.sha256(s.encode('utf-8')).hexdigest()
def write(p,s):p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s,encoding='utf-8',newline='\n')
def save(p,s):write(p,json.dumps(s,ensure_ascii=False,indent=2)+'\n')
spec=importlib.util.spec_from_file_location('source_parser',HERE/'extract-upstream-danmaku-list-menu.py');parser=importlib.util.module_from_spec(spec);spec.loader.exec_module(parser)
def decl(s,name,indent=''):
 mask=parser.masked(s)
 m=re.search(r'(?m)^'+re.escape(indent)+r'(?:(?:private|internal|suspend|inline)\s+)*fun(?:\s*<[^>]*>)?\s+'+name+r'\s*\(',mask);assert m,name
 a=mask.index('(',m.start());b=parser.balanced(mask,a)
 if mask[b:].lstrip().startswith(':'):
  c=b
  while mask[c] not in '={':c+=1
 else:
  c=b
  while mask[c].isspace():c+=1
 if mask[c]=='{':end=parser.balanced(mask,c,'{','}')
 else:
  # Expression declaration ends at the next same-level declaration/comment,
  # preserving its multiline original Flow expression.
  tail=mask[c:];n=re.search(r'(?m)^'+re.escape(indent)+r'(?:(?:private|internal|suspend|inline)\s+)*(?:fun|val|const val|enum class|data class|class|object)\b',tail)
  end=c+n.start() if n else len(s)
  original=s[m.start():end]
  comment=re.search(r'(?m)^'+re.escape(indent)+r'//',original)
  if comment:end=m.start()+comment.start()
 return s[m.start():end].rstrip(),m.start()
def schema(s,name):
 mask=parser.masked(s);m=re.search(r'(?m)^(?:(?:internal|private|data|enum)\s+)*class '+name+r'\b',mask);assert m,name
 opening=mask.index('(',m.start());end=parser.balanced(mask,opening)
 tail=mask[end:].lstrip()
 if tail.startswith('{'):end=parser.balanced(mask,mask.index('{',end),'{','}')
 return s[m.start():end]
def generate(repo,output,standalone=False):
 paths=[BASE+x for x in ['feature/video/ui/components/DanmakuSettingsPanel.kt','core/store/SettingsManager.kt','feature/video/danmaku/DanmakuSettingsPolicy.kt','feature/video/danmaku/DanmakuCloudRuleSyncPolicy.kt','feature/video/danmaku/DanmakuSyncStatusPolicy.kt','data/repository/DanmakuRepository.kt','feature/video/ui/section/VideoPlayerSectionPolicy.kt','feature/video/ui/section/VideoPlayerSection.kt']]
 sources={};ids=[];emitted=[]
 for path in paths:
  from v025_source_paths import canonical_source
  canonical_file=canonical_source(repo,path)
  canonical_path=canonical_file.relative_to(repo.resolve()).as_posix()
  s=subprocess.check_output(['git','show',COMMIT+':'+canonical_path],cwd=repo).decode().replace('\r\n','\n');assert canonical_file.read_text(encoding='utf-8').replace('\r\n','\n')==s,path
  sources[path]=s;ids.append(dict(path=canonical_path,previousPath=path,pinnedCommit=COMMIT,sha256LF=sha(s),gitBlob=subprocess.check_output(['git','rev-parse',COMMIT+':'+canonical_path],cwd=repo,text=True).strip()))
 def emit(path,s,origin,mode,patches=None,original=None):
  write(output/path,s);row=dict(path=path,origin=origin,mode=mode,sha256LF=sha(s),adaptations=patches or [])
  if original is not None:
   reverse=s
   for p in reversed(patches or []):
    assert reverse.count(p['after'])==1,(path,p);reverse=reverse.replace(p['after'],p['before'])
   assert reverse==original,path;row['reverseNormalizedOriginalByteEqual']=True
  emitted.append(row)
 def adapt(s,a,b,rows):assert s.count(a)==1,(a,s.count(a));rows.append(dict(before=a,after=b));return s.replace(a,b)
 # Full original UI, all three sections and manager/import/cloud/add/delete branches.
 s=sources[paths[0]];patches=[]
 s=adapt(s,'import androidx.activity.compose.rememberLauncherForActivityResult','import com.bilipai.desktop.ui.rememberDesktopDanmakuRuleImportLauncher as rememberLauncherForActivityResult',patches)
 s=adapt(s,'import androidx.activity.result.contract.ActivityResultContracts','import com.bilipai.desktop.ui.DesktopDanmakuOpenRuleDocument',patches)
 s=adapt(s,'import androidx.compose.ui.platform.LocalConfiguration','import com.bilipai.desktop.ui.LocalDesktopDanmakuSettingsViewport as LocalConfiguration',patches)
 s=adapt(s,'import androidx.compose.ui.platform.LocalContext','import com.bilipai.desktop.ui.LocalDesktopDanmakuSettingsPlatform as LocalContext',patches)
 s=adapt(s,'import androidx.compose.ui.window.Dialog\n','import com.bilipai.desktop.ui.DesktopWindowsDanmakuDialog as Dialog\n',patches)
 s=adapt(s,'import androidx.compose.ui.Modifier\n','import androidx.compose.ui.Modifier\nimport androidx.compose.ui.semantics.contentDescription\nimport androidx.compose.ui.semantics.semantics\n',patches)
 s=adapt(s,'text = settingsScope.badgeLabel,','text = if (isFullscreenStyle) "全屏播放" else "窗口播放",',patches)
 s=adapt(s,'text = settingsScope.subtitle,','text = "开关、字号、行距和区域与全屏播放同步，其余样式独立",',patches)
 s=adapt(s,'text = "竖屏弹幕显示区域",','text = "窗口弹幕显示区域",',patches)
 s=adapt(s,'''                colors = AppSliderDefaults.colors(
                    thumbColor = colors.sliderThumbColor,
                    activeTrackColor = colors.sliderActiveTrackColor,
                    inactiveTrackColor = colors.sliderInactiveTrackColor
                ),
                modifier = Modifier.fillMaxWidth()
''','''                colors = AppSliderDefaults.colors(
                    thumbColor = colors.sliderThumbColor,
                    activeTrackColor = colors.sliderActiveTrackColor,
                    inactiveTrackColor = colors.sliderInactiveTrackColor
                ),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = label }
''',patches)
 s=adapt(s,'import com.android.purebilibili.data.repository.DanmakuRepository','// Root supplies the sole owned cloud API through the platform binding.',patches)
 s=adapt(s,'contract = ActivityResultContracts.OpenDocument()','contract = DesktopDanmakuOpenRuleDocument',patches)
 s=adapt(s,'context.contentResolver.openInputStream(uri)','context.openRuleInput(uri)',patches)
 for name in ['getDanmakuCloudFilterRules','addDanmakuCloudFilterRule','deleteDanmakuCloudFilterRule']:
  s=adapt(s,'DanmakuRepository.'+name,'context.cloud.'+name,patches)
 toast=[('''                        android.widget.Toast.makeText(
                            context,
                            "已同步云端弹幕屏蔽规则（${mapped.size} 条）",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()''','''                        context.showFeedback("已同步云端弹幕屏蔽规则（${mapped.size} 条）")'''),('''                        android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()''','''                        context.showFeedback(it)'''),('''                        android.widget.Toast.makeText(
                            context,
                            error.message?.takeIf(String::isNotBlank) ?: "云端规则同步失败",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()''','''                        context.showFeedback(error.message?.takeIf(String::isNotBlank) ?: "云端规则同步失败")''')]
 for a,b in toast:s=adapt(s,a,b,patches)
 emit('com/android/purebilibili/feature/video/ui/components/DanmakuSettingsPanel.kt',s,paths[0],'selected-full-renderer',patches,sources[paths[0]])
 for path in paths[2:5]:
  if standalone:emit(path.removeprefix(BASE),sources[path],path,'direct',[],sources[path])
 # Existing SettingsScope is owned by the installed list/menu producer: reference it.
 original=sources[paths[1]];models=[schema(original,n) for n in ['DanmakuPanelWidthMode','PortraitDanmakuDisplayAreaMode','DanmakuSettings']]
 models += [decl(original,n)[0] for n in ['normalizeDanmakuDisplayArea','normalizeDanmakuFontScale','normalizeDanmakuFullscreenPanelWidthMode','resolveDanmakuSettingsScope']]
 body='package com.android.purebilibili.core.store\nimport com.android.purebilibili.feature.video.danmaku.DANMAKU_DEFAULT_OPACITY\nimport kotlin.math.abs\n\n'+'\n\n'.join(models)+'\n'
 emit('com/android/purebilibili/core/store/DesktopOriginalDanmakuSettingsModels.kt',body,paths[1],'selected-original-model-policy')
 original=sources[paths[5]];models=[schema(original,n) for n in ['DanmakuCloudFilterRule','DanmakuCloudFilterRules','DanmakuCloudSyncSettings','DanmakuCloudConfigPayload']]
 helpers=[re.search(r'(?m)^private fun Boolean\.toCloudFlag\(\)[^\n]+',original).group(0)]
 helpers += re.findall(r'(?m)^internal const val DANMAKU_CLOUD_FONT_SIZE_[^\n]+',original)
 helpers += [decl(original,n)[0] for n in ['mapDanmakuDisplayAreaRatioToCloudValue','mapDanmakuFontScaleToCloudFontSize','buildDanmakuCloudConfigPayload','isDanmakuCloudSyncSuccessful']]
 emit('com/android/purebilibili/data/repository/DesktopOriginalDanmakuCloudModels.kt','package com.android.purebilibili.data.repository\nimport com.android.purebilibili.core.store.normalizeDanmakuDisplayArea\n\n'+'\n\n'.join(models+helpers)+'\n',paths[5],'selected-original-cloud-model-policy')
 # Exact original parameter/error mappings. The existing Root authority injects
 # its already-owned API, CSRF and admission; no client/cache/session is made.
 methods=[];contracts=[]
 for name in ['getDanmakuCloudFilterRules','addDanmakuCloudFilterRule','deleteDanmakuCloudFilterRule','syncDanmakuCloudConfig']:
  raw,start=decl(original,name,'    ');s=raw
  s=s.replace('com.android.purebilibili.core.store.TokenManager.csrfCache','readCsrf()')
  s=s.replace('withContext(Dispatchers.IO) {\n','withContext(Dispatchers.IO) {\n            assertOwned()\n            kotlinx.coroutines.currentCoroutineContext().ensureActive()\n',1)
  mask=parser.masked(s);m=re.search(r'val response = api\.',mask);opening=mask.index('(',m.end());end=parser.balanced(mask,opening)
  s=s[:end]+'\n                kotlinx.coroutines.currentCoroutineContext().ensureActive()\n                assertOwned()'+s[end:]
  s=s.replace('            } catch (e: Exception) {','            } catch (e: Exception) {\n                assertOwned()')
  if name=='syncDanmakuCloudConfig':s=s.replace('        } catch (e: Exception) {','        } catch (cancelled: CancellationException) {\n            throw cancelled\n        } catch (e: Exception) {\n            assertOwned()')
  methods.append(s);contracts.append(dict(member=name,sourceLine=original[:start].count('\n')+1,originalBodySha256LF=sha(raw),adaptedBodySha256LF=sha(s),changes='Only Root CSRF alias and ownership/caller cancellation checks before and after existing API await; fields/server mappings unchanged.'))
 body='''package com.android.purebilibili.data.repository
import com.android.purebilibili.core.network.BilibiliApi
import kotlinx.coroutines.*
internal class DesktopOriginalDanmakuCloudRuleProtocol(
    private val api:BilibiliApi, private val readCsrf:()->String?, private val assertOwned:()->Unit,
) {
'''+ '\n\n'.join(methods)+'\n}\n'
 emit('com/android/purebilibili/data/repository/DesktopOriginalDanmakuCloudRuleProtocol.kt',body,paths[5],'selected-original-protocol')
 # Map/key precedence and all original setters use the ONE supplied global backing.
 original=sources[paths[1]]
 begin=original.index('    private const val DEFAULT_DANMAKU_OPACITY');end=original.index('    internal fun mapDanmakuSettingsFromPreferences(')
 keys=original[begin:end].rstrip().replace('Preferences.Key<','DesktopPreferenceKey<').replace('preferences: Preferences','preferences: DesktopPreferenceSnapshot')
 constants=re.match(r'(?:    private const val [^\n]+\n)+',keys).group(0)
 keys='    private companion object {\n'+constants+'    }\n'+keys[len(constants):]
 mapping=decl(original,'mapDanmakuSettingsFromPreferences','    ')[0].replace('preferences: Preferences','preferences: DesktopPreferenceSnapshot')
 getter=decl(original,'getDanmakuSettings','    ')[0].replace('        context: Context,\n','').replace('context.settingsDataStore.data','store.snapshot("settings")')
 setters=['setDanmakuEnabled','setDanmakuOpacity','setDanmakuFontScale','setDanmakuSpeed','setDanmakuArea','setPortraitDanmakuDisplayAreaMode','setDanmakuFontWeight','setDanmakuStrokeWidth','setDanmakuLineHeight','setDanmakuScrollDurationSeconds','setDanmakuStaticDurationSeconds','setDanmakuScrollFixedVelocity','setDanmakuStaticToScroll','setDanmakuMassiveMode','setDanmakuAllowScroll','setDanmakuAllowTop','setDanmakuAllowBottom','setDanmakuAllowColorful','setDanmakuAllowSpecial','setDanmakuWeightFilterLevel','setDanmakuHideInteractiveCommands','setDanmakuSmartOcclusion','setDanmakuFullscreenPanelWidthMode','setDanmakuMergeDuplicates','setDanmakuDuplicateMergeWindowMs','setDanmakuDuplicateMergeCountThreshold','setDanmakuCloudSyncEnabled','forceDanmakuDefaults']
 selected=[];setterrows=[]
 for name in setters:
  raw,start=decl(original,name,'    ');s=raw.replace('context: Context, ','').replace('        context: Context,\n','').replace('context: Context','').replace('context.settingsDataStore.edit','writeOriginal')
  selected.append(s);setterrows.append(dict(member=name,sourceLine=original[:start].count('\n')+1,originalBodySha256LF=sha(raw),adaptedBodySha256LF=sha(s),changes='Context argument removed; original edit body delegates owned global Store atomic update.'))
 cloudGet=decl(original,'getDanmakuCloudSyncEnabled','    ')[0].replace('context: Context','').replace('context.settingsDataStore.data','store.snapshot("settings")')
 body='''package com.bilipai.desktop.settings
import com.android.purebilibili.core.store.*
import com.android.purebilibili.feature.video.danmaku.*
import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

internal class DesktopOriginalDanmakuPreferences(
    private val store:DesktopPluginStore,
    val blocks:DesktopDanmakuBlockPreferences,
    private val withOwnedAdmission:((()->Unit)->Boolean),
) {
    init {store.requireObjectNamespace("settings")}
    private val DANMAKU_DEFAULTS_VERSION=5
    private val KEY_DANMAKU_CLOUD_SYNC_ENABLED=booleanPreferencesKey("danmaku_cloud_sync_enabled")
    private fun floatPreferencesKey(name:String)=DesktopPreferenceKey(name){value:JsonElement->(value as? JsonPrimitive)?.floatOrNull}
    private fun intPreferencesKey(name:String)=DesktopPreferenceKey(name){value:JsonElement->(value as? JsonPrimitive)?.intOrNull}
    private class Editor {
        val values=linkedMapOf<String,JsonElement?>()
        operator fun <T> set(key:DesktopPreferenceKey<T>,value:T){values[key.name]=when(value){is String->JsonPrimitive(value);is Boolean->JsonPrimitive(value);is Number->JsonPrimitive(value);else->error("Unsupported original danmaku value")}}
        operator fun <T> get(key:DesktopPreferenceKey<T>):T?=before[key]
        lateinit var before:DesktopPreferenceSnapshot
    }
    private suspend fun writeOriginal(block:(Editor)->Unit) {
        val caller=currentCoroutineContext()
        withContext(Dispatchers.IO) {
            if(!withOwnedAdmission { store.updateFromSnapshot("settings") { snapshot ->
                caller.ensureActive();val editor=Editor();editor.before=snapshot;block(editor);editor.values
            } }) throw CancellationException("Danmaku settings owner retired")
        }
    }
    fun currentSettings(scope:DanmakuSettingsScope)=mapDanmakuSettingsFromPreferences(store.snapshot("settings").value,scope)
    suspend fun setDanmakuBlockRulesRaw(value:String,scope:DanmakuSettingsScope)=blocks.setDanmakuBlockRulesRaw(value,scope)
    internal suspend fun migrateMissingOriginalLegacyValues(values:Map<String,JsonElement>) {
        writeOriginal { editor -> values.forEach { (name,value) ->
            val key=DesktopPreferenceKey<JsonElement>(name){it}
            if(editor[key]==null)editor.values[name]=value
        } }
    }
'''+keys+'\n\n'+mapping+'\n\n'+getter+'\n\n'+cloudGet+'\n\n'+'\n\n'.join(selected)+'\n}\n'
 emit('com/bilipai/desktop/settings/DesktopOriginalDanmakuPreferences.kt',body,paths[1],'selected-original-global-store-adapter')
 # Direction is original player presentation policy, not desktop window aspect guess.
 policy=decl(sources[paths[6]],'resolveVideoPlayerDanmakuSettingsScope')[0]
 emit('com/android/purebilibili/feature/video/ui/section/DesktopOriginalDanmakuScopePolicy.kt','package com.android.purebilibili.feature.video.ui.section\nimport com.android.purebilibili.core.store.*\n\n'+policy+'\n',paths[6],'selected-original-scope-policy')
 # Complete original account config-sync state and effects live outside the popup.
 player=sources[paths[7]];state=player[player.index('        var pendingDanmakuCloudSync by remember {'):player.index('        fun buildDanmakuCloudSyncSettings(')]
 functions='\n\n'.join(decl(player,n,'        ')[0] for n in ['buildDanmakuCloudSyncSettings','queueDanmakuCloudSync','requestDanmakuCloudSyncNow'])
 functions=functions.replace('            if (!canSyncDanmakuCloud) return','            if (!platform.isOwned() || !canSyncDanmakuCloud) return')
 functions=functions.replace('android.os.SystemClock.elapsedRealtime()','platform.elapsedRealtimeMillis()')
 effects=[]
 for anchor in ['        LaunchedEffect(canSyncDanmakuCloud, danmakuCloudSyncEnabled) {','        LaunchedEffect(pendingDanmakuCloudSync, canSyncDanmakuCloud, danmakuManualSyncRequestVersion) {']:
  start=player.index(anchor);opening=parser.masked(player).index('{',start);end=parser.balanced(parser.masked(player),opening,'{','}');effects.append(player[start:end])
 reset=effects[0].replace('{\n','{\n            if (!platform.isOwned()) return@LaunchedEffect\n',1)
 effect=effects[1].replace('{\n','{\n            if (!platform.isOwned()) return@LaunchedEffect\n',1)
 effect=effect.replace('            danmakuCloudSyncUiState = resolveDanmakuCloudSyncStateAfterStarted(', '            kotlinx.coroutines.currentCoroutineContext().ensureActive()\n            if (!platform.isOwned()) return@LaunchedEffect\n            danmakuCloudSyncUiState = resolveDanmakuCloudSyncStateAfterStarted(')
 effect=effect.replace('com.android.purebilibili.data.repository.DanmakuRepository\n                .syncDanmakuCloudConfig(settings)','platform.cloud.syncDanmakuCloudConfig(settings)\n            kotlinx.coroutines.currentCoroutineContext().ensureActive()\n            if (!platform.isOwned()) return@LaunchedEffect')
 effect=effect.replace('''                android.util.Log.w(
                    "VideoPlayerSection",
                    "Danmaku cloud sync failed: ${result.exceptionOrNull()?.message}"
                )''','''                platform.onCloudSyncFailure(result.exceptionOrNull()?.message)''')
 body='''package com.bilipai.desktop.ui
import androidx.compose.runtime.*
import com.android.purebilibili.core.store.DanmakuSettings
import com.android.purebilibili.data.repository.DanmakuCloudSyncSettings
import com.android.purebilibili.feature.video.danmaku.*
import kotlinx.coroutines.ensureActive

internal class DesktopDanmakuCloudSyncBinding(
    val uiState:DanmakuCloudSyncUiState,
    val queueChange:((DanmakuCloudSyncSettings)->DanmakuCloudSyncSettings)->Unit,
    val requestNow:()->Unit,
    val onEnabledChange:(Boolean)->Unit,
)
/** Mount under the existing player/page owner, outside the transient settings popup. */
@Composable internal fun rememberDesktopOriginalDanmakuCloudSyncBinding(
    settings:DanmakuSettings,cloudSyncEnabled:Boolean,isLoggedIn:Boolean,
    platform:DesktopDanmakuSettingsPlatform,
):DesktopDanmakuCloudSyncBinding {
    val danmakuEnabled=settings.enabled
    val danmakuAllowScroll=settings.allowScroll
    val danmakuAllowTop=settings.allowTop
    val danmakuAllowBottom=settings.allowBottom
    val danmakuAllowColorful=settings.allowColorful
    val danmakuAllowSpecial=settings.allowSpecial
    val danmakuOpacity=settings.opacity
    val danmakuDisplayArea=settings.displayArea
    val danmakuSpeed=settings.speed
    val danmakuFontScale=settings.fontScale
    val danmakuCloudSyncEnabled=cloudSyncEnabled
    val canSyncDanmakuCloud=shouldSyncDanmakuSettingsToCloud(isLoggedIn,cloudSyncEnabled)
'''+state+functions+'\n\n'+reset+'\n\n'+effect+'''
    return DesktopDanmakuCloudSyncBinding(danmakuCloudSyncUiState,
        queueChange={ change ->
            if(platform.isOwned()) {
                val changed=change(buildDanmakuCloudSyncSettings())
                queueDanmakuCloudSync(changed.enabled,changed.allowScroll,changed.allowTop,changed.allowBottom,
                    changed.allowColorful,changed.allowSpecial,changed.opacity,changed.displayAreaRatio,changed.speed,changed.fontScale)
            }
        },requestNow=::requestDanmakuCloudSyncNow,onEnabledChange={ enabled ->
            if(platform.isOwned()&&!enabled) {
                pendingDanmakuCloudSync=null
                danmakuCloudSyncUiState=DanmakuCloudSyncUiState()
            }
        })
}
'''
 emit('com/bilipai/desktop/ui/DesktopOriginalDanmakuCloudSyncBinding.kt',body,paths[7],'selected-original-state-effects-bridge')
 save(output/'source-inventory.json',dict(pinnedCommit=COMMIT,sourceIdentities=ids,outputs=emitted,cloudProtocolMembers=contracts,settingsSetterMembers=setterrows,existingReferences=['DanmakuSettingsScope (installed original-danmaku-list-menu producer)','DesktopDanmakuBlockPreferences (same installed body/Root global backing)','DanmakuKeywordFilterPolicy/Proto/models/Root Repository/Operations/Store/Session (reference, never re-emit)','Original AppThemeAdaptiveTabRow/full shared renderer (reference)']))
 return emitted
if __name__=='__main__':
 import argparse
 cli=argparse.ArgumentParser();cli.add_argument('--repo',type=Path,default=STABLE);cli.add_argument('--output',type=Path,required=True);cli.add_argument('--standalone',action='store_true');args=cli.parse_args()
 generate(args.repo,args.output,standalone=args.standalone)
