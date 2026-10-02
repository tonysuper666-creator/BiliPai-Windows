from pathlib import Path
import difflib,hashlib,json,os,re,subprocess,sys
sys.dont_write_bytecode=True;sys.stdout.reconfigure(encoding='utf8')
P=Path(__file__).resolve().parent;C=P.parents[2].parent/'BiliPai-v023';UP='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
def wide(p):
 s=os.path.abspath(str(p));return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
def write(p,t):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_text(t,encoding='utf8',newline='\n')
def exact(s,a,b,n=1):
 assert s.count(a)==n,(a,s.count(a));return s.replace(a,b)
def between(s,a,b,c):
 assert s.count(a)==1,(a,s.count(a));i=s.index(a);j=s.index(b,i);return s[:i]+c+s[j:]
path='app/src/main/java/com/android/purebilibili/feature/bangumi/BangumiPlayerScreen.kt'
raw=subprocess.check_output(['git','-C',str(C),'show',UP+':'+path]).decode('utf8').replace('\r\n','\n');s=raw
for line in ['import android.app.Activity','import android.content.Context','import android.content.ContextWrapper','import android.content.pm.ActivityInfo','import android.content.res.Configuration','import android.widget.Toast','import android.annotation.SuppressLint','import com.android.purebilibili.core.player.HiResCompatibleRenderersFactory','import com.android.purebilibili.core.util.applyPlayerRequestedOrientation','import androidx.compose.ui.platform.LocalConfiguration','import androidx.compose.ui.platform.LocalContext','import androidx.compose.ui.platform.LocalView','import androidx.core.view.WindowCompat','import androidx.core.view.WindowInsetsCompat','import androidx.lifecycle.viewmodel.compose.viewModel','import androidx.media3.exoplayer.ExoPlayer','import com.android.purebilibili.core.ui.setWindowNavigationBarColor','import com.android.purebilibili.core.ui.setWindowStatusBarColor','import com.android.purebilibili.feature.video.danmaku.rememberDanmakuManager','import com.android.purebilibili.feature.video.player.MiniPlayerManager','import com.android.purebilibili.feature.video.handoff.PlaybackHandoffRegistry']:
 s=exact(s,line+'\n','')
