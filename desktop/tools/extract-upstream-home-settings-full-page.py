from pathlib import Path
import ast, difflib, hashlib, importlib.util, json, re, subprocess, sys, textwrap

sys.stdout.reconfigure(encoding='utf-8')
import argparse
args=argparse.ArgumentParser(description="Whole fixed v0.2.5 HOME branch and original preference recipes over mandatory actual Root leaves")
args.add_argument('--repo',type=Path,required=True);args.add_argument('--output',type=Path,required=True)
cli=args.parse_args();REPO=cli.repo.resolve();OUT=cli.output.resolve()
COMMIT='79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40'
def wide(p): return Path('\\\\?\\'+str(p.absolute()))
def read(p): return wide(p).read_bytes().decode('utf-8').replace('\r\n','\n')
def write(p,s):
    wide(p.parent).mkdir(parents=True,exist_ok=True)
    wide(p).write_bytes(s.encode('utf-8') if isinstance(s,str) else s)
def sha(s): return hashlib.sha256(s.encode() if isinstance(s,str) else s).hexdigest()
def js(p,v): write(p,json.dumps(v,ensure_ascii=False,indent=2)+'\n')
sys.path.insert(0,str(REPO/'desktop/tools'))
def load(name,p):
    spec=importlib.util.spec_from_file_location(name,wide(p));m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
