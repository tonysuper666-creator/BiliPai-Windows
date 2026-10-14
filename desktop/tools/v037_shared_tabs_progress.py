"""Fixed complete original v037 tab and progress source closure."""
from pathlib import Path
import hashlib,json
COMMIT='8b47769d22c0d5bc88ae5248cee4aa04ff10d62c'
MANIFEST_SHA='2751699cbd2924b6c230959f96dafdf77a47d82bc3cd5b660a7f5cdaa42bc5b9'
PINS=[{'path': 'design-system/src/main/java/com/android/purebilibili/core/ui/renderer/material3/AppMaterial3TonalPillTabRow.kt', 'filename': 'AppMaterial3TonalPillTabRow.kt', 'bytes': 10411, 'sha256Bytes': 'f80d77a704e1bc5f018f29610707a28087d893837533ca3dcf160924dcfca7ca', 'gitBlob': '804bcb791210f868afffea8740404f85b93f4660'}, {'path': 'design-system/src/main/java/com/android/purebilibili/core/ui/renderer/material3/AppTonalPillTabPolicy.kt', 'filename': 'AppTonalPillTabPolicy.kt', 'bytes': 695, 'sha256Bytes': '326b3c206b9501e0bd7b80528ed95790e6f87c54aad1540d32eb8d1357c91653', 'gitBlob': '094767e98ae7377f0357ca6777c474694dd9f4bb'}, {'path': 'design-system/src/test/java/com/android/purebilibili/core/ui/renderer/material3/AppTonalPillTabPolicyTest.kt', 'filename': 'AppTonalPillTabPolicyTest.kt', 'bytes': 2461, 'sha256Bytes': '462faf0b86e5c0c9a99966b5a4ca8955018104ec02a107825e8b55d1e1a81e3e', 'gitBlob': 'fb861f86a38851ea3fae74122e825c4c976ded74'}, {'path': 'design-tokens/src/main/java/com/android/purebilibili/core/ui/MediaProgressTrack.kt', 'filename': 'MediaProgressTrack.kt', 'bytes': 1165, 'sha256Bytes': 'a9520bd48d2db25bade2b3cd624a4d3892f43e25b40437f17e932571b86acd72', 'gitBlob': '298fca1bdc78d0ef42c26b6950e10100c93512fc'}, {'path': 'design-system/src/main/java/com/android/purebilibili/core/ui/components/AppSegmentedControl.kt', 'filename': 'AppSegmentedControl.kt', 'bytes': 20249, 'sha256Bytes': 'bfdafea4e07aa424b5ea56d8d8b3e739de275e1a2c066c0727479a8fac7365d7', 'gitBlob': '771d74cf5055a09aa0645677cf82d5a051a62135'}, {'path': 'design-system/src/main/java/com/android/purebilibili/core/ui/renderer/material3/AppMaterial3SegmentedControl.kt', 'filename': 'AppMaterial3SegmentedControl.kt', 'bytes': 8558, 'sha256Bytes': 'c52824fb5412e2c06e7446d47d82cf4e826d5349eb5d382524e3de4a64a9ec62', 'gitBlob': '8fe3707f9badebfddb63c7a987c89614baf99d4b'}, {'path': 'design-system/src/main/java/com/android/purebilibili/core/ui/renderer/miuix/AppMiuixSegmentedControl.kt', 'filename': 'AppMiuixSegmentedControl.kt', 'bytes': 19170, 'sha256Bytes': '812d752a19dec5cdeee8d95999af1ec92b6c664905e2e1e29eafa4561c28dc26', 'gitBlob': '927157ab229d01c305b6eb31f5d4237bff4f04e6'}, {'path': 'design-system/src/main/java/com/android/purebilibili/core/ui/AppSegmentedControlPolicy.kt', 'filename': 'AppSegmentedControlPolicy.kt', 'bytes': 3512, 'sha256Bytes': '5f6ab6a3b9888ababe428e98ddf82c698829ece5e9bc1b25bd1c83d7f93f2043', 'gitBlob': '248ff64da4fc3c00dc437c2b2564aea2620d87e9'}, {'path': 'app/src/main/java/com/android/purebilibili/core/ui/components/AppLiquidAwareTabRow.kt', 'filename': 'AppLiquidAwareTabRow.kt', 'bytes': 14137, 'sha256Bytes': 'd79aee60a7161f40ed44c9892e7323b0ee0a7b53e6e4c4340ae5867718e4f09b', 'gitBlob': '2c5d557608877e157bad7dcebc4197043581745c'}]
def sha(raw):return hashlib.sha256(raw).hexdigest()
def wide(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92)
 return Path(s if s.startswith(prefix) else prefix+s)
def fixed_sources():
 root=Path(__file__).resolve().parent.parent/'upstream-slices/v037-shared-tabs-progress-closure'
 raw=(root/'manifest.json').read_bytes();assert sha(raw)==MANIFEST_SHA
 m=json.loads(raw);assert m['fixedUpstreamCommit']==COMMIT and m['files']==PINS
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
CONTROL_OUTPUTS={'AppTonalPillTabPolicy.kt','AppSegmentedControlPolicy.kt','MediaProgressTrack.kt'}
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
  p=wide(Path(output)/rel);p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(text.encode())
  outputs.append(dict(path=rel,origin=row['path'],fixedCommit=COMMIT,mode='direct-complete-original',generated=True,sha256LF=sha(text.encode()),source=row,sourceManifestSHA256=MANIFEST_SHA))