s=exact(s,'import androidx.compose.ui.unit.sp\n','import androidx.compose.ui.unit.sp\nimport com.bilipai.desktop.ui.*\nimport com.bilipai.desktop.ui.DesktopOriginalMpvOverlayControl as Player\nimport com.bilipai.desktop.ui.DesktopHomeCardWindowMetrics as LocalConfiguration\nimport android.util.Log\n')
s=exact(s,'@SuppressLint("UnsafeOptInUsageError")\n','')
s=exact(s,'fun BangumiPlayerScreen(','internal fun BangumiPlayerScreen(')
s=exact(s,'viewModel: BangumiPlayerViewModel = viewModel(),','viewModel: BangumiPlayerViewModel,')
s=exact(s,'commentViewModel: VideoCommentViewModel = viewModel()','commentViewModel: VideoCommentViewModel')
s=exact(s,'    val context = LocalContext.current\n    val view = LocalView.current\n','    val platform = LocalDesktopOriginalBangumiPlayerScreenPlatform.current\n    val context = platform.section.settingsContext\n    val nativeFullscreen by platform.fullscreen.collectAsStateWithLifecycle()\n')
s=exact(s,'    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE','    val isLandscape = platform.window.presentation.isLandscape')
s=exact(s,'    val hostActivity = remember(context) { context.findActivity() }','    val hostPresentation = platform.window.presentation')
s=exact(s,'    val usesInWindowFullscreen = playerWindowOrientationPolicy.usesInWindowFullscreen','    val usesInWindowFullscreen = true // Existing Windows fullscreen presentation; no display rotation is fabricated.')
s=exact(s,'    val isFullscreen = playerPresentation.isFullscreen','    val isFullscreen = nativeFullscreen')
s=exact(s,'.background(if (isFullscreen) Color.Black else AppSurfaceTokens.groupedListContainer())','.background(Color.Transparent)')
s=between(s,'    // 创建 ExoPlayer','    //  [优化] 播放诊断监听先于加载注册，避免错过首帧事件后误报黑屏', '''    // Borrow the installed native owner; no Media3 builder/player lifetime here.
    val exoPlayer = platform.player
    LaunchedEffect(platform, viewModel, isCourse) {
        platform.ensureMiniCallbacks(viewModel, isCourse)
    }

''')
s=s.replace('android.util.Log.','Log.').replace('androidx.media3.common.Player.', 'Player.')
s=exact(s,'override fun onPlayerError(error: androidx.media3.common.PlaybackException)', 'override fun onPlayerError(error: DesktopOriginalNativePlaybackError)')
s=s.replace('error.errorCodeName','error.failure?.kind?.name ?: "UNKNOWN"')
s=exact(s,'Log.e("BangumiPlayer", "❌ 播放错误: ${error.failure?.kind?.name ?: "UNKNOWN"} - ${error.message}", error)','Log.e("BangumiPlayer", "❌ 播放错误: ${error.failure?.kind?.name ?: "UNKNOWN"} - ${error.message}")')
s=between(s,'                val isDrmError = error.failure?.kind?.name ?: "UNKNOWN" ==','                if (isDrmError)', '                val isDrmError = platform.isActualDrmFailure(error)\n')
s=between(s,'            override fun onPlayerErrorChanged(', '            override fun onPlaybackStateChanged', '')
s=re.sub(r'Toast\.makeText\(context, ([^\n]+), Toast\.LENGTH_SHORT\)\.show\(\)',r'platform.showFeedback(\1)',s)
s=exact(s,'    val danmakuManager = rememberDanmakuManager("bangumi:$seasonId:$currentEpisodeIdForDebug")','    val danmakuManager = platform.section.danmaku')
s=between(s,'    // 绑定 Player','    // 辅助函数：切换屏幕方向', '''    // Same Overlay player attachment and Store-position handoff; close only views.
    DisposableEffect(platform, exoPlayer) {
        val lease = platform.acquireDanmakuPlayer(exoPlayer)
        onDispose { lease.close() }
    }
    DisposableEffect(platform, seasonId, currentEpisodeIdForDebug) {
        val lease = platform.acquirePlaybackHandoff(seasonId, currentEpisodeIdForDebug) { exoPlayer.currentPosition }
        onDispose { lease.close() }
    }
    DisposableEffect(platform, isFullscreen) {
        val lease = platform.acquirePresentation(isFullscreen)
        onDispose { lease.close() }
    }

''')
s=between(s,'        val activity = context.findActivity() ?: return','    var previousDisplayRole', '''        userRequestedFullscreen = !isFullscreen
        hostPresentation.requestOrientation(target, displayContext)
    }

''')
s=exact(s,'            userRequestedFullscreen = !isFullscreen\n            return','            userRequestedFullscreen = !isFullscreen\n            platform.requestFullscreen(!isFullscreen)\n            return')
s=s.replace('LaunchedEffect(hostActivity,','LaunchedEffect(hostPresentation,')
s=exact(s,'            hostActivity?.applyPlayerRequestedOrientation(\n                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,\n                displayContext = displayContext,\n            )','            hostPresentation.requestOrientation(-1, displayContext)')
s=between(s,'    DisposableEffect(context, displayContext, isFullscreen, isPlayerScreenLocked, isTablet)', '    LaunchedEffect(isLandscape) {', '''    DisposableEffect(platform, isFullscreen, isPlayerScreenLocked, isTablet) {
        val shouldLockOrientation = !isTablet && isFullscreen && isPlayerScreenLocked
        val lease = platform.section.setScreenshotAndOrientationLock(shouldLockOrientation)
        onDispose { lease.close() }
    }

    // A real rotation sensor is optional. Windows has no Android accelerometer;
    // monitor aspect is never converted to physical rotation samples.
    DisposableEffect(platform, displayContext, isTablet, usesInWindowFullscreen, isPlayerScreenLocked) {
        val sensor = hostPresentation.rotationSensor
        var lastOrientation = -1
        var lastPortraitAppliedAtMs = 0L
        val lease = if (!isPlayerScreenLocked && !isTablet && !usesInWindowFullscreen && sensor != null)
            sensor.observeDegrees { orientation ->
                if (orientation >= 0) {
                    val newOrientation = when (orientation) {
                        in 315..360, in 0..45 -> 0
                        in 46..134 -> 270
                        in 135..224 -> 180
                        in 225..314 -> 90
                        else -> lastOrientation
                    }
                    if (newOrientation != lastOrientation && lastOrientation != -1) {
                        val isDeviceLandscape = newOrientation == 90 || newOrientation == 270
                        val isUprightPortrait = newOrientation == 0
                        val now = platform.elapsedRealtimeMillis()
                        if (latestIsLandscape && isUprightPortrait && now - lastPortraitAppliedAtMs >= 700L) {
                            lastPortraitAppliedAtMs = now
                            userRequestedFullscreen = false
                            hostPresentation.requestOrientation(1, displayContext)
                        } else if (!latestIsLandscape && isDeviceLandscape) {
                            hostPresentation.requestOrientation(6, displayContext)
                        }
                    }
                    lastOrientation = newOrientation
                }
            } else null
        onDispose { lease?.close() }
    }

''')
s=between(s,'    // 沉浸式状态栏控制','    Box(\n        modifier = Modifier\n            .fillMaxSize()', '''    // Same Window registration is supplied by acquirePresentation; Android
    // status/navigation bars have no fabricated Windows counterpart.

''')
s=between(s,'                                    runCatching {\n                                        context.startActivity(', '                                }\n                            )\n                        }\n                    }', '''                                    platform.share.shareText("分享课程", shareText)
''')
s=between(s,'/**\n * 辅助函数：从 Context 获取 Activity','@Composable\nprivate fun BangumiPlayNoticeOverlay(', '')
# Player alias is the actual same native callback/readback boundary.
# Existing sole settings owners retain original keys and their current canonical Store.
s=s.replace('com.android.purebilibili.core.store.SettingsManager\n        .getSponsorBlockEnabled', 'com.android.purebilibili.core.store.DesktopOriginalBangumiPlayerUiSettings\n        .getSponsorBlockEnabled')
s=s.replace('com.android.purebilibili.core.store.SettingsManager\n        .getAutoSkipOpEd', 'com.android.purebilibili.core.store.DesktopOriginalBangumiPlayerUiSettings\n        .getAutoSkipOpEd')
s=s.replace('com.android.purebilibili.core.store.SettingsManager\n            .getPortraitPlayerCollapseMode', 'com.android.purebilibili.core.store.DesktopOriginalPlayerSectionSettings\n            .getPortraitPlayerCollapseMode')
s=s.replace('com.android.purebilibili.core.store.SettingsManager\n            .getPauseOnPlayerCollapseEnabled', 'com.android.purebilibili.core.store.DesktopOriginalPlayerSectionSettings\n            .getPauseOnPlayerCollapseEnabled')
s=s.replace('com.android.purebilibili.core.store.SettingsManager\n        .getCommentDefaultSortMode(context)', 'com.android.purebilibili.core.store.DesktopOriginalReplySettings\n        .getCommentDefaultSortMode(context.pluginContext)')
s=s.replace('com.android.purebilibili.core.store.SettingsManager.getCommentDefaultSortModeSync(context)','com.android.purebilibili.core.store.DesktopOriginalReplySettings.getCommentDefaultSortModeSync(context.pluginContext)')
# Every scoped danmaku setter/getter is the already installed original preference owner.
s=s.replace('com.android.purebilibili.core.store.SettingsManager\n        .getDanmakuSettings(context, activeDanmakuScope)','platform.section.danmakuPreferences\n        .getDanmakuSettings(activeDanmakuScope)')
s=s.replace('initialValue = com.android.purebilibili.core.store.DanmakuSettings()', 'initialValue = platform.section.danmakuPreferences.currentSettings(activeDanmakuScope)')
s=re.sub(r'com\.android\.purebilibili\.core\.store\.SettingsManager\.(setDanmaku\w+)\(\s*context,\s*',r'platform.section.danmakuPreferences.\1(',s)
assert 'SettingsManager' not in s
unexpected=[t for t in ['android.app.','android.content.','Toast.','LocalView','ExoPlayer.Builder(','exoPlayer.release()','AndroidView(','getInstance(context','androidx.media3.'] if t in s]
assert not unexpected,unexpected
a=raw.splitlines(keepends=True);b=s.splitlines(keepends=True);edits=[]
for tag,i,j,k,l in difflib.SequenceMatcher(None,a,b,autojunk=False).get_opcodes():
 if tag!='equal':edits.append(dict(startLine=i,endLineExclusive=j,before=''.join(a[i:j]),after=''.join(b[k:l]),beforeSha256LF=sha(''.join(a[i:j]))))
