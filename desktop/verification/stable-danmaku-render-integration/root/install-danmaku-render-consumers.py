from pathlib import Path
import hashlib,json,sys
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2].parent/'BiliPai-v023'
LANE=REPO/'desktop/.local/stable-danmaku-render-config-consumers-parity';DELTA=REPO/'desktop/.local/stable-danmaku-monitor-passive-delta'
OUT=HERE/'danmaku-render-consumers-install';OUT.mkdir(exist_ok=True);apply='--apply' in sys.argv
def wide(p):
    s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def read(p):return wide(p).read_bytes()
def sha(b):return hashlib.sha256(b).hexdigest()
def pin(p,d):
    b=read(p);assert sha(b)==d,p;return b
manifest=pin(LANE/'frozen-handoff.json','6d221c053309abd218b55a129634726bf5f99f28d4458a374069351468b69103');m=json.loads(manifest)
delta=json.loads(pin(DELTA/'frozen-handoff.json','ab13142a81130473f2fa4aaf2dee90816a5cccf24ba175cc499cd480f51f59a5'))
for cohort,base in ((m,LANE),(delta,DELTA)):
    for r in cohort['evidence']:pin(r['path'],r['sha256Bytes'])
originals={};planned={};records=[];rebase=[]
def body(path):
    if path not in planned:
        raw=read(REPO/path);originals[path]=raw;planned[path]=raw.decode().replace('\r\n','\n')
    return planned[path]
payload_producer='desktop/tools/extract-upstream-danmaku-list-menu.py'
for row in m['payload']:
    path=row['target'];raw=pin(row['source'],row['sha256Bytes'])
    originals[path]=read(REPO/path) if wide(REPO/path).exists() else None
    if path==payload_producer:
        first=next(h for h in json.loads(read(LANE/'local-hunks.json')) if h['path']==path)
        assert sha(originals[path].decode().replace('\r\n','\n').encode())==first['beforeSha256LF']
    planned[path]=raw.decode().replace('\r\n','\n')
groups=(('advanced',json.loads(pin(LANE/'local-hunks.json',m['localHunksSHA256']))),
        ('monitor',json.loads(pin(DELTA/'local-hunks.json',delta['localHunksSHA256']))),
        ('existing-test-caller',json.loads(read(LANE/'test-only-hunks.json'))))
for group,hunks in groups:
    for h in hunks:
        path=h['path']
        if group=='advanced' and path==payload_producer:continue
        before=body(path);drift=sha(before.encode())!=h['beforeSha256LF']
        if 'append' in h:
            assert h['old']=='' and not drift
            after=before+h['append']
        elif 'occurrenceOffset' in h:
            at=h['occurrenceOffset'];assert before[at:at+len(h['old'])]==h['old'],(path,group,at)
            after=before[:at]+h['new']+before[at+len(h['old']):]
        elif 'requiredOldCount' in h:
            assert before.count(h['old'])==h['requiredOldCount']
            positions=[];at=before.find(h['old'])
            while at>=0:
                positions.append(at);at=before.find(h['old'],at+len(h['old']))
            at=positions[h['occurrence']];after=before[:at]+h['new']+before[at+len(h['old']):]
        else:
            assert before.count(h['old'])==1,(path,group,before.count(h['old']),h.get('reason'))
            after=before.replace(h['old'],h['new'],1)
        if drift:rebase.append(dict(path=path,group=group,expectedBeforeLF=h['beforeSha256LF'],actualBeforeLF=sha(before.encode()),exactSingleLocalBodyMatched=True))
        else:assert sha(after.encode())==h['afterSha256LF'],(path,group)
        planned[path]=after;records.append(dict(path=path,group=group,reason=h.get('reason','existing required test-call capability'),beforeLF=sha(before.encode()),afterLF=sha(after.encode())))
test_path='desktop/src/test/kotlin/com/bilipai/desktop/danmaku/DanmakuTest.kt';before=body(test_path)
old='        override fun systemChromeInsetPx()=0 // Declared unused fixture chrome, never a production default.\n'
assert before.count(old)==1
after=before.replace(old,old+'        override fun maximumDisplayShortSidePx()=360f // Explicit fixture-only display mode, never a product fallback.\n',1)
planned[test_path]=after;records.append(dict(path=test_path,group='monitor-test-required-port',beforeLF=sha(before.encode()),afterLF=sha(after.encode()),reason='Existing collision fixture explicitly declares its own new required display port; no product default.'))
registry_path='desktop/upstream-sources.json';registry=json.loads(body(registry_path));assert len(registry['sources'])==770
by_path={r['path']:r for r in registry['sources']};d=json.loads(pin(LANE/'registry-delta.json',m['registryDeltaSHA256']))
assert d['newCount']==3
for operation in d['new']:
    row=operation['row'];assert row['path'] not in by_path
    original=read(REPO/row['path']).decode().replace('\r\n','\n');assert sha(original.encode())==row['sha256']
    registry['sources'].append(row);by_path[row['path']]=row
for operation in d['merge']:
    row=operation['row'];actual=by_path[row['path']];assert actual['sha256']==row['sha256']
    actual['features']=list(dict.fromkeys(actual['features']+row['features']))
assert len(registry['sources'])==773
planned[registry_path]=json.dumps(registry,ensure_ascii=False,indent=2)+'\n'
files=[]
for path,text in planned.items():
    result=text.encode();before=originals[path];files.append(dict(path=path,beforeSHA256Bytes=sha(before) if before is not None else None,afterSHA256Bytes=sha(result)))
    if apply:
        if before is not None:
            backup=wide(OUT/'baseline'/path);backup.parent.mkdir(parents=True,exist_ok=True);assert not backup.exists();backup.write_bytes(before)
        p=wide(REPO/path);p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(result)
report=dict(applied=apply,wholeOverwritesOnlyTwoExplicitPayloads=True,localHunks=records,exactLocalHunksRebasedAgainstNewerRoot=rebase,
    installedFiles=files,candidateSourceIdentities=773,resources=213,actualRootMonitorRuntimeAccepted=False,
    ordinaryNativePassThroughMatchesOriginalDefault=True,structuredSettingsHostAndRootGuardsModified=False,
    originalAdvanced213AndMonitor32FrozenEvidenceModified=False,wholeClassesPassed=False,desktopExeReplaced=False)
(OUT/('install-report.json' if apply else 'dry-run-report.json')).write_bytes((json.dumps(report,indent=2)+'\n').encode())
print(json.dumps(dict(applied=apply,files=len(files),localHunks=len(records),rebasedLocalHunks=len(rebase),sourceIdentities=773)))
