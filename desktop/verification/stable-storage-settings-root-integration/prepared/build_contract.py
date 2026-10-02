from pathlib import Path
import difflib,hashlib,json,os,subprocess
P=Path(__file__).resolve().parent;M=P.parents[2];C=M.parent/'BiliPai-v023'
BASE='a5ba7265ffcc0157cd9cdab768e0a211fa9e4478'
FEATURE='desktop-storage-cache-settings-owner-parity'
def wide(p):return Path('\\\\?\\'+os.path.abspath(p))
def read(p):return wide(p).read_bytes()
def hash(b):return hashlib.sha256(b).hexdigest()
def norm(b):return b.replace(b'\r\n',b'\n')
def write(p,b):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes(b.encode('utf8')if isinstance(b,str)else b)
def dump(p,v):write(p,json.dumps(v,ensure_ascii=False,indent=2)+'\n')
def git(path):
 r=subprocess.run(['git','show',BASE+':'+path],cwd=C,capture_output=True)
 return r.stdout if r.returncode==0 else None
rows=[];existing=[];patches=[]
for source in sorted((P/'prepared').rglob('*')):
 if not source.is_file()or source.suffix not in('.kt','.py'):continue
 target=source.relative_to(P/'prepared').as_posix();after=read(source);before=git(target)
 row=dict(target=target,prepared=source.relative_to(P).as_posix(),afterSha256Bytes=hash(after),afterSha256LF=hash(norm(after)),new=before is None)
 if before is not None:
  baseline=P/'baseline'/target
  if not wide(baseline).exists():write(baseline,read(C/target))
  original=read(baseline);assert norm(original)==norm(before),target
  row.update(beforeSha256Bytes=hash(original),beforeSha256LF=hash(norm(before)),baseline=baseline.relative_to(P).as_posix())
  old=norm(original).decode('utf8');new=norm(after).decode('utf8');a=old.splitlines(keepends=True);b=new.splitlines(keepends=True)
  for context in (3,6,12,24,48):
   groups=list(difflib.SequenceMatcher(a=a,b=b,autojunk=False).get_grouped_opcodes(context))
   if all(old.count(''.join(a[g[0][1]:g[-1][2]]))==1 and new.count(''.join(b[g[0][3]:g[-1][4]]))==1 for g in groups):break
  hunks=[]
  for index,group in enumerate(groups):
   i1,i2=group[0][1],group[-1][2];j1,j2=group[0][3],group[-1][4]
   # Prefer the changed lines alone when uniquely anchored in both directions.
   # In particular Root's new Comment binding around the mount must not be overwritten.
   ti,tj=i1,j1;ei,ej=i2,j2
   while ti<ei and tj<ej and a[ti]==b[tj]:ti+=1;tj+=1
   while ti<ei and tj<ej and a[ei-1]==b[ej-1]:ei-=1;ej-=1
   coreOld=''.join(a[ti:ei]);coreNew=''.join(b[tj:ej])
   if coreOld and coreNew and old.count(coreOld)==1 and new.count(coreNew)==1:i1,i2,j1,j2=ti,ei,tj,ej
   remove=''.join(a[i1:i2]);add=''.join(b[j1:j2]);assert remove!=add and old.count(remove)==1,(target,index)
   hunks.append(dict(id=index+1,beforeLine=i1+1,afterLine=j1+1,before=remove,after=add,beforeSha256LF=hash(remove.encode()),afterSha256LF=hash(add.encode())))
  prospective=old
  for h in hunks:assert prospective.count(h['before'])==1;prospective=prospective.replace(h['before'],h['after'])
  assert prospective==new,target
  inverse=new
  for h in reversed(hunks):assert inverse.count(h['after'])==1;inverse=inverse.replace(h['after'],h['before'])
  assert inverse==old,target
  current=norm(read(C/target)).decode('utf8')
  patch=dict(target=target,baselineHead=BASE,contextLines=context,baselineSha256LF=hash(old.encode()),preparedSha256LF=hash(new.encode()),forwardAndInverseVerified=True,
      candidateCurrentSha256LF=hash(current.encode()),currentEqualsBaseline=current==old,
      currentAnchorMatches=[dict(id=h['id'],count=current.count(h['before']))for h in hunks],hunks=hunks)
  patchfile=Path('patches')/(target.replace('/','__')+'.json');dump(P/patchfile,patch)
  row['exactHunkFile']=patchfile.as_posix();row['hunks']=len(hunks);existing.append(target);patches.append(patch)
 rows.append(row)