output='com/android/purebilibili/feature/bangumi/DesktopOriginalBangumiPlayerScreen.kt'
write(P/'generated'/output,'// Complete original v0.2.3 Screen; explicit same-native Window/lifecycle boundaries.\n'+s)
write(P/'screen-recipe.json',json.dumps(dict(upstreamCommit=UP,originalPath=path,originalSha256LF=sha(raw),output=output,adaptedSha256LF=sha(s),edits=edits,fullOriginalBody=True,actualRootConsumer=False,platformBoundaries=['Borrow required same player/VM/comments; no Android player construction or release','Fullscreen/user intent goes existing Window; no invented physical rotation','Root-retained Mini callback installation survives covered navigation','Danmaku/handoff/presentation use captured same-owner view handles','Actual native failures preserve DRM retry only on explicit native evidence','Canonical existing settings preference owners; original collapse/comment/settings logic preserved']),ensure_ascii=False,indent=2)+'\n')
print('Prepared full Screen',len(raw.splitlines()),'original lines',len(edits),'boundary edits')
settingsPath='app/src/main/java/com/android/purebilibili/core/store/SettingsManager.kt'
settings=subprocess.check_output(['git','-C',str(C),'show',UP+':'+settingsPath]).decode('utf8').replace('\r\n','\n')
seeds=['KEY_SPONSOR_BLOCK_ENABLED','KEY_AUTO_SKIP_OP_ED','KEY_SPONSOR_BLOCK_AUTO_SKIP','getSponsorBlockEnabled','getAutoSkipOpEd','getSponsorBlockAutoSkip'];pieces=[]
for seed in seeds:
 if seed.startswith('KEY_'):
  match=re.search(r'(?m)^    private val '+seed+r' =[^\n]+\n',settings);assert match;pieces.append(match.group())
 else:
  match=re.search(r'(?m)^    fun '+seed+r'\([^\n]+\n[^\n]+\n',settings);assert match;pieces.append(match.group())
settingsBody='\n'.join(pieces)
write(P/'generated/com/android/purebilibili/core/store/DesktopOriginalBangumiPlayerUiSettings.kt','''package com.android.purebilibili.core.store
import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext as Context
import com.bilipai.desktop.ui.playerBooleanPreferencesKey as booleanPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
internal object DesktopOriginalBangumiPlayerUiSettings {
'''+settingsBody+'\n}\n')
write(P/'settings-recipe.json',json.dumps(dict(upstreamCommit=UP,originalPath=settingsPath,originalSha256LF=sha(settings),selectedNames=seeds,exactOriginalBody=settingsBody,sha256LF=sha(settingsBody)),ensure_ascii=False,indent=2)+'\n')