media=load('home_media',REPO/'desktop/tools/extract-upstream-media.py')
parser=media.parser_for(REPO)
pins={}
from v025_source_paths import canonical_source
from v033_home_refresh_undo import advance_source as advance_home_undo
SOURCE_PINS = {'app/src/main/java/com/android/purebilibili/feature/settings/screen/AppearanceSettingsScreen.kt': {'sha256Raw': '91d372c45a2d2c5b784c53b9890db4a67f361b41841feab08a44d2855c5e4ace', 'sha256LF': '91d372c45a2d2c5b784c53b9890db4a67f361b41841feab08a44d2855c5e4ace'}, 'app/src/main/java/com/android/purebilibili/core/store/SettingsManager.kt': {'sha256Raw': '5799bb8802992594ae9494b48d6357ee00ecc7be03d97ed0dcb5fede7774328c', 'sha256LF': '5799bb8802992594ae9494b48d6357ee00ecc7be03d97ed0dcb5fede7774328c'}, 'app/src/main/java/com/android/purebilibili/feature/settings/SettingsViewModel.kt': {'sha256Raw': 'eda6a70ef0e32386580c96badf27ea808d3a29af08acf2628e3439aecf5ad787', 'sha256LF': 'eda6a70ef0e32386580c96badf27ea808d3a29af08acf2628e3439aecf5ad787'}, 'app/src/main/java/com/android/purebilibili/core/store/BackToTopSettingsStore.kt': {'sha256Raw': '7cfc48da9652fa33262fb1ce4458b84ea442a0e34733f846836b21a9dc73c97e', 'sha256LF': '7cfc48da9652fa33262fb1ce4458b84ea442a0e34733f846836b21a9dc73c97e'}, 'app/src/main/java/com/android/purebilibili/feature/settings/policy/AppearanceSettingsLayoutPolicy.kt': {'sha256Raw': 'e43f6ef01194630951fb53bcd2064fdc067db3a14c0d5d9b60a0654d0d36ad94', 'sha256LF': 'e43f6ef01194630951fb53bcd2064fdc067db3a14c0d5d9b60a0654d0d36ad94'}, 'app/src/main/java/com/android/purebilibili/feature/settings/SettingsSearchFocusPolicy.kt': {'sha256Raw': '8813c9c1e764122542c3a9dd826fbd069d8f25896e56b21c456ab832246ba1a8', 'sha256LF': '8813c9c1e764122542c3a9dd826fbd069d8f25896e56b21c456ab832246ba1a8'}, 'app/src/main/java/com/android/purebilibili/feature/profile/SplashWallpaperPickerSheet.kt': {'sha256Raw': '5f12de49fb65f3aea34800fba8ae3512a549835c48b63281be85a766ec89eb1b', 'sha256LF': '5f12de49fb65f3aea34800fba8ae3512a549835c48b63281be85a766ec89eb1b'}, 'app/src/main/java/com/android/purebilibili/feature/home/HomeCategoryPage.kt': {'sha256Raw': 'cfc3ff16df87d7b8935fd21536499c43bafd724865e316f14275b38ad1887ad2', 'sha256LF': 'cfc3ff16df87d7b8935fd21536499c43bafd724865e316f14275b38ad1887ad2'}, 'app/src/main/java/com/android/purebilibili/feature/home/components/HomeHeroCarousel.kt': {'sha256Raw': 'eb5ec33451f5e8ddca271b15ffd5a88739796ad7f7b6566b8b60c4d9380b2637', 'sha256LF': 'eb5ec33451f5e8ddca271b15ffd5a88739796ad7f7b6566b8b60c4d9380b2637'}, 'app/src/main/java/com/android/purebilibili/feature/home/HomeWallpaperBackdrop.kt': {'sha256Raw': 'd966dbd9074fdf29b97785ebf7a724e5635e8098abf2f44c9d2ccf31eb38d2f6', 'sha256LF': 'd966dbd9074fdf29b97785ebf7a724e5635e8098abf2f44c9d2ccf31eb38d2f6'}, 'app/src/main/res/drawable/ms_edit_note_24.xml': {'sha256Raw': '4cbd128be9fa8ac5a53bf7c5a08948e0f847af99d7943b66840c26653b73ebef', 'sha256LF': '4cbd128be9fa8ac5a53bf7c5a08948e0f847af99d7943b66840c26653b73ebef'}, 'app/src/main/res/drawable/ms_format_list_bulleted_24.xml': {'sha256Raw': 'a654dae477269b1387817471f9b9c0dc1624b3662b1a933e5e09414be12567df', 'sha256LF': 'a654dae477269b1387817471f9b9c0dc1624b3662b1a933e5e09414be12567df'}, 'app/src/main/res/drawable/ms_keyboard_arrow_right_24.xml': {'sha256Raw': 'd3f53b271fdb2150775b4252bf46139c3ab5ca872802372a1089e6e1dd6f2a35', 'sha256LF': 'd3f53b271fdb2150775b4252bf46139c3ab5ca872802372a1089e6e1dd6f2a35'}, 'app/src/main/java/com/android/purebilibili/feature/settings/ui/SettingsSections.kt': {'sha256Raw': 'e306f73ee7cf49877248eb01063816d50f43f61b47c9d41a52ab372917de54f7', 'sha256LF': 'e306f73ee7cf49877248eb01063816d50f43f61b47c9d41a52ab372917de54f7'}, 'app/src/main/java/com/android/purebilibili/feature/profile/ProfileViewModel.kt': {'sha256Raw': 'b8f658eef9e4ac99479479b51b14a9d38d17f6bc7a669694e0b6ee4f02d9e411', 'sha256LF': 'b8f658eef9e4ac99479479b51b14a9d38d17f6bc7a669694e0b6ee4f02d9e411'}}
manifest=json.loads(read(REPO/'desktop/upstream-sources.json'))
assert manifest['upstreamCommit']==COMMIT, 'Whole Home source commit drift'
def original(p):
    physical=wide(canonical_source(REPO,p)).read_bytes()
    lf=physical.replace(b'\r\n',b'\n')
    expected=SOURCE_PINS[p]
    assert sha(lf)==expected['sha256LF'], 'Whole Home fixed source SHA drift: '+p
    pins[p]={**expected,'physicalRawSha256':sha(physical)}
    return lf.decode()
