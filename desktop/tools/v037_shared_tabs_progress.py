"""Fixed complete original v037 tab and progress source closure."""
from pathlib import Path
import hashlib,json
COMMIT='8b47769d22c0d5bc88ae5248cee4aa04ff10d62c'
MANIFEST_SHA='413cfd2afaee35d0ed584a9688749e65b1f5cacd405ed6660d4a4bac528ba0f9'
PINS=[{'path': 'design-system/src/main/java/com/android/purebilibili/core/ui/renderer/material3/AppMaterial3TonalPillTabRow.kt', 'filename': 'AppMaterial3TonalPillTabRow.kt', 'bytes': 10411, 'sha256Bytes': 'f80d77a704e1bc5f018f29610707a28087d893837533ca3dcf160924dcfca7ca', 'gitBlob': '804bcb791210f868afffea8740404f85b93f4660'}, {'path': 'design-system/src/main/java/com/android/purebilibili/core/ui/renderer/material3/AppTonalPillTabPolicy.kt', 'filename': 'AppTonalPillTabPolicy.kt', 'bytes': 695, 'sha256Bytes': '326b3c206b9501e0bd7b80528ed95790e6f87c54aad1540d32eb8d1357c91653', 'gitBlob': '094767e98ae7377f0357ca6777c474694dd9f4bb'}, {'path': 'design-system/src/test/java/com/android/purebilibili/core/ui/renderer/material3/AppTonalPillTabPolicyTest.kt', 'filename': 'AppTonalPillTabPolicyTest.kt', 'bytes': 2461, 'sha256Bytes': '462faf0b86e5c0c9a99966b5a4ca8955018104ec02a107825e8b55d1e1a81e3e', 'gitBlob': 'fb861f86a38851ea3fae74122e825c4c976ded74'}, {'path': 'design-tokens/src/main/java/com/android/purebilibili/core/ui/MediaProgressTrack.kt', 'filename': 'MediaProgressTrack.kt', 'bytes': 1165, 'sha256Bytes': 'a9520bd48d2db25bade2b3cd624a4d3892f43e25b40437f17e932571b86acd72', 'gitBlob': '298fca1bdc78d0ef42c26b6950e10100c93512fc'}, {'path': 'design-system/src/main/java/com/android/purebilibili/core/ui/components/AppSegmentedControl.kt', 'filename': 'AppSegmentedControl.kt', 'bytes': 20249, 'sha256Bytes': 'bfdafea4e07aa424b5ea56d8d8b3e739de275e1a2c066c0727479a8fac7365d7', 'gitBlob': '771d74cf5055a09aa0645677cf82d5a051a62135'}, {'path': 'design-system/src/main/java/com/android/purebilibili/core/ui/renderer/material3/AppMaterial3SegmentedControl.kt', 'filename': 'AppMaterial3SegmentedControl.kt', 'bytes': 8558, 'sha256Bytes': 'c52824fb5412e2c06e7446d47d82cf4e826d5349eb5d382524e3de4a64a9ec62', 'gitBlob': '8fe3707f9badebfddb63c7a987c89614baf99d4b'}, {'path': 'design-system/src/main/java/com/android/purebilibili/core/ui/renderer/miuix/AppMiuixSegmentedControl.kt', 'filename': 'AppMiuixSegmentedControl.kt', 'bytes': 19170, 'sha256Bytes': '812d752a19dec5cdeee8d95999af1ec92b6c664905e2e1e29eafa4561c28dc26', 'gitBlob': '927157ab229d01c305b6eb31f5d4237bff4f04e6'}, {'path': 'design-system/src/main/java/com/android/purebilibili/core/ui/AppSegmentedControlPolicy.kt', 'filename': 'AppSegmentedControlPolicy.kt', 'bytes': 3512, 'sha256Bytes': '5f6ab6a3b9888ababe428e98ddf82c698829ece5e9bc1b25bd1c83d7f93f2043', 'gitBlob': '248ff64da4fc3c00dc437c2b2564aea2620d87e9'}, {'path': 'app/src/main/java/com/android/purebilibili/core/ui/components/AppLiquidAwareTabRow.kt', 'filename': 'AppLiquidAwareTabRow.kt', 'bytes': 14137, 'sha256Bytes': 'd79aee60a7161f40ed44c9892e7323b0ee0a7b53e6e4c4340ae5867718e4f09b', 'gitBlob': '2c5d557608877e157bad7dcebc4197043581745c'}, {'path': 'core-player/src/main/java/com/android/purebilibili/core/player/PlaybackProgressPolicy.kt', 'filename': 'PlaybackProgressPolicy.kt', 'bytes': 352, 'sha256Bytes': '967c36f11c1553faa430c1240c65c914fdae6c78c72f1e08739d71842b344f83', 'gitBlob': '9c9bffd6d6f4f59cf765b00ac6881a8459e259f0'}, {'path': 'app/src/main/java/com/android/purebilibili/core/ui/components/LiquidDockViewport.kt', 'filename': 'LiquidDockViewport.kt', 'bytes': 2421, 'sha256Bytes': '9d3d8f2483f5ef17afaed04ba011670687530a7c4f442ee6c470cdf547d97e37', 'gitBlob': '96b9f3df76537979d1ff9b6d0037e06ce1f8abbc'}, {'path': 'app/src/main/java/com/android/purebilibili/core/ui/components/TabSelectionScroll.kt', 'filename': 'TabSelectionScroll.kt', 'bytes': 8353, 'sha256Bytes': 'ab5863809527929e6eea1a055c53ed4bdfb1bc00607cb19296f7e1cacf9ae07e', 'gitBlob': '7a45394256f2dec5ae6bcd18bdd05c578595acca'}, {'path': 'design-system/src/main/java/com/android/purebilibili/core/ui/components/TabVisibilityPolicy.kt', 'filename': 'TabVisibilityPolicy.kt', 'bytes': 645, 'sha256Bytes': '437012ce32299ddc57eba46932632b1f19cd90508bbfa572f0a4c3646b880a21', 'gitBlob': 'd6dd102ca8197ca29d5489c53b2512888d7740c1'}, {'path': 'design-system/src/main/java/com/android/purebilibili/core/ui/components/AppPressHighlightMotion.kt', 'filename': 'AppPressHighlightMotion.kt', 'bytes': 655, 'sha256Bytes': 'bbd9058b296860b04e2f91b9ffd7637f080954c05ca10d76729129db0658bedb', 'gitBlob': 'a6e8a2b13e201a95d61ab6ec4866b1f60717d09f'}, {'path': 'app/src/main/java/com/android/purebilibili/feature/video/ui/components/DanmakuColorOptions.kt', 'filename': 'DanmakuColorOptions.kt', 'bytes': 1414, 'sha256Bytes': 'e7d550dce3f0c4a9d1809f4f72e3c900fd343a612e09f54c1c48d5900b3f3817', 'gitBlob': '1c937798948548d5f81fa36598b4ace88b9384f9'}, {'path': 'app/src/main/java/com/android/purebilibili/feature/video/ui/overlay/CenterPlaybackControl.kt', 'filename': 'CenterPlaybackControl.kt', 'bytes': 2866, 'sha256Bytes': 'e29844924ac90f466a1ee3768ab44a1cea727a0b8ea2c6fbef306b0a8087664f', 'gitBlob': '0bab4d7b971e53ea9c13596a7151a4ef5ca9835d'}, {'path': 'design-system/src/main/java/com/android/purebilibili/core/ui/components/AppLiquidValueControlPolicy.kt', 'filename': 'AppLiquidValueControlPolicy.kt', 'bytes': 418, 'sha256Bytes': '625848453d293c438ea1f7ec320ccdcdd938ad3e2f3b9c226deb174257193ef0', 'gitBlob': 'aaf6e85c56d75bc3bc5de38c8efeda374d05a6eb'}, {'path': 'app/src/main/java/com/android/purebilibili/feature/video/ui/feedback/VideoActionFeedbackPolicy.kt', 'filename': 'VideoActionFeedbackPolicy.kt', 'bytes': 2653, 'sha256Bytes': '74b6f70e58b3785e4f6018a71e19fb57b94684af1daf0c13ed013bb9278619a1', 'gitBlob': '8c72be45dade9da06c7eeceabe04032f2b468eeb'}]
RESOURCE_PINS=[{'path': 'app/src/main/res/font/piliplus_custom_icons.ttf', 'filename': 'piliplus_custom_icons.ttf', 'bytes': 16944, 'sha256Bytes': '083ac97afebed9d1070a9cf8af9411294432e4f1200b6c5df931756d78a41b5b', 'gitBlob': 'f7e4a4eaca64a0ee844b15d5f326c2c4629d13c0'}]

