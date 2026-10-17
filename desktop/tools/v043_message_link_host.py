"""Retain the whole original message resolver and bind its H5 host guard to upstream policy.

This repairs the confirmed H5 comment fast path before the general URL parser.
The existing original recursion handles nested bilibili://browser links. No second
resolver, request client, account owner or navigation dispatcher is introduced.
"""
from pathlib import Path
import hashlib
import json

COMMIT = '35e2fac8a3870a2227a323e19f29f1e93d7e591c'
ARCHIVE = 'desktop/upstream-slices/v043-message-link-host'
MANIFEST_SHA256 = '9aa4a140f2f8ef04daa366edb5559397e39a832c3cbf58fb026244b1e0277301'
PINS = [{'path': 'app/src/main/java/com/android/purebilibili/navigation/MessageLinkNavigationPolicy.kt', 'archiveFile': 'MessageLinkNavigationPolicy.kt', 'bytes': 10477, 'sha256': '3208a12aa98ecc40dfd560dc444bbfadab982cc3c33f6eb8cc18adec3467e2b1', 'gitBlob': '9ea862ac7e86f624ab123cf9c0c1b1acb3d8c095', 'referenceOnly': False}, {'path': 'app/src/test/java/com/android/purebilibili/navigation/MessageLinkNavigationPolicyTest.kt', 'archiveFile': 'MessageLinkNavigationPolicyTest.kt', 'bytes': 10612, 'sha256': '1a4e5529659a47ce66cf7ce528182b337ea8160001fa74e0795dead792624805', 'gitBlob': '4a2dc1a4089ba263df0f26cbb1b95fe03c11a199', 'referenceOnly': False}]
IMPORT_BEFORE = b'import com.android.purebilibili.core.util.BilibiliNavigationTarget\n'
IMPORT_AFTER = IMPORT_BEFORE + b'import com.android.purebilibili.core.util.isBilibiliHost\n'
HOST_BEFORE = b'host.contains("bilibili.com")'
HOST_AFTER = b'isBilibiliHost(host)'

def sha(raw):return hashlib.sha256(raw).hexdigest()
def need(condition,message):
    if not condition:raise ValueError(message)

def verified_raw(repo):
    repo=Path(repo).resolve();root=repo/ARCHIVE
    for parent in [root,*root.parents]:
        if parent==repo:break
        need(not parent.is_symlink(),'Linked message host archive ancestor')
    need(root.is_dir() and root.resolve().is_relative_to(repo),'Missing/escaping message host archive')
    expected={'manifest.json',*(pin['archiveFile']for pin in PINS)}
    need({p.name for p in root.iterdir()}==expected,'Unexpected message host archive leaves')
    for name in expected:need((root/name).is_file() and not (root/name).is_symlink(),'Linked/non-file message host input')
    raw=(root/'manifest.json').read_bytes()
    need(sha(raw)==MANIFEST_SHA256,'Message host manifest pin changed')
    manifest=json.loads(raw)
    need((manifest['schemaVersion'],manifest['repository'],manifest['tag'],manifest['commit'],manifest['files'])==
         (1,'jay3-yy/BiliPai','v0.4.3',COMMIT,PINS),'Message host manifest identity changed')
    files={}
    for pin in PINS:
        raw=(root/pin['archiveFile']).read_bytes()
        need(len(raw)==pin['bytes'] and sha(raw)==pin['sha256'],'Message host original bytes changed: '+pin['archiveFile'])
        need(hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest()==pin['gitBlob'],'Message host original blob changed')
        files[pin['archiveFile']]=raw
    return files

def original_policy(repo, predecessor):
    files=verified_raw(repo);raw=files['MessageLinkNavigationPolicy.kt']
    need(predecessor==raw,'Message host original predecessor changed')
    need(raw.count(IMPORT_BEFORE)==1 and raw.count(HOST_BEFORE)==1,'Message H5 host anchor is not unique')
    adapted=raw.replace(IMPORT_BEFORE,IMPORT_AFTER,1).replace(HOST_BEFORE,HOST_AFTER,1)
    need(adapted.count(IMPORT_AFTER)==1 and adapted.count(HOST_AFTER)==1,'Adapted message host guard is not unique')
    need(adapted.replace(HOST_AFTER,HOST_BEFORE,1).replace(IMPORT_AFTER,IMPORT_BEFORE,1)==raw,'Whole original message inverse failed')
    return {'com/android/purebilibili/navigation/MessageLinkNavigationPolicy.kt':adapted},dict(
        upstreamCommit=COMMIT,source=PINS[0]['path'],archive=ARCHIVE,manifestSHA256=MANIFEST_SHA256,
        originalSHA256=sha(raw),adaptedSHA256=sha(adapted),wholeOriginal=True,completeOriginalInverse=True,
        semanticRepair='Only the H5 comment fast path uses existing upstream isBilibiliHost instead of substring matching',
        originalRecursionUnchanged=True,originalJvmCases=17,jvmTestsExecuted=False,applicationExecuted=False)

def original_tests(repo):
    raw=verified_raw(repo)['MessageLinkNavigationPolicyTest.kt']
    need(raw.count(b'    @Test\n')==17 and b'import kotlin.test.Test' in raw,'Original message test framework/count changed')
    return {'com/android/purebilibili/navigation/MessageLinkNavigationPolicyTest.kt':raw}
