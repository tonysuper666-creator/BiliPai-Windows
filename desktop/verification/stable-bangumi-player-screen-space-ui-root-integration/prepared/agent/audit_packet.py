from pathlib import Path
import ast,hashlib,json,os,subprocess,sys
sys.dont_write_bytecode=True;sys.stdout.reconfigure(encoding='utf8')
P=Path(__file__).resolve().parent;C=P.parents[2].parent/'BiliPai-v023';BASE='cd8317d54fff02c2d920e295397aef502333f69b';UP='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
def wide(p):
 s=os.path.abspath(str(p));prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix)else prefix+s)
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
def fileSha(p):return hashlib.sha256(wide(p).read_bytes()).hexdigest()
def read(p):return wide(p).read_text(encoding='utf8').replace('\r\n','\n')
def load(p):return json.loads(read(p))
def write(p,v):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf8',newline='\n')
def get(ref,path):return subprocess.check_output(['git','-C',str(C),'show',ref+':'+path]).decode('utf8').replace('\r\n','\n')
compileResult=load(P/'runs/14/result.json');proof=load(P/'proof-runs/04/result.json');assert compileResult['passed']and compileResult['snapshot']==90 and proof['passed']and proof['snapshot']==90
for r in compileResult['explicitProspectiveSources']:assert fileSha(r['path'])==r['sha256Bytes'],r['path']
assert fileSha(proof['prospectiveJar']['path'])==proof['prospectiveJar']['sha256Bytes']
assert 'PASS 59 assertions' in read(P/'proof-runs/04/run.log')
generated=list(wide(P/'generated').rglob('*.kt'));replay=list(wide(P/'producer-replay').rglob('*.kt'));assert len(generated)==len(replay)==8
outputs=[]
for f in generated:
 rel=f.relative_to(wide(P/'generated'));assert f.read_bytes()==wide(P/'producer-replay'/rel).read_bytes(),rel
 outputs.append(dict(output=rel.as_posix(),sha256Bytes=fileSha(f),soleProducerReplayByteExact=True))
pgcOutputs=[]
for f in wide(P/'lifecycle-generated').rglob('*.kt'):
 rel=f.relative_to(wide(P/'lifecycle-generated'));assert f.read_bytes()==wide(P/'pgc-producer-replay'/rel).read_bytes()
 pgcOutputs.append(dict(output=rel.as_posix(),sha256Bytes=fileSha(f),soleProducerReplayByteExact=True))
assert len(pgcOutputs)==7
full=load(P/'ui-recipes.json')['recipes']+[load(P/'components-recipe.json'),load(P/'screen-recipe.json')]
inverse=[]
for r in full:
 raw=get(UP,r['originalPath']);assert sha(raw)==r['originalSha256LF'];lines=raw.splitlines(keepends=True);adapted=[];reconstructed=[];cursor=0
 for e in r['edits']:
  i,j=e['startLine'],e['endLineExclusive'];before=''.join(lines[i:j]);assert before==e['before'] and sha(before)==e['beforeSha256LF'];unchanged=''.join(lines[cursor:i]);adapted.extend([unchanged,e['after']]);reconstructed.extend([unchanged,before]);cursor=j
 adapted.append(''.join(lines[cursor:]));reconstructed.append(''.join(lines[cursor:]));body=''.join(adapted)
 assert ''.join(reconstructed)==raw and sha(body)==r['adaptedSha256LF']
 assert read(P/'generated'/r['output']).endswith(body)
 inverse.append(dict(originalPath=r['originalPath'],originalSha256LF=sha(raw),originalLines=len(lines),adaptedSha256LF=sha(body),exactReverseWholeBody=True,explicitPlatformEditCount=len(r['edits']),businessSubset=False,actualInstalled=False))