def sha(raw):return hashlib.sha256(raw).hexdigest()
def wide(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92)
 return Path(s if s.startswith(prefix) else prefix+s)
def fixed_sources():
 root=Path(__file__).resolve().parent.parent/'upstream-slices/v037-shared-tabs-progress-closure'
 raw=(root/'manifest.json').read_bytes();assert sha(raw)==MANIFEST_SHA
 m=json.loads(raw);assert m['fixedUpstreamCommit']==COMMIT and m['files']==PINS
 assert m['resources']==RESOURCE_PINS
 for row in RESOURCE_PINS:
  data=(root/row['filename']).read_bytes()
  assert len(data)==row['bytes'] and sha(data)==row['sha256Bytes']
  assert hashlib.sha1(b'blob '+str(len(data)).encode()+b'\0'+data).hexdigest()==row['gitBlob']
 result={}
 for row in PINS:
  raw=(root/row['filename']).read_bytes()
  assert len(raw)==row['bytes'] and sha(raw)==row['sha256Bytes']
  assert hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest()==row['gitBlob']
  result[row['path']]=raw.decode('utf-8')
 return result
def selected_source(path,canonical):
 return fixed_sources().get(path,canonical)
def identity_for(path):
 row=next((r for r in PINS if r['path']==path),None)
 if row is None:return None
 return dict(pinnedCommit=COMMIT,originalPath=path,path='desktop/upstream-slices/v037-shared-tabs-progress-closure/'+row['filename'],sha256Bytes=row['sha256Bytes'],gitBlobOid=row['gitBlob'],bytes=row['bytes'])
