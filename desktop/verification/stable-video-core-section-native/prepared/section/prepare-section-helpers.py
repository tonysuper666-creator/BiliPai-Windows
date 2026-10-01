"""Whole original helper sources required by the complete PlayerSection renderer."""
from pathlib import Path
import hashlib,json,subprocess,sys
sys.dont_write_bytecode=True
P=Path(__file__).resolve().parent; MAIN=P.parents[2]; REPO=MAIN.parent/'BiliPai-v023'
COMMIT='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589';BASE='app/src/main/java/com/android/purebilibili/'
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def write(p,b):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes(b.encode()if isinstance(b,str)else b)
def sha(b):return hashlib.sha256(b.encode()if isinstance(b,str)else b).hexdigest()
rows=[]
for rel in [
 'feature/video/playback/session/PlaybackSeekController.kt',
 'feature/video/ui/overlay/FullscreenDoubleTapPolicy.kt',
 'feature/video/ui/components/SponsorSkipUI.kt',
 'feature/video/ui/components/TwoFingerSpeedFeedbackOverlay.kt',
 'feature/video/ui/components/VideoAspectRatioPreferenceMapper.kt',
 'feature/video/subtitle/SubtitleFeaturePolicy.kt',
 'feature/video/ui/section/VideoPlayerUiLayoutPolicy.kt',
 'feature/video/ui/section/VideoPlayerTopBarPolicy.kt',
 'feature/video/ui/section/VideoPlayerDanmakuLoadPolicy.kt',
 'core/ui/adaptive/InputDevicePolicy.kt',
 'feature/video/ui/section/DanmakuViewportHost.kt',
]:
 path=BASE+rel; raw=subprocess.check_output(['git','show',COMMIT+':'+path],cwd=REPO).replace(b'\r\n',b'\n');text=raw.decode();write(P/'original-stable'/path,raw)
 adaptations=[]
 for a,b in [
  ('import androidx.media3.common.Player','import com.bilipai.desktop.ui.DesktopOriginalMpvOverlayControl as Player'),
 ]:
  if a in text:adaptations.append(dict(before=a,after=b));text=text.replace(a,b)
 if rel.endswith('DanmakuViewportHost.kt'):
  a=text.index('    val context = LocalContext.current');b=text.index('    BoxWithConstraints(',a)
  before=text[a:b];after='    val density = LocalDensity.current\n    val reference = com.bilipai.desktop.ui.LocalDesktopOriginalVideoSectionPlatform.current.danmakuReferencePixels\n'
  adaptations.append(dict(before=before,after=after));text=text[:a]+after+text[b:]
  text='\n'.join(line for line in text.split('\n') if not(line.startswith('import android.')or line in ['import androidx.compose.ui.platform.LocalConfiguration','import androidx.compose.ui.platform.LocalContext','import com.android.purebilibili.core.util.LocalAppWindowAdaptiveInfo']))
 write(P/'section-generated/com/android/purebilibili'/rel,text)
 rows.append(dict(originalPath=path,originalSha256LF=sha(raw),gitBlob=subprocess.check_output(['git','rev-parse',COMMIT+':'+path],cwd=REPO,text=True).strip(),generatedPath='section-generated/com/android/purebilibili/'+rel,generatedSha256LF=sha(text),fullOriginalBody=True,adaptations=adaptations))
write(P/'section-helper-inventory.json',json.dumps(dict(commit=COMMIT,sources=rows,scope='Whole source-only original helpers. One player import alias; no player/client/Store is constructed.'),indent=2)+'\n')
print(json.dumps(dict(wholeHelpers=len(rows))))
