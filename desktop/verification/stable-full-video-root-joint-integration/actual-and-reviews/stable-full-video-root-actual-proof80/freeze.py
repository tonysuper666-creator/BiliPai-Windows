from pathlib import Path
import hashlib, json, os

P = Path(__file__).resolve().parent
def wide(p): return Path('\\\\?\\' + os.path.abspath(p))
def sha(p): return hashlib.sha256(wide(p).read_bytes()).hexdigest()
def dump(p,v): wide(p).write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf8',newline='\n')
success=P/'runs/actual82-01'
result=json.loads(wide(success/'acceptance-result.json').read_text(encoding='utf8'))
assert result['status']=='PASS_IDLE_ACTUAL_ROOT' and result['assertions']==17
assert result['productSourceOverrides']==0 and result['loadedOriginsBytesAllActual']
assert wide(success/'pins-before.json').read_bytes()==wide(success/'pins-after.json').read_bytes()
assert result['detailSuccessAccepted']==False and result['PiPTransferAccepted']==False
dump(P/'prepared-source.json',dict(status='actual82-idle-cohort-passed',
    sourceSHA256Bytes=sha(P/'RootIdleFixture.kt'),runnerSHA256Bytes=sha(P/'run.py'),
    actualSnapshot=82,fixtureOnly=True,productSourceOverrides=0,initialAnticipatedPhase=80))
artifacts=[]; excluded=[]
roots=[P/'RootIdleFixture.kt',P/'run.py',P/'freeze.py',P/'README.md',P/'prepared-source.json']
for run in ('actual81-01','actual81-02','actual81-03','actual82-01'):
    for f in sorted((P/'runs'/run).glob('*')):
        if f.is_file():
            if f.suffix in ('.jar','.class'): excluded.append(dict(path=f.relative_to(P).as_posix(),sha256Bytes=sha(f),reason='Fixture compiler output, not install payload'))
            else: roots.append(f)
    proof=P/'runs'/run/'fixture-owned-runtime/root-idle-proof.json'
    if wide(proof).exists(): roots.append(proof)
for name in ('actual-idle-root.png','actual-settings-cover.png'):
    roots.append(success/'fixture-owned-runtime'/name)
for f in roots:
    artifacts.append(dict(path=f.relative_to(P).as_posix(),bytes=wide(f).stat().st_size,sha256Bytes=sha(f)))
dump(P/'frozen-handoff.json',dict(status='PASS_IDLE_ACTUAL_ROOT',actualSnapshot=82,
    manifestSHA256='91bb404a9be4c94f3e3e93eed068bfe50b2ca62558dd0af95322d3740797d339',
    orderedCPSHA256='68f82a3f4a3f43f8dae5625fbcb0b891b7f68c93f7b5bbc361eb5b588bb30b8b',
    immutableEntries=101,assertions=17,loadedProductOrigins=6,productSourceOverrides=0,
    copyWhitelist=[],artifacts=artifacts,binaryExclusions=excluded,
    retainedFailures=['actual81-01 fixture compile','actual81-02 readonly fixture slot reflection','actual81-03 actual product constructor volume callback'],
    installedCorrection='stable-video-root-constructor-volume-delta single Assembler hunk, built by Root in immutable82',
    boundaries=dict(unchangedMainEntry=False,detailSuccess=False,realAccount=False,globalInput=False,
        videoToVideoCarrier=False,pipTransfer=False,smtcButton=False,castOrDownloadSuccess=False,
        settingsVisualAccepted=False,settingsSnapshot='Captured transitional Home darkened frame after actual key changed; exact typed route/retention passed, visual Settings rendering not accepted'),
    omitted='Compiler classes/JARs and isolated runtime cache/PluginStore/LOCALAPPDATA files are not payload/evidence mirrors. OrderedCP/pin receipts remain included.'))
print(json.dumps(dict(manifestSHA256=sha(P/'frozen-handoff.json'),artifacts=len(artifacts),binaryExclusions=len(excluded)),indent=2))
