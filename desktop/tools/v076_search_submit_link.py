"""Original v0.7.6 search-submit link policy, its tests and AppNavigation submit callbacks.

The archive was added after the legacy read point, so it is read from the merged checkout
beside this module, never from the legacy tree the linked-dock producer reads its other
originals from."""
from pathlib import Path
import hashlib,json
COMMIT='0ec9c4dd25ad8f364dcc32dc7a3309bba9b30f1c'
INTRODUCED_BY='39ad3afe2628322aa9d3bf16ecb4a6b0f7c753fa'
ARCHIVE='desktop/upstream-slices/v076-search-submit-link'
MANIFEST_SHA256='8a44e0e4092f5a7a705217d154c1fc1c856471e9c745ee7e86118bd2bc64c4fe'
PINS=[{'path': 'app/src/main/java/com/android/purebilibili/navigation/SearchSubmitPolicy.kt', 'archiveFile': 'SearchSubmitPolicy.kt', 'bytes': 1911, 'sha256': '8b19bb72f2fddddbe3a7cacdb62b54dbeebec5c687493fd4e355b4a4acf60061', 'gitBlob': 'd3f116cbbe436c9a20f4a5ea2a5eea8096c150e1', 'referenceOnly': False}, {'path': 'app/src/test/java/com/android/purebilibili/navigation/SearchSubmitPolicyTest.kt', 'archiveFile': 'SearchSubmitPolicyTest.kt', 'bytes': 2551, 'sha256': '18fb6d672552fec58693c0b2235909347d41a3abd80610954e21c789760f1385', 'gitBlob': '81500704bf37669010dc1b9a63d11dd5ee5fdddc', 'referenceOnly': False}, {'path': 'app/src/main/java/com/android/purebilibili/navigation/AppNavigation.kt', 'archiveFile': 'AppNavigation.kt', 'bytes': 348441, 'sha256': '5ab389e0ee91533b36f3d838b4330a78a3f0fe252268448c4bb9a45b8eca1c32', 'gitBlob': 'bf3ea46112be1adf6aa740611faa2ac5cf74e31f', 'referenceOnly': True}]
def need(condition,message):
    if not condition:raise ValueError(message)
def checkout():
    return Path(__file__).resolve().parents[2]
def verified_raw(repo=None):
    repo=Path(repo or checkout()).resolve();root=repo/ARCHIVE
    for parent in [root,*root.parents]:
        if parent==repo:break
        need(not parent.is_symlink(),'Linked v076 search-submit archive ancestor')
    need(root.is_dir()and root.resolve().is_relative_to(repo),'Missing/escaping v076 search-submit archive')
    expected={'manifest.json',*(p['archiveFile']for p in PINS)}
    need({p.name for p in root.iterdir()}==expected,'Unexpected search-submit archive leaves')
    for name in expected:need((root/name).is_file()and not(root/name).is_symlink(),'Linked/non-file search-submit input')
    raw=(root/'manifest.json').read_bytes();need(hashlib.sha256(raw).hexdigest()==MANIFEST_SHA256,'Search-submit manifest pin changed')
    m=json.loads(raw);need((m['schemaVersion'],m['repository'],m['tag'],m['commit'],m['introducedBy'],m['files'])==(1,'jay3-yy/BiliPai','v0.7.6',COMMIT,INTRODUCED_BY,PINS),'Search-submit manifest identity changed')
    result={}
    for pin in PINS:
        raw=(root/pin['archiveFile']).read_bytes()
        need(len(raw)==pin['bytes']and hashlib.sha256(raw).hexdigest()==pin['sha256'],'Search-submit original bytes changed: '+pin['archiveFile'])
        need(hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest()==pin['gitBlob'],'Search-submit original blob changed')
        result[pin['archiveFile']]=raw
    return result
def original_policy(repo=None):
    raw=verified_raw(repo)['SearchSubmitPolicy.kt']
    need(b'data class OpenLink(val url: String)'in raw and raw.count(b'return SearchSubmitAction.OpenLink(')==1,'Original short-link submit branch changed')
    return raw.decode('utf-8')
def original_navigation(repo=None):
    return verified_raw(repo)['AppNavigation.kt'].decode('utf-8')
def original_tests(repo=None):
    raw=verified_raw(repo)['SearchSubmitPolicyTest.kt']
    need(raw.count(b'    @Test\n')==7 and b'import kotlin.test.Test'in raw,'Original search-submit test count/framework changed')
    return {PINS[1]['path'].removeprefix('app/src/test/java/'):raw}
def identity():
    return dict(archive=ARCHIVE,tag='v0.7.6',commit=COMMIT,introducedBy=INTRODUCED_BY,manifestSha256=MANIFEST_SHA256,files=[dict(path=p['path'],gitBlob=p['gitBlob'],referenceOnly=p['referenceOnly'])for p in PINS])
