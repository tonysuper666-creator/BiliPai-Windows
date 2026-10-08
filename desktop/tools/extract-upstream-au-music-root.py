"""Full original AU Screen/content over the existing Listen/Root Music ports.
The BV overload, MusicUiState and MusicPlayerContent retain their existing sole
producers. Full original forward/inverse pins must pass before output writes."""
from __future__ import annotations
import argparse,hashlib,json
from pathlib import Path
from v025_source_paths import canonical_source
COMMIT = "79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40"
RECIPE = json.loads('{"originalPath":"app/src/main/java/com/android/purebilibili/feature/audio/screen/MusicDetailScreen.kt","output":"com/android/purebilibili/feature/audio/screen/MusicDetailScreen.kt","fullOriginalSha256LF":"d4040f6ed3088403d817b2ad2b8f5b35f1ae7a359e57066398b1bb3127b85369","adaptedSha256LF":"e632fb880840de0b7b7917155a80c305cc5c2b16f0db0a692966be13be0aae78","edits":[{"offset":186,"before":"import androidx.compose.ui.platform.LocalContext\\n","after":"","label":"Android platform/global imports removed; actual owners injected"},{"offset":248,"before":"import androidx.lifecycle.viewmodel.compose.viewModel\\n","after":"","label":"Android platform/global imports removed; actual owners injected"},{"offset":248,"before":"import com.android.purebilibili.core.store.HomeSettings\\n","after":"","label":"Android platform/global imports removed; actual owners injected"},{"offset":248,"before":"import com.android.purebilibili.core.store.SettingsManager\\n","after":"","label":"Android platform/global imports removed; actual owners injected"},{"offset":605,"before":"import com.android.purebilibili.feature.video.screen.AudioModeScreen\\n","after":"","label":"Android platform/global imports removed; actual owners injected"},{"offset":605,"before":"import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackViewModel\\n","after":"","label":"Android platform/global imports removed; actual owners injected"},{"offset":534,"before":"import com.android.purebilibili.feature.audio.viewmodel.MusicViewModel\\n","after":"import com.bilipai.desktop.ui.DesktopOriginalAuMusicPage\\nimport com.bilipai.desktop.ui.LocalDesktopOriginalMusicUiPlatform\\n","label":"stateless same-AU-session page port, not another MusicVM"},{"offset":793,"before":"    viewModel: MusicViewModel = viewModel()\\n","after":"    viewModel: DesktopOriginalAuMusicPage,\\n    state: MusicUiState\\n","label":"actual state projected by existing Session; no new StateFlow"},{"offset":864,"before":"    val context = LocalContext.current\\n    val state by viewModel.uiState.collectAsStateWithLifecycle()\\n\\n    LaunchedEffect(sid) {\\n        viewModel.initPlayer(context)\\n        viewModel.loadMusic(sid)\\n    }","after":"    LaunchedEffect(sid, viewModel.isActive()) {\\n        if (viewModel.isCurrentUi()) viewModel.loadMusic(sid)\\n    }","label":"actual composition caller; existing session/native actor already initialized"},{"offset":1033,"before":"/** \\u89c6\\u9891 DASH \\u97f3\\u8f68\\u5165\\u53e3\\u3002 */\\n@Composable\\ninternal fun MusicDetailScreen(\\n    musicTitle: String,\\n    bvid: String,\\n    cid: Long,\\n    onBack: () -> Unit,\\n    onVideoModeClick: (String, Long) -> Unit,\\n    playerViewModel: VideoPlaybackViewModel = viewModel()\\n) {\\n    AudioModeScreen(\\n        viewModel = playerViewModel,\\n        onBack = onBack,\\n        onVideoModeClick = onVideoModeClick,\\n        initialBvid = bvid,\\n        initialCid = cid,\\n        titleOverride = musicTitle\\n    )\\n}\\n\\n","after":"","label":"BV overload already consumed through full AudioMode physical leaf; no duplicate owner"},{"offset":1126,"before":"    viewModel: MusicViewModel\\n","after":"    viewModel: DesktopOriginalAuMusicPage\\n","label":"six original AU controls invoke required same-session source port"},{"offset":1172,"before":"    val context = LocalContext.current\\n    val homeSettings by SettingsManager.getHomeSettings(context).collectAsStateWithLifecycle(\\n        initialValue = HomeSettings(),\\n        context = kotlin.coroutines.EmptyCoroutineContext\\n    )","after":"    val homeSettings by LocalDesktopOriginalMusicUiPlatform.current.homeSettings.collectAsStateWithLifecycle()","label":"actual Root initialized HomeSettings StateFlow; no Android singleton/default/store"}],"fullOriginalInverse":true}')
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
def produce(repo,output):
    source=canonical_source(repo,RECIPE['originalPath']).read_text(encoding='utf-8').replace('\r\n','\n')
    assert sha(source)==RECIPE['fullOriginalSha256LF'], 'AU source changed; manual replay required'
    body=source
    for e in RECIPE['edits']:
        i=e['offset'];assert body[i:i+len(e['before'])]==e['before'],e['label']
        body=body[:i]+e['after']+body[i+len(e['before']):]
    assert sha(body)==RECIPE['adaptedSha256LF']
    inverse=body
    for e in reversed(RECIPE['edits']):
        i=e['offset'];assert inverse[i:i+len(e['after'])]==e['after']
        inverse=inverse[:i]+e['before']+inverse[i+len(e['after']):]
    assert inverse==source
    output=Path(str(output) if str(output).startswith('\\\\?\\') else '\\\\?\\'+str(output.absolute()))
    target=output/RECIPE['output'];target.parent.mkdir(parents=True,exist_ok=True)
    header='// Generated from '+RECIPE['originalPath']+'; do not edit.\n// LF-normalized SHA-256: '+sha(source)+'\n'
    target.write_text(header+body,encoding='utf-8',newline='\n')
    (output/'au-screen-source-receipt.json').write_text(json.dumps(dict(source=RECIPE['originalPath'],
        sourceSHA256LF=sha(source),adaptedSHA256LF=sha(body),fullOriginalInverse=True),indent=2)+'\n',encoding='utf-8')
if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--repo',type=Path,required=True);p.add_argument('--output',type=Path)
    p.add_argument('--inventory',action='store_true');a=p.parse_args()
    if a.inventory:print(json.dumps([dict(path=RECIPE['originalPath'],mode='policy-extract',sha256=RECIPE['fullOriginalSha256LF'],features=['original-au-music-page'])],indent=2))
    elif a.output:produce(a.repo,a.output)
    else:p.error('Pass --output or --inventory')