P='app/src/main/java/com/android/purebilibili/'
AP=P+'feature/settings/screen/AppearanceSettingsScreen.kt'
SM=P+'core/store/SettingsManager.kt'
VM=P+'feature/settings/SettingsViewModel.kt'
BACK=P+'core/store/BackToTopSettingsStore.kt'
LAY=P+'feature/settings/policy/AppearanceSettingsLayoutPolicy.kt'
screen=original(AP);manager=original(SM);vm=original(VM);back=original(BACK);layout=original(LAY)
manager, v033_home_undo_selection = advance_home_undo(REPO, SM, manager)
for p in [P+'feature/settings/SettingsSearchFocusPolicy.kt',P+'feature/profile/SplashWallpaperPickerSheet.kt',P+'feature/home/HomeCategoryPage.kt',P+'feature/home/components/HomeHeroCarousel.kt',P+'feature/home/HomeWallpaperBackdrop.kt']:
    try: original(p)
    except subprocess.CalledProcessError: pass

lines=screen.splitlines(True)
prelude=''.join(lines[265:268])+''.join(lines[274:279])+''.join(lines[280:282])+'''    LaunchedEffect(focusRequest?.token, isTablet) {
        val request = focusRequest ?: return@LaunchedEffect
        if (request.target != SettingsSearchTarget.HOME_FEED) return@LaunchedEffect
        val index = resolveHomeSettingsScrollIndex(request.focusId) ?: return@LaunchedEffect
        listState.animateScrollToItem(index)
        SettingsSearchFocusController.clear(request.token)
    }
'''+''.join(lines[429:523])+''.join(lines[527:533])
branch=''.join(lines[1357:1932])
assert branch.startswith('        if (contentMode == AppearanceSettingsContentMode.HOME) {')
assert branch.rstrip().endswith('}') and branch.count('SplashWallpaperPickerSheet(')==1
adaptations=[]
def replace(s,a,b,label):
    n=s.count(a);assert n>0,(label,a)
    adaptations.append({'label':label,'before':a,'after':b,'count':n})
    return s.replace(a,b)
adapt=replace(branch,'AppearanceSettingsContentMode.HOME','DesktopOriginalHomeSettingsMode.HOME','Required whole HOME mode identity')
adapt=replace(adapt,'DisplayMode.entries','DesktopOriginalHomeDisplayMode.entries','Reuse original DisplayMode declaration with distinct generated owner')
adapt=replace(adapt,'scope.launch {','actions.launch {','Required page-owned original preference operation')
adapt=replace(adapt,'ImageRequest.Builder(context)','ImageRequest.Builder(imageContext)','Actual existing Coil PlatformContext')
adapt=replace(adapt,'android.widget.Toast.makeText(context, "已恢复回顶按钮默认位置", android.widget.Toast.LENGTH_SHORT).show()','afterCommitNotice("已恢复回顶按钮默认位置")','Original notice after actual preference commit')
adapt=replace(adapt,'BackToTopSettingsStore.resetCustomOffset(context)','BackToTopSettingsStore.resetCustomOffset(context)\n                                        afterCommitResetBackToTopOffset(backToTop)','Existing original Root offset UI cache resets only after actual canonical commit')
converter=load('home_vectors',REPO/'desktop/tools/extract-upstream-settings-search.py')
sharedSymbols=set(converter.symbol_names(REPO));extraSymbols=[]
for name in sorted(set(re.findall(r'R\.drawable\.(\w+)',branch))):
    original('app/src/main/res/drawable/'+name+'.xml')
    if name in sharedSymbols: owner='DesktopSettings'
    elif name=='ms_keyboard_arrow_right_24': owner='DesktopSettingsCategory'
    else: owner='DesktopOriginalHomeSettings';extraSymbols.append(name)
    adapt=replace(adapt,'com.android.purebilibili.feature.settings.rememberMaterialSymbol(com.android.purebilibili.R.drawable.'+name+')',owner+'Vectors.vector('+owner+'Symbols.'+name+')','Exact original XML resource '+name)
