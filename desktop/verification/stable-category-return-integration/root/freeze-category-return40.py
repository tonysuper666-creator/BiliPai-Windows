from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023';OUT=REPO/'desktop/verification/stable-category-return-integration'
assert not OUT.exists()
sha=lambda b:hashlib.sha256(b).hexdigest()
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
rows=[];excluded=[]
def put(name,b):
 p=wide(OUT/name);p.parent.mkdir(parents=True,exist_ok=True);assert not p.exists();p.write_bytes(b)
 rows.append(dict(path=name,sha256Bytes=sha(b),sizeBytes=len(b)))
install=json.loads((HERE/'category-return-install/installed.json').read_text(encoding='utf-8'))
for name,laneName in [('category','stable-home-category-page-parity'),('return','stable-home-return-navigation-parity')]:
 lane=MAIN/'desktop/.local'/laneName;raw=wide(lane/'frozen-handoff.json').read_bytes();assert sha(raw)==install['preparedManifests'][name]
 put('prepared/'+name+'/frozen-handoff.json',raw);d=json.loads(raw)
 for r in d.get('artifacts',d.get('rawArtifacts',[])):
  b=wide(lane/r['path']).read_bytes();assert sha(b)==r['sha256Bytes']
  if Path(r['path']).suffix in ('.jar','.class','.kotlin_module','.dll','.pyc'):excluded.append(dict(lane=name,path=r['path'],sha256Bytes=sha(b),reason='Rebuildable preparation/compiler output'))
  else:put('prepared/'+name+'/'+r['path'],b)
for folder in ['category-return-install','category-return-actual40']:
 for p in sorted(wide(HERE/folder).rglob('*'),key=str):
  if not p.is_file():continue
  name='root/'+folder+'/'+p.relative_to(wide(HERE/folder)).as_posix();b=p.read_bytes()
  if p.suffix in ('.jar','.class','.kotlin_module','.dll','.pyc'):excluded.append(dict(path=name,sha256Bytes=sha(b),reason='Rebuildable fixture output'))
  else:put(name,b)
for name in ['install-category-return.py','prove-category-return40.py','freeze-category-return40.py','jvm-method-name-audit-40.json']:put('root/'+name,(HERE/name).read_bytes())
snap=MAIN/'desktop/.local/stable-product-snapshot-40'
for name in ['manifest.json','ordered-runtime-cp.json']:put('root/snapshot40/'+name,(snap/name).read_bytes())
put('root/classes-40.log',(REPO/'desktop/.local/stable-build-repair/classes-40.log').read_bytes())
proof=json.loads((HERE/'category-return-actual40/result.json').read_text(encoding='utf-8'));assert proof['passed']and proof['productionOverrides']==0
report=dict(wholeClassesPhase=40,wholeClassesPassed=True,sourceIdentityCount=871,resourceCount=213,actualRuntimeEntries=97,actualKotlinClasses=11156,invalidJvmMethodNames=0,
 completeOriginalCategoryUiAndViewModelCompiled=True,originalReturnNavigationOwnersCompiled=True,actualProductClassOrigins=10,categoryAssertions=16,categoryGroups=3,returnGroups=7,productionOverrides=0,
 fullRootNavigationOrPhysicalWindowAccepted=False,fullHomeRootMounted=False,newWindowsExeDeployed=False,
 pending=['Retained four-page aggregate/factory and simultaneous old planner retirement','Original Profile and Live subroutes','Full original navigation host, actual click geometry/backstack and transition window acceptance','Final EXE deployment'])
put('integration-report.json',(json.dumps(report,indent=2)+'\n').encode());put('root/compiled-output-exclusions.json',(json.dumps(excluded,indent=2)+'\n').encode())
manifest=(json.dumps(dict(schema='raw-artifact-manifest-v1',artifacts=rows),indent=2)+'\n').encode();wide(OUT/'artifact-manifest.json').write_bytes(manifest)
wide(OUT/'README.md').write_text('''# Original Category and Home return navigation — installed source closure

Windows classes40 compiles the full original 368-line Category UI and ViewModel, six original navigation3 bodies and the original AppNavigation/AppTopLevel selected callers. Only five source payloads were installed. Two existing sole producers now select the original fixed-column skeleton and original CutePersonLoadingIndicator wrapper; no reference helper, new dependency, API, client or persistent cache was installed. The inventory is871 original identities and213 resources.

The actual immutable40/97 product graph passes the same original prepared fixture bodies: Category3 groups/16 assertions and Return7 groups. Ten class-origin observations point to the actual product JAR, with zero production class replacements. Category checks single busy admission, replacement refresh, old response/finally isolation and route-only retirement with the same temporary global PluginStore. Return checks rejected navigation, immutable click geometry, exact500/501ms boundaries, nested source restoration after actual synthetic exposure changes and retired-owner rejection. Fixture input uses synthetic admission/region responses and no socket or real account mutation. Historical fixture scope text remains preserved; the outer actual40 receipt identifies the installed graph accurately.

This slice does not mount Root routes or accept a physical Window, related-detail stack, transition pixels or EXE. The original Home factory, four-page aggregate, Profile, Live subnavigation and full navigation host remain pending. Frozen preparation evidence is preserved, while rebuildable binaries are excluded and enumerated.
''',encoding='utf-8',newline='\n')
print(json.dumps(dict(rawArtifacts=len(rows),excludedBinaries=len(excluded),manifestSha256Bytes=sha(manifest))))
