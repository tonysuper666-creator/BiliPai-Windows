"""Original detail chrome/layout producer; platform interfaces are supplied by Root.
Direct sources are emitted only in --standalone and copied once by the source registry in production.
"""
from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import hashlib
import importlib.util
import json
import re
import subprocess
import sys

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
OUTPUT = HERE / 'generated'
STANDALONE = False
REPO = next(p for p in HERE.parents if (p / '.git').exists())
TAG = 'v0.2.3-alpha.9'
BASE = 'app/src/main/java/com/android/purebilibili/'
DESIGN = 'design-system/src/main/java/com/android/purebilibili/'

def safe(path):
    value = str(Path(path).absolute())
    return Path(value if value.startswith('\\\\?\\') else '\\\\?\\' + value)

def write(path, value):
    safe(path.parent).mkdir(parents=True, exist_ok=True)
    safe(path).write_text(value, encoding='utf-8', newline='\n')

def read(path):
    current = safe(_desktop_canonical_source(REPO, path)).read_text(encoding='utf-8').replace('\r\n','\n')
    if STANDALONE:
        original = subprocess.run(['git','show',TAG+':'+path],cwd=REPO,capture_output=True,check=True).stdout.decode().replace('\r\n','\n')
        assert current == original, path
    return current

def load(name,path):
    spec = importlib.util.spec_from_file_location(name,REPO/path)
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    return module

appearance = load('detail_declarations','desktop/tools/extract-appearance-platform.py')
parser = load('detail_parser','desktop/tools/sync-upstream.py')
inventory = []

def emit(path, text, name=None, mode='selected-platform-adapt', note=''):
    if mode == 'direct' and not STANDALONE:
        return
    source = read(path)
    package = re.search(r'(?m)^package (\S+)',source).group(1)
    target = OUTPUT/package.replace('.','/')/(name or Path(path).name)
    digest = hashlib.sha256(source.encode()).hexdigest()
    write(target,'// Original source '+path+'\n// LF SHA256 '+digest+'\n'+text)
    inventory.append(dict(path=path,sha256=digest,mode=mode,feature='dynamic-detail-container',output=str(target.relative_to(OUTPUT)),note=note))

def select(path,names,imports,name):
    source = read(path)
    package = re.search(r'(?m)^package (\S+)',source).group(1)
    body = appearance.declarations(parser,source,names)
    emit(path,'package '+package+'\n\n'+imports+'\n'+body,name)

def between(source,start,end):
    assert source.count(start)==1,start
    begin=source.index(start); finish=source.index(end,begin)
    return source[begin:finish].rstrip()+'\n'