preludeAdapt=prelude.replace('BackToTopSettingsStore\n        .isEnabled(context)','backToTop.backToTopEnabled')
preludeAdapt=preludeAdapt.replace('DEFAULT_BACK_TO_TOP_BUTTON_ENABLED','backToTop.initialBackToTopEnabled()')
preludeAdapt=preludeAdapt.replace('BackToTopSettingsStore.getCustomOffsetDp(context)','backToTop.backToTopOffset')
preludeAdapt=preludeAdapt.replace('BackToTopSettingsStore.getCachedOffsetDp()','backToTop.initialBackToTopOffset()')
preludeAdapt=preludeAdapt.replace('LocalWindowSizeClass.current','actualWindowSizeClass')
imports='''@file:OptIn(androidx.compose.animation.ExperimentalAnimationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.android.purebilibili.feature.settings
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.PlatformContext
import coil3.request.crossfade
import com.android.purebilibili.core.store.*
import com.android.purebilibili.core.ui.*
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.core.ui.animation.EntranceGroup
import com.android.purebilibili.core.ui.animation.entrance
import com.android.purebilibili.core.theme.iOSBlue
import com.android.purebilibili.core.theme.iOSTeal
import com.android.purebilibili.core.util.WindowSizeClass
import com.android.purebilibili.feature.settings.ui.LocalSettingsTopContentPadding
import com.bilipai.desktop.ui.DesktopFavoritePreferences
import com.bilipai.desktop.ui.DesktopOriginalHomeSettingsActions
import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext
import com.bilipai.desktop.settings.DesktopSettingsVectors
import com.bilipai.desktop.settings.DesktopSettingsSymbols
import com.bilipai.desktop.settings.DesktopSettingsCategoryVectors
import com.bilipai.desktop.settings.DesktopSettingsCategorySymbols
import com.android.purebilibili.core.store.DesktopOriginalHomeSettingsManager as SettingsManager
import com.android.purebilibili.core.store.DesktopOriginalHomeBackToTopSettings as BackToTopSettingsStore
'''
body=imports+'''
private enum class DesktopOriginalHomeSettingsMode { HOME }
/** Entire original HOME conditional body. Root supplies the existing Home decoder and actual page-owned effects. */
@Composable
internal fun DesktopOriginalHomeSettingsContent(
    modifier: Modifier = Modifier,
    state: HomeSettings,
    viewModel: DesktopOriginalHomeSettingsViewModel,
    context: DesktopOriginalPlayerSettingsContext,
    imageContext: PlatformContext,
    actions: DesktopOriginalHomeSettingsActions,
    backToTop: DesktopFavoritePreferences,
    actualWindowSizeClass: WindowSizeClass,
) {
    val contentMode = DesktopOriginalHomeSettingsMode.HOME
'''+preludeAdapt+'\n'+''.join(lines[582:590])+adapt+'''    }
    }
}
'''
display=media.function(vm,'setDisplayMode',parser)
vmMethods=[media.function(vm,n,parser) for n in ['setDisplayMode','setGridColumnCount','setHomeFeedCardWidthPreset','toggleHeaderCollapse']]
vmAdapt=[m.replace('viewModelScope.launch {','actions.launch {').replace('android.content.Context.MODE_PRIVATE','DesktopOriginalPlayerSettingsContext.MODE_PRIVATE') for m in vmMethods]
enum=vm[vm.index('enum class DisplayMode('):].strip().replace('enum class DisplayMode(','internal enum class DesktopOriginalHomeDisplayMode(')
vmBody='''package com.android.purebilibili.feature.settings
import com.android.purebilibili.core.store.HomeFeedCardWidthPreset
import com.android.purebilibili.core.store.DesktopOriginalHomeSettingsManager as SettingsManager
import com.bilipai.desktop.ui.DesktopOriginalHomeSettingsActions
import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext
/** Original four Home UI actions; only Android VM scope/context become required Root page leaves. */
internal class DesktopOriginalHomeSettingsViewModel(
    private val context: DesktopOriginalPlayerSettingsContext,
    private val actions: DesktopOriginalHomeSettingsActions,
) {
'''+textwrap.indent('\n\n'.join(vmAdapt),'    ')+'\n}\n\n'+enum+'\n'
methods=sorted(set(re.findall(r'SettingsManager(?:\s*\n\s*)?\.([A-Za-z_]\w*)\(',preludeAdapt+'\n'+adapt+'\n'+'\n'.join(vmMethods))))
methods+=['setHomeWallpaperUri']
methods+=['setSplashRandomPoolUris']
# setHeaderCollapseEnabled delegates to this entire original member.
methods+=['setHomeHeaderCollapseMode']
methods+=['getHomeRefreshUndoVisible','setHomeRefreshUndoVisible']
functions=[media.function(manager,n,parser) for n in methods]
keys=sorted(set(re.findall(r'\bKEY_[A-Z_]+\b','\n'.join(functions))))
keydefs=[]
for k in keys:
    m=re.search(r'(?m)^\s*private val '+k+r'\s*=\s*(?:boolean|int|float|string|long)PreferencesKey\([^\n]+\)',manager)
    assert m,k
    keydefs.append(textwrap.dedent(m.group()).strip())
