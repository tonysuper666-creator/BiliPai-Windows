from pathlib import Path
import hashlib, importlib.util, json, re, subprocess, sys, zipfile
sys.dont_write_bytecode=True
P=Path(__file__).resolve().parent; MAIN=P.parents[2]; REPO=MAIN.parent/'BiliPai-v023'
COMMIT='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
SOURCE='app/src/main/java/com/android/purebilibili/core/store/SettingsManager.kt'
def wide(p):
 s=str(Path(p).absolute()); return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def read(p): return wide(p).read_bytes().replace(b'\r\n',b'\n')
def write(p,b):
 wide(p).parent.mkdir(parents=True,exist_ok=True); wide(p).write_bytes(b.encode() if isinstance(b,str) else b)
def sha(b): return hashlib.sha256(b.encode() if isinstance(b,str) else b).hexdigest()
def module(name,p):
 s=importlib.util.spec_from_file_location(name,p); m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
c=module('frozen_control_selectors',MAIN/'desktop/.local/stable-video-player-full-controls-parity/prepare.py')
t=subprocess.check_output(['git','show',COMMIT+':'+SOURCE],cwd=REPO).replace(b'\r\n',b'\n').decode()
obj=t[t.index('{',t.index('object SettingsManager'))+1:t.rfind('}')]
seeds=['getPlayerInteractionSettings','getAutoPortraitFullscreen','getAutoRotateEnabled','getClickToPlay','getClickToPlaySync','getFullscreenMode','getHomeUpBadgesVisible','getHorizontalAdaptationEnabled','getLiveSurfaceCardTransitionEnabled','getMiniPlayerModeSync','getPauseOnPlayerCollapseEnabled','getPortraitPlayerCollapseMode','getStopPlaybackOnExitSync','getSubtitleAutoPreference','getTabletCommentPanelWidthPreset','getVideoNoteDefaultCollapsed']
sectionSource=read(P/'original-stable/app/src/main/java/com/android/purebilibili/feature/video/ui/section/VideoPlayerSection.kt').decode()
controlSource=read(MAIN/'desktop/.local/stable-video-player-full-controls-parity/generated/com/android/purebilibili/core/store/DesktopOriginalVideoControlSettings.kt').decode()
existingControlNames=set(re.findall(r'\bfun\s+(\w+)\s*\(',controlSource))
sectionNames=set(re.findall(r'SettingsManager\s*\.\s*(\w+)',sectionSource))
sectionNames={n for n in sectionNames if 'Danmaku' not in n and n not in existingControlNames}
seeds=sorted(set(seeds)|sectionNames)
body,closure=c.member_closure(obj,seeds)
body=body.replace('?: DEFAULT_PLAYER_DIAGNOSTIC_LOGGING_ENABLED','?: context.defaultPlayerDiagnosticLoggingEnabled()')
imports='''package com.android.purebilibili.core.store
import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext as Context
import com.bilipai.desktop.ui.DesktopOriginalPlayerPreferenceValues as Preferences
import com.bilipai.desktop.ui.DesktopOriginalPlayerPreferenceValues as MutablePreferences
import com.bilipai.desktop.ui.playerBooleanPreferencesKey as booleanPreferencesKey
import com.bilipai.desktop.ui.playerIntPreferencesKey as intPreferencesKey
import com.bilipai.desktop.ui.playerFloatPreferencesKey as floatPreferencesKey
import com.bilipai.desktop.ui.playerStringPreferencesKey as stringPreferencesKey
import com.android.purebilibili.feature.video.subtitle.SubtitleAutoPreference
import com.android.purebilibili.feature.video.subtitle.normalizeSubtitleVerticalOffsetFraction
import com.android.purebilibili.core.store.player.DesktopOriginalVideoPlayerSettings as PlayerSettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
'''
keySource='app/src/main/java/com/android/purebilibili/core/store/player/PlayerSettingsStore.kt'
keyText=subprocess.check_output(['git','show',COMMIT+':'+keySource],cwd=REPO).replace(b'\r\n',b'\n').decode()
keyDecl=c.selector.declarations(c.parser,keyText,['longPressSpeedPreferenceKey'])
write(P/'generated/com/android/purebilibili/core/store/DesktopOriginalPlayerSectionSettings.kt',imports+'\n'+keyDecl+'\nobject DesktopOriginalPlayerSectionSettings {\n'+body+'\n}\n')
names=['PlayerInteractionSettings','AutoExitFullscreenMode','resolveAutoExitFullscreenMode','FullscreenMode','FullscreenAspectRatio','PortraitPlayerCollapseMode','TabletCommentPanelWidthPreset','LONG_PRESS_SPEED_HINT_DEFAULT_SCALE','LONG_PRESS_SPEED_HINT_SCALE_MIN','LONG_PRESS_SPEED_HINT_SCALE_MAX','LONG_PRESS_SPEED_HINT_DEFAULT_ALPHA','LONG_PRESS_SPEED_HINT_ALPHA_MIN','LONG_PRESS_SPEED_HINT_ALPHA_MAX','normalizeLongPressSpeedHintScale','normalizeLongPressSpeedHintAlpha','resolveDefaultPlayerDiagnosticLoggingEnabled']
selected=c.selector.declarations(c.parser,t,names)
write(P/'generated/com/android/purebilibili/core/store/DesktopOriginalPlayerInteractionSettings.kt','package com.android.purebilibili.core.store\nimport com.android.purebilibili.feature.video.subtitle.SubtitleAutoPreference\n'+selected+'\n')
contextPath='desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalPlayerSettingsContext.kt'
base=read(REPO/contextPath).decode(); candidate=base; hunks=[]
for before,after in [('    private val videoOverlayPlatform: DesktopOriginalVideoOverlayPlatform? = null\n','    private val videoOverlayPlatform: DesktopOriginalVideoOverlayPlatform? = null,\n    private val largeScreenOrFoldableConfiguration: (() -> Boolean)? = null,\n    private val isDebugBuild: (() -> Boolean)? = null\n'),('    internal fun requireCurrent()', '    internal fun isLargeScreenOrFoldableConfiguration(): Boolean {\n        requireCurrent()\n        return checkNotNull(largeScreenOrFoldableConfiguration) { "Root actual physical monitor device default is required" }.invoke()\n    }\n    internal fun defaultPlayerDiagnosticLoggingEnabled(): Boolean {\n        requireCurrent()\n        return com.android.purebilibili.core.store.resolveDefaultPlayerDiagnosticLoggingEnabled(checkNotNull(isDebugBuild) { "Root actual build type is required" }.invoke())\n    }\n    internal fun requireCurrent()')]:
 assert candidate.count(before)==1; candidate=candidate.replace(before,after,1);hunks.append(dict(before=before,after=after))
