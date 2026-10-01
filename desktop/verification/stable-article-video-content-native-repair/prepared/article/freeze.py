from pathlib import Path
import hashlib,json,os
H=Path(__file__).resolve().parent;PREFIX=chr(92)*2+'?'+chr(92)
def wide(p):
 s=os.path.abspath(p);return Path(s if s.startswith(PREFIX) else PREFIX+s)
def sha(p):return hashlib.sha256(wide(p).read_bytes()).hexdigest()
def read(p):return wide(p).read_text(encoding='utf-8')
def row(p):return dict(path=p.relative_to(wide(H)).as_posix(),sha256Bytes=sha(p),sizeBytes=p.stat().st_size)
assert json.loads(read(H/'compile-02/result.json'))['passed']
assert json.loads(read(H/'proof-04/proof.json'))['runExit']==0
assert 'RESULT assertions=19 groups=1' in read(H/'proof-04/run.log')
assert json.loads(read(H/'audit-04/result.json'))['passed']
copy=[]
for source,target in [('prepared/tools/extract-upstream-article-detail.py','desktop/tools/extract-upstream-article-detail.py'),('prepared/manual/com/bilipai/desktop/ui/DesktopOriginalArticleBindings.kt','desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalArticleBindings.kt')]:
 copy.append(dict(source=source,target=target,sha256Bytes=sha(H/source)))
whitelist=dict(copies=copy,patches=[dict(path='protocol-producer.patch',sha256Bytes=sha(H/'protocol-producer.patch'),base=json.loads(read(H/'tool-delta.json'))['baseLfSha256'],desired=json.loads(read(H/'tool-delta.json'))['candidateLfSha256'])],registryUnion='registry-delta.json',gradleSnippet='gradle-snippet.kts',rootLeafRecipe='ROOT-INTEGRATION.md',doNotInstall=['prepared/selected task-only whole direct closure','prepared/protocol outputs or whole producer replacement','task class/JAR/DLL/temp Stores','audit replay/registry overlays','old failure payloads'])
wide(H/'install-whitelist.json').write_text(json.dumps(whitelist,indent=2)+'\n',encoding='utf-8')
allFiles=[p for p in wide(H).rglob('*') if p.is_file()]
excluded=[row(p) for p in allFiles if p.suffix in ['.class','.jar','.dll','.pyc'] or '/temporary/' in p.as_posix()]
wide(H/'excluded-runtime-artifacts.json').write_text(json.dumps(dict(doNotInstallOrCommit=True,artifacts=excluded),indent=2)+'\n',encoding='utf-8')
history=dict(productionUnchangedByProof=True,proof01='fixture syntax and runner variable shadow; failed source preserved',proof02='original parser trims trailing whitespace; expected literal corrected',proof03='terminal application interceptor precedes BridgeInterceptor Cookie header; actual admitted CookieJar tested instead',audit01='inverse removed import reinserted at wrong position',audit02='required new registry row absent from live baseline; explicit prospective replay overlay added',audit03='inverse mapping omitted NetworkModule qualifier',rootPreparedFailures='prepare-attempt01/02/03 and compile01 preserved under original scope')
wide(H/'historical-status.json').write_text(json.dumps(history,indent=2)+'\n',encoding='utf-8')
artifacts=[]
for p in sorted(wide(H).rglob('*')):
 if not p.is_file() or p.name=='frozen-handoff.json':continue
 rel=p.relative_to(wide(H)).as_posix()
 if p.suffix in ['.class','.jar','.dll','.pyc'] or '/temporary/' in '/'+rel or '/__pycache__/' in '/'+rel:continue
 if '/old-protocol/' in rel or '/new-protocol/' in rel or '/ui-replay/' in rel:continue
 artifacts.append(row(p))
out=dict(scope='prepared complete original Article UI/model/protocol and focused owned request proof',targetCommit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',mainIntegration=False,actualBase=dict(snapshot='stable-product-snapshot-49',manifest='7db2b7bc2816c9b7f10521c1ad1678196f4446a8bcde5cc5a837d678ac675a35',cp='569b953e5def0dcaba76d9a6a6d96fea9f3572ad910e6edf13f23b88d91ee821',entries=97),gates=dict(compile='compile-02',inputs=8,classes=37,proof='proof-04',assertions=19,groups=1,sourceAudit='audit-04',sourceChecks=49,originalUiLines=505),installWhitelistSha=sha(H/'install-whitelist.json'),recipeSha=sha(H/'ROOT-INTEGRATION.md'),artifactsCount=len(artifacts),artifacts=artifacts,outsideScope=['Root typed leaf mounted runtime','real account/HTTP/TLS','physical window/native Gallery/input','EXE/package delivery'])
wide(H/'frozen-handoff.json').write_text(json.dumps(out,indent=2)+'\n',encoding='utf-8')
for r in artifacts:assert sha(H/r['path'])==r['sha256Bytes']
print('FROZEN',len(artifacts),'SHA',sha(H/'frozen-handoff.json'),'whitelist',sha(H/'install-whitelist.json'),'recipe',sha(H/'ROOT-INTEGRATION.md'))
