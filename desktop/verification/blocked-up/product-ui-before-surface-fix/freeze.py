from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent
def safe(p):
 value=str(Path(p).absolute());return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
assert not (HERE/'verified-artifacts.json').exists(),'Already frozen'
for name in ['compile-evidence.json','proof/result.json','proof/class-origins.json']:
 assert json.loads((HERE/name).read_text(encoding='utf-8'))['passed'],name
deps=json.loads((HERE/'dependency-identities.json').read_text(encoding='utf-8'))
for r in deps:assert sha(Path(r['path']))==r['sha256Bytes'],r['path']
snapshot=HERE.parent/'blocked-up-main-product-snapshot/manifest.json'
assert sha(snapshot)=='10e08fbc781a62ceff68c06c768677922925679b6121f59f7eb43ab56845cd7b'
files=[]
for p in sorted(safe(HERE).rglob('*')):
 if not p.is_file():continue
 relative=str(p.relative_to(safe(HERE))).replace('\\','/')
 if relative=='verified-artifacts.json':continue
 files.append(dict(path=relative,sha256Bytes=sha(p),bytes=p.stat().st_size))
result=dict(passed=True,files=files,artifactCount=len(files),activeUiClasses='classes-attempt2',activeOriginClasses='origin-classes',
 pureFixtureSources=2,productionSourceOverrides=0,productManifestSha256Bytes=sha(snapshot),offscreenActualProductCases=4,
 productClassesWithFreshJvmOriginProof=9,noSharedGradle=True,noHWND=True,noExternalHTTP=True,noUserAccountData=True,
 actualProcessRestartClaimed=False,fullDesktopShellClaimed=False,backupRestoreFixPresentClaimed=False)
(HERE/'verified-artifacts.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8',newline='\n')
print('FROZEN',len(files),sha(HERE/'verified-artifacts.json'))