write(P/'proof-only'/contextPath,candidate)
write(P/'settings-context-local-hunks.json',json.dumps(dict(path=contextPath,baseSha256LF=sha(base),candidateSha256LF=sha(candidate),hunks=hunks,applyOnlyExactHunks=True,neverCopyProofWholeSource=True,rootBinding='Pass the installed same Root DesktopHomeWindowsPreferencesPlatform.defaultTabletUseSidebar (actual monitor dimensions/DPI and exact original >=600 threshold). Hinge capability remains explicitly unavailable.'),indent=2)+'\n')
write(P/'section-settings-selection.json',json.dumps(dict(commit=COMMIT,path=SOURCE,sourceSha256LF=sha(t),originalMembers=closure,originalTopLevelDeclarations=names,requiredSameRootContext='Existing DesktopOriginalPlayerSettingsContext over one global DesktopPluginStore settings + original mirror namespaces. No new Store or SettingsManager.',existingFamilyReferences=['DesktopOriginalVideoControlSettings','DesktopOriginalVideoContentSettings','DesktopHomeSettingsPort','DesktopOriginalLongPressSpeedSettings'],missingGetters=seeds,scope='Prospective source only; original defaults, normalizers and exact keys kept.'),ensure_ascii=False,indent=2)+'\n')
print(json.dumps(dict(members=len(closure),topLevel=len(names),source=sha(t))))
