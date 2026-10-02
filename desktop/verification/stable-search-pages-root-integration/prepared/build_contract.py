from pathlib import Path
import hashlib,json,os,subprocess,difflib
P=Path(__file__).resolve().parent;M=P.parents[2];C=M.parent/'BiliPai-v023';HEAD='6ac84c8036ed4c75e2944d92d1020566e283e983';FEATURE='desktop-full-original-search-root-parity'
def wide(p):return Path('\\\\?\\'+os.path.abspath(p))
def read(p):return wide(p).read_bytes()
def sha(b):return hashlib.sha256(b).hexdigest()
def dump(p,v):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf8',newline='\n')
def git(path):return subprocess.check_output(['git','show',HEAD+':'+path],cwd=C)
existing=[];new=[]
for file in sorted((P/'prepared').rglob('*')):
 if not file.is_file():continue
 path=file.relative_to(P/'prepared').as_posix();prepared=read(file)
 try:baseline=git(path)
 except subprocess.CalledProcessError:
  new.append(dict(path=path,preparedPath=str(file),sha256Bytes=sha(prepared)));continue
 before=baseline.decode().replace('\r\n','\n');after=prepared.decode().replace('\r\n','\n')
 a=before.splitlines(keepends=True);b=after.splitlines(keepends=True);matcher=difflib.SequenceMatcher(a=a,b=b,autojunk=False)
 hunks=[]
 for group in matcher.get_grouped_opcodes(4):
  i0=group[0][1];i1=group[-1][2];j0=group[0][3];j1=group[-1][4]
  old=''.join(a[i0:i1]);newText=''.join(b[j0:j1]);assert before.count(old)==1,path
  hunks.append(dict(before=old,after=newText,baselineLine=i0+1,beforeSha256LF=sha(old.encode()),afterSha256LF=sha(newText.encode())))
 inverse=after
 for h in hunks:assert inverse.count(h['after'])==1;inverse=inverse.replace(h['after'],h['before'])
 assert inverse==before,path
 existing.append(dict(path=path,baselineGitBytesSha=sha(baseline),baselineLFsha=sha(before.encode()),preparedPath=str(file),preparedBytesSha=sha(prepared),prospectiveLFsha=sha(after.encode()),exactHunks=hunks))
catalog=json.loads(git('desktop/upstream-sources.json'));sourceBy={r['path']:r for r in catalog['sources']};resourceBy={r['path']:r for r in catalog.get('resources',[])}
adaption=json.loads(read(P/'adaptation-spec.json'));srcPaths={r['path']for r in adaption['fullAdaptations']}|set(adaption['directSources'])
BASE='app/src/main/java/com/android/purebilibili/'
srcPaths|={BASE+p for p in ['core/store/SettingsManager.kt','core/ui/skeleton/ContentLoadingSkeletons.kt','data/repository/SearchLoadPolicy.kt','data/repository/SearchRecommendPolicy.kt','core/database/dao/SearchHistoryDao.kt','data/model/entity/SearchHistory.kt','core/store/SearchHintSettingsStore.kt','navigation3/BiliPaiNavKey.kt','data/model/response/SearchModels.kt','core/network/ApiClient.kt','core/network/WbiUtils.kt','core/ui/BackToTopPreference.kt','core/ui/components/AppLiquidGlassBackToTopButton.kt','core/store/BackToTopSettingsStore.kt']}
unions=[];rows=[]
for path in sorted(srcPaths):
 raw=read(C/path);pin=sha(raw.replace(b'\r\n',b'\n'))
 if path in sourceBy:
  baseline=sourceBy[path];assert baseline['sha256']==pin,path
  unions.append(dict(path=path,sha256=pin,baselineMode=baseline.get('mode'),appendFeatures=[FEATURE],preserveEveryExistingField=True))
 else:rows.append(dict(path=path,sha256=pin,features=[FEATURE],mode='direct'if path in adaption['directSources']else'platform-rewrite'if (path.endswith('ViewModel.kt')or path.endswith('BackToTopSettingsStore.kt'))else'policy-extract'))
resources=[]
for path in ['app/src/main/res/values/strings.xml','app/src/main/res/values-zh-rTW/strings.xml','app/src/main/res/values-en/strings.xml']:
 if path in resourceBy:resources.append(dict(path=path,sha256=resourceBy[path]['sha256'],appendFeatures=[FEATURE],preserveEveryExistingField=True))
gradle='''
// Complete original Search/SearchTrending page and VM bodies. Common DIRECT sources stay
// solely with prepareUpstreamSources; no selected duplicate submit/tab-order declarations.
val extractOriginalSearchPages by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-search-pages.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-search-pages").get().asFile.absolutePath)
    inputs.file("tools/extract-upstream-search-pages.py")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "desktop-full-original-search-root-parity" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-search-pages"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-search-pages")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalSearchPages) }
'''
wide(P/'gradle-append.kts').write_text(gradle,encoding='utf8',newline='\n')
delta=dict(baseline=HEAD,baselineSources=len(sourceBy),baselineResources=len(resourceBy),feature=FEATURE,newSources=rows,existingSourceUnions=unions,existingResourceUnions=resources,newResources=[],newDependencies=[],gradleAppendFile=str(P/'gradle-append.kts'),gradleAppendSha256Bytes=sha(gradle.encode()),soleProducer='desktop/tools/extract-upstream-search-pages.py',directProducer='prepareUpstreamSources')
dump(P/'registry-delta.json',delta)
dump(P/'install-contract.json',dict(baseline=HEAD,candidateWritten=False,existingFamilies=existing,newCanonicalFiles=new,registryDeltaFile=str(P/'registry-delta.json'),gradleAppendFile=str(P/'gradle-append.kts'),doNotCopyPreparedWholeExistingFiles=True,doNotCopyGeneratedOrDirectPreparationTrees=True))
print('Families',len(existing),'hunks',sum(len(r['exactHunks'])for r in existing),'newfiles',len(new),'sources+',len(rows),'unions',len(unions),'resourcesunions',len(resources))
for row in rows:print('NEW',row['mode'],row['path'])
for row in unions:
 if '/feature/search/'in row['path']:print('UNION',row['baselineMode'],row['path'])