for name in sorted(set(re.findall(r'\bSPLASH_PREFS[A-Z_]*\b','\n'.join(functions)))):
    m=re.search(r'(?m)^\s*(?:private\s+)?const val '+name+r'\s*=\s*[^\n]+',manager)
    assert m,name
    keydefs.append(textwrap.dedent(m.group()).strip())
aliases='''package com.android.purebilibili.core.store
import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext as Context
import com.bilipai.desktop.ui.playerBooleanPreferencesKey as booleanPreferencesKey
import com.bilipai.desktop.ui.playerIntPreferencesKey as intPreferencesKey
import com.bilipai.desktop.ui.playerFloatPreferencesKey as floatPreferencesKey
import com.bilipai.desktop.ui.playerStringPreferencesKey as stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
'''
facade=aliases+'\n/** Selected original Home getters/setters over the existing Root global document. */\ninternal object DesktopOriginalHomeSettingsManager {\n'+textwrap.indent('\n'.join(keydefs)+'\n\n'+'\n\n'.join(functions),'    ')+'\n}\n'
# The original facade caller also depends on this original top-level pure policy.
# The sole Home settings output owns it; no other current generated/main owner exists.
headerCollapsePolicy=media.function(manager,'resolveHomeHeaderCollapseModeForTopBarHide',parser)
facade+='\n'+headerCollapsePolicy+'\n'
backSet=media.function(back,'setEnabled',parser)
backReset=media.function(back,'resetCustomOffset',parser)
backResetAdapt=backReset.replace('    cachedOffset = Pair(DEFAULT_BACK_TO_TOP_OFFSET_X_DP, DEFAULT_BACK_TO_TOP_OFFSET_Y_DP)\n','    // Root owns the existing BackToTop cached-offset projection; its observed committed snapshot updates that cache.\n')
backFacade=aliases+'''\n/** Original persisted enabled/reset recipes; the required Root BackToTop projection remains the only UI cache. */
internal object DesktopOriginalHomeBackToTopSettings {
    private val enabledKey = booleanPreferencesKey("back_to_top_button_enabled")
    private val offsetXDpKey = floatPreferencesKey("back_to_top_button_offset_x_dp")
    private val offsetYDpKey = floatPreferencesKey("back_to_top_button_offset_y_dp")
'''+textwrap.indent(backSet+'\n\n'+backResetAdapt,'    ')+'\n}\n'
gen=OUT/'generated'
generated={
 'com/android/purebilibili/feature/settings/DesktopOriginalHomeSettingsContent.kt':body,
 'com/android/purebilibili/feature/settings/DesktopOriginalHomeSettingsViewModel.kt':vmBody,
 'com/android/purebilibili/core/store/DesktopOriginalHomeSettingsManager.kt':facade,
 'com/android/purebilibili/core/store/DesktopOriginalHomeBackToTopSettings.kt':backFacade,
 'com/android/purebilibili/feature/settings/AppearanceSettingsLayoutPolicy.kt':layout,
}
if extraSymbols:
    converter.symbol_names=lambda _:extraSymbols
    generated['com/bilipai/desktop/settings/DesktopOriginalHomeSettingsVectors.kt']=converter.vectors(REPO).replace('DesktopSettingsSymbols','DesktopOriginalHomeSettingsSymbols').replace('DesktopSettingsVectors','DesktopOriginalHomeSettingsVectors')
    generated['com/android/purebilibili/feature/settings/DesktopOriginalHomeSettingsContent.kt']=body.replace('import com.bilipai.desktop.settings.DesktopSettingsSymbols','import com.bilipai.desktop.settings.DesktopOriginalHomeSettingsVectors\nimport com.bilipai.desktop.settings.DesktopOriginalHomeSettingsSymbols\nimport com.bilipai.desktop.settings.DesktopSettingsSymbols')