def detail_layout():
    path=BASE+'feature/dynamic/DynamicDetailScreen.kt'; source=read(path)
    success=between(source,'                val commentTargetKey = remember(state.item)', '                DynamicSubReplyPreviewHost(')
    first=success.index('                val cardContent:')
    last=success.index('                val commentContent:',first)
    success=success[:first]+'''                val cardContent: LazyListScope.() -> Unit = {
                    item {
                        card(effectiveDetailImageLayout) {
                            detailScrollScope.launch {
                                if (useSplitLayout) commentListState.animateScrollToItem(0)
                                else detailListState.animateScrollToItem(1)
                            }
                        }
                    }
                }
'''+success[last:]
    success=success.replace('interactionViewModel','session')
    success=success.replace('com.android.purebilibili.core.store.TokenManager.midCache','currentMid')
    success=success.replace('                        shouldLoadMoreDynamicDetailComments(',
        '                        // A fast desktop request may publish rows before LazyColumn remeasures.\n'
        '                        // Never compare a new raw page with the old skeleton/header geometry.\n'
        '                        desktopDynamicCommentSlotsMeasured(itemCount, comments.size, useSplitLayout) &&\n'
        '                        shouldLoadMoreDynamicDetailComments(')
    success=re.sub(r'android\.widget\.Toast\.makeText\(context,\s*(\w+),\s*android\.widget\.Toast\.LENGTH_SHORT\)\.show\(\)',r'if (platform.isOwned()) platform.showFeedback(\1)',success)
    assert success.count('session.postComment(state.item.id_str, message, images) {') == 1
    success=success.replace('session.postComment(state.item.id_str, message, images) {',
        'session.postComment(state.item.id_str, message, images, onSubmissionCancelled = { onResult(false) }) {', 1)
    assert 'android.widget.' not in success
    success=success.replace('Build.VERSION.SDK_INT >= Build.VERSION_CODES.S','desktopDetailRenderEffectsSupported()')
    success=success.replace('AndroidRenderEffect.createBlurEffect(', 'androidx.compose.ui.graphics.BlurEffect(')
    success=success.replace('Shader.TileMode.CLAMP,','edgeTreatment = androidx.compose.ui.graphics.TileMode.Clamp,')
    success=success.replace(').asComposeRenderEffect()',')')
    thread=between(source,'                DynamicSubReplyPreviewHost(', '                if (showImagePreview && previewImages.isNotEmpty())')
    thread=thread.replace('interactionViewModel','session')
    thread=thread.replace('com.android.purebilibili.core.store.TokenManager.midCache','currentMid')
    thread=re.sub(r'android\.widget\.Toast\.makeText\(\s*context,\s*(\w+),\s*android\.widget\.Toast\.LENGTH_SHORT,?\s*\)\.show\(\)',r'if (platform.isOwned()) platform.showFeedback(\1)',thread)
    assert 'android.widget.' not in thread
    top=between(source,'    AppScaffold(\n        blurContentReady', '            is DynamicDetailUiState.Success -> {')
    top=top.replace('DynamicDetailUiState','DesktopOriginalDynamicDetailUiState')
    top=top.replace('AppScaffold(','ImmersiveAppScaffold(')
    top=top.replace('SettingsManager.DynamicDetailImageLayout','DynamicDetailImageLayout')
    top=top.replace('com.android.purebilibili.core.ui.CutePersonLoadingIndicator()','AdaptiveLoadingIndicator()')
    top=top.replace('retryToken++','onRetry()')
    # Reuse the already frozen raw header/items/composer declarations. This selects
    # the original SCREEN layout, not a second comment renderer or cache model.
    body='''package com.bilipai.desktop.ui
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.dp
import androidx.compose.material3.*
import com.android.purebilibili.core.ui.*
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.core.theme.*
import com.android.purebilibili.core.util.*
import com.android.purebilibili.core.ui.transition.resolvePredictiveBackBlurFrame
import com.android.purebilibili.core.store.DesktopDynamicCardSettings.DynamicDetailImageLayout
import com.android.purebilibili.data.model.response.DynamicItem
import com.android.purebilibili.feature.dynamic.*
import com.android.purebilibili.feature.dynamic.components.*
import com.android.purebilibili.feature.video.ui.components.*
import com.bilipai.desktop.appearance.LocalDesktopStrings
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

'''
    state=appearance.declarations(parser,source,['DynamicDetailUiState'])
    state=state.replace('private sealed','internal sealed').replace('DynamicDetailUiState','DesktopOriginalDynamicDetailUiState')
    body+=state+'''
/** Root owns the detail read/confirmed mutations. This original screen consumes its
 * current raw item, original Reply session and the existing complete CardHost. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun DesktopOriginalDynamicDetailLayout(
    dynamicId: String,
    uiState: DesktopOriginalDynamicDetailUiState,
    session: DesktopOriginalDynamicReplySession,
    defaultDetailImageLayout: DynamicDetailImageLayout,
    liquidGlassEnabled: Boolean,
    currentMid: Long?,
    openCommentRootRpid: Long = 0L,
    openCommentTargetRpid: Long = 0L,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onUserClick: (Long) -> Unit,
    card: @Composable (DynamicDetailImageLayout, onCommentClick: () -> Unit) -> Unit,
) {
    key(dynamicId, session) {
    val platform = LocalDesktopCommentBindings.current
    if (!platform.isOwned() || !session.isOwned()) return
    val strings = LocalDesktopStrings.current
    val screenTitle = strings["dynamic_detail_title"]
    val backLabel = strings["common_back"]
    val retryLabel = strings["common_retry"]
    val detailCommentBackdrop = if (liquidGlassEnabled) rememberLayerBackdrop() else null
    var detailImageLayoutOverrideName by rememberSaveable(dynamicId) { mutableStateOf<String?>(null) }
    val effectiveDetailImageLayout = remember(detailImageLayoutOverrideName,defaultDetailImageLayout) {
        detailImageLayoutOverrideName?.let { name -> DynamicDetailImageLayout.entries.firstOrNull { it.name == name } }
            ?: defaultDetailImageLayout
    }
    val comments by session.comments.collectAsState()
    val commentsLoading by session.commentsLoading.collectAsState()
    val commentsRefreshing by session.commentsRefreshing.collectAsState()
    val commentsRefreshError by session.commentsRefreshError.collectAsState()
    val commentsLoadingMore by session.commentsLoadingMore.collectAsState()
    val commentTotalCount by session.commentTotalCount.collectAsState()
    val commentSortMode by session.dynamicCommentSortMode.collectAsState()
    val subReplyState by session.subReplyState.collectAsState()
    val commentReplyTarget by session.commentReplyTarget.collectAsState()
    var subReplyCoveredBlurProgress by remember { mutableFloatStateOf(0f) }
    val detailListState = rememberLazyListState()
    val commentListState = rememberLazyListState()
    val useSplitLayout = LocalWindowSizeClass.current.shouldUseSplitLayout
    val detailScrollScope = rememberCoroutineScope()
    var showImagePreview by remember { mutableStateOf(false) }
    var previewImages by remember { mutableStateOf<List<String>>(emptyList()) }
    var previewInitialIndex by remember { mutableIntStateOf(0) }
    var previewSourceRect by remember { mutableStateOf<ImagePreviewSourceAnchor?>(null) }
    var previewTextContent by remember { mutableStateOf<ImagePreviewTextContent?>(null) }
    LaunchedEffect(commentsRefreshError) {
        commentsRefreshError?.let { if (platform.isOwned()) platform.showFeedback(it) }
    }
'''+top+'''            is DesktopOriginalDynamicDetailUiState.Success -> {
'''+success+thread+'''
                if (showImagePreview && previewImages.isNotEmpty()) {
                    ImagePreviewDialog(images=previewImages,initialIndex=previewInitialIndex,
                        sourceRect=previewSourceRect?.rect,sourceRects=previewSourceRect?.galleryRects.orEmpty(),
                        sourceCornerRadiusDp=previewSourceRect?.cornerRadiusDp ?: AppShapes.containerCornerDp(ContainerLevel.Field).value,
                        textContent=previewTextContent,onDismiss={showImagePreview=false;previewTextContent=null})
                }
            }
        }
    }
}
}
'''
    body=body.replace('resolveDynamicDetailBottomBarColor(', 'resolveDesktopOriginalReplyBottomBarColor(')
    emit(path,body,'DesktopOriginalDynamicDetailLayout.kt',note='Original screen success layout/top bar. Card action ownership remains the existing Main host. Modal and liquid material are separately declared closure dependencies.')

