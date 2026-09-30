from pathlib import Path
import hashlib,json,sys,zipfile
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2]
def safe(p):
 value=str(Path(p).absolute());return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(path,value):safe(path).write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
manifest=HERE/'frozen-handoff.json'
assert not safe(manifest).exists(),'Never overwrite frozen proof'
evidence=json.loads(safe(HERE/'compile-evidence.json').read_text(encoding='utf-8'))
assert evidence['passed'] and evidence['activeClasses']=='classes-attempt5'
for row in evidence['sources']:assert sha(HERE/row['path'])==row['sha256Bytes'],row['path']
dependencies=json.loads(safe(HERE/'dependency-identities.json').read_text(encoding='utf-8'))
for row in dependencies:assert sha(row['path'])==row['sha256Bytes'],row['path']
baselines=json.loads(safe(HERE/'target-baselines.json').read_text(encoding='utf-8'))
for row in baselines:
 assert sha(REPO/row['path'])==row['baseSha256Bytes'],row['path']
 assert sha(HERE/'prepared'/row['path'])==row['desiredSha256Bytes'],row['path']
with zipfile.ZipFile(safe(dependencies[0]['path'])) as jar:product={n for n in jar.namelist() if n.endswith('.class')}
active={p.relative_to(safe(HERE/evidence['activeClasses'])).as_posix() for p in safe(HERE/evidence['activeClasses']).rglob('*.class')}
overlap=sorted(active&product)
allowed=('com/bilipai/desktop/ui/CommunityDynamicScreensKt','com/bilipai/desktop/ui/ComposableSingletons$CommunityDynamicScreensKt',
 'com/bilipai/desktop/settings/DesktopHomeRecommendationSettingsKt','com/bilipai/desktop/settings/ComposableSingletons$DesktopHomeRecommendationSettingsKt')
assert overlap and all(any(n.startswith(prefix+'$') or n==prefix+'.class' for prefix in allowed) for n in overlap),overlap
write(HERE/'product-class-overlap.json',dict(passed=True,onlyExistingConsumerClassFamilies=True,
 activeClassCount=len(active),overriddenClassCount=len(overlap),overriddenClasses=overlap,
 allowedFamilies=list(allowed),allOtherProductClassBytesUnchanged=True))
originals=json.loads(safe(HERE/'source-inventory.json').read_text(encoding='utf-8'))
rootManifest=REPO/'desktop/upstream-sources.json';existing={s['path']:s for s in json.loads(safe(rootManifest).read_text(encoding='utf-8'))['sources']}
new=[];extend=[]
for row in originals:
 actual=hashlib.sha256(safe(REPO/row['path']).read_text(encoding='utf-8').replace('\r\n','\n').encode()).hexdigest()
 assert actual==row['sha256'],row['path']
 if row['path'] in existing:
  assert existing[row['path']]['sha256']==actual,row['path'];extend.append(dict(path=row['path'],addFeatures=row['features']))
 else:new.append(row)
write(HERE/'source-registration-delta.json',dict(contextManifestSHA256Bytes=sha(rootManifest),
 existingIdentityFeatureExtensions=extend,newIdentities=new,resources=[],newDependencies=[],
 replaceWholeManifest=False,replaceWholeShell=False,replaceWholeBuildFile=False))
assert len(new)==12 and len(extend)==2
ui=json.loads(safe(HERE/'proof/ui-result.json').read_text(encoding='utf-8'))
assert len(ui)==2 and all(row['passed'] for row in ui)
assert json.loads(safe(HERE/'proof/junit-result.json').read_text(encoding='utf-8'))==dict(tests=13,passed=13,failed=0)
assert json.loads(safe(HERE/'proof/transport-result.json').read_text(encoding='utf-8'))['passed']
files=[]
for path in sorted(safe(HERE).rglob('*')):
 if not path.is_file() or '__pycache__' in path.parts or path.suffix=='.pyc':continue
 relative=path.relative_to(safe(HERE)).as_posix()
 if relative=='frozen-handoff.json':continue
 files.append(dict(path=relative,sha256Bytes=sha(path),sizeBytes=path.stat().st_size))
write(manifest,dict(frozen=True,activeClasses=evidence['activeClasses'],artifacts=files,dependencies=dependencies,
 originalSourceIdentities=originals,targets=baselines,regularJUnitMethods=13,pythonSourceChecks=4,
 originalUIThemes=2,actualProductRetrofitInterceptorFixturePassed=True,PNGCount=10,
 scope='Two real original settings with actual prepared consumers; not full Home/Dynamic parity',
 sharedGradle=False,mainEdits=False,HWND=False,accountData=False,actualHTTP=False,
 history='Attempts1/2 diagnostics and later exact cohorts preserved; only attempt5 is the final active source/class identity'))
print('FROZEN',len(files),'artifacts; SHA',sha(manifest))