sectionsPath=P+'feature/settings/ui/SettingsSections.kt';sections=original(sectionsPath)
entry=''.join(sections.splitlines(True)[444:462])
wrapper=media.function(sections,'SettingsRootCategoryEntranceSection',parser)
generated['com/android/purebilibili/feature/settings/DesktopOriginalHomeSettingsCategoryEntry.kt']='''package com.android.purebilibili.feature.settings
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.android.purebilibili.core.ui.animation.entrance
@Composable
internal fun DesktopOriginalHomeSettingsCategoryEntry(onHomeClick: () -> Unit) {
'''+entry.replace('SettingsRootCategoryEntranceSection {','DesktopOriginalHomeSettingsEntranceSection {').replace('actions.onHomeClick','onHomeClick')+'\n}\n\n@Composable\n'+wrapper.replace('SettingsRootCategoryEntranceSection(','DesktopOriginalHomeSettingsEntranceSection(')+'\n'
for rel,s in generated.items():write(gen/rel,s)
picker=load('home_whole_picker',REPO/'desktop/tools/v025_home_wallpaper_picker.py')
pickerGenerated,pickerProof=picker.generate(REPO,pins)
for rel,s in pickerGenerated.items():write(gen/rel,s)
generated.update(pickerGenerated)
js(OUT/'whole-picker-source-inverse.json',pickerProof)
js(OUT/'home-source-contract.json',{'upstreamCommit':COMMIT,'sourcePins':pins,'originalHomeBranchLines':[1358,1932],
 'wholeHomeConditionalBody':True,'wholeSharedAppearanceScreen':False,'v033HomeUndoSelection':v033_home_undo_selection,'originalMethods':methods,'originalVmMethods':['setDisplayMode','setGridColumnCount','setHomeFeedCardWidthPreset','toggleHeaderCollapse'],
 'branchAdaptations':adaptations,'rawOriginalBranchSha256LF':sha(branch),'preparedBranchSha256LF':sha(adapt),
 'runtimeCompiled':False,'actualHomeConsumerUiVerified':False,'actualWallpaperPickerVerified':False})
inverse=adapt
for row in reversed(adaptations):
    assert inverse.count(row['after'])==row['count'],row
    inverse=inverse.replace(row['after'],row['before'])
assert inverse==branch
js(OUT/'inverse-check.json',{'wholeHomeConditionalBodyExact':inverse==branch,'originalSha256LF':sha(branch),'inverseSha256LF':sha(inverse),
    'vmBodiesExactAfterScopeAndContextInverse':all(a.replace('actions.launch {','viewModelScope.launch {').replace('DesktopOriginalPlayerSettingsContext.MODE_PRIVATE','android.content.Context.MODE_PRIVATE')==b for a,b in zip(vmAdapt,vmMethods)),
    'settingsManagerSelectedWholeMethodsRetained':len(methods),
    'wholeOriginalHeaderCollapsePolicySha256LF':sha(headerCollapsePolicy),'runtimeEvidence':False})
print(json.dumps({'generated':len(generated),'homeWholeBranchLines':len(branch.splitlines()),'wholeOriginalSettingsMethods':len(methods),'inverse':'PASS','runtimeEvidence':False},ensure_ascii=False))
