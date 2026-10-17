"""Complete original v036 deferred padding and shared refresh closure."""
from pathlib import Path
import hashlib,json
COMMIT='3608e0347ba3314ac74190d724d78598fdbad879'
MANIFEST_SHA='9c684c5a61d6a2847782f64ca660750a73ebd0c65593bd8d31ae5736974ece4c'
PINS=[{'path': 'app/src/main/java/com/android/purebilibili/feature/video/screen/VideoContentDeferredLayout.kt', 'filename': 'VideoContentDeferredLayout.kt', 'bytes': 2593, 'sha256Bytes': '891f3f44d01bc5bd7b0ee22a0eca1e95c29c6c64c1bd481e8fc887fee071772b', 'gitBlob': 'b356c7bdb379129afb346768f3eebd13017a9d3a'}, {'path': 'design-system/src/main/java/com/android/purebilibili/core/ui/AdaptivePullToRefreshBox.kt', 'filename': 'AdaptivePullToRefreshBox.kt', 'bytes': 6013, 'sha256Bytes': '0e2dcc71d6dda0a1e0ff6df952a111540f13e83022d6470b2aea89f23afa62c1', 'gitBlob': 'de1dd750c0fee15f718adb1d08ceb045c4ba4319'}, {'path': 'design-system/src/main/java/com/android/purebilibili/core/ui/AdaptivePullToRefreshPolicy.kt', 'filename': 'AdaptivePullToRefreshPolicy.kt', 'bytes': 1125, 'sha256Bytes': 'b176222984174db4c73fe4009bbf1401328d38889258dc45b03e33e5878a011f', 'gitBlob': '16b1734d4be39f5a3e048cba012ac4d512a774b1'}, {'path': 'design-system/src/test/java/com/android/purebilibili/core/ui/AdaptivePullToRefreshPolicyTest.kt', 'filename': 'AdaptivePullToRefreshPolicyTest.kt', 'bytes': 1373, 'sha256Bytes': '34f0fb7c69f1d9f3fc376e8b50ae833e0ade4cd528b4546f239858ca24225bf6', 'gitBlob': '0bac89874ea8f4d53c1965e15e5975917206f35e'}, {'path': 'design-system/src/main/java/com/android/purebilibili/core/ui/ComfortablePullToRefreshBox.kt', 'filename': 'ComfortablePullToRefreshBox.kt', 'bytes': 1479, 'sha256Bytes': '1a6b2e1f989eadcaab4fe516351d4c09ab56db867fafa1e5b18644d2d320465d', 'gitBlob': '8f0ccd8c6abfe2dfebc6017e26f9fe646c6312a6'}]
OLD_HOME_OUTPUTS={
    'AdaptivePullToRefreshBox.kt':'3640aca6be394b8271e0dd1c88006497066e3fe5603a748ad2d9246f8a2f852d',
    'ComfortablePullToRefreshBox.kt':'eca07051fea2b41dd3fff923cf5f79ee3d671bd732bd592ea882345b00a781b9',
    'DesktopHomeOriginalPullRefreshText.kt':'fb2db6278cbbbfd2569c9d9f6fdf6367aa0879577f48db378079dae90e4357db',
}

def fixed_sources():
    archive=Path(__file__).resolve().parent.parent/'upstream-slices/v036-comment-refresh-dependencies'
    raw=(archive/'manifest.json').read_bytes()
    if hashlib.sha256(raw).hexdigest()!=MANIFEST_SHA:raise ValueError('Fixed refresh closure manifest changed')
    manifest=json.loads(raw)
    if manifest['fixedUpstreamCommit']!=COMMIT or manifest['files']!=PINS:raise ValueError('Unknown original refresh closure')
    result={}
    for row in PINS:
        raw=(archive/row['filename']).read_bytes()
        if len(raw)!=row['bytes'] or hashlib.sha256(raw).hexdigest()!=row['sha256Bytes'] or hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest()!=row['gitBlob']:raise ValueError('Original refresh source bytes changed')
        result[row['filename']]=(row,raw)
    return result

def target_for(output,row):
    return output/'com/android/purebilibili'/row['path'].split('com/android/purebilibili/')[1]

def generate_video_padding(output):
    row,raw=fixed_sources()['VideoContentDeferredLayout.kt']
    target=target_for(output,row)
    target.parent.mkdir(parents=True,exist_ok=True)
    target.write_bytes(raw)
    return target

def extend_same_home_producer(output,test_output):
    originals=fixed_sources()
    core=output/'com/android/purebilibili/core/ui'
    old={}
    for name,expected in OLD_HOME_OUTPUTS.items():
        target=core/name
        raw=target.read_bytes().replace(b'\r\n',b'\n')
        actual=hashlib.sha256(raw).hexdigest()
        if actual!=expected:raise ValueError('Unknown existing Home refresh output: '+name)
        old[name]=actual
    proof=dict(upstreamCommit=COMMIT,originalExistingOutputPins=old,
               wholeOriginalAdaptiveAndComfortableWrappers=True,
               wholeOriginalPolicyAndTests=True,protectedHomeGeneratorChanged=False,
               secondRefreshScreenCreated=False,sourceQualified=False)
    proof_bytes=(json.dumps(proof,indent=2)+'\n').encode()
    policy_row,policy_raw=originals['AdaptivePullToRefreshPolicy.kt']
    policy_target=target_for(output,policy_row)
    previous_proof=output/'v036-refresh-closure-proof.json'
    # The primary generator rewrites its old wrappers but leaves this extension's
    # policy in place on a repeated Gradle invocation. Reuse only the exact fixed
    # policy plus this sole producer's completed receipt. Validate before writing.
    if policy_target.exists() or policy_target.is_symlink():
        reusable=(policy_target.is_file() and not policy_target.is_symlink() and
                  policy_target.stat().st_size==len(policy_raw) and policy_target.read_bytes()==policy_raw and
                  previous_proof.is_file() and not previous_proof.is_symlink() and
                  previous_proof.stat().st_size==len(proof_bytes) and previous_proof.read_bytes()==proof_bytes)
        if not reusable:raise ValueError('Unexpected second full refresh policy producer')
    # Replace the exact existing framework outputs in their sole task. The other
    # task's generator, checkout and file claims are unchanged.
    for name in ['AdaptivePullToRefreshBox.kt','ComfortablePullToRefreshBox.kt','AdaptivePullToRefreshPolicy.kt']:
        row,raw=originals[name]
        target=target_for(output,row)
        target.write_bytes(raw)
    (core/'DesktopHomeOriginalPullRefreshText.kt').unlink()
    row,raw=originals['AdaptivePullToRefreshPolicyTest.kt']
    target=target_for(test_output,row)
    target.parent.mkdir(parents=True,exist_ok=True)
    target.write_bytes(raw)
    (output/'v036-refresh-closure-proof.json').write_bytes(proof_bytes)