def thread_container():
    path=DESIGN+'core/ui/AppSheetComponents.kt';body=read(path)
    body=body.replace('import androidx.compose.ui.platform.LocalConfiguration','import com.android.purebilibili.core.util.LocalWindowSizeClass\nimport com.bilipai.desktop.ui.DesktopCommentDialogNavigationHost')
    for line in ['import androidx.compose.ui.platform.LocalView\n','import androidx.navigationevent.findViewTreeNavigationEventDispatcherOwner\n']:
        body=body.replace(line,'')
    begin=body.index('    val owner = LocalView.current.findViewTreeNavigationEventDispatcherOwner()')
    end=body.index('\n}\n',begin)
    body=body[:begin]+'''    DesktopCommentDialogNavigationHost {
        val backState = rememberNavigationEventState(NavigationEventInfo.None)
        NavigationBackHandler(state=backState,isBackEnabled=dismissOnBackPress,onBackCompleted=onDismissRequest)
        content()
    }'''+body[end:]
    body=body.replace('val configuration = LocalConfiguration.current','val configuration = LocalWindowSizeClass.current')
    body=body.replace('configuration.screenWidthDp','configuration.widthDp.value.toInt()')
    body=body.replace('configuration.screenHeightDp','configuration.heightDp.value')
    body=body.replace('                decorFitsSystemWindows = false,\n','')
    body=body.replace('            decorFitsSystemWindows = false,\n','')
    emit(path,body,'DesktopOriginalDetailAppSheetComponents.kt',note='Original two-style neutral M3 host/body/layout/motion. Android LocalView is a fresh task-owned desktop dialog navigation input; actual measured window metrics are used.')
    path=DESIGN+'core/ui/InteractiveOverlayProgressPolicy.kt'
    emit(path,read(path),mode='direct')
    path=BASE+'core/ui/CommentWindowNavigation.kt';body=read(path)
    body=body.replace('import androidx.compose.ui.platform.LocalView','import com.bilipai.desktop.ui.LocalDesktopCommentDialogOwner')
    body=body.replace('import androidx.navigationevent.findViewTreeNavigationEventDispatcherOwner\n','')
    body=body.replace('LocalView.current.findViewTreeNavigationEventDispatcherOwner()','LocalDesktopCommentDialogOwner.current')
    emit(path,body,note='Same owner is provided only within the modal input host, never the underlying route dispatcher.')
    path=BASE+'feature/video/ui/components/SubReplySheet.kt';body=read(path)
    old='''    if (state.visible && state.rootReply != null) {
        val rootReply = state.rootReply'''
    new='''    if (state.visible && state.rootReply != null) {
        key(state.rootReply.rpid) {
        val rootReply = state.rootReply'''
    assert body.count(old)==1;body=body.replace(old,new)
    assert body.rstrip().endswith('    }\n}')
    body=body.rstrip()[:-len('    }\n}')]+'        }\n    }\n}\n'
    emit(path,body,note='Entire source subtree is keyed by real root rpid to retire the old rememberCoroutineScope and derived offsets together.')
    path=BASE+'feature/dynamic/components/DynamicSubReplyPreviewHost.kt';body=read(path)
    body=body.replace('import androidx.compose.ui.geometry.Rect','import androidx.compose.ui.geometry.Rect\nimport com.bilipai.desktop.ui.LocalDesktopCommentBindings\nimport kotlinx.coroutines.ensureActive')
    body=body.replace('    val emoteCatalogSessionKey = DynamicEmoteCatalog.currentSessionKey()',
        '    val platform = LocalDesktopCommentBindings.current\n    if (!platform.isOwned()) return\n    val catalog = platform.emotes\n    val emoteCatalogSessionKey = catalog.currentSessionKey()')
    body=body.replace('DynamicEmoteCatalog.snapshot()','catalog.snapshot()')
    body=body.replace('        emoteMap = DynamicEmoteCatalog.ensureLoaded()',
        '        val loaded = catalog.ensureLoaded()\n        ensureActive()\n        if (platform.isOwned()) emoteMap = loaded')
    emit(path,body,note='Reuses the same existing card/session emote catalog, with cancellation and post-await owner validation.')

