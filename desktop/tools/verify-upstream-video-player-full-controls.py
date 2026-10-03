from pathlib import Path
import hashlib, importlib.util, json, tempfile, sys
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def sha(p):return hashlib.sha256(wide(p).read_bytes().replace(b'\r\n',b'\n')).hexdigest()
EXPECTED_GENERATED_OUTPUTS=frozenset(['com/android/purebilibili/feature/video/ui/components/CommentThreadNavigationBlur.kt', 'com/android/purebilibili/feature/video/ui/overlay/BottomControlBar.kt', 'com/android/purebilibili/feature/video/ui/overlay/TopControlBar.kt', 'com/android/purebilibili/core/store/DesktopOriginalPlayerProgressPlacement.kt', 'com/android/purebilibili/core/store/DesktopOriginalPlayerProgressBehaviorModels.kt', 'com/android/purebilibili/feature/video/ui/components/VideoAspectRatio.kt', 'com/android/purebilibili/feature/video/ui/components/SeekPreviewBubble.kt', 'com/android/purebilibili/feature/video/ui/overlay/PlayerOverlayModels.kt', 'com/android/purebilibili/core/store/player/DesktopOriginalVideoPlayerSettings.kt', 'com/android/purebilibili/feature/video/ui/components/QualityMenu.kt', 'com/android/purebilibili/feature/video/ui/components/VideoSettingsPanel.kt', 'com/android/purebilibili/feature/video/ui/components/PagesSelector.kt', 'com/android/purebilibili/core/ui/components/PlaybackSpeedPreferenceControl.kt', 'com/android/purebilibili/core/store/DesktopOriginalVideoControlSettings.kt', 'com/android/purebilibili/feature/video/ui/overlay/PlaybackOrderSelectionSheet.kt', 'com/android/purebilibili/feature/video/ui/components/DanmakuSendDialog.kt', 'com/android/purebilibili/core/ui/performance/DesktopOriginalPlayerPanelFrameRate.kt', 'com/android/purebilibili/feature/video/ui/components/PlaybackCdnDiagnostics.kt', 'com/android/purebilibili/feature/video/playback/session/PlaybackUserActionTracker.kt', 'com/android/purebilibili/feature/video/usecase/DesktopOriginalOverlayPlaybackActions.kt', 'com/android/purebilibili/feature/video/ui/overlay/VideoPlayerOverlayContracts.kt', 'com/android/purebilibili/feature/video/ui/overlay/VideoPlayerOverlay.kt', 'com/android/purebilibili/feature/video/screen/DesktopOriginalPlayerSystemBarInsetPolicy.kt'])

def verify(repo,output,report):
 path=Path(__file__).with_name('extract-upstream-video-player-full-controls.py')
 spec=importlib.util.spec_from_file_location('verify_one_original_controls',path);tool=importlib.util.module_from_spec(spec);spec.loader.exec_module(tool)
 with tempfile.TemporaryDirectory(prefix='bilipai-verify-controls-')as tmp:
  tool.generate(repo,tmp)
  rows=[]
  for row in tool.OUTPUTS:
   if row['generated']:
    expected=Path(tmp)/row['path'];actual=Path(output)/row['path']
    assert sha(expected)==sha(actual)==row['sha256LF'],row['path']+' generated source differs'
    rows.append(dict(path=row['path'],sha256LF=sha(actual)))
  assert len(rows)==len(EXPECTED_GENERATED_OUTPUTS)
  assert {r["path"]for r in rows}==EXPECTED_GENERATED_OUTPUTS, "Selected controls identity set changed"
 wide(report).parent.mkdir(parents=True,exist_ok=True)
 wide(report).write_text(json.dumps(dict(passed=True,commit=tool.COMMIT,sourceIdentities=len(tool.SOURCES),selectedOutputs=rows,directCopyOutputs=sum(not row['generated'] for row in tool.OUTPUTS),scope='Exact selected source generation; existing protocol/editor gates remain independent'),indent=2)+'\n',encoding='utf-8')
if __name__=='__main__':verify(Path(sys.argv[1]),Path(sys.argv[2]),Path(sys.argv[3]))