assert len(inverse)==5
screen=read(P/'generated/com/android/purebilibili/feature/bangumi/DesktopOriginalBangumiPlayerScreen.kt');physical=read(P/'prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalBangumiPlayerPhysicalLeaf.kt');rootOwner=read(P/'prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalBangumiPlayerRootOwner.kt')
assert 'val targetType = if (isPugv) 33 else 1' in screen and 'commentType = targetType' in screen
assert all('key.'+field in physical for field in ['seasonId','epId','resumePositionMs','isCourse','preferredAid'])
for forbidden in ['MpvPlayer(','ExoPlayer.Builder(', 'Retrofit.Builder(', 'DesktopSessionStore(', 'PlaybackSessionStore(', 'DesktopRepository(']:
 assert forbidden not in screen and forbidden not in rootOwner and forbidden not in physical,forbidden
vmRecipeTool=get(BASE,'desktop/tools/extract-upstream-bangumi-player.py');parsed=ast.parse(vmRecipeTool)
recipes=next(json.loads(ast.literal_eval(n.value.args[0]))for n in parsed.body if isinstance(n,ast.Assign)and any(isinstance(t,ast.Name)and t.id=='RECIPES'for t in n.targets))
assert len(recipes)==6
producer=read(P/'prepared/desktop/tools/extract-upstream-bangumi-player.py');parsedNew=ast.parse(producer);newRecipes=next(json.loads(ast.literal_eval(n.value.args[0]))for n in parsedNew.body if isinstance(n,ast.Assign)and any(isinstance(t,ast.Name)and t.id=='RECIPES'for t in n.targets));assert newRecipes==recipes
hunks=load(P/'root-exact-hunks.json')['hunks'];lifecycle=load(P/'lifecycle-exact-hunk.json');hunks.append({k:v for k,v in lifecycle.items()if k in ['path','before','after','beforeCount','baseSha256LF','preparedSha256LF']})
native=load(P/'native-retirement-exact-hunks.json')
for h in native['hunks']:hunks.append(dict(path=native['path'],before=h['before'],after=h['after'],beforeCount=h['count'],baseSha256LF=native['baseSha256LF'],preparedSha256LF=native['preparedSha256LF']))
assert len(hunks)==10
targets=[]
for path in sorted(set(h['path']for h in hunks)):
 raw=get(BASE,path);body=raw;family=[h for h in hunks if h['path']==path]
 for h in family:
  assert h['baseSha256LF']==sha(raw) and body.count(h['before'])==h['beforeCount'];body=body.replace(h['before'],h['after'])
 assert body==read(P/'prepared'/path),path
 targets.append(dict(path=path,baseSha256LF=sha(raw),preparedSha256LF=sha(body),fragments=len(family),wholeFileCopyAllowed=False))
write(P/'exact-hunks.json',hunks);write(P/'targets.json',dict(candidateBase=BASE,upstreamCommit=UP,existingTextFamilies=len(targets),textFragments=len(hunks),targets=targets))
whitelist=[]
for f in wide(P/'prepared').rglob('*'):
 if not f.is_file():continue
 rel=f.relative_to(wide(P/'prepared')).as_posix()
 if rel in {t['path']for t in targets}:continue
 assert not wide(C/rel).exists(),rel
 whitelist.append(dict(source=str(f),target=rel,sha256Bytes=fileSha(f)))
