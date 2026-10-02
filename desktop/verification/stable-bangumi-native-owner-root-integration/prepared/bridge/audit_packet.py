from pathlib import Path
import ast,hashlib,importlib.util,json,os,re,subprocess,sys,zipfile
sys.dont_write_bytecode=True;sys.stdout.reconfigure(encoding='utf8')
P=Path(__file__).resolve().parent;M=P.parents[2];C=M.parent/'BiliPai-v023';F=M/'desktop/.local/stable-original-bangumi-player-root-parity'
BASE='464f5573331dfc7f7c2f1e47a5bf6bbac8cfd201';UP='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589';NEW='79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40'
def wide(p):
 s=os.path.abspath(str(p));return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def sha(b):return hashlib.sha256(b).hexdigest()
def read(p):return wide(p).read_text(encoding='utf8').replace('\r\n','\n')
def write(p,t):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_text(t,encoding='utf8',newline='\n')
def git(*args):return subprocess.check_output(['git','-C',str(C),*args]).decode('utf8').replace('\r\n','\n')
def original(path,commit=UP):return git('show',commit+':'+path)
def record(path):return dict(path=str(path),sha256Bytes=sha(wide(path).read_bytes()))
assert git('rev-parse','HEAD').strip()==BASE
status=git('status','--porcelain');assert not status,status
assert sha(wide(F/'frozen-development-handoff.json').read_bytes())=='2dbe1b31f8b7e2a760eee0ca08515c9b855e03a740e0c2190fac70edd6aad554'

# Independent replay from pinned git-show source: do not execute the prospective
# producer to assert its own business correctness.
vm=json.loads(read(F/'vm-recipes.json'));checked=[]
for r in vm['recipes']:
 raw=original(r['originalPath']);assert sha(raw.encode())==r['originalSha256LF'];body=raw
 for h in reversed(r['edits']):
  lines=body.splitlines(keepends=True);i,j=h['startLine'],h['endLineExclusive'];before=''.join(lines[i:j])
  assert before==h['before']and sha(before.encode())==h['beforeSha256LF']
  body=''.join(lines[:i])+h['after']+''.join(lines[j:])
 assert sha(body.encode())==r['adaptedSha256LF']
 additions=[]
 if r['output'].endswith('DesktopOriginalBangumiPlayerViewModel.kt'):
  for before,after in [
   ('        val cachedDash: Dash? = null,\n','        val cachedDash: Dash? = null,\n        val cachedPlayData: BangumiVideoInfo? = null,\n'),
   ('                cachedDash = playData.dash,\n','                cachedDash = playData.dash,\n                cachedPlayData = playData,\n'),
   ('                    cachedDash = dash,\n','                    cachedDash = dash,\n                    cachedPlayData = playData,\n')]:
   assert body.count(before)==1;body=body.replace(before,after);additions.append(dict(before=before,after=after))
 output=read(P/'generated'/r['output']);assert output.split('\n',1)[1]==body,r['output']
 original_methods=re.findall(r'(?m)^\s*(?:(?:private|protected|override|suspend|open|internal)\s+)*fun\s+(\w+)\s*\(',raw)
 retained=set(re.findall(r'(?m)^\s*(?:(?:private|protected|override|suspend|open|internal)\s+)*fun\s+(\w+)\s*\(',body))
 excluded=set(original_methods)-retained
 assert excluded<={'createDashMediaSource','writeDashManifest','buildProgressiveMediaSourceFactory','onCleared'}
 checked.append(dict(originalPath=r['originalPath'],originalSha256LF=r['originalSha256LF'],output=r['output'],
  actualOutputSha256Bytes=sha(output.encode()),fullOriginalBodyInverse=True,originalMethodNames=original_methods,
  retainedMethodNames=sorted(retained),explicitPlatformReplacements=sorted(excluded),
  originalPlatformBoundaries=r['platformBoundaries'],nativeMetadataOnlyAdditions=additions,rootFullPlayerConsumer=False))
protocol='com/android/purebilibili/data/repository/DesktopOriginalBangumiPlayRequests.kt'
assert read(P/'generated'/protocol)==read(F/'generated'/protocol)
protocol_inverse=json.loads(read(F/'protocol-inverse.json'))
assert sha(original(protocol_inverse['originalPath']).encode())==protocol_inverse['originalSourceSHA']

