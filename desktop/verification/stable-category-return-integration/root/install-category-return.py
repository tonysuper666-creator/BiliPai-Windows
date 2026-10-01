from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
OUT=HERE/'category-return-install'
if OUT.exists():assert not any(OUT.iterdir())
else:OUT.mkdir()
sha=lambda b:hashlib.sha256(b).hexdigest()
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
lanes={
 'category':(MAIN/'desktop/.local/stable-home-category-page-parity','33a9d7f107a9dce5454241dba2f7e43d03c25f6dee22d890e9d72369ee878b8b'),
 'return':(MAIN/'desktop/.local/stable-home-return-navigation-parity','6a7eb9e749bfc00f4f4d528385e1f8e68990d134457e4ef1b903813e6df9f2eb'),
}
for name,(lane,pin) in lanes.items():
 raw=wide(lane/'frozen-handoff.json').read_bytes();assert sha(raw)==pin,name
 doc=json.loads(raw)
 for row in doc.get('artifacts',doc.get('rawArtifacts',[])):
  assert sha(wide(lane/row['path']).read_bytes())==row['sha256Bytes'],(name,row['path'])
installed=[];hunks=[];new=[];merged=[]
def payload(lane,source,target):
 p=REPO/target;assert not p.exists(),target;p.parent.mkdir(parents=True,exist_ok=True)
 b=wide(lane/source).read_bytes();p.write_bytes(b);installed.append(dict(path=target,sha256Bytes=sha(b)))
category=lanes['category'][0];returns=lanes['return'][0]
for name in ['desktop/tools/extract-upstream-category-page.py','desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopCategoryEnvironment.kt','desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopCategoryRouteHost.kt']:
 payload(category,'prepared/'+name,name)
for row in json.loads((returns/'install-whitelist.json').read_text(encoding='utf-8'))['files']:
 payload(returns,row['source'],row['destination'])
for row in json.loads((category/'sole-helper-producer-hunks.json').read_text(encoding='utf-8')):
 p=REPO/row['path'];raw=p.read_bytes().replace(b'\r\n',b'\n');assert sha(raw)==row['baseSha256LF'],row['path']
 before=raw.decode('utf-8');text=before
 for change in row['changes']:
  assert text.count(change['before'])==1,(row['path'],change['before'][:80]);text=text.replace(change['before'],change['after'])
 assert sha(text.encode())==row['candidateSha256LF'],row['path']
 (OUT/Path(row['path']).name).write_bytes(raw)
 p.write_text(text,encoding='utf-8',newline='\n');hunks.append(row)
registryPath=REPO/'desktop/upstream-sources.json';registry=json.loads(registryPath.read_text(encoding='utf-8'));beforeCount=len(registry['sources']);index={r['path']:r for r in registry['sources']}
def merge(row):
 p=row['path'];h=row['sha256'];assert sha((REPO/p).read_bytes().replace(b'\r\n',b'\n'))==h,p
 if p in index:
  current=index[p];assert current['sha256']==h
  for f in row.get('features',row.get('addFeatures',[])):
   if f not in current['features']:current['features'].append(f)
  merged.append(p)
 else:
  assert 'mode' in row
  current={k:row[k]for k in ('path','sha256','mode','features')};registry['sources'].append(current);index[p]=current;new.append(p)
recipe=json.loads((category/'registry-recipe.json').read_text(encoding='utf-8'));merge(recipe['page'])
for row in recipe['existing']:merge(row)
for row in json.loads((returns/'upstream-source-delta.json').read_text(encoding='utf-8'))['rows']:merge(row)
registryPath.write_text(json.dumps(registry,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
build=REPO/'desktop/build.gradle.kts';text=build.read_text(encoding='utf-8');snippet=''
for task,tool,directory,feature in [
 ('prepareOriginalCategoryPage','tools/extract-upstream-category-page.py','generated/category-page','category-page'),
 ('prepareOriginalHomeReturnNavigation','tools/extract-upstream-home-return-navigation.py','generated/home-return-navigation','stable-home-return-navigation')]:
 assert 'val '+task+' by'not in text
 snippet+='''
val __TASK__ by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "__TOOL__",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("__DIR__").get().asFile.absolutePath)
    inputs.files("__TOOL__", "tools/sync-upstream.py")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "__FEATURE__" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("__DIR__"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("__DIR__")) }
tasks.named("compileKotlin") { dependsOn(__TASK__) }
'''.replace('__TASK__',task).replace('__TOOL__',tool).replace('__DIR__',directory).replace('__FEATURE__',feature)
marker='val extractUpstreamHomeFullCard by tasks.registering(Exec::class) {';assert text.count(marker)==1
build.write_text(text.replace(marker,snippet+'\n'+marker),encoding='utf-8',newline='\n')
report=dict(payloads=installed,sharedProducerHunks=hunks,sourceCountBefore=beforeCount,sourceCountAfter=len(registry['sources']),newIdentities=new,featureMerges=merged,preparedManifests={k:v[1]for k,v in lanes.items()},actualRootMounted=False)
(OUT/'installed.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8');print(json.dumps(dict(payloads=len(installed),sourceCount=len(registry['sources']),rootMounted=False)))
