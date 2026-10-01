from pathlib import Path
import subprocess,hashlib,json
H=Path(__file__).resolve().parent; R=H.parents[2].parent/'BiliPai-v023'
commit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'; base='app/src/main/java/com/android/purebilibili/'
files=['feature/video/viewmodel/PlayerToastPolicy.kt','feature/video/viewmodel/AiSummaryRetryPolicy.kt','feature/video/note/AiSummaryNoteDraftPolicy.kt','feature/video/interaction/InteractiveVideoChoicePolicy.kt','feature/video/playback/policy/PlaybackPostLoadPlan.kt','feature/video/playback/policy/PluginPollingPolicy.kt','feature/download/BatchDownloadCandidatePolicy.kt','feature/download/BatchDownloadQueuePolicy.kt']
files+=['feature/video/interaction/InteractiveVideoVariablePolicy.kt']
rows=[]
for rel in files:
 raw=subprocess.check_output(['git','-C',str(R),'show',commit+':'+base+rel]).decode().replace('\r\n','\n')
 body=raw.replace('import androidx.media3.common.Player','import com.bilipai.desktop.ui.DesktopOriginalMpvOverlayControl as Player')
 out=H/'prepared/prerequisites/com/android/purebilibili'/rel;out.parent.mkdir(parents=True,exist_ok=True);out.write_text(body,encoding='utf-8',newline='\n')
 rows.append(dict(originalPath=base+rel,originalSha256LF=hashlib.sha256(raw.encode()).hexdigest(),output=str(out.relative_to(H)),outputSha256Bytes=hashlib.sha256(body.encode()).hexdigest(),platformDelta='Actual MPV constant aliases only' if raw!=body else 'none'))
rel='core/util/NetworkUtils.kt';raw=subprocess.check_output(['git','-C',str(R),'show',commit+':'+base+rel]).decode().replace('\r\n','\n')
body=raw[raw.index('internal fun resolvePlaybackDefaultQualityId'):]
out=H/'prepared/prerequisites/com/android/purebilibili/core/util/DesktopOriginalVideoDefaultQuality.kt';out.parent.mkdir(parents=True,exist_ok=True);out.write_text('package com.android.purebilibili.core.util\nimport com.android.purebilibili.data.model.VideoQuality\n'+body,encoding='utf-8',newline='\n')
rows.append(dict(originalPath=base+rel,originalSha256LF=hashlib.sha256(raw.encode()).hexdigest(),output=str(out.relative_to(H)),outputSha256Bytes=hashlib.sha256(out.read_bytes()).hexdigest(),platformDelta='Selected complete three original default-quality pure functions'))
(H/'prerequisite-source-identities.json').write_text(json.dumps(dict(upstreamCommit=commit,preparedOnly=True,sources=rows),indent=2)+'\n',encoding='utf-8',newline='\n')
print('Full original prerequisite files',len(rows))