# Existing source edits are exact fragments only. Verify forward/reverse replay,
# never install the prepared reference files by wholesale replacement.
targets=json.loads(read(P/'targets.json'));hunks=json.loads(read(P/'exact-hunks.json'))
assert targets['candidateBase']==BASE and targets['targetCount']==9 and targets['hunkCount']==17
for t in targets['targets']:
 before=read(C/t['path']);assert sha(before.encode())==t['beforeSha256LF'];body=before
 rows=[h for h in hunks if h['target']==t['path']];assert len(rows)==t['hunkCount']
 for h in reversed(rows):
  anchor=h['prefix']+h['before']+h['suffix'];assert sha(h['before'].encode())==h['beforeSha256LF']
  assert sha(anchor.encode())==h['exactAnchorSha256LF']and body.count(anchor)==1
  body=body.replace(anchor,h['prefix']+h['after']+h['suffix'])
 assert body==read(P/'prepared'/t['path'])and sha(body.encode())==t['afterSha256LF']
 for h in rows:
  anchor=h['prefix']+h['after']+h['suffix'];assert body.count(anchor)==1
  body=body.replace(anchor,h['prefix']+h['before']+h['suffix'])
 assert body==before

# Compare the new entire ordinary VM against the actual sole producer + one
# explicit platform delta; the existing giant original RECIPES are unchanged.
video_path='desktop/tools/extract-upstream-video-full-owner.py'
spec=importlib.util.spec_from_file_location('actual_video',C/video_path);actual_video=importlib.util.module_from_spec(spec);spec.loader.exec_module(actual_video)
actual_video.generate(C,P/'actual-video-replay',standalone=False)
delta={};exec(read(P/'fragments/video-owner-delta.py.txt'),delta)
vm_path='com/android/purebilibili/feature/video/viewmodel/VideoPlaybackViewModel.kt'
actual_body=read(P/'actual-video-replay'/vm_path)
assert read(P/'generated-video'/vm_path)==delta['bangumi_shared_owner_delta'](vm_path,actual_body)
def recipe_text(text):
 tree=ast.parse(text)
 return {n.targets[0].id:ast.dump(n.value,include_attributes=False)for n in tree.body if isinstance(n,ast.Assign)and isinstance(n.targets[0],ast.Name)and n.targets[0].id in ['RECIPES','FIXTURE','PROTOCOL']}
assert recipe_text(read(C/video_path))==recipe_text(read(P/'prepared'/video_path))
assert recipe_text(read(F/'prepared/desktop/tools/extract-upstream-bangumi-player.py'))==recipe_text(read(P/'prepared/desktop/tools/extract-upstream-bangumi-player.py'))

# The final compile consumes real immutable actual88 with explicit overlays. All
# historical compile/proof records remain unchanged and keep their own scopes.
S=M/'desktop/.local/stable-product-snapshot-88';cp=json.loads(read(S/'ordered-runtime-cp.json'))
assert sha(wide(S/'manifest.json').read_bytes())=='fb097cce71237ce93b6220940a5e930c9672239511f3937d037ebc05af0939d2'
assert sha(wide(S/'ordered-runtime-cp.json').read_bytes())=='0252dc7a049e87084a0510bf406808188648f759f977cfd453f0803ed7e1a62c'
assert len(cp)==105
for r in cp:assert sha(wide(r['path']).read_bytes())==r['sha256Bytes']
run=json.loads(read(P/'runs/06/result.json'));assert run['passed']and run['sourceCount']==19 and run['snapshot']==88
for r in run['explicitProspectiveSources']:assert sha(wide(r['path']).read_bytes())==r['sha256Bytes']
proof=json.loads(read(P/'proof-runs/04/result.json'));assert proof['passed']and proof['snapshot']==88
assert 'PASS 39 assertions;'in read(P/'proof-runs/04/run.log')
overlays=[];prefixes=[]
for r in run['explicitProspectiveSources']:
 path=Path(r['path']);name=path.name
 if name not in run['intentionalExistingSourceOverlays']:continue
 text=read(path);package=re.search(r'^package\s+([\w.]+)',text,re.M)[1].replace('.','/')
 names=re.findall(r'(?m)^(?:(?:internal|private|public|sealed|data|abstract|enum|open)\s+)*(?:class|interface|object)\s+(\w+)',text)
 for name in names:prefixes.append(package+'/'+name)
 prefixes.append(package+'/'+path.stem+'Kt')
 overlays.append(dict(file=path.name,topLevelClassPrefixes=[package+'/'+n for n in names]))
