from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import argparse, hashlib, importlib.util, json, os, textwrap

TARGET='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
APP='app/src/main/java/com/android/purebilibili/navigation/AppNavigation.kt'
PAGER='app/src/main/java/com/android/purebilibili/navigation/MainBottomPagerState.kt'
TOP='app/src/main/java/com/android/purebilibili/navigation/AppTopLevelNavigationPolicy.kt'
PREFIX=chr(92)*2+'?'+chr(92)
def safe(p):
    s=os.path.abspath(p); return Path(s if s.startswith(PREFIX) else PREFIX+s)
def digest(s): return hashlib.sha256(s.encode()).hexdigest()
def load(path):
    spec=importlib.util.spec_from_file_location('root_nav_tokens',safe(path))
    m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
def balanced(parser,raw,start):
    tokens=parser.kotlin_tokens(raw)
    opened=next((s for t,s,e in tokens if s>=start and t=='{'))
    depth=0
    for token,s,e in tokens:
        if s<opened: continue
        depth+=(token=='{')-(token=='}')
        if depth==0:return raw[start:e]
    raise ValueError('unclosed selected original block')
def generate(repo,out,standalone=False):
    parser=load(repo/'desktop/tools/sync-upstream.py')
    raw=safe(_desktop_canonical_source(repo, APP)).read_text(encoding='utf-8').replace('\r\n','\n')
    pager=safe(_desktop_canonical_source(repo, PAGER)).read_text(encoding='utf-8').replace('\r\n','\n')
    top=safe(_desktop_canonical_source(repo, TOP)).read_text(encoding='utf-8').replace('\r\n','\n')
    receipt={'target':TARGET,'sources':[
        {'path':APP,'sha256LF':digest(raw),'mode':'existing-feature-merge'},
        {'path':PAGER,'sha256LF':digest(pager),'mode':'direct'},
        {'path':TOP,'sha256LF':digest(top),'mode':'existing-feature-merge'}], 'outputs':[], 'selected':[]}
    def emit(rel,body):
        p=out/rel;safe(p.parent).mkdir(parents=True,exist_ok=True)
        safe(p).write_text(body,encoding='utf-8',newline='\n')
        receipt['outputs'].append({'path':rel,'sha256LF':digest(body)})
    origin='// Original source '+APP+'\n// LF SHA256 '+digest(raw)+'\n'
    keyhelper=balanced(parser,raw,raw.index('fun bottomPagerNavKeyForItem('))
    keyhelper=textwrap.dedent(keyhelper)
    original_keyhelper=keyhelper
    keyhelper=keyhelper.replace('fun bottomPagerNavKeyForItem(', 'internal fun bottomPagerNavKeyForItem(', 1)
    start=raw.index('BiliPaiNavEntryContentRole.MAIN_HOST -> {')
    block=balanced(parser,raw,start)
    inner=block[block.index('{')+1:block.rindex('}')]
    body=textwrap.dedent(inner).strip()
    receipt['selected'].append({'name':'MAIN_HOST','sha256OriginalBlock':digest(block)})
    receipt['selected'].append({'name':'bottomPagerNavKeyForItem','sha256OriginalBlock':digest(original_keyhelper),'adaptation':'function-scope to internal top-level'})
    header='''package com.android.purebilibili.navigation

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.ui.Modifier
import com.android.purebilibili.core.ui.LocalBottomBarVisible
import com.android.purebilibili.core.ui.transition.LocalVideoCardSharedElementSourceRoute
import com.android.purebilibili.feature.home.components.BottomNavItem
import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.android.purebilibili.navigation3.toLegacyRoute

'''
    signature='''@Composable internal fun DesktopOriginalMainHostPager(
    visibleBottomBarItems: List<BottomNavItem>,
    bottomPagerState: PagerState,
    mainBottomPagerState: MainBottomPagerState,
    bottomPagerContentReady: Boolean,
    bottomPagerSaveableStateHolder: SaveableStateHolder,
    resolveMainHostBottomBarVisible: () -> Boolean,
    RenderNavigationContent: @Composable (key: BiliPaiNavKey, isBottomPagerPageActive: Boolean, isBottomPagerHosted: Boolean) -> Unit,
) {
'''
    # Calling a required Kotlin function-typed port cannot use named argument syntax. Values and
    # entire original MAIN_HOST measure/preload/saveable/source-route logic remain unmodified.
    replacements=[('key = pageKey,','pageKey,'),
        ('isBottomPagerPageActive = page == bottomPagerState.settledPage,','page == bottomPagerState.settledPage,'),
        ('isBottomPagerHosted = true,','true,')]
    adapted=body
    for before,after in replacements:
        assert adapted.count(before)==1;(adapted:=adapted.replace(before,after))
    restored=adapted
    for before,after in reversed(replacements):restored=restored.replace(after,before,1)
    assert restored==body
    emit('com/android/purebilibili/navigation/DesktopOriginalMainHostPager.kt',
        origin+header+keyhelper+'\n\n'+signature+textwrap.indent(adapted,'    ')+'\n}\n')
    # The original per-family open IDs preserve supplied IDs and real monotonic clock ordering.
    start=raw.index('val sessionScopedKey = when (key)')
    selected=balanced(parser,raw,start)
    adapted=selected.replace('val sessionScopedKey = when (key)','return when (key)',1)
    adapted=adapted.replace('SystemClock.uptimeMillis()','DesktopHomeClock.elapsedRealtime()')
    receipt['selected'].append({'name':'sessionScopedKey','sha256OriginalBlock':digest(selected)})
    emit('com/bilipai/desktop/ui/DesktopOriginalRootSessionKeys.kt', origin+'''package com.bilipai.desktop.ui
import com.android.purebilibili.navigation3.BiliPaiNavKey

/** Exact stable AppNavigation session-scoped key construction; Root window lifetime. */
internal class DesktopOriginalRootSessionKeys {
    private var lastVideoDetailOpenId = 0L
    private var lastLiveAreaDetailOpenId = 0L
    private var lastSearchOpenId = 0L
    fun decorate(key: BiliPaiNavKey): BiliPaiNavKey {
'''+textwrap.indent(textwrap.dedent(adapted),'        ')+'\n    }\n}\n')
    if standalone:
        emit('com/android/purebilibili/navigation/MainBottomPagerState.kt',pager)
    nav_parser=load(repo/'desktop/tools/extract-upstream-navigation3-host.py')
    names=['BottomPagerRenderBudget','BOTTOM_BAR_MAX_VISIBLE_ITEMS','BOTTOM_PAGER_MAX_PRELOAD_DISTANCE',
        'resolveBottomPagerPageForRoute','resolveBottomPagerItemForPage',
        'shouldResetNavigation3BackStackForBottomPager','resolveVisibleBottomBarItems',
        'resolveActiveBottomTabRoute','shouldShowBottomBarForNavigation','shouldMountSidebarForNavigation',
        'resolveBottomPagerSaveableStateKey','resolveBottomPagerBeyondViewportPageCount',
        'resolveBottomPagerRenderBudget','shouldEnableBottomPagerUserScroll','shouldComposeBottomPagerPage']
    body=nav_parser.declarations(parser,top,names)
    emit('com/android/purebilibili/navigation/DesktopOriginalRootPagerPolicy.kt',
        '// Original source '+TOP+'\n// LF SHA256 '+digest(top)+'\npackage com.android.purebilibili.navigation\n\n'
        'import com.android.purebilibili.feature.home.components.BottomNavItem\n'
        'import com.android.purebilibili.navigation3.BiliPaiNavKey\n'
        'import com.android.purebilibili.navigation3.toLegacyRoute\n\n'+body)
    receipt['selected'].append({'name':'pager-policy','declarations':names,'sha256OriginalBlock':digest(body)})
    def selected(path,names,rel,header,changes=()):
        original=safe(_desktop_canonical_source(repo, path)).read_text(encoding='utf-8').replace('\r\n','\n')
        body=nav_parser.declarations(parser,original,names)
        adapted=body
        for before,after in changes:
            assert before in adapted;adapted=adapted.replace(before,after)
        emit(rel,'// Original source '+path+'\n// LF SHA256 '+digest(original)+'\n'+header+adapted)
        receipt['sources'].append({'path':path,'sha256LF':digest(original),'mode':'policy-extract','selectedDeclarations':names})
    selected('design-system/src/main/java/com/android/purebilibili/core/ui/BottomBarContentPaddingPolicy.kt',
        ['BottomBarContentPaddingSpec','resolveBottomBarContentPaddingSpec','resolveBottomBarContentPadding','rememberAppBottomBarContentPadding','calculateBottomBarContentPadding'],
        'com/android/purebilibili/core/ui/BottomBarContentPaddingPolicy.kt',
        'package com.android.purebilibili.core.ui\nimport androidx.compose.runtime.Composable\nimport androidx.compose.ui.unit.*\nimport com.android.purebilibili.core.theme.*\n\n')
    selected('app/src/main/java/com/android/purebilibili/navigation/AppNavigationMotionSpec.kt',
        ['AppNavigationMotionSpec','FALLBACK_FADE_DURATION_MILLIS','QUICK_RETURN_FADE_DURATION_MILLIS',
         'SEAMLESS_FADE_DURATION_MILLIS','CARD_DISABLED_TARGET_SLIDE_MAX_DURATION_MILLIS',
         'CARD_TARGET_FALLBACK_SLIDE_MAX_DURATION_MILLIS','resolveAppNavigationMotionSpec'],
        'com/android/purebilibili/navigation/AppNavigationMotionSpec.kt',
        'package com.android.purebilibili.navigation\nimport com.android.purebilibili.core.ui.motion.AppMotionTokens\n\n')
    selected('app/src/main/java/com/android/purebilibili/navigation/AppNavigationPlaybackPolicy.kt',
        ['shouldDeferBottomBarRevealOnVideoReturn','shouldDelayBottomBarRevealAfterVideoReturn','resolveVideoReturnBottomBarRevealDelayMs'],
        'com/android/purebilibili/navigation/DesktopOriginalRootPlaybackChromePolicy.kt',
        'package com.android.purebilibili.navigation\n\n')
    selected('app/src/main/java/com/android/purebilibili/navigation/AppNavigationPlaybackPolicy.kt',
        ['shouldEnableVideoDetailSharedTransition'],
        'com/android/purebilibili/navigation/DesktopOriginalRootPlaybackHolderPolicy.kt',
        'package com.android.purebilibili.navigation\n\n')
    # Reuse the already emitted full original policy in home-full-card; one top-function owner.
    stale_chrome = safe(out / 'com/android/purebilibili/core/ui/transition/DesktopOriginalRootChromeReveal.kt')
    if stale_chrome.exists():
        stale_chrome.unlink()
    selected(APP, ['shouldAutoEnterPortraitForStandardVideoNavigation','resolveStandardVideoRoute'],
        'com/android/purebilibili/navigation/DesktopOriginalRootStandardVideoRoute.kt',
        'package com.android.purebilibili.navigation\nimport java.net.URLEncoder\nimport java.nio.charset.StandardCharsets\n\n')
    offline='app/src/main/java/com/android/purebilibili/feature/download/OfflineVideoRoutingPolicy.kt'
    offline_body=safe(_desktop_canonical_source(repo, offline)).read_text(encoding='utf-8').replace('\r\n','\n')
    receipt['sources'].append({'path':offline,'sha256LF':digest(offline_body),'mode':'direct'})
    if standalone: emit('com/android/purebilibili/feature/download/OfflineVideoRoutingPolicy.kt',offline_body)
    selected('app/src/main/java/com/android/purebilibili/feature/home/HomeWallpaperBackdrop.kt',
        ['DepthSyncedGlobalHomeWallpaperBackdrop'],
        'com/android/purebilibili/feature/home/DesktopOriginalRootDepthWallpaper.kt',
        'package com.android.purebilibili.feature.home\nimport androidx.compose.runtime.Composable\nimport androidx.compose.foundation.layout.*\nimport androidx.compose.ui.Modifier\nimport androidx.compose.ui.geometry.Rect\nimport androidx.compose.ui.graphics.Color\nimport com.android.purebilibili.core.ui.adaptive.MotionTier\nimport com.bilipai.desktop.ui.rememberDesktopDynamicReduceMotion as rememberSystemReduceMotion\nimport com.android.purebilibili.core.ui.transition.*\n\n')
    # Full original shell visibility/reserve/padding equations. Only service getters, independently
    # owned page state and Windows actual inset are supplied values, rather than Android globals.
    chrome=raw[raw.index('        val isSettingsScreen = activeBottomTabRoute'):raw.index('        val setBottomBarVisible: (Boolean)')]
    original_chrome=chrome
    a=chrome.index('        val audioNowPlayingBarEnabled by SettingsManager')
    b=chrome.index('        val finalBottomBarVisible =',a)
    chrome=chrome[:a]+chrome[b:]
    chrome=chrome.replace('isBottomBarVisible = true','setBottomBarVisible(true)')
    chrome=chrome.replace('SettingsManager.BottomBarVisibilityMode','DesktopFavoriteNavigationTypes.BottomBarVisibilityMode')
    chrome=chrome.replace('WindowInsets.navigationBars\n                .asPaddingValues()\n                .calculateBottomPadding()','navigationBarsBottom')
    signature='package com.bilipai.desktop.ui\nimport androidx.compose.runtime.*\nimport androidx.compose.animation.core.MutableTransitionState\nimport androidx.compose.ui.unit.Dp\nimport com.android.purebilibili.core.ui.rememberAppBottomBarContentPadding\nimport com.android.purebilibili.feature.home.components.*\nimport com.android.purebilibili.feature.video.player.PlaylistItem\nimport com.android.purebilibili.navigation.*\nimport com.android.purebilibili.navigation3.BiliPaiReturnSessionState\n\ninternal class DesktopOriginalRootChromeState(\n    val sideBarMountGate:Boolean,\n    val bottomBarCanMount:Boolean,\n    val finalBottomBarVisible:Boolean,\n    val collapseLinkedPlaybackDock:Boolean,\n    val bottomBarVisibilityState:MutableTransitionState<Boolean>,\n    val bottomBarContentPadding:Dp,\n)\n@Composable internal fun desktopOriginalRootChromeState(\n    currentRoute:String?, activeBottomTabRoute:String?, bottomBarMountRoute:String?,\n    visibleBottomBarRoutes:Set<String>, isTabletLayout:Boolean, useSideNavigation:Boolean,\n    isVideoDetailDestination:Boolean, navigation3ReturnSession:BiliPaiReturnSessionState,\n    cardTransitionEnabled:Boolean, driveBottomBarByProgress:Boolean,\n    videoCardSourceChromeVisible:Boolean, bottomBarVisibilityMode:DesktopFavoriteNavigationTypes.BottomBarVisibilityMode,\n    isBottomBarVisible:Boolean, setBottomBarVisible:(Boolean)->Unit,\n    isBottomBarFloating:Boolean, audioNowPlayingBarEnabled:Boolean, audioNowPlayingActive:Boolean,\n    audioNowPlayingItem:PlaylistItem?, currentBottomNavItem:BottomNavItem, scrollOffsetState:MutableFloatState,\n    bottomBarUiSkinDecoration:BottomBarUiSkinDecoration?, navigationBarsBottom:Dp,\n):DesktopOriginalRootChromeState {\n'
    emit('com/bilipai/desktop/ui/DesktopOriginalRootChromeState.kt',origin+signature+textwrap.dedent(chrome)+
        '\nreturn DesktopOriginalRootChromeState(sideBarMountGate,bottomBarCanMount,finalBottomBarVisible,\n'
        '    collapseLinkedPlaybackDock,bottomBarVisibilityState,bottomBarContentPadding)\n}\n')
    receipt['selected'].append({'name':'full-original-chrome-state','sha256OriginalBlock':digest(original_chrome),
        'platformChanges':['caller-owned state/getters','current Windows inset','visibility setter typed port']})

    safe(out/'source-receipt.json').write_text(json.dumps(receipt,indent=2)+'\n',encoding='utf-8')
    return receipt

if __name__=='__main__':
    ap=argparse.ArgumentParser();ap.add_argument('--repo',type=Path,required=True)
    ap.add_argument('--output',type=Path,required=True);ap.add_argument('--standalone',action='store_true')
    a=ap.parse_args();generate(a.repo,a.output,a.standalone)
