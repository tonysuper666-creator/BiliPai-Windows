"""Whole upstream v043 parsers, host policy and original deterministic regression tests.

The existing space producer is the sole output owner. It binds Android Uri/log
imports and preserves the video host in custom numeric paths. Both reviewed
changes invert to the complete untouched upstream source.
"""
from pathlib import Path
import hashlib
import json

COMMIT = '35e2fac8a3870a2227a323e19f29f1e93d7e591c'
ARCHIVE = 'desktop/upstream-slices/v043-navigation'
MANIFEST_SHA256 = '877b90467279252644e663b959282addc366d2d6c61a3a8b075d219542653f1e'
PINS = [{'path': 'app/src/main/java/com/android/purebilibili/core/util/BilibiliHostPolicy.kt', 'archiveFile': 'BilibiliHostPolicy.kt', 'bytes': 548, 'sha256': '07c7a0a57fb65e6190faf0e1993fb8c538c30713750f919796aea14e226b11a4', 'gitBlob': '38a539f089c19e8337b49d8fa5f3f7a6018ed05c', 'referenceOnly': False}, {'path': 'app/src/main/java/com/android/purebilibili/core/util/BilibiliNavigationTargetParser.kt', 'archiveFile': 'BilibiliNavigationTargetParser.kt', 'bytes': 15631, 'sha256': '54d60f08ef150eb43e12c1edb20d910357786a038c01d4acab169b9f7c9a4522', 'gitBlob': 'bc6952c11a1c4ec75e9e8ae1efd8192106566022', 'referenceOnly': False}, {'path': 'app/src/main/java/com/android/purebilibili/core/util/BilibiliUrlParser.kt', 'archiveFile': 'BilibiliUrlParser.kt', 'bytes': 17964, 'sha256': 'bae695d7bec9f041e5111fc621ba2ab9b39ddd23aaabc166acd52015637b500b', 'gitBlob': 'f082232d597e59b8eae8fd24fbd93d92d7ac37f3', 'referenceOnly': False}, {'path': 'app/src/test/java/com/android/purebilibili/core/util/BilibiliNavigationTargetParserTest.kt', 'archiveFile': 'BilibiliNavigationTargetParserTest.kt', 'bytes': 8535, 'sha256': '4ecd56649fe6444699e8f64fff4c251afc815602d1a2fb429c89f2e35126f980', 'gitBlob': '5e2fcc0070e81aefa00c81e2bf907a6d6bc4d878', 'referenceOnly': False}, {'path': 'app/src/test/java/com/android/purebilibili/core/util/BilibiliUrlParserRegressionTest.kt', 'archiveFile': 'BilibiliUrlParserRegressionTest.kt', 'bytes': 3295, 'sha256': '2dbd52c58a41823f5b6ceca40ec54e59150672709224e76f3878d364a796a069', 'gitBlob': '1d649c79d6cdb038d7b1b87cf9b6d0a3ebd4b03b', 'referenceOnly': False}]
PREDECESSORS = {'BilibiliNavigationTargetParser.kt': 'bf3f2e86d526e60a48787f17b578afea86f0515e8ee62894bfdeb7eec5d63dd8', 'BilibiliUrlParser.kt': '54f37b9d5dbf2fd7d7305a072c7c63446bdfe047ebf9bfac4bf352e6b85386ba'}
URI_BEFORE = b'import android.net.Uri'
URI_AFTER = b'import java.net.URI as Uri\nimport com.bilipai.desktop.data.DesktopSpaceRouteLog as Logger'
VIDEO_PATH_BEFORE = b'inputUri.host.equals("video", ignoreCase = true) -> path'
VIDEO_PATH_AFTER = b'inputUri.host.equals("video", ignoreCase = true) -> "/video$path"'

def sha(raw): return hashlib.sha256(raw).hexdigest()
def need(value, message):
    if not value: raise ValueError(message)