intersections=run['actualClassFqnIntersections']
assert intersections and all(any(n==p+'.class'or n.startswith(p+'$')for p in prefixes)for n in intersections)

# The original actual Root still consumes the older browser leaf. Helpers and
# seven full business bodies compiling do not imply the complete UI is wired.
shell=read(C/'desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt')
root_lines=[dict(line=i+1,text=line.strip())for i,line in enumerate(shell.splitlines())if 'BangumiPlayer'in line or 'BangumiBrowserScreen('in line]
assert 'com.android.purebilibili.feature.bangumi.BangumiPlayerScreen('not in shell
out=dict(candidateBase=BASE,candidateStatus=status,upstreamCommit=UP,immutableActualSnapshot=88,orderedClasspathEntries=105,
 fullOriginalBusinessBodyInverse=checked,protocolOriginalInverse=protocol_inverse,protocolOutputSameAsFrozen=True,
 ordinarySoleProducerRecipesUnchanged=True,ordinaryOutputEqualsActualPlusDeclaredDelta=True,
 existingExactTargetFamilies=9,existingExactFragments=17,reverseReplayByteEqual=True,
 narrowCompiledSources=19,intentionalExistingSourceOverlays=overlays,actualFqnIntersections=intersections,
 actualRootPlayerConsumer=root_lines,cpuProofAssertions=39,productionWrites=0,fullScreenPrepared=False,
 completeOriginalPlayerAccepted=False,normalProductCompile=False,rootRuntimeAccepted=False,nativeRuntimeAccepted=False,realAccountAccepted=False)
write(P/'source-and-boundary-audit.json',json.dumps(out,ensure_ascii=False,indent=2)+'\n')

new_files=[f for f in wide(P/'prepared').rglob('*')if f.is_file()and not(C/f.relative_to(wide(P/'prepared'))).exists()]
assert len(new_files)==6
whitelist=[dict(source=str(f),target=f.relative_to(wide(P/'prepared')).as_posix(),sha256Bytes=sha(f.read_bytes()))for f in sorted(new_files)]
registry=json.loads(read(P/'registry-delta.json'));assert registry['sourcesBefore']==1219 and registry['sourcesAfter']==1225
contract=dict(candidateBase=BASE,upstreamCommit=UP,immutableSnapshot=88,orderedCpEntries=105,
 installableBridgeSlice=True,installableFullPlayer=False,newFileCopyWhitelist=whitelist,
 existingExactHunks=record(P/'exact-hunks.json'),existingTargets=record(P/'targets.json'),
 wholeExistingFileOverwriteForbidden=True,generatedFilesCopyForbidden=True,
 generatedSoleProducers=[dict(path='desktop/tools/extract-upstream-bangumi-player.py',outputDirectory='desktop/build/generated/original-bangumi-player',outputs=7),dict(path=video_path,outputsChanged=1)],
 registryDelta=record(P/'registry-delta.json'),sourceInverseAudit=record(P/'source-and-boundary-audit.json'),
 sourcesBefore=1219,sourcesAfter=1225,resourcesBefore=244,resourcesAfter=244,newDependencies=[],
 narrowCompile=record(P/'runs/06/result.json'),cpuProof=record(P/'proof-runs/04/result.json'),
 fullScreenPrepared=False,rootFullPlayerLeafWired=False,pugvCommentType33Wired=False,originalDownloadRootWired=False,
 pgcFinalRetirementHeartbeatWired=False,miniNextEpisodeRootWired=False,allPlayerFunctionsAccepted=False,
 normalProductCompile=False,rootRuntimeAccepted=False,nativeRuntimeAccepted=False,realAccountAccepted=False,productionWrites=0)
write(P/'install-contract.json',json.dumps(contract,ensure_ascii=False,indent=2)+'\n')
print('PASS six complete body inverses + unchanged complete protocol; sole producer replay; 9/17 exact fragments; 19 explicit narrow sources/7 overlay families;39 CPU checks; full Player unaccepted')