CONTROL_OUTPUTS={'AppSegmentedControlPolicy.kt', 'DanmakuColorOptions.kt', 'MediaProgressTrack.kt', 'AppPressHighlightMotion.kt', 'CenterPlaybackControl.kt', 'TabVisibilityPolicy.kt', 'PlaybackProgressPolicy.kt', 'AppTonalPillTabPolicy.kt', 'AppLiquidValueControlPolicy.kt', 'VideoActionFeedbackPolicy.kt'}
def emit_control_foundations(output,outputs,test_output=None):
 originals=fixed_sources()
 for row in PINS:
  if '/test/java/' in row['path']:
   if test_output is not None:
    text=originals[row['path']]
    before='''        val normalized = path.removePrefix("design-system/")
        return listOf(
            File("design-system/$path"),
            File("../design-system/$path"),
            File(path),
            File("../$path"),
        ).firstOrNull(File::exists)?.readText() ?: error("Cannot locate $path")'''
    after='''        // The build consumes these exact immutable upstream files, after platform adaptation.
        val filename = File(path).name
        return listOf(
            File("upstream-slices/v037-shared-tabs-progress-closure/$filename"),
            File("desktop/upstream-slices/v037-shared-tabs-progress-closure/$filename"),
        ).firstOrNull(File::exists)?.readText() ?: error("Cannot locate pinned $path")'''
    assert text.count(before)==1,'Original tonal tab test source lookup changed'
    text=text.replace(before,after,1)
    rel=row['path'].split('/test/java/',1)[1]
    target=wide(Path(test_output)/rel);target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(text.encode())
   continue
  if row['filename'] not in CONTROL_OUTPUTS:continue
  rel=row['path'].split('/main/java/',1)[1]
  assert not any(r['path']==rel for r in outputs)
  text=originals[row['path']]
  from v040_liquid_value_controls import retained_original_override
  effective_raw,effective_row,effective_commit,effective_manifest=retained_original_override(Path(__file__).resolve().parents[2],row['path'],text.encode(),row,COMMIT,MANIFEST_SHA)
  text=effective_raw.decode('utf-8')
  if row['filename']=='AppLiquidValueControlPolicy.kt':
   assert text.count('import android.os.Build\n')==1 and text.count('Build.VERSION.SDK_INT >= 33')==1
   text=text.replace('import android.os.Build\n','').replace('Build.VERSION.SDK_INT >= 33','com.bilipai.desktop.ui.desktopDetailRenderEffectsSupported()')
  p=wide(Path(output)/rel);p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(text.encode())
  outputs.append(dict(path=rel,origin=row['path'],fixedCommit=effective_commit,mode='full-original-Windows-Skia-capability-boundary' if row['filename']=='AppLiquidValueControlPolicy.kt' else 'direct-complete-original',generated=True,sha256LF=sha(text.encode()),source=effective_row,sourceManifestSHA256=effective_manifest))