def verified_raw(repo):
    repo=Path(repo).resolve();root=repo/ARCHIVE
    for parent in [root,*root.parents]:
        if parent==repo: break
        need(not parent.is_symlink(), 'Linked v043 navigation archive ancestor')
    need(root.is_dir() and root.resolve().is_relative_to(repo), 'Missing/escaping v043 navigation archive')
    expected={'manifest.json',*(p['archiveFile'] for p in PINS)}
    need({p.name for p in root.iterdir()}==expected, 'Unexpected v043 navigation archive leaves')
    for name in expected:
        need((root/name).is_file() and not (root/name).is_symlink(), 'Linked/non-file v043 navigation input')
    manifestraw=(root/'manifest.json').read_bytes()
    need(sha(manifestraw)==MANIFEST_SHA256, 'v043 navigation manifest pin changed')
    manifest=json.loads(manifestraw)
    need((manifest['schemaVersion'],manifest['repository'],manifest['tag'],manifest['commit'],manifest['files'])==
         (1,'jay3-yy/BiliPai','v0.4.3',COMMIT,PINS), 'v043 navigation manifest identity changed')
    result={}
    for pin in PINS:
        raw=(root/pin['archiveFile']).read_bytes()
        need(len(raw)==pin['bytes'] and sha(raw)==pin['sha256'], 'v043 navigation bytes changed: '+pin['archiveFile'])
        need(hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest()==pin['gitBlob'], 'v043 navigation blob changed')
        result[pin['archiveFile']]=raw
    return result

def original_sources(repo, old_navigation, old_url):
    for name,raw in [('BilibiliNavigationTargetParser.kt',old_navigation),('BilibiliUrlParser.kt',old_url)]:
        need(sha(raw)==PREDECESSORS[name], 'Original parser predecessor changed: '+name)
    files=verified_raw(repo);sources={};proof=[]
    for pin in PINS[:3]:
        raw=files[pin['archiveFile']];data=raw
        if pin['archiveFile']=='BilibiliUrlParser.kt':
            need(data.count(URI_BEFORE)==1, 'Android URI import is not unique')
            data=data.replace(URI_BEFORE,URI_AFTER,1)
            # The original numeric-path recognizer requires /video/. A custom
            # URI stores "video" in its host; retain that context before matching.
            need(data.count(VIDEO_PATH_BEFORE)==1, 'Custom video path anchor is not unique')
            data=data.replace(VIDEO_PATH_BEFORE,VIDEO_PATH_AFTER,1)
            restored=data.replace(VIDEO_PATH_AFTER,VIDEO_PATH_BEFORE,1).replace(URI_AFTER,URI_BEFORE,1)
            need(data.count(URI_AFTER)==data.count(VIDEO_PATH_AFTER)==1 and restored==raw, 'Original parser reviewed inverse failed')
        need(b'import android.' not in data, 'Unexpected Android-only parser dependency')
        output='com/android/purebilibili/core/util/'+pin['archiveFile']
        sources[output]=data
        proof.append(dict(source=pin['path'],originalSha256=sha(raw),adaptedSha256=sha(data),
                          wholeOriginal=True,completeOriginalInverse=True))
    return sources,dict(upstreamCommit=COMMIT,archive=ARCHIVE,manifestSHA256=MANIFEST_SHA256,
        predecessors=PREDECESSORS,sources=proof,originalTestCases=31,
        onlyPlatformImportAdaptation=False,numericCustomVideoCompatibilityRepair=True,
        mainCompiled=False,jvmTestsExecuted=False)

def original_tests(repo):
    files=verified_raw(repo);result={}
    for pin,count in zip(PINS[3:],[21,10],strict=True):
        raw=files[pin['archiveFile']]
        need(raw.count(b'    @Test\n')==count and b'import kotlin.test.Test' in raw, 'Original parser test framework/count changed')
        result[pin['path'].removeprefix('app/src/test/java/')]=raw
    return result

def validate_outputs(repo, *outputs):
    repo=Path(repo).resolve();targets=[Path(p).resolve() for p in outputs if p is not None]
    protected=[repo/p for p in ['app','core-data','network-core','design-system','desktop/upstream-slices',
        'desktop/src','desktop/tools','.git']]
    for target in targets:
        for original in protected:
            need(not target.is_relative_to(original) and not original.is_relative_to(target), 'Output overlaps original source')
    for index,target in enumerate(targets):
        for other in targets[index+1:]:
            need(not target.is_relative_to(other) and not other.is_relative_to(target), 'Parser main/test outputs overlap')
