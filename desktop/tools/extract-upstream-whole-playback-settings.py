from pathlib import Path
import argparse, difflib, hashlib, importlib.util, json, re, subprocess, sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent
COMMIT='79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40'
BASE='app/src/main/java/com/android/purebilibili/'
SCREEN=BASE+'feature/settings/screen/PlaybackSettingsScreen.kt'
MANAGER=BASE+'core/store/SettingsManager.kt'
VM=BASE+'feature/settings/SettingsViewModel.kt'
SELECTION=BASE+'feature/settings/PlaybackSettingsSelectionPolicy.kt'
CACHE=BASE+'core/store/PlayerSettingsCache.kt'
def sha(v):return hashlib.sha256(v.encode() if isinstance(v,str) else v).hexdigest()
def module(n,p):
    s=importlib.util.spec_from_file_location(n,p);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
def wide(p):return Path('\\\\?\\'+str(p.resolve())) if sys.platform=='win32' and not str(p).startswith('\\\\?\\') else p
def write(p,t):p=wide(p);p.parent.mkdir(parents=True,exist_ok=True);p.write_text(t,encoding='utf-8',newline='\n')
def main(repo, output):
    sys.path.insert(0,str(repo/'desktop/tools'))
    from v025_source_paths import canonical_source
    controls=module('original_playback_controls',repo/'desktop/tools/extract-upstream-video-player-full-controls.py')
    controls.parser=module('original_playback_tokens',repo/'desktop/tools/sync-upstream.py')
    controls.selector=module('original_playback_selector',repo/'desktop/tools/extract-appearance-platform.py')
    platform=module('original_playback_publication_platform',HERE/'v025_playback_settings_platform.py')
    pins=[];proof=[];outputs=[]
    def read(path):
        original=canonical_source(repo,path);raw=original.read_bytes();t=raw.replace(b'\r\n',b'\n').decode()
        committed=subprocess.check_output(['git','show',COMMIT+':'+path],cwd=repo).replace(b'\r\n',b'\n')
        assert t.encode()==committed,path
        if not any(p['path']==path for p in pins):
            pins.append({'path':path,'rawSha256':sha(raw),'sha256LF':sha(t),'gitBlob':subprocess.check_output(['git','rev-parse',COMMIT+':'+path],cwd=repo,text=True).strip()})
            write(output/'original-lf'/path,t)
        return t
    def adapted(path,before,after,mode='whole-source-adapted'):
        a=before.splitlines(keepends=True);b=after.splitlines(keepends=True);ops=[]
        for tag,i,j,k,l in difflib.SequenceMatcher(None,a,b,autojunk=False).get_opcodes():
            if tag!='equal':ops.append({'beforeStart':i,'beforeEnd':j,'afterStart':k,'afterEnd':l,'before':''.join(a[i:j]),'after':''.join(b[k:l])})
        restored=list(b)
        for op in reversed(ops):
            assert restored[op['afterStart']:op['afterEnd']]==op['after'].splitlines(keepends=True)
            restored[op['afterStart']:op['afterEnd']]=op['before'].splitlines(keepends=True)
        assert ''.join(restored)==before
        proof.append({'source':path,'mode':mode,'sha256LF':sha(before),'adaptedSha256LF':sha(after),'fullInverse':True,'operations':ops})
        return after
    def emit(rel,t):
        write(output/'generated'/rel,t);outputs.append({'path':rel,'sha256LF':sha(t),'lines':len(t.splitlines())})
    s=original=read(SCREEN)
    s=s.replace('import android.app.AppOpsManager\n','').replace('import android.content.Intent\n','').replace('import android.net.Uri\n','').replace('import android.os.Build\n','').replace('import android.provider.Settings\n','')
    s=s.replace('import androidx.activity.compose.rememberLauncherForActivityResult\n','').replace('import androidx.activity.result.contract.ActivityResultContracts\n','')
    s=s.replace('import android.content.Context','import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext as Context')
    s=s.replace('import androidx.compose.ui.platform.LocalContext','import com.bilipai.desktop.ui.LocalDesktopOriginalPlayerSettingsContext as LocalContext')
    for line in ['import androidx.compose.ui.platform.LocalLifecycleOwner\n','import androidx.lifecycle.viewmodel.compose.viewModel\n','import androidx.lifecycle.Lifecycle\n','import androidx.lifecycle.LifecycleEventObserver\n','import com.android.purebilibili.core.store.TokenManager\n','import com.android.purebilibili.core.store.DEFAULT_PLAYER_DIAGNOSTIC_LOGGING_ENABLED\n']:
        assert s.count(line)==1,line;s=s.replace(line,'',1)
    s=s.replace('import androidx.compose.ui.res.stringResource','import com.bilipai.desktop.appearance.LocalDesktopStrings')
    s=s.replace('import com.android.purebilibili.R','import com.bilipai.desktop.settings.DesktopPlaybackSettingsSymbols\nimport com.bilipai.desktop.settings.desktopPlaybackSettingsVector\nimport com.bilipai.desktop.settings.DesktopOriginalPlaybackSettingsBindings as SettingsViewModel\nimport com.bilipai.desktop.settings.DesktopOriginalPlaybackSettingsState as SettingsUiState')
    s=s.replace('viewModel: SettingsViewModel = viewModel(),','viewModel: SettingsViewModel,')
    s=s.replace('fun PlaybackSettingsScreen(', 'internal fun PlaybackSettingsScreen(', 1)
    s=s.replace('fun PlaybackSettingsContent(', 'internal fun PlaybackSettingsContent(', 1)
    s=s.replace('import com.android.purebilibili.core.store.SettingsManager','import com.android.purebilibili.core.store.DesktopOriginalPlaybackSettingsPreferences as SettingsManager')
    s=s.replace('com.android.purebilibili.core.store.SettingsManager','com.android.purebilibili.core.store.DesktopOriginalPlaybackSettingsPreferences')
    s=s.replace('import com.android.purebilibili.core.store.player.PlayerSettingsStore','import com.android.purebilibili.core.store.player.DesktopOriginalVideoPlayerSettings as PlayerSettingsStore')
    for key in ['common_back','playback_settings_title']:
        assert s.count('stringResource(R.string.'+key+')')==1
        s=s.replace('stringResource(R.string.'+key+')','LocalDesktopStrings.current["'+key+'"]')
    drawables=sorted(set(re.findall(r'R\.drawable\.(\w+)',original)))
    s=s.replace('com.android.purebilibili.feature.settings.rememberMaterialSymbol','desktopPlaybackSettingsVector').replace('rememberMaterialSymbol(R.drawable.','desktopPlaybackSettingsVector(R.drawable.')
    s=s.replace('com.android.purebilibili.R.drawable.','DesktopPlaybackSettingsSymbols.').replace('R.drawable.','DesktopPlaybackSettingsSymbols.')
    s=s.replace('com.android.purebilibili.core.util.AnalyticsHelper.logSettingChange','viewModel.logSettingChange')
    s=s.replace('com.android.purebilibili.data.repository.VideoRepository.isPlaybackLoggedIn()','viewModel.isPlaybackLoggedIn()').replace('com.android.purebilibili.data.repository.VideoRepository.isPlaybackVip()','viewModel.isPlaybackVip()')
    s=s.replace('DEFAULT_PLAYER_DIAGNOSTIC_LOGGING_ENABLED','context.defaultPlayerDiagnosticLoggingEnabled()')
    # Every original setting child Job remains owned by this real composition;
    # mirror writes run on IO, with the same required page permit and error consumer.
    s=s.replace('rememberCoroutineScope()','viewModel.bindWriteScope(rememberCoroutineScope { viewModel.writeContext })')
    start=s.index('    // 检查画中画权限\n');end=s.index('    // 权限弹窗逻辑\n',start)
    s=s[:start]+'''    // Windows uses the existing floating-player capability, with no Android permission screen.
    fun checkPipPermission(): Boolean = viewModel.pictureInPictureAvailable()
    fun gotoPipSettings() = viewModel.showPictureInPictureUnavailable()

'''+s[end:]
    s=s.replace('检测到未开启「画中画」权限。请在设置中开启该权限，否则无法使用小窗播放。','当前窗口的小窗播放器尚不可用，请检查播放器初始化状态后重试。Windows 不需要 Android 画中画授权。').replace('AppText("去设置")','AppText("检查播放器")',1)
    home='com.android.purebilibili.core.store.DesktopOriginalPlaybackSettingsPreferences\n                        .getHomeSettings(context)'
    assert s.count(home)==1,(home,s.count(home));s=s.replace(home,'viewModel.homeSettings',1)
    start=s.index('    val lifecycleOwner = LocalLifecycleOwner.current\n');end=s.index('    val inlineSwipeSeekSeconds by',start)
    s=s[:start]+'''    // Actual Windows adapter supports source-owned viewport dimming only.
    // Preserve an imported preference; opening this page never silently rewrites it.
    val canWriteSystemSettings = false

'''+s[end:]
    start=s.index('    if (showSystemBrightnessPermissionDialog) {\n');end=s.index('    AppPreferenceGroup {\n',start)
    s=s[:start]+s[end:]
    start=s.index('            subtitle = when {\n',s.index('title = "调节系统亮度"'));end=s.index('            iconTint = iOSOrange\n',start)
    s=s[:start]+'''            subtitle = "Windows 当前支持播放画面亮度，不修改物理显示器的系统亮度。",
            checked = false,
            enabled = false,
            onCheckedChange = { viewModel.showSystemBrightnessUnavailable() },
'''+s[end:]
    # Rotation sensor/orientation is an Android device capability. Window fullscreen
    # and its retained viewport modes remain backed by the existing Windows owner.
    rotation='            title = "自动横竖屏切换",\n            subtitle = autoRotateSubtitle,\n            checked = autoRotateEnabled,\n'
    assert s.count(rotation)==1
    s=s.replace(rotation,'            title = "自动横竖屏切换",\n            subtitle = "当前 Windows 窗口没有设备旋转传感器；全屏和横屏适配仍可使用。",\n            checked = false,\n            enabled = false,\n',1)
    for title,note in [
        ('播放时暂停其他应用的声音','Windows 当前无法通过此开关请求其他应用暂停；多个应用可能同时发声。'),
        ('响度均衡','Windows 播放器的自动响度均衡暂不可用，保留曲目原始响度。'),
    ]:
        begin=s.index('title = "'+title+'",')
        a=s.index('subtitle = ',begin);b=s.index('            checked = ',a)
        # Keep whole original row/state/callback; don't publish a preference on entry.
        s=s[:a]+'subtitle = "'+note+'",\n                            enabled = false,\n                '+s[b:]
    assert not re.search(r'\b(?:AppOpsManager|Intent|Uri|Build\.VERSION|Settings\.System|LocalLifecycleOwner|R\.(?:string|drawable))\b',s)
    emit('com/android/purebilibili/feature/settings/PlaybackSettingsScreen.kt',adapted(SCREEN,original,s))
    # Both screenshot policies are complete original pure Kotlin files. Sync owns
    # production copies; this task pins the exact originals used by the whole page.
    for relative in ['feature/screenshot/AppScreenshotPolicy.kt',
                     'feature/screenshot/AppScreenshotRegionPolicy.kt']:
        path = BASE + relative
        p = read(path)
        proof.append({'source': path, 'mode': 'exact-original-whole-pure-direct-sync',
                      'sha256LF': sha(p), 'fullInverse': True, 'before': p, 'after': p})
    # Retain the complete original favorite getter/setter and key. Only Context,
    # key factory and unused Android DataStore import change. The supplied Context
    # joins the existing original journal/final caller+Root permit over the same Store.
    path = BASE + 'core/store/FavoriteInteractionSettingsStore.kt'
    p = read(path)
    q = p.replace('import android.content.Context',
        'import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext as Context')
    q = q.replace('import androidx.datastore.preferences.core.booleanPreferencesKey',
        'import com.bilipai.desktop.ui.playerBooleanPreferencesKey as booleanPreferencesKey')
    q = q.replace('import androidx.datastore.preferences.core.edit\n', '')
    emit('com/android/purebilibili/core/store/FavoriteInteractionSettingsStore.kt',
         adapted(path, p, q))
    # Promote the entire original pure selection policy. Root must withdraw the old
    # two-declaration producer output, retaining its test assertions against this owner.
    p=read(SELECTION);q=p.replace('import com.android.purebilibili.core.store.SettingsManager','import com.android.purebilibili.core.store.DesktopFeedSettings as SettingsManager')
    emit('com/android/purebilibili/feature/settings/PlaybackSettingsSelectionPolicy.kt',adapted(SELECTION,p,q))
    m=read(MANAGER);obj=m[m.index('{',m.index('object SettingsManager'))+1:m.rfind('}')]
    seeds=sorted(set(re.findall(r'\bSettingsManager\s*\.\s*(\w+)',original))-{'getHomeSettings'}|{'getHwDecode','setHwDecode','getGestureSensitivity','setGestureSensitivity','getDoubleTapLike','setDoubleTapLike','getAutoSkipOpEd','setAutoSkipOpEd','getHeaderBlurEnabled','getAndroidNativeLiquidGlassEnabled'})
    body,closure=controls.member_closure(obj,seeds)
    before=body
    body=body.replace('PlayerSettingsCache.','DesktopOriginalPlaybackSettingsCache.')
    body=body.replace('com.android.purebilibili.core.util.isLargeScreenOrFoldableConfiguration(context)','context.isLargeScreenOrFoldableConfiguration()')
    body=body.replace('DEFAULT_PLAYER_DIAGNOSTIC_LOGGING_ENABLED','context.defaultPlayerDiagnosticLoggingEnabled()')
    body=platform.control_settings_body(body)
    adapted(MANAGER,before,body,'complete-selected-original-member-closure-with-explicit-platform-adaptations')
    used={w for w,_,_ in controls.parser.kotlin_tokens(body)}
    imports=[]
    for line in m[:m.index('object SettingsManager')].splitlines():
        if line.startswith('import ') and line.rsplit('.',1)[-1] in used:
            if line.startswith(('import android.','import androidx.datastore.')):continue
            if 'isLargeScreenOrFoldableConfiguration' in line:continue
            if 'core.store.player.PlayerSettingsStore' in line:line='import com.android.purebilibili.core.store.player.DesktopOriginalVideoPlayerSettings as PlayerSettingsStore'
            imports.append(line)
    header='''package com.android.purebilibili.core.store
import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext as Context
import com.bilipai.desktop.ui.DesktopOriginalPlayerPreferenceValues as Preferences
import com.bilipai.desktop.ui.DesktopOriginalPlayerPreferenceValues as MutablePreferences
import com.bilipai.desktop.ui.playerBooleanPreferencesKey as booleanPreferencesKey
import com.bilipai.desktop.ui.playerFloatPreferencesKey as floatPreferencesKey
import com.bilipai.desktop.ui.playerIntPreferencesKey as intPreferencesKey
import com.bilipai.desktop.ui.playerLongPreferencesKey as longPreferencesKey
import com.bilipai.desktop.ui.playerStringPreferencesKey as stringPreferencesKey
'''+''.join(x+'\n'for x in dict.fromkeys(imports))
    emit('com/android/purebilibili/core/store/DesktopOriginalPlaybackSettingsPreferences.kt',header+'\ninternal object DesktopOriginalPlaybackSettingsPreferences {\n'+body+'\n}\n')
    # The original synchronous cache setters project into exactly the already used
    # mirror namespace; singleton volatile cache authority is not introduced on desktop.
    cache=read(CACHE);cachebody=cache[cache.index('{',cache.index('object PlayerSettingsCache'))+1:cache.rfind('\n}\n')]
    cbody,cclosure=controls.member_closure(cachebody,['setHwDecodeEnabled','setPlayerDiagnosticLoggingEnabled','setDashSegmentRequestsEnabled'])
    cacheSelected=cbody
    cbody=re.sub(r'(?m)^\s*(?:hwDecodeEnabled|playerDiagnosticLoggingEnabled|dashSegmentRequestsEnabled) = enabled\n','\n',cbody)
    cbody=re.sub(r'(?m)^\s*Logger\.d\(TAG,.*\)\n','\n',cbody)
    cbody=re.sub(r'(?m)^\s*@Volatile\s*\n\s*private var \w+: Boolean\? = null\s*\n','\n',cbody)
    adapted(CACHE,cacheSelected,cbody,'complete-selected-original-mirror-setters-no-independent-process-cache')
    emit('com/android/purebilibili/core/store/DesktopOriginalPlaybackSettingsCache.kt','package com.android.purebilibili.core.store\nimport com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext as Context\ninternal object DesktopOriginalPlaybackSettingsCache {\n'+cbody+'\n}\n')
    # These constants are canonical originals not emitted by current control owners.
    emit('com/android/purebilibili/core/store/DesktopOriginalPlaybackSettingsDefaults.kt','package com.android.purebilibili.core.store\n'+controls.selector.declarations(controls.parser,m,['DEFAULT_DASH_SEGMENT_REQUESTS_ENABLED','LONG_PRESS_SPEED_HINT_STEP'])+'\n')
    migration=cache[cache.index('internal fun resolveMigratedHwDecodeValue'):]
    emit('com/android/purebilibili/core/store/DesktopOriginalHwDecodeMigrationPolicy.kt','package com.android.purebilibili.core.store\n'+migration)
    proof.append({'source':CACHE,'mode':'exact-original-complete-pure-migration-policy','sha256LF':sha(migration),'before':migration,'after':migration,'fullInverse':True})
    converter=module('original_playback_vectors',repo/'desktop/tools/extract-upstream-settings-search.py')
    converter.symbol_names=lambda unused:drawables
    vec=converter.vectors(repo).replace('DesktopSettingsSymbols','DesktopPlaybackSettingsSymbols').replace('DesktopSettingsVectors','DesktopPlaybackSettingsVectors')
    vec+='\n@Composable internal fun desktopPlaybackSettingsVector(name: String): ImageVector = DesktopPlaybackSettingsVectors.vector(name)\n'
    for n in drawables:read('app/src/main/res/drawable/'+n+'.xml')
    emit('com/bilipai/desktop/settings/DesktopPlaybackSettingsVectors.kt',vec)
    vm=read(VM)
    methods=[]
    for name in ['toggleHwDecode','setGestureSensitivity','toggleDoubleTapLike','toggleAutoSkipOpEd']:
        a,b=controls.function_range(vm,name);beforeMethod=vm[a:b]
        afterMethod=beforeMethod.replace('SettingsManager.','DesktopOriginalPlaybackSettingsPreferences.')
        methods.append(afterMethod)
        proof.append({'source':VM,'mode':'exact-original-complete-selected-callback','name':name,'sha256LF':sha(beforeMethod),'adaptedSha256LF':sha(afterMethod),'before':beforeMethod,'after':afterMethod,'onlyFacadeAliasChanged':True})
    bindings='''package com.bilipai.desktop.settings
import com.android.purebilibili.core.store.DesktopOriginalPlaybackSettingsPreferences
import com.android.purebilibili.core.store.HomeSettings
import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.coroutines.CoroutineContext

/** Narrow ABI of the complete original page; all keys and callbacks use its one Root Store. */
internal data class DesktopOriginalPlaybackSettingsState(
    val hwDecode: Boolean = true,
    val gestureSensitivity: Float = 1.0f,
    val headerBlurEnabled: Boolean = true,
    val androidNativeLiquidGlassEnabled: Boolean = true,
    val autoSkipOpEd: Boolean = false,
    val doubleTapLike: Boolean = true,
) {
    val isLiquidGlassEnabled: Boolean
        get() = androidNativeLiquidGlassEnabled
}
internal class DesktopOriginalPlaybackSettingsBindings(
    val context: DesktopOriginalPlayerSettingsContext,
    ownerScope: CoroutineScope,
    initialState: DesktopOriginalPlaybackSettingsState,
    val homeSettings: StateFlow<HomeSettings>,
    private val owns: () -> Boolean,
    private val admit: ((() -> Unit) -> Boolean),
    onFailure: (Throwable) -> Unit,
    private val onNotice: (String) -> Unit,
    private val pictureInPicture: () -> Boolean,
    private val playbackLoggedIn: () -> Boolean,
    private val playbackVip: () -> Boolean,
    private val settingChange: (String, String) -> Unit,
) {
    val writeContext: CoroutineContext = Dispatchers.IO + CoroutineExceptionHandler { _, failure ->
        if (failure !is CancellationException && owns()) onFailure(failure)
    }
    private val viewModelScope = bindWriteScope(CoroutineScope(ownerScope.coroutineContext + writeContext))
    fun bindWriteScope(scope: CoroutineScope) = DesktopOriginalPlaybackSettingsWriteScope(context, scope, owns, admit)
    val state = combine(
        DesktopOriginalPlaybackSettingsPreferences.getHwDecode(context),
        DesktopOriginalPlaybackSettingsPreferences.getGestureSensitivity(context),
        DesktopOriginalPlaybackSettingsPreferences.getHeaderBlurEnabled(context),
        DesktopOriginalPlaybackSettingsPreferences.getAndroidNativeLiquidGlassEnabled(context),
        DesktopOriginalPlaybackSettingsPreferences.getAutoSkipOpEd(context),
        DesktopOriginalPlaybackSettingsPreferences.getDoubleTapLike(context),
    ) { values: Array<Any> -> DesktopOriginalPlaybackSettingsState(values[0] as Boolean, values[1] as Float,
        values[2] as Boolean, values[3] as Boolean, values[4] as Boolean, values[5] as Boolean)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, initialState)
    fun pictureInPictureAvailable(): Boolean = owns() && pictureInPicture()
    fun showPictureInPictureUnavailable() { if (owns()) onNotice("当前小窗播放器未初始化，请稍后重试。") }
    fun showSystemBrightnessUnavailable() { if (owns()) onNotice("Windows 当前仅调整播放画面亮度。") }
    fun isPlaybackLoggedIn(): Boolean { if (!owns()) throw CancellationException("Original settings owner retired"); return playbackLoggedIn() }
    fun isPlaybackVip(): Boolean { if (!owns()) throw CancellationException("Original settings owner retired"); return playbackVip() }
    fun logSettingChange(name: String, value: String) { if (owns()) settingChange(name, value) }
'''+ '\n'.join(methods)+'''
}

/** Calls retain original child jobs and original setter bodies. Their final DataStore and
 * mirror permits also check this captured caller Job through the installed operation context. */
internal class DesktopOriginalPlaybackSettingsWriteScope(
    private val context: DesktopOriginalPlayerSettingsContext,
    private val scope: CoroutineScope,
    private val owns: () -> Boolean,
    private val admit: ((() -> Unit) -> Boolean),
) : CoroutineScope {
    override val coroutineContext: CoroutineContext get() = scope.coroutineContext
    fun launch(block: suspend CoroutineScope.() -> Unit): Job = scope.launch {
        val callerScope = this
        com.bilipai.desktop.plugins.DesktopSubscriptionWriteAdmission.withOwned(owns, admit) {
            com.bilipai.desktop.ui.DesktopOriginalPlaybackPreferenceOperation.run(context, owns) { block(callerScope) }
        }
    }
}
'''
    emit('com/bilipai/desktop/settings/DesktopOriginalPlaybackSettingsBindings.kt',bindings)
    selection_data={'mode':'complete-original-selected-member-closure','seeds':seeds,'closure':closure,'originalMembersSha256LF':sha(before),'adaptedMembersSha256LF':sha(body),'platformTransforms':['Context/DataStore/key aliases','sole existing PlayerSettingsStore facade','actual Root display default callback','same Store mirror cache projection'], 'cacheClosure':cclosure}
    # Root installer uses this proof, not guesses about source counts or a past runtime graph.
    write(output/'source-proof.json',json.dumps({'schemaVersion':1,'canonicalCommit':COMMIT,'wholeSourceProofs':proof,'preferenceSelection':selection_data,'pins':pins,'outputs':outputs,'compileExecuted':False,'actualUIExecuted':False},ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'wholeScreens':2,'managerClosureMembers':len(closure),'managerLines':len(before.splitlines()),'outputs':len(outputs),'sourcePins':len(pins),'sourceProofSha256':sha((output/'source-proof.json').read_bytes()),'compileExecuted':False},ensure_ascii=False))
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--repo',type=Path,required=True);p.add_argument('--output',type=Path,required=True);args=p.parse_args();main(args.repo.resolve(),args.output.resolve())
