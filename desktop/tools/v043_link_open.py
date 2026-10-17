"""Whole original v043 link admission/policy and tests for the actual Windows Root."""
from pathlib import Path
import hashlib,json
COMMIT='35e2fac8a3870a2227a323e19f29f1e93d7e591c'
ARCHIVE='desktop/upstream-slices/v043-link-open'
MANIFEST_SHA256='7b9f020fa7887da655c734727a39c79fae85a9214e33382ae422f645fd3bab7f'
PINS=[{'path': 'app/src/main/java/com/android/purebilibili/feature/web/LinkOpenGate.kt', 'archiveFile': 'LinkOpenGate.kt', 'bytes': 590, 'sha256': 'e60b0847ce10f31d6772f31164802555c97b26a837ba8d40b2954db82e777bef', 'gitBlob': '304a3560872d4c0e693524fa14ab81328d6f5586', 'referenceOnly': False}, {'path': 'app/src/main/java/com/android/purebilibili/navigation/BilibiliLinkNavigationPolicy.kt', 'archiveFile': 'BilibiliLinkNavigationPolicy.kt', 'bytes': 3013, 'sha256': '1f5fa7163f491916cebf470f239829f306d1e45a05fddf3815d90c0d03240121', 'gitBlob': 'ebf2e9286e47d9b8b3b4f92d66ad637dc8532416', 'referenceOnly': False}, {'path': 'app/src/test/java/com/android/purebilibili/feature/web/LinkOpenGateTest.kt', 'archiveFile': 'LinkOpenGateTest.kt', 'bytes': 715, 'sha256': '7897664c68e7698ccb1c94e7e07720e9276bd4784bd4e3858a72c1d974b74bde', 'gitBlob': '5e4a5173afdf449f93ebe6dbb3dc7a5807d90c6a', 'referenceOnly': False}, {'path': 'app/src/test/java/com/android/purebilibili/navigation/BilibiliLinkNavigationPolicyTest.kt', 'archiveFile': 'BilibiliLinkNavigationPolicyTest.kt', 'bytes': 2371, 'sha256': '239e5e4aea87178cce1e8d9db74d83ec24c2b42f78aae93f4195187f9340cc58', 'gitBlob': '925a01acb386e4f27c532753a99ad29a305a368c', 'referenceOnly': False}]
def need(condition,message):
    if not condition:raise ValueError(message)
def verified_raw(repo):
    repo=Path(repo).resolve();root=repo/ARCHIVE
    for parent in [root,*root.parents]:
        if parent==repo:break
        need(not parent.is_symlink(),'Linked v043 link-open archive ancestor')
    need(root.is_dir()and root.resolve().is_relative_to(repo),'Missing/escaping v043 link-open archive')
    expected={'manifest.json',*(p['archiveFile']for p in PINS)}
    need({p.name for p in root.iterdir()}==expected,'Unexpected link-open archive leaves')
    for name in expected:need((root/name).is_file()and not(root/name).is_symlink(),'Linked/non-file link-open input')
    raw=(root/'manifest.json').read_bytes();need(hashlib.sha256(raw).hexdigest()==MANIFEST_SHA256,'Link-open manifest pin changed')
    m=json.loads(raw);need((m['schemaVersion'],m['repository'],m['tag'],m['commit'],m['files'])==(1,'jay3-yy/BiliPai','v0.4.3',COMMIT,PINS),'Link-open manifest identity changed')
    result={}
    for pin in PINS:
        raw=(root/pin['archiveFile']).read_bytes()
        need(len(raw)==pin['bytes']and hashlib.sha256(raw).hexdigest()==pin['sha256'],'Link-open original bytes changed: '+pin['archiveFile'])
        need(hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest()==pin['gitBlob'],'Link-open original blob changed')
        result[pin['archiveFile']]=raw
    return result
def original_sources(repo):
    files=verified_raw(repo)
    return {pin['path'].removeprefix('app/src/main/java/'):files[pin['archiveFile']]for pin in PINS[:2]}
def original_tests(repo):
    files=verified_raw(repo);result={}
    for pin,count in zip(PINS[2:],[2,6],strict=True):
        raw=files[pin['archiveFile']];need(raw.count(b'    @Test\n')==count and b'import kotlin.test.Test'in raw,'Original link-open test count/framework changed')
        result[pin['path'].removeprefix('app/src/test/java/')]=raw
    return result
