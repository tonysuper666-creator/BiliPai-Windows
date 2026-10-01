from pathlib import Path
import hashlib,json,subprocess,zipfile,sys
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
LANE=MAIN/'desktop/.local/stable-miuix5c91-nav-source-review'
FORK=REPO/'desktop/third-party/miuix5157'
OUT=HERE/'miuix5c91-nav-install'
assert not OUT.exists()
def wide(p):
    s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def read(p):return wide(p).read_bytes()
def sha(b):return hashlib.sha256(b).hexdigest()
def pin(p,h):
    b=read(p);assert sha(b)==h,p;return b
raw=pin(LANE/'evidence-manifest.json','40c49cf00959b09a650304edb5117b7c02e958ae58775d401209ec9929b18e63')
m=json.loads(raw);assert m['artifactCount']==40
for r in m['artifactRows']:b=pin(LANE/r['relativePath'],r['sha256Bytes']);assert len(b)==r['bytes']
archive=pin(m['archive']['path'],m['archive']['sha256Bytes'])
contract=json.loads(read(LANE/'build-and-platform-contract.json'))
build=read(FORK/'build.gradle.kts');assert sha(build.replace(b'\r\n',b'\n'))==contract['baseBuildSha256LF']
provenance=pin(FORK/'upstream-provenance.json',contract['baseProvenance']['sha256Bytes'])
p=json.loads(provenance);assert len(p['files'])==195 and p['commit']==m['commit']
new_rows=json.loads(read(LANE/'nav-provenance-append-only-rows.json'));assert len(new_rows)==30
assert not any(r['path'].startswith('miuix-nav/') for r in p['files'])
checks=[]
with zipfile.ZipFile(wide(m['archive']['path'])) as z:
    prefix=z.namelist()[0].split('/')[0]+'/'
    for r in new_rows:
        b=z.read(prefix+r['path']);assert sha(b)==r['sha256']
        candidate=pin(LANE/'source-inputs/upstream'/r['storagePath'],r['sha256'])
        assert b==candidate
        target=FORK/'upstream'/r['storagePath'];assert not wide(target).exists()
        checks.append((target,b,r))
patch=LANE/'same-project-nav-build-proposal.patch'
subprocess.run(['git','apply','--check',str(patch)],cwd=REPO,check=True)
OUT.mkdir();(OUT/'baseline-build.gradle.kts').write_bytes(build);(OUT/'baseline-provenance.json').write_bytes(provenance)
for target,b,r in checks:wide(target).parent.mkdir(parents=True,exist_ok=True);wide(target).write_bytes(b)
p['files'].extend(new_rows)
assert len(p['files'])==225
(FORK/'upstream-provenance.json').write_text(json.dumps(p,indent=2)+'\n',encoding='utf-8',newline='\n')
subprocess.run(['git','apply',str(patch)],cwd=REPO,check=True)
assert sha(read(FORK/'build.gradle.kts').replace(b'\r\n',b'\n'))==contract['proposedBuildSha256LF']
subprocess.run([sys.executable,str(FORK/'verify-source.py'),'--root',str(FORK)],cwd=REPO,check=True)
report=dict(applied=True,frozenReviewSHA256=sha(raw),sameSoleProject=':miuix5157',newProjectsOrClients=0,
    originalSourceCommit=m['commit'],canonicalFiles=225,compiledOriginalKotlin=195,additionalRawSourceFiles=30,
    originalRowsByteHashesUnchanged=True,buildBaselineLF=contract['baseBuildSha256LF'],buildAfterLF=contract['proposedBuildSha256LF'],
    immutableDependencyAlreadyInActualRuntimeDeclaredExplicitly=True,noImplicitCollectionsUpgrade=True,wholeClassesAndRootNavAccepted=False)
(OUT/'installed.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8',newline='\n')
print(json.dumps(report))