assert len(whitelist)==6
guard=dict(pinnedGitRef=BASE,currentHead=subprocess.check_output(['git','-C',str(C),'rev-parse','HEAD']).decode().strip(),currentStatus=subprocess.check_output(['git','-C',str(C),'status','--short']).decode('utf8'),candidateWrittenByThisLane=False)
boundaries=[
 dict(boundary='Complete Screen/Content/Components/Overlay/Collapsed UI',prepared=True,proof='5 exact whole-body inverses + 8 byte-exact sole producer outputs + actual90 narrow compile',normalProductCompile=False,actualRootRuntime=False),
 dict(boundary='Actual typed PGC/PUGV Root leaf',prepared=True,proof='all 5 NavKey fields consumed; exact Shell branch above old fallback; same actual Root platform/Assembly',actualRootRuntime=False),
 dict(boundary='One HTTP/WBI/account/settings owner',prepared=True,proof='same captured invocations/pinned primary service; actual Root settings and authorization receipt; no new store/client/session',accountRuntime=False),
 dict(boundary='One native player/source publication',prepared=True,proof='existing installed89 native bridge; actual90 NativeOwner/Store negative admission and unmounted source-version CPU cases',nativeAckOrFrame=False),
 dict(boundary='Full PUGV comments',prepared=True,proof='original commentType33 + original actual episode oid, real owner metadata/reply/composer/sort; same Root comments domain',accountRuntime=False),
 dict(boundary='Full multiDURL/Referer/rawDash/selected audio',prepared=True,proof='57 assertion fixture includes full physical/native/cache plans; same-token Subject/Mini projection keeps full original MPD helper',nativeRuntime=False),
 dict(boundary='Mini/episode queue/background',prepared=True,proof='actual retained shell Mini/Playlist references; only own callbacks cleared; covered route view disposal retains PGC scope',actualRootRuntime=False),
 dict(boundary='Final heartbeat and listener retirement',prepared=True,proof='capture old admitted source position/receipt/primary csrf+mid before episode or ordinary takeover; same Root scope final dispatch; original type4 fields/privacy/cancellation/server-denial CPU cases; remove old borrowed listener',actualRootTakeoverRuntime=False),
 dict(boundary='Factual original course download task',prepared=True,proof='same manager/destination/account/source receipt and full original course group/order/owner/duration/quality metadata; explicit rights0 rejects; absent PUGV rights retains original accepted course-URL behavior; no permission fields filled',realDownloadRuntime=False),
 dict(boundary='Android-only OS Handoff/rotation/Media3/GLES',prepared=True,proof='explicit same Windows Window/native/enhancement/typed resume boundaries; Android OS registration unavailable and reported without fabricated availability',nativeRuntime=False),
]
audit=dict(candidateBase=BASE,upstreamCommit=UP,snapshot=90,orderedCpEntries=105,guard=guard,originalBodyInverses=inverse,sourcePolicySelections=[load(P/'ui-policy-recipe.json'),load(P/'settings-recipe.json'),load(P/'sponsor-recipe.json')],rawDanmakuBoundary=load(P/'raw-danmaku-boundary.json'),soleUiProducerOutputs=outputs,solePgcProducerOutputs=pgcOutputs,pgcProducerOriginalSixRecipesUnchanged=True,fullScreenPrepared=True,actualPhysicalRootLeafPrepared=True,boundaries=boundaries,normalProductCompile=False,actualRootRuntime=False,accountRuntime=False,nativeRuntime=False,newPlayerClientStoreOrSession=False,sourceRegistryIsNotFunctionCompletion=True)
write(P/'source-and-boundary-audit.json',audit)
def ref(path):return dict(path=str(P/path),sha256Bytes=fileSha(P/path))
contract=dict(candidateBase=BASE,upstreamCommit=UP,immutableSnapshot=90,orderedCpEntries=105,installableFullPlayerSourceClosure=True,completeOriginalPlayerAccepted=False,newFileCopyWhitelist=whitelist,existingExactHunks=ref('exact-hunks.json'),existingTargets=ref('targets.json'),wholeExistingFileOverwriteForbidden=True,generatedFilesCopyForbidden=True,generatedSoleProducers=[dict(path='desktop/tools/extract-upstream-bangumi-player-ui.py',outputDirectory='desktop/build/generated/original-bangumi-player-ui',outputs=8),dict(path='desktop/tools/extract-upstream-bangumi-player.py',outputDirectory='desktop/build/generated/original-bangumi-player',outputs=7,onlyLifecycleAndFailureBoundaryChanged=True)],registryDelta=ref('registry-delta.json'),sourceInverseAudit=ref('source-and-boundary-audit.json'),narrowCompile=ref('runs/14/result.json'),cpuProof=ref('proof-runs/04/result.json'),guard=guard,normalProductCompile=False,actualRootNativeAccountAccepted=False,requiredAfterInstallation=['normal classes/compileTestKotlin with no prospective overlays','actual physical Root PGC/PUGV screen and shared native transport/frame/error recovery','real account PUGV comments/download/heartbeat/ordinary takeover and covered-page Mini continuation'],v025=False,v025UpgradeMappingSeparate=True)
write(P/'install-contract.json',contract)
print('Audited 5 whole body inverses, 8 byte-exact sole outputs;',len(whitelist),'new files,',len(targets),'existing exact text families,',len(hunks),'fragments; full runtime acceptance remains false')
