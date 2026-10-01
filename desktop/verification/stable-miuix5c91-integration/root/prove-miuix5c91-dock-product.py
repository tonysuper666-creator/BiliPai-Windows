from pathlib import Path
import hashlib, json, subprocess, importlib.util, zipfile, sys
HERE=Path(__file__).resolve().parent; MAIN=HERE.parents[2]; REPO=MAIN.parent/'BiliPai-v023'
phase=int(sys.argv[1]); SNAP=MAIN/f'desktop/.local/stable-product-snapshot-{phase}'
OUT=MAIN/f'desktop/.local/stable-miuix5c91-main{phase}-dock-ui-proof'
resume=len(sys.argv)>2 and sys.argv[2]=='--resume-collector'
assert resume or not OUT.exists()
def wide(p):
    s=str(Path(p).absolute()); return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def read(p): return wide(p).read_bytes()
def sha(b): return hashlib.sha256(b).hexdigest()
def pin(p, digest):
    b=read(p); assert sha(b)==digest,p; return b
manifest=read(SNAP/'manifest.json'); metadata=json.loads(manifest)
assert metadata['wholeCandidateClassesPassed'] and metadata['sourceRegistryCount']==770
cp_raw=pin(SNAP/'ordered-runtime-cp.json',metadata['orderedRuntimeClasspathSha256Bytes']); cp=json.loads(cp_raw)
assert len(cp)==92
for row in cp: pin(row['path'],row['sha256Bytes'])
main=next(r['path'] for r in cp if r.get('source')=='desktop/build/classes/kotlin/main')
fork=next(r['path'] for r in cp if 'miuix5157-jvm-' in r['path'])
assert '0.9.4-5c91d5e5-windows-source1' in fork
spec=importlib.util.spec_from_file_location('compiler',MAIN/'desktop/.local/source9-appearance/compile-miuix.py')
c=importlib.util.module_from_spec(spec); spec.loader.exec_module(c)
OUT.mkdir(exist_ok=resume); results=[]
for name, lane, frozen, fixture, entry in (
    ('frosted-audio','stable-frosted-audio-renderer-parity','a35f99c0f41a474c08814fcddfc9dd99f01b003aab83b54b919d9059901efb73','FrostedAudioFixture.kt','com.bilipai.desktop.ui.frostedAudioProof.FrostedAudioFixtureKt'),
    ('linked-dock','stable-linked-dock-parity','e5eb03ec2e44d0b20e6817bcd53eab30851ff60682f7467cb19ce2475e4a9602','LinkedDockFixture.kt','com.bilipai.desktop.ui.linkedDockProof.LinkedDockFixtureKt')):
    lane=REPO/'desktop/.local'/lane
    m=json.loads(pin(lane/'frozen-handoff.json',frozen))
    row=next(r for r in m['evidence'] if Path(r['path']).name==fixture)
    original=pin(row['path'],row['sha256Bytes']); adapted=original
    adaptation=None
    if name=='linked-dock':
        old=b'"top.yukonga.miuix.kmp.icon.extended.HomeKt" to candidate,'
        new=b'"top.yukonga.miuix.kmp.icon.extended.HomeKt" to Path.of(args[3]).toRealPath(),'
        assert original.count(old)==1
        adapted=original.replace(old,new,1)
        adaptation='Only fixture expected CodeSource for full Home icon points to the actual sole Miuix5157 dependency; every behavior assertion and pointer event is unchanged.'
    run=OUT/name; completed=resume and (run/'runtime/result.json').exists()
    run.mkdir(exist_ok=resume)
    if completed: assert read(run/fixture)==adapted
    else: (run/fixture).write_bytes(adapted)
    args=['-no-stdlib','-no-reflect','-jvm-target','21','-Xplugin='+str(c.PLUGIN),
          '-module-name','com_bilipai_desktop_bilipai_windows','-Xfriend-paths='+main,
          '-cp',';'.join(r['path'] for r in cp),'-d',str(run/'classes'),str(run/fixture)]
    args_text='\n'.join('"'+a.replace('\\','/')+'"' for a in args)
    if completed: assert (run/'compile.args').read_text(encoding='utf-8')==args_text
    else:
        (run/'compile.args').write_text(args_text,encoding='utf-8')
        p=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-Xmx2g','-cp',';'.join(map(str,c.COMPILER)),
            'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(run/'compile.args')],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=180)
        (run/'compile.log').write_text(p.stdout+p.stderr,encoding='utf-8'); p.check_returncode()
    compiled={str(p.relative_to(run/'classes')).replace('\\','/') for p in (run/'classes').rglob('*.class')}
    overlaps=[]
    for r in cp:
        with zipfile.ZipFile(wide(r['path'])) as z:
            overlaps.extend(dict(className=n,artifact=r['path']) for n in compiled.intersection(z.namelist()))
    assert not overlaps,overlaps
    runtime=run/'runtime'
    if not completed:
        runtime.mkdir()
        p=subprocess.run([str(c.JAVA),'-Djava.awt.headless=true','-Dfile.encoding=UTF-8','-cp',str(run/'classes')+';'+';'.join(r['path'] for r in cp),
            entry,str(runtime),main,main,fork],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=90)
        (run/'runtime.log').write_text(p.stdout+p.stderr,encoding='utf-8'); p.check_returncode()
    actual=json.loads(read(runtime/'result.json'))
    terminal=(run/'runtime.log').read_text(encoding='utf-8').strip().splitlines()[-1]
    if name=='frosted-audio':
        assert actual['status']=='PASS' and actual['assertions']==63 and actual['pointerPairs']==32
        assert json.loads(terminal)==actual
    else:
        assert actual['assertions']==57 and actual['pointerPairs']==6 and actual['caseCount']==6
        assert terminal=='PASS 57 assertions, 6 cases, 6 actual Compose pointer pairs'
    result=dict(name=name,passed=True,exactFrozenFixtureSHA256=sha(original),executedFixtureSHA256=sha(adapted),
                fixtureOnlyAdaptation=adaptation,productionClassOverrides=0,overlaps=overlaps,completedRunCollectedWithoutRepeating=completed,
                actualProductPhase=phase,actualProductCodeSource=main,actualMiuixCodeSource=fork,**{k:v for k,v in actual.items() if k!='passed'})
    (run/'accepted.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8'); results.append(result)
    print(json.dumps(dict(name=name,passed=True,assertions=actual['assertions'],pointerPairs=actual['pointerPairs'])))
for r in cp: pin(r['path'],r['sha256Bytes'])
result=dict(passed=True,actualProductPhase=phase,actualSnapshotSHA256=sha(manifest),actualOrderedCP_SHA256=sha(cp_raw),orderedCPCount=92,
            productionClassOverrides=0,cases=results,assertions=sum(r['assertions'] for r in results),pointerPairs=sum(r['pointerPairs'] for r in results),
            rootNavigationMounted=False,actualNativePlaybackAccepted=False,realAccountOrHTTPUsed=False,systemHWNDUsed=False,desktopExeReplaced=False)
(OUT/'result.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8'); print(json.dumps(dict(passed=True,assertions=result['assertions'],pointerPairs=result['pointerPairs'])))
