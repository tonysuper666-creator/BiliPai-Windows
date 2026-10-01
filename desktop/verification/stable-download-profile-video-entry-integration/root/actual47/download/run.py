from pathlib import Path
import hashlib,json,subprocess,importlib.util,sys,zipfile,re
sys.stdout.reconfigure(encoding='utf-8');sys.dont_write_bytecode=True
LANE=Path(__file__).resolve().parent;MAIN=LANE.parents[2];OLD=MAIN/'desktop/.local/stable-download-list-parity'
def safe(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def read(p):return safe(p).read_text(encoding='utf-8')
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def save(p,v):safe(p).write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
assert sha(OLD/'frozen-handoff.json')=='582f49bc0b43b01f31b6ca93f2381a2ac299771af5d80d8648f86c7d62a50958'
old=json.loads(read(OLD/'frozen-handoff.json'));oldFiles={row['path']:row['sha256Bytes'] for row in old['artifacts']}
source=OLD/'DownloadListFixture.kt';assert sha(source)==oldFiles['DownloadListFixture.kt']
snapshot=MAIN/'desktop/.local/stable-product-snapshot-47'
assert sha(snapshot/'manifest.json')=='72e862d74e18c2b388853634f0ef47c48ca1adba25b5d074ecd23fc6ecb7c3e0'
assert sha(snapshot/'ordered-runtime-cp.json')=='9fecec31b818a1b1d8be25f628ff6ec69e354a5c179ca42445aa1a1db3d52e94'
cp=json.loads(read(snapshot/'ordered-runtime-cp.json'));assert len(cp)==97
for row in cp:assert sha(row['path'])==row['sha256Bytes']
mainJar=snapshot/'main-kotlin.jar';assert sha(mainJar)=='6c67aade3e1862046237409101cff747c0f0913f5a868ed10b5955e3410dda2a'
assert str(mainJar) in [row['path'] for row in cp]
reference=OLD/'compile-03/original-download-list.jar';assert sha(reference)=='c2f2b68fb3bb06ee29e673f88b510484b49ae81928615cf23a95b2b7b83aa959'
assert str(reference) not in [row['path'] for row in cp]
spec=importlib.util.spec_from_file_location('proof',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
number=sys.argv[1] if len(sys.argv)>1 else '01';out=LANE/('run-'+number);assert not safe(out).exists();safe(out).mkdir()
before={'runtimeEntries':cp,'fixtureSource':{'path':str(source),'sha256Bytes':sha(source)},'fixtureOnlyOriginRunner':{'path':str(LANE/'ActualDownloadOriginRunner.kt'),'sha256Bytes':sha(LANE/'ActualDownloadOriginRunner.kt')},'fixtureOnlyRequiredPublicationConstruction':{'path':str(LANE/'ActualDownloadFixtureConstruction.kt'),'sha256Bytes':sha(LANE/'ActualDownloadFixtureConstruction.kt')},'referenceJarInspectedOnly':{'path':str(reference),'sha256Bytes':sha(reference),'onRuntimeClasspath':False},'oldFrozenManifestSha256Bytes':sha(OLD/'frozen-handoff.json')}
save(out/'pins-before.json',before)
args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(r['path'] for r in cp),'-Xfriend-paths='+str(mainJar),'-Xplugin='+str(c.PLUGIN),'-module-name','actual47_download_fixture','-d',str(out/'classes'),str(source),str(LANE/'ActualDownloadOriginRunner.kt'),str(LANE/'ActualDownloadFixtureConstruction.kt')]
argfile=out/'compiler.args';safe(argfile).write_text('\n'.join('"'+str(a).replace('\\','/')+'"' for a in args),encoding='utf-8',newline='\n')
r=subprocess.run([str(c.JAVA),'-Xmx3g','-Dfile.encoding=UTF-8','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(argfile)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=120)
safe(out/'compile.log').write_text(r.stdout+r.stderr,encoding='utf-8')
if r.returncode:print(r.stderr);sys.exit(r.returncode)
fixture=out/'actual47-download-fixture.jar';actual=set()
for row in cp:
 with zipfile.ZipFile(safe(row['path'])) as z:actual.update(n for n in z.namelist() if n.endswith('.class'))
fixtureNames=[]
with zipfile.ZipFile(safe(fixture),'w',zipfile.ZIP_DEFLATED) as z:
 for p in safe(out/'classes').rglob('*'):
  if p.is_file():
   name=p.relative_to(safe(out/'classes')).as_posix()
   if name.endswith('.class'):assert name not in actual,name;fixtureNames.append(name)
   z.writestr(name,p.read_bytes())
runtime=[str(fixture)]+[row['path'] for row in cp];assert str(reference) not in runtime
command=[str(c.JAVA),'-Dfile.encoding=UTF-8','-Dsun.stdout.encoding=UTF-8','-Dsun.stderr.encoding=UTF-8','-Djava.awt.headless=true','-cp',';'.join(runtime),'com.bilipai.desktop.proof.ActualDownloadOriginRunnerKt',str(out/'owned-fixture'),str(reference),str(mainJar)]
save(out/'runtime-command.json',command)
r=subprocess.run(command,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=45)
safe(out/'run.log').write_text(r.stdout+r.stderr,encoding='utf-8')
if r.returncode:print(r.stdout+r.stderr);sys.exit(r.returncode)
result=json.loads(re.search(r'DOWNLOAD_LIST_PROOF (\{[^\n]+\})',r.stdout).group(1));origin=json.loads(re.search(r'ACTUAL_DOWNLOAD_ORIGIN_PROOF (\{[^\n]+\})',r.stdout).group(1))
assert result['status']=='PASS' and result['assertions']==21 and result['pointerPairs']==2 and origin['status']=='PASS'
for row in cp:assert sha(row['path'])==row['sha256Bytes']
assert sha(source)==before['fixtureSource']['sha256Bytes'] and sha(reference)==before['referenceJarInspectedOnly']['sha256Bytes'] and sha(OLD/'frozen-handoff.json')==before['oldFrozenManifestSha256Bytes']
save(out/'pins-after.json',before)
result.update({'actualSnapshot':47,'actualRuntimeEntries':97,'actualInstalledOriginalUi':True,'runtimeProductionOverrides':0,'fixtureOnlyClassCount':len(fixtureNames),'fixtureProductionClassIntersection':[],'classOrigins':origin,'newFixtureJarSha256Bytes':sha(fixture),'all97RuntimeAndFixturePinsBeforeAfterEqual':True,'sharedGradleRun':False,'referenceJarRuntimeOrCompilerEntry':False,'legacyFieldMeaning':{'loadedCandidateClasses':'Historical fixture field: these 36 names are loaded from actual47; the older reference JAR supplies names only, never code.'}})
save(out/'proof-result.json',result)
items=[]
for p in sorted(safe(LANE).rglob('*')):
 if p.is_file() and p.suffix not in ['.class','.pyc'] and 'owned-fixture' not in p.parts and p.name!='frozen-handoff.json':items.append({'path':p.relative_to(safe(LANE)).as_posix(),'sha256Bytes':sha(p),'size':p.stat().st_size})
save(LANE/'frozen-handoff.json',{'status':'PASS_FROZEN_ACTUAL47_FIXTURE_ONLY','actualSnapshot':47,'runtimeEntries':97,'fixtureUnchangedFromFrozen58':True,'originalUiProof':result,'classOriginsBeforeAfter':origin,'productionOverrides':0,'sharedEdits':False,'sharedGradleRun':False,'fullRootMountedOrRuntimeAccepted':False,'old58Modified':False,'files':items})
print(json.dumps({'manifest':str(LANE/'frozen-handoff.json'),'manifestSha256Bytes':sha(LANE/'frozen-handoff.json'),'proof':result},indent=2))