def _generate_body():
    path=DESIGN+'core/ui/AdaptiveChrome.kt'
    body=read(path)
    body=body.replace('import androidx.compose.ui.platform.LocalConfiguration','import com.android.purebilibili.core.util.LocalWindowSizeClass')
    for name in ['LocalGlobalWallpaperBackdropVisible','LocalImmersiveTopChromeActive']:
        body,count=re.subn(r'(?m)^val '+name+r' = compositionLocalOf \{ false \}\n', '', body)
        assert count==1,name
    body=body.replace('LocalConfiguration.current.screenWidthDp < 600','LocalWindowSizeClass.current.widthDp < 600.dp')
    emit(path,body,'DesktopOriginalDetailAdaptiveChrome.kt',note='Reuse the two existing Main locals; Android configuration width is measured desktop width.')
    for name in ['AdaptiveScaffoldPolicy.kt','TopReadabilityChromePolicy.kt']:
        path=DESIGN+'core/ui/'+name
        emit(path,read(path),mode='direct')

    path=BASE+'core/util/WindowSizeUtils.kt'; source=read(path)
    body=between(source,'enum class WindowHeightSizeClass','internal fun resolveWindowWidthSizeClass(widthDp: Dp)')
    body+=between(source,'internal fun resolveWindowHeightSizeClass(heightDp: Dp)','internal fun resolveWindowWidthSizeClass(\n')
    body+=between(source,'data class WindowSizeClass(', '/**\n * Returns true only while the current activity')
    emit(path,'package com.android.purebilibili.core.util\n\nimport androidx.compose.runtime.compositionLocalOf\nimport androidx.compose.ui.unit.*\nimport kotlin.math.min\n\n'+body,'DesktopOriginalDetailWindowModels.kt',note='WindowWidthSizeClass and width resolver are reused from Main. Actual host always provides measured width/height; no Android device/posture query.')
    select(BASE+'core/util/HingeLayoutPolicy.kt',['AppHingeFeature'],'import androidx.compose.ui.unit.IntRect\n','DesktopOriginalDetailHingeModel.kt')
    select(BASE+'core/util/FoldableDisplayPolicy.kt',['AppFoldableDisplayRole','AppDisplayNaturalOrientation','AppFoldableDetectionBasis','AppDisplayContext','LARGE_SCREEN_SMALLEST_WIDTH_DP','resolveLargeScreenOrFoldableConfiguration'],'','DesktopOriginalDetailDisplayModel.kt')
    path=BASE+'core/ui/SplitLayout.kt'; emit(path,read(path),mode='direct',note='Reuse sole Main AppAdaptiveSplitLayout low-level renderer.')
    path=BASE+'feature/video/ui/components/CommentThreadDrag.kt'; emit(path,read(path),mode='direct')
    path=BASE+'feature/video/ui/components/VideoCommentSheetHost.kt'
    select(path,['resolveCommentThreadPredictiveBackOffsetY','resolveCommentThreadCoveredBlurProgress'],'','DesktopOriginalDetailThreadPolicies.kt')
    path=BASE+'core/ui/blur/ChromeBackdropSource.kt'; emit(path,read(path),mode='direct')
    path=DESIGN+'core/ui/blur/ProgressiveFade.kt'; emit(path,read(path),mode='direct')
    path=BASE+'feature/home/components/ProgressiveTopChrome.kt'; body=read(path)
    body=body.replace('import android.os.Build','import com.bilipai.desktop.ui.desktopDetailRenderEffectsSupported')
    body=body.replace('    sdkInt: Int = Build.VERSION.SDK_INT,\n','')
    body=body.replace('sdkInt >= Build.VERSION_CODES.TIRAMISU','desktopDetailRenderEffectsSupported()')
    emit(path,body,note='Android SDK gate is replaced by actual Compose RenderEffect.isSupported; the original shader/shape/fade body is unchanged. HWND/GPU is not proved by this gate.')
    path=BASE+'core/ui/ImmersiveAppScaffold.kt'; body=read(path)
    body=body.replace('import android.os.Build','import com.bilipai.desktop.ui.LocalDesktopDetailForeground\nimport com.bilipai.desktop.ui.desktopDetailRenderEffectsSupported\nimport com.bilipai.desktop.ui.rememberDesktopDetailHazeState\nimport dev.chrisbanes.haze.hazeSource')
    body=body.replace('import com.android.purebilibili.core.ui.blur.hazeSourceCompat\n','')
    body=body.replace('import com.android.purebilibili.core.ui.blur.rememberRecoverableHazeState\n','')
    body=body.replace('import com.android.purebilibili.core.ui.blur.shouldAllowRenderEffectBackedHazeEffect\n','')
    body=body.replace('    val lowBlurBudget = isLowBlurBudgetForced()','    val lowBlurBudget = isLowBlurBudgetForced()\n    val foreground = LocalDesktopDetailForeground.current')
    body=body.replace('shouldAllowRenderEffectBackedHazeEffect(Build.VERSION.SDK_INT)','foreground && desktopDetailRenderEffectsSupported()')
    body=body.replace('rememberRecoverableHazeState(initialBlurEnabled = true)','rememberDesktopDetailHazeState()')
    body=body.replace(') && !lowBlurBudget',') && !lowBlurBudget && foreground')
    body=body.replace('val hazeReady = hazeState != null &&', 'val hazeReady = foreground && hazeState != null &&')
    body=body.replace('progressiveAvailable = backdrop != null,', 'progressiveAvailable = progressive && backdrop != null,')
    body=body.replace('Modifier.hazeSourceCompat(hazeState)','Modifier.hazeSource(hazeState)')
    emit(path,body,note='Android BackgroundManager/API recreation becomes required actual desktop foreground binding. Original readiness/priorities/background/sample separation are retained.')
    path=BASE+'core/util/WindowSizeUtils.kt'; source=read(path)
    body=between(source,'fun Modifier.responsiveContentWidth(', '/**\n',)
    emit(path,'package com.android.purebilibili.core.util\nimport androidx.compose.ui.*\nimport androidx.compose.ui.unit.*\nimport androidx.compose.foundation.layout.*\n\n'+body,'DesktopOriginalDetailResponsiveWidth.kt')
    path=BASE+'feature/dynamic/DynamicLayoutPolicy.kt'
    select(path,['resolveDynamicFeedMaxWidth'],'import androidx.compose.ui.unit.*\n','DynamicLayoutPolicy.kt')
    path=BASE+'feature/video/ui/components/BottomInputBar.kt'
    select(path,['shouldUseFloatingLiquidBottomInputBar','resolveBottomInputBarContentBottomPadding'],
        'import androidx.compose.ui.unit.*\nimport com.android.purebilibili.core.store.resolveGlobalLiquidGlassReuseEnabled\n','DesktopOriginalDetailInputPadding.kt')
    path=BASE+'core/store/HomeSettingsUiPresetPolicy.kt'
    select(path,['resolveGlobalLiquidGlassReuseEnabled'],'','DesktopOriginalDetailLiquidReusePolicy.kt')
    path=BASE+'core/ui/transition/PredictiveBackBackgroundPolicy.kt'
    source=read(path)
    body=appearance.declarations(parser,source,['PREDICTIVE_BACK_MAX_BLUR_RADIUS_PX_DARK','PREDICTIVE_BACK_MAX_BLUR_RADIUS_PX_LIGHT','PREDICTIVE_BACK_BLUR_QUANTUM_PX','PREDICTIVE_BACK_LIGHT_SEPARATION_TINT_ALPHA','PredictiveBackBlurFrame','resolvePredictiveBackMaxBlurRadiusPx','resolvePredictiveBackSeparationTintAlpha','resolvePredictiveBackBlurFrame','quantizePredictiveBackBlurRadius'])
    body=body.replace('    sdkInt: Int = Build.VERSION.SDK_INT,\n','')
    body=body.replace('sdkInt >= Build.VERSION_CODES.S','desktopDetailRenderEffectsSupported()')
    emit(path,'package com.android.purebilibili.core.ui.transition\nimport com.android.purebilibili.core.ui.adaptive.MotionTier\nimport com.bilipai.desktop.ui.desktopDetailRenderEffectsSupported\nimport kotlin.math.roundToInt\n\n'+body,'DesktopOriginalDetailPredictiveBlurPolicy.kt',note='Original quantum/radius/math; Android API gate becomes actual Compose support check.')
    detail_layout()
    thread_container()
    if STANDALONE:
        write(OUTPUT/'original-source-inventory.json',json.dumps(inventory,indent=2)+'\n')

def generate(repo, output, *, standalone=False):
    global REPO, OUTPUT, STANDALONE, appearance, parser, inventory
    REPO = Path(repo)
    OUTPUT = Path(output)
    STANDALONE = standalone
    appearance = load('detail_declarations', 'desktop/tools/extract-appearance-platform.py')
    parser = load('detail_parser', 'desktop/tools/sync-upstream.py')
    inventory = []
    _generate_body()

if __name__ == '__main__':
    import argparse
    arguments = argparse.ArgumentParser()
    arguments.add_argument('--repo', type=Path, required=True)
    arguments.add_argument('--output', type=Path, required=True)
    arguments.add_argument('--standalone', action='store_true')
    options = arguments.parse_args()
    generate(options.repo, options.output, standalone=options.standalone)

