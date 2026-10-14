"""Fixed whole latest original top/bottom controls and layout policies."""
from pathlib import Path
import hashlib,json
COMMIT='8b47769d22c0d5bc88ae5248cee4aa04ff10d62c'
MANIFEST_SHA='be70bab320f8213ae710a0df27a4693a069e79a2f6e3d8d77e2c0117c40a82c0'
PINS=[{'path': 'app/src/main/java/com/android/purebilibili/feature/video/ui/overlay/BottomControlBar.kt', 'filename': 'BottomControlBar.kt', 'bytes': 104670, 'sha256Bytes': 'd869e05e416286c340431cd06c66aff59350b6ee656c249763e15564ebf8d29c', 'gitBlob': 'e51b037453d737805d7c7cca9cbd0c9881e95595'}, {'path': 'app/src/main/java/com/android/purebilibili/feature/video/ui/overlay/BottomControlBarLayoutPolicy.kt', 'filename': 'BottomControlBarLayoutPolicy.kt', 'bytes': 7279, 'sha256Bytes': 'e64ed514bae94db7f80150ce52b8bc52522556f4731bc7532c4624c9370febb4', 'gitBlob': '62f82362acfdbfb620d42f6170cd623330c325f0'}, {'path': 'app/src/main/java/com/android/purebilibili/feature/video/ui/overlay/TopControlBar.kt', 'filename': 'TopControlBar.kt', 'bytes': 30227, 'sha256Bytes': 'ce2a77f51a5b7edeee80e1392f09148cf166705969d05c3e66b92ad8a243e21b', 'gitBlob': 'f053b6de66769878730278f042110bbefdd8bd80'}, {'path': 'app/src/main/java/com/android/purebilibili/feature/video/ui/overlay/TopControlBarLayoutPolicy.kt', 'filename': 'TopControlBarLayoutPolicy.kt', 'bytes': 2657, 'sha256Bytes': 'caa69fb20cad28f3d923902edf640ce8fccd3a68846685980a6f7b783fdfe6d6', 'gitBlob': 'd68abaa895ab25c98bc2aa8ac8aa9376af6e98b1'}, {'path': 'app/src/main/java/com/android/purebilibili/feature/video/ui/overlay/VideoPlayerOverlayVisualPolicy.kt', 'filename': 'VideoPlayerOverlayVisualPolicy.kt', 'bytes': 5315, 'sha256Bytes': 'c016665dcb7720f4120cbdf589ec60e5fc80d711cec14abae8454b2d3c04e60b', 'gitBlob': 'b898dbdd3bb97f705a3a0edfcb7251b35cfd79de'}, {'path': 'app/src/test/java/com/android/purebilibili/feature/video/ui/overlay/BottomControlBarLayoutPolicyTest.kt', 'filename': 'BottomControlBarLayoutPolicyTest.kt', 'gitBlob': '4901fb8e87148f97b98e6c81441a2f629d6e8389', 'sha256Bytes': '9b905803dc1889a632e242504618b2ccb5c3dd8943f78c07ba5a4387f2809f65', 'bytes': 2270}, {'path': 'app/src/test/java/com/android/purebilibili/feature/video/ui/overlay/BottomControlBarPolicyTest.kt', 'filename': 'BottomControlBarPolicyTest.kt', 'gitBlob': '0f7598e5730aa48a8431fe4d4dfeac77980f7367', 'sha256Bytes': 'cd8556b5989081bfa158d932c6f19164d9167a023b8004848a14cf28741475de', 'bytes': 11280}, {'path': 'app/src/test/java/com/android/purebilibili/feature/video/ui/overlay/TopControlBarLayoutPolicyTest.kt', 'filename': 'TopControlBarLayoutPolicyTest.kt', 'gitBlob': '9aaa9492ecf6b8ce434a16687bb9fd21de93d19b', 'sha256Bytes': 'ca5444e2483f61b11b08c9d55fd005e2a9ce8201d57e05996c4da32799c947bc', 'bytes': 2817}, {'path': 'app/src/test/java/com/android/purebilibili/feature/video/ui/overlay/TopControlBarPolicyTest.kt', 'filename': 'TopControlBarPolicyTest.kt', 'gitBlob': '23bd4ecf2008c7116e152cfb342a4c9cd0d16570', 'sha256Bytes': '89a8db4485f2b24d2a004cb9f1e10aaf60d309751d7feb72cdba1bc4507a06b9', 'bytes': 5794}]

def fixed_sources():
    archive=Path(__file__).resolve().parent.parent/'upstream-slices/v037-player-chrome'
    raw=(archive/'manifest.json').read_bytes()
    if hashlib.sha256(raw).hexdigest()!=MANIFEST_SHA:raise ValueError('Player chrome manifest changed')
    manifest=json.loads(raw)
    if manifest['files']!=PINS or manifest['fixedUpstreamCommit']!=COMMIT:raise ValueError('Unknown player chrome source contract')
    result={}
    for row in PINS:
        raw=(archive/row['filename']).read_bytes()
        blob=hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest()
        if len(raw)!=row['bytes'] or hashlib.sha256(raw).hexdigest()!=row['sha256Bytes'] or blob!=row['gitBlob']:raise ValueError('Original player chrome bytes changed')
        result[row['path']]=raw.decode('utf-8')
    return result

def selected_source(path,canonical):
    return fixed_sources().get(path,canonical)

def generate_policy_tests(output):
    sources = fixed_sources()
    result = []
    for row in PINS:
        if '/src/test/' not in row['path']:
            continue
        target = output / 'com/android/purebilibili' / row['path'].split('com/android/purebilibili/')[1]
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(sources[row['path']].encode('utf-8'))
        result.append(target)
    if len(result) != 4:
        raise ValueError('Incomplete original player policy tests')
    return result

def write_source_proof(output, rel, adapted, generated):
    path = 'app/src/main/java/com/android/purebilibili/' + rel
    originals = fixed_sources()
    if path not in originals:
        return
    from v029_comment_search import whole_proof
    proof = whole_proof(originals[path], adapted)
    proof.update(upstreamCommit=COMMIT, sourcePath=path, generated=generated,
                 wholeOriginalSelected=True, sourceQualified=False,
                 pixelEquivalenceQualified=False)
    target = output / 'v037-source-proofs' / (Path(rel).name + '.json')
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes((json.dumps(proof, ensure_ascii=False, indent=2) + '\n').encode('utf-8'))
