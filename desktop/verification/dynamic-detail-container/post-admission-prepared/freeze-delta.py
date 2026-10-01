from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent
FINAL=HERE/'frozen-final-01'
def safe(p):
    p=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(p if p.startswith(prefix) else prefix+p)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(p,s):safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(s,encoding='utf-8',newline='\n')
def cp(p,q):safe(q.parent).mkdir(parents=True,exist_ok=True);safe(q).write_bytes(safe(p).read_bytes());assert sha(p)==sha(q)
assert not FINAL.exists()
e=json.loads((HERE/'compile-evidence-02.json').read_text(encoding='utf-8'));assert e['passed'] and len(e['existingMainProductOverrides'])==1
for r in e['sourceHashes']+e['dependencies']:assert sha(r['path'])==r['sha256Bytes']
for name in ['composer-session-result-02.json','post-admission-result-02.json']:assert json.loads((HERE/name).read_text())['passed']
for name in ['base/extract-upstream-dynamic-detail.py','desired/extract-upstream-dynamic-detail.py','integration.patch','delta-identity.json','compile-delta.py','run-delta.py','compile-evidence-02.json','compile-02.log','compiler-02.args','composer-session-result-02.json','composer-session-result-02.log','composer-session-result-02-classload.log','post-admission-result-02.json','post-admission-result-02.log','post-admission-result-02-classload.log','PostAdmissionFixture.kt','same-owner-full-liquid-layout.png','freeze-delta.py']:
 cp(HERE/name,FINAL/name)
for name in ['compile-evidence-03.json','compile-03.log','composer-session-result-03.log','composer-session-result-03-classload.log']:
 cp(HERE.parent/name,FINAL/'history'/name)
cp(HERE/'composer-session-result-01.json',FINAL/'history/composer-session-result-01-inaccurate-rawMain-label.json')
cp(HERE/'composer-session-result-01.log',FINAL/'history/composer-session-result-01.log')
for i,r in enumerate(e['sourceHashes']):
 p=Path(r['path']);cp(p,FINAL/'proof-sources'/f'{i:02d}-{p.name}')
write(FINAL/'dependency-identities.json',json.dumps(e['dependencies'],indent=2)+'\n')
write(FINAL/'INSTALL.txt',"""One narrow Windows postComment task-admission fix. Do not install task classes or generated Kotlin.
Base extractor byte SHA b4fb708e2ca2e8fd13795ed72bca444fa2bbb1a3cd85857ab4b7a6bdc765ec73.
Desired extractor byte SHA f93d88d47e87a418b41cc3217a759210a0a2d94cbe33325c3d713e9724e04004.
Copy desired/extract-upstream-dynamic-detail.py to the same Main tools target only after matching base; or inspect integration.patch. Existing Root selectedCommentTarget getter is retained.
Only postComment first checks isOwned, captures existing immutable original reply target before launchOwned, then uses unchanged original request body and parameters. Subject parsing/CSRF/transport/reload/count/generation/retirement remain. Original post body has no posting state/admission gate; no business duplicate gate added. Composer immediate cleanup order and global CoroutineStart are unchanged.
Actual immutable828/89CP plus exactly one declared prepared Session product override. Other NEXT UI sources are prepared original source closures, not Main UI acceptance. Strict original full composer/layout IME dispatch preserves 701 root and 701 parent. Six deterministic actual queued dispatcher cases pass: capture before synchronous clear, close queued, pre-cancelled scope, retired account owner, changed request generation, close after non-cancellable request starts (no late callback).
Original unmodified actual Main Session loss is retained in history03: post root/parent 0/0 violates unchanged 701/701 assertion. First patched result01 had inaccurate rawMainReplySession=true label: actual class-load source was prepared override; keep history and do not credit that label. Corrected result02 writes actual CodeSource, rawMain=false/preparedSessionAdmissionOverride=true.
Reuse PostAdmissionFixture.kt + pure frozen ReplySessionFixture.kt test helper against a future fixed actual Main friend classpath, with no product override; requests are synthetic terminal fixtures, no network. No Main/sharedGradle/HWND/account writes performed here.
""")
art=[]
for raw in safe(FINAL).rglob('*'):
 if raw.is_file():
  p=Path(str(raw)[4:]);art.append(dict(path=p.relative_to(FINAL).as_posix(),sha256Bytes=sha(p),bytes=raw.stat().st_size))
manifest=dict(artifactCount=len(art),scope='One prepared existing Session admission override; source-only install, actual UI and queued synthetic terminal proof; not Main consumer acceptance',MainConsumerAcceptance=False,installPayloads=[dict(source='desired/extract-upstream-dynamic-detail.py',target='desktop/tools/extract-upstream-dynamic-detail.py',baseSha256Bytes='b4fb708e2ca2e8fd13795ed72bca444fa2bbb1a3cd85857ab4b7a6bdc765ec73',desiredSha256Bytes='f93d88d47e87a418b41cc3217a759210a0a2d94cbe33325c3d713e9724e04004')],artifacts=sorted(art,key=lambda x:x['path']))
write(FINAL/'frozen-handoff.json',json.dumps(manifest,indent=2)+'\n')
for r in art:assert sha(FINAL/r['path'])==r['sha256Bytes']
print(json.dumps(dict(path=str(FINAL/'frozen-handoff.json'),sha256Bytes=sha(FINAL/'frozen-handoff.json'),artifacts=len(art)),indent=2))