assert len(rows)==23 and len(existing)==19,(len(rows),len(existing))
registry=read(C/'desktop/upstream-sources.json');catalog=json.loads(registry)
delta=[];identities=json.loads(read(P/'generated06/storage-source-inventory.json'))
for original in identities:
 identity=original['path'];kind='resources'if'/src/main/res/'in identity else'sources';raw=read(C/identity);normalized=norm(raw)
 matches=[r for r in catalog[kind]if r['path']==identity];assert len(matches)<=1
 if matches:
  old=matches[0];assert old['sha256']==hash(raw if old.get('hashNormalization','lf')=='raw'else normalized)
  delta.append(dict(kind=kind,path=identity,operation='feature-union',expectedExisting=old,appendFeature=FEATURE,sourceRawSha256Bytes=hash(raw),sourceLFSha256=hash(normalized)))
 else:
  new=dict(path=identity,mode=original['mode'],features=[FEATURE],sha256=hash(normalized))
  delta.append(dict(kind=kind,path=identity,operation='append-identity',row=new,sourceRawSha256Bytes=hash(raw),sourceLFSha256=hash(normalized)))
dump(P/'registry-delta.json',dict(baselineHead=BASE,currentInputSha256Bytes=hash(registry),appendSources=3,unionSources=4,appendResources=1,rows=delta,
    noWholeRegistryReplacement=True,currentSources=len(catalog['sources']),currentResources=len(catalog['resources']),resultSources=len(catalog['sources'])+3,resultResources=len(catalog['resources'])+1))
gradle='''
// Original storage section, settings writes, cache confirmation and pure automatic policy.
// CacheClearUiPolicy remains mode=direct, produced solely by prepareUpstreamSources.
val extractOriginalStorageSettings by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-storage-settings.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-storage-settings").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-storage-settings.py", "tools/extract-upstream-media.py",
        "tools/extract-upstream-settings-search.py", "tools/sync-upstream.py")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "desktop-storage-cache-settings-owner-parity" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
    inputs.files(originalResources.filter { "desktop-storage-cache-settings-owner-parity" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-storage-settings"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-storage-settings")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalStorageSettings) }
'''
write(P/'gradle-append.kts',gradle)
dump(P/'gradle-delta.json',dict(target='desktop/build.gradle.kts',appendFile='gradle-append.kts',appendSha256Bytes=hash(gradle.encode()),baselineHead=BASE,currentInputSha256Bytes=hash(read(C/'desktop/build.gradle.kts')),
    noWholeBuildFileReplacement=True,task='extractOriginalStorageSettings',standaloneCommand='gradle -p desktop extractOriginalStorageSettings classes compileTestKotlin',newDependencies=0,
    generatedKotlin=5,directOriginalKotlin=1,directSoleProducer='prepareUpstreamSources'))
dump(P/'install-contract.json',dict(frozen=False,baselineHead=BASE,candidateWritten=False,copyWhitelist=rows,
    modifiedExistingSources=19,newOwnedSources=3,newProducer=1,exactHunkFamilies=19,sourceRegistryDelta='registry-delta.json',gradleAppend='gradle-delta.json',
    fullShellCompiled=True,CPUAssertions=46,actualNativeAssertions=20,RootMounted=False,accountVideoPassed=False,systemReceiverPassed=False,longPathSupported=False,detachTimeoutBoundaryPassed=False,
    instructions='Only exact related hunks on existing sources. New files copy by raw SHA. Preserve Parent current Comment/settings-route edits. Never replace whole Shell/registry/build files.'))
dump(P/'inverse-checks.json',dict(passed=True,families=len(patches),totalHunks=sum(len(p['hunks'])for p in patches),forwardInverse=True,baselineHead=BASE,
    candidateChangedTargets=[dict(target=p['target'],anchors=p['currentAnchorMatches'])for p in patches if not p['currentEqualsBaseline']]))
print('Contract',len(rows),'production files',len(patches),'existing exact forward/inverse families; registry +3 sources/+1 resource; no direct producer duplication')
