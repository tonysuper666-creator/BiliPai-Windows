from pathlib import Path
import hashlib, importlib.util, json, re, subprocess, sys, zipfile
sys.stdout.reconfigure(encoding='utf-8', errors='replace');sys.dont_write_bytecode=True
H=Path(__file__).resolve().parent;MAIN=H.parents[2]
S=MAIN/'desktop/.local/stable-product-snapshot-76';OUT=H/'runs/01';assert not OUT.exists();OUT.mkdir(parents=True)
def wide(p):
    s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def read(p):return wide(p).read_bytes()
def sha(p):return hashlib.sha256(read(p)).hexdigest()
def write(p,b):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes(b)
def dump(p,v):write(p,(json.dumps(v,indent=2)+'\n').encode())
assert sha(S/'manifest.json')=='65078b6029eeeaf44f9df605a884386b9f0495de7a76b28df1b616bd5c82176a'
assert sha(S/'ordered-runtime-cp.json')=='b8f76524bb39b8d09e50ea8532cd8eae6ee94c569a2e8140d650b86b43f11507'
cp=json.loads(read(S/'ordered-runtime-cp.json'));assert len(cp)==101
for row in cp:assert sha(row['path'])==row['sha256Bytes'],row['path']
sources=[]
for lane,name,packetPin in [('stable-original-settings-io-parity','SettingsIoProof.kt','0c9daf306710df68f2af52aae9bf75c1b21b217fdd2b70b780e2fc2a585e0b83'),
    ('stable-original-music-storage-parity','MusicStorageProof.kt','f3a99438b304983ca7dd9806c507baeecd0baf292a63d9bd573c0807c71fece1')]:
    origin=MAIN/'desktop/.local'/lane
    assert sha(origin/'frozen-handoff.json')==packetPin
    row=next(r for r in json.loads(read(origin/'frozen-handoff.json'))['artifacts'] if r['path']==name)
    assert sha(origin/name)==row['sha256Bytes']
    target=OUT/'inputs'/name;write(target,read(origin/name));sources.append(target)
spec=importlib.util.spec_from_file_location('cc',MAIN/'desktop/.local/source9-appearance/compile-miuix.py')
cc=importlib.util.module_from_spec(spec);spec.loader.exec_module(cc)
pins=dict(snapshot=76,manifest=sha(S/'manifest.json'),orderedCP=sha(S/'ordered-runtime-cp.json'),runtime=cp,
    inputs=[dict(path=str(p),sha256Bytes=sha(p),exactFrozenFixture=True) for p in sources],
    compiler=[dict(path=str(p),sha256Bytes=sha(p)) for p in [cc.JAVA]+cc.COMPILER])
dump(OUT/'pins-before.json',pins)
output=OUT/'fixture.jar'
args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','com_bilipai_desktop_bilipai_windows',
    '-Xfriend-paths='+cp[1]['path'],'-cp',';'.join(r['path'] for r in cp),'-d',str(output)]+list(map(str,sources))
write(OUT/'compile.args','\n'.join('"'+a.replace('\\','/')+'"' for a in args).encode())
command=[str(cc.JAVA),'-Xmx1g','-Dfile.encoding=UTF-8','-cp',';'.join(map(str,cc.COMPILER)),
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(OUT/'compile.args')]
dump(OUT/'compile-command.json',command)
r=subprocess.run(command,capture_output=True,timeout=120);write(OUT/'compile.log',r.stdout+r.stderr)
if r.returncode:
    dump(OUT/'result.json',dict(exit=r.returncode,compilerPassed=False,productionOverrides=0))
    print((r.stdout+r.stderr).decode('utf-8',errors='replace'));raise SystemExit(r.returncode)
exercised=['com/bilipai/desktop/plugins/DesktopPluginStore.class','com/bilipai/desktop/ui/DesktopOriginalPlayerSettingsContext.class',
    'com/bilipai/desktop/ui/DesktopOriginalMusicStorageBinding.class']+['com/android/purebilibili/core/store/'+name+'.class' for name in
    ['DesktopOriginalAudioHistoryStore','DesktopOriginalLocalPlaylistStore','PlayHistoryEntry','PlayLastSession','LocalPlaylist','LocalPlaylistItem']]
origins={};product=set()
for row in cp:
    with zipfile.ZipFile(wide(row['path'])) as archive:
        names=set(archive.namelist());product.update(n for n in names if n.endswith('.class'))
        for name in exercised:
            if name in names:origins.setdefault(name,[]).append(row)
with zipfile.ZipFile(wide(output)) as archive:classes={n for n in archive.namelist() if n.endswith('.class')}
assert product.isdisjoint(classes)
assert len(origins)==9 and all(len(v)==1 and v[0]['sha256Bytes']==cp[1]['sha256Bytes'] for v in origins.values())
dump(OUT/'class-origins.json',origins);dump(OUT/'overlap.json',dict(productClassOverlap=[],fixtureClasses=len(classes)))
results=[]
for name in ['SettingsIoProof','MusicStorageProof']:
    command=[str(cc.JAVA),'-Djava.awt.headless=true','-Dfile.encoding=UTF-8','-cp',
        str(output)+';'+';'.join(r['path'] for r in cp),'com.bilipai.desktop.ui.'+name+'Kt']
    dump(OUT/(name+'-command.json'),command)
    r=subprocess.run(command,capture_output=True,timeout=40);log=r.stdout+r.stderr;write(OUT/(name+'.log'),log)
    match=re.search((name+' PASS (\\d+) assertions;').encode(),log);passed=r.returncode==0 and match is not None
    results.append(dict(fixture=name,passed=passed,exit=r.returncode,assertions=int(match[1]) if passed else 0))
    if not passed:print(log.decode('utf-8',errors='replace'))
for row in cp:assert sha(row['path'])==row['sha256Bytes'],row['path']
for row in pins['inputs']+pins['compiler']:assert sha(row['path'])==row['sha256Bytes'],row['path']
dump(OUT/'pins-after.json',pins)
passed=all(r['passed'] for r in results)
dump(OUT/'result.json',dict(passed=passed,actualSnapshot=76,runtimeEntries=101,productionOverrides=0,
    fixtureSourceCount=2,fixtureClasses=len(classes),uniqueInstalledProductClassOrigins=9,
    assertions=sum(r['assertions'] for r in results),fixtures=results,pinsUnchanged=True,
    actualInstalledSettingsAndOriginalMusicStorage=True,fullOriginalStoreObjects=True,canonicalModelsRemainSoleOwners=True,
    standaloneMirrorCallerCancellationClaimed=False,completeRootMounted=False,nativeOrWindowAccepted=False,desktopExeReplaced=False))
print(json.dumps(dict(passed=passed,assertions=sum(r['assertions'] for r in results),productionOverrides=0,
    snapshot=76,uniqueInstalledProductClassOrigins=9)))
raise SystemExit(0 if passed else 1)
