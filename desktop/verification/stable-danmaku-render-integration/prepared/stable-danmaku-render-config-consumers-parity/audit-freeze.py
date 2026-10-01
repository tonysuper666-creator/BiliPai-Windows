from pathlib import Path
import hashlib,importlib.util,json,re,subprocess,sys,zipfile
sys.dont_write_bytecode=True
L=Path(__file__).resolve().parent;ROOT=L.parents[2];MAIN=ROOT.parent/'BiliPai';SNAP=MAIN/'desktop/.local/stable-product-snapshot-22';PANEL=MAIN/'desktop/.local/stable-danmaku-settings-panel-parity'
def safe(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def byteSha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
def save(p,v):safe(p).write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
checks=[]
def require(label,condition):assert condition,label;checks.append(label)
require('fixed-settings179',byteSha(PANEL/'frozen-handoff.json')=='51acaae885d7cf690f2b17aaf4ea856b02aae81e8b25c0086a5ffb01dddeb6a8')
require('fixed515',byteSha(L.parent/'stable-danmaku-root-consumers-parity/frozen-handoff.json')=='51551c0d5070f6980e7511c052816fee4e5b0a1cb517e82f4f60208dce5eb01f')
snapshot=json.loads(read(SNAP/'manifest.json'));inputs={r['path']:r['sha256Bytes'] for r in snapshot['inputs']};baseline=json.loads(read(L/'canonical-baselines.json'));baseBy={r['path']:r for r in baseline}
baselineReceipts=[]
for r in baseline:
 require('baseline-lf:'+r['path'],sha(read(r['source']))==r['sha256LF'])
 p=r['path'];raw=byteSha(r['source']);record=dict(r,sha256Bytes=raw,actual22ExpectedBytes=inputs.get(p),actual22SourceByteEqual=False)
 if p in inputs:
  record['actual22SourceByteEqual']=raw==inputs[p]
  # Scheduler/Live original checkout uses CRLF; actual22 record binds those bytes while LF forms bind hunks.
  if not record['actual22SourceByteEqual'] and p.endswith(('DanmakuScheduler.kt','LiveDanmakuRenderer.kt')):
   current=byteSha(ROOT/p);require('actual22-normalized-byte-binding:'+p,current==inputs[p] and read(ROOT/p)==read(r['source']));record['actual22SourceByteEqual']=True;record['actual22OriginalCheckoutBytes']=current
 if p.endswith(('DanmakuScheduler.kt','LiveDanmakuRenderer.kt','DanmakuOverlay.kt')):require('actual22-renderer-base:'+p,record['actual22SourceByteEqual'])
 if 'review-only-callers' in str(r['source']) or p.endswith('/DesktopShell.kt'):record['callerNote']='Captured Root minimal hunk context only; not compiled immutable whole caller claim'
 baselineReceipts.append(record)
save(L/'baseline-receipts.json',baselineReceipts)
hunks=json.loads(read(L/'local-hunks.json'));versions={}
producer='desktop/tools/extract-upstream-danmaku-list-menu.py';versions[producer]=read(L/'baseline'/producer)
for row in hunks:
 p=row['path'];s=versions.get(p)
 if s is None:s=read(baseBy[p]['source'])
 require('hunk-before:'+str(len(checks))+':'+p,sha(s)==row['beforeSha256LF'])
 require('hunk-count:'+str(len(checks))+':'+p,s.count(row['old'])==row.get('requiredOldCount',1))
 out=s.replace(row['old'],row['new'],1)
 require('hunk-after:'+str(len(checks))+':'+p,sha(out)==row['afterSha256LF']);versions[p]=out
for p,s in versions.items():
 folder='prepared' if p==producer else ('review-only-callers' if (L/'review-only-callers'/p).exists() else 'review-only')
 require('candidate-body-from-exact-hunks:'+p,s==read(L/folder/p))
spec=importlib.util.spec_from_file_location('producer',L/'prepared'/producer);g=importlib.util.module_from_spec(spec);spec.loader.exec_module(g)
inventory=json.loads(read(L/'generated-source-inventory.json'));sourceRows=json.loads(read(L/'source-inventory.json'));retained={r['path']:r for r in sourceRows}
for row in sourceRows:
 require('original-full-retained-sha:'+row['path'],byteSha(row['fullSourceRetained'])==row['sha256Bytes'])
 blob=subprocess.check_output(['git','-C',str(ROOT),'rev-parse',g.COMMIT+':'+row['path']],text=True).strip();require('fixed-git-blob:'+row['path'],blob==row['gitBlob'])
for row in inventory['sources']:require('generator-source-fixed:'+row['path'],row['pinnedCommit']==g.COMMIT)
for row in inventory['emitted']:require('generated-source-current:'+row['path'],sha(read(L/'generated'/row['path']))==row['sha256LF'])
configOrigin=g.PATHS[6];original=read(retained[configOrigin]['fullSourceRetained']);config=read(L/'generated/com/android/purebilibili/feature/video/danmaku/DanmakuConfig.kt')
for name,indent in [('resolveDanmakuScrollDurationMillis',''),('resolveDanmakuLayerLineHeightPx',''),('resolveDanmakuPinnedDurationMillis',''),('resolveDanmakuMinimumVisibleLines',''),('resolveDanmakuFallbackMaxLines',''),('resolveActiveDisplayBand','    ')]:
 require('complete-original-pure-function:'+name,g.function(original,name,indent)[0]==g.function(config,name,indent)[0])
require('complete-original-visible-budget-except-log',g.function(original,'resolveDanmakuVisibleLineCount','')[0]==g.function(config,'resolveDanmakuVisibleLineCount','')[0].replace('DesktopDanmakuConfigLog.i(','android.util.Log.i('))
require('full-config-reverse-original',next(x for x in inventory['emitted'] if x['path'].endswith('/DanmakuConfig.kt'))['reverseNormalizedOriginalByteEqual'])
require('full-config-textsize-original-expression','20f * viewport.density * fontScale.coerceIn(0.3f, 2f) * viewport.scale' in config)
allGenerated='\n'.join(read(L/'generated'/x['path']) for x in inventory['emitted'])
require('sole-bilibili-font-helper',len(re.findall(r'fun resolveBilibiliDanmakuFontScale\(',allGenerated))==1)
require('sole-required-font-schema','val typeface: Typeface,' in allGenerated and 'val typeface: Typeface? = Typeface.DEFAULT,' not in allGenerated)
layer=read(L/'generated/com/android/purebilibili/feature/video/danmaku/DesktopOriginalDanmakuLayerPolicy.kt');manager=read(retained[g.PATHS[5]]['fullSourceRetained'])
require('full-layer-original',g.function(layer,'resolveDanmakuRenderLayerType','')[0]==g.function(manager,'resolveDanmakuRenderLayerType','')[0])
require('full-layer-standard-map-original',g.function(layer,'mapLayerTypeToDanmakuType','')[0]==g.function(manager,'mapLayerTypeToDanmakuType')[0].replace('    private fun','internal fun',1))
liveOriginal=read(retained[g.PATHS[13]]['fullSourceRetained'])
for file,startText,endText,tail in [
 ('DesktopOriginalLiveDanmakuRenderConfig.kt','            val textSize = DEFAULT_DANMAKU_TEXT_SIZE_PX *','\n        }\n    )','\n}\n'),
 ('DesktopOriginalLiveDanmakuAdmission.kt','            val typeFilter = DanmakuTypeFilterSettings(','            val currentEngine = engine','            return true\n}\n')]:
 emitted=next(x for x in inventory['emitted'] if x['path'].endswith('/'+file));candidate=read(L/'generated'/emitted['path']);a=candidate.index(startText);body=candidate[a:];require('full-live-wrapper-tail:'+file,body.endswith(tail));body=body[:-len(tail)]
 for r in reversed(emitted['adaptations']):
  require('full-live-adapter-count:'+file+':'+str(len(checks)),body.count(r['after'])==r.get('occurrenceCount',1));body=body.replace(r['after'],r['before'])
 start=liveOriginal.index(startText);end=liveOriginal.index(endText,start);require('full-live-original-body-reverse:'+file,body==liveOriginal[start:end])
engine=read(retained[g.PATHS[14]]['fullSourceRetained']);budget=read(L/'generated/com/android/purebilibili/feature/video/danmaku/DesktopOriginalDanmakuEngineBudgets.kt')
for name,prefix in [('desktopOriginalDanmakuPinnedLineCount','            top.lineCount = '),('desktopOriginalDanmakuItemMargin','            scroll.itemMargin = ')]:
 expr=engine[engine.index(prefix)+len(prefix):].split('\n')[0];require('exact-original-engine-expression:'+name,' = '+expr+'\n' in budget)
overlay=versions['desktop/src/main/kotlin/com/bilipai/desktop/danmaku/DanmakuOverlay.kt'];scheduler=versions['desktop/src/main/kotlin/com/bilipai/desktop/danmaku/DanmakuScheduler.kt'];live=versions['desktop/src/main/kotlin/com/bilipai/desktop/danmaku/LiveDanmakuRenderer.kt'];scalar=versions['desktop/src/main/kotlin/com/bilipai/desktop/danmaku/DanmakuSettings.kt'];platform=read(L/'prepared/desktop/src/main/kotlin/com/bilipai/desktop/danmaku/DesktopOriginalDanmakuRenderPlatform.kt')
require('required-neutral-frame','config:DanmakuRenderConfig, measure:' in scheduler and 'rowHeight: Int' not in scheduler)
require('original-durations-current-frame','maxOf(config.scrollDurationMs, config.pinnedDurationMs)' in scheduler and 'settings.scrollDurationSeconds * settings.speedFactor' not in scheduler)
require('current-engine-budget-consumer','desktopOriginalDanmakuPinnedLineCount(config)' in scheduler and 'desktopOriginalDanmakuItemMargin(config)' in scheduler)
require('required-video-live-admission','private val liveAdmission:Boolean' in scheduler)
require('actual-packet-self-weight','comment.originalElement?.isSelf != true && comment.weight < weightFilterLevel' in scalar)
require('no-MID-hash-self-guess','TokenManager' not in scalar and 'currentMid' not in scalar)
require('live-no-video-weight','desktopOriginalLiveDanmakuAllows' in scalar and 'liveAdmission=true' in live)
require('real-root-font-window','rootWindow:()->Window' in platform and 'UIManager.getFont("Label.font")' in platform and 'Microsoft YaHei' not in platform+overlay+live)
require('physical-actual-DPI','hypot(transform.scaleX,transform.shearY)' in platform and 'configurePhysicalPixels(physical)' in overlay)
require('video-live-distinct-original-config','resolveDesktopOriginalLiveDanmakuRenderConfig(configuration' in overlay and 'configuration.originalConfig(renderPlatform).resolveRenderConfig' in overlay)
require('mode-in-real-config-cache','val live:Boolean' in overlay)
require('cloud-authority-unchanged','queueChange' not in platform+scalar+scheduler+overlay)
require('no-new-store-http-player',all(x not in platform for x in ['DesktopPluginStore(','OkHttpClient(','DesktopRepository(','MpvPlayer(','MutableStateFlow(']))
oldLive=read(baseBy['desktop/src/main/kotlin/com/bilipai/desktop/danmaku/LiveDanmakuRenderer.kt']['source'])
for name in ['expire','add','fetchImage','removeSuperChats','seconds']:
 require('current-live-queue-expiry-business-unchanged:'+name,g.function(oldLive,name)[0]==g.function(live,name)[0])
require('required-window-in-Root-remember','remember(player, repository, hostWindow)' in versions['desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt'])
require('all-production-overlay-ctors-explicit',sum(s.count('DanmakuOverlay(') for p,s in versions.items() if p.endswith(('DesktopShell.kt','DesktopOverlayNativeSmoke.kt','DownloadScreens.kt','MediaScreens.kt')))==5 and sum(s.count('renderPlatform = ') for p,s in versions.items() if p.endswith(('DesktopShell.kt','DesktopOverlayNativeSmoke.kt','DownloadScreens.kt','MediaScreens.kt')))==5)
compiled=json.loads(read(L/'compile-06/compile-result.json'));require('compile06-pass',compiled['status']=='PASS' and len(compiled['sourceInputs'])==14)
for r in compiled['sourceInputs']:require('compiled-current-source:'+r['source'],byteSha(r['source'])==r['sha256Bytes'])
require('serialization-plugin-current-pinned',byteSha(compiled['serializationCompilerPlugin']['path'])==compiled['serializationCompilerPlugin']['sha256Bytes'])
proof=json.loads(read(L/'proof-04/proof-result.json'));require('proof04-real-font-and2725',proof['status']=='PASS' and proof['assertions']==2725 and proof['actualAWTFont']=='Dialog' and proof['nativeWindowOrHTTP']==False)
tests=json.loads(read(L/'existing-tests-02/result.json'));require('existing11tests-pass',tests['status']=='PASS' and 'PASS 11 methods' in tests['output'])
jar=L/'compile-06/original-danmaku-render-config-consumers.jar'
with zipfile.ZipFile(safe(jar)) as z:own={n for n in z.namelist() if n.endswith('.class')}
actual=set()
for p in [SNAP/'main-kotlin.jar',PANEL/'compile-06/original-danmaku-settings.jar']:
 with zipfile.ZipFile(safe(p)) as z:actual.update(n for n in z.namelist() if n.endswith('.class'))
allowed=['com/bilipai/desktop/danmaku/DanmakuSettings','com/bilipai/desktop/danmaku/DanmakuScheduler','com/bilipai/desktop/danmaku/PositionedDanmaku','com/bilipai/desktop/danmaku/DanmakuOverlay','com/bilipai/desktop/danmaku/DanmakuPoolSourceSnapshot','com/bilipai/desktop/danmaku/LiveDanmakuRenderer','com/bilipai/desktop/ui/DesktopDanmakuSettingsProjectionKt','com/bilipai/desktop/ui/DesktopDanmakuPresentation']
overlap=own&actual;unexpected=sorted(n for n in overlap if not any(n.startswith(p) for p in allowed));require('only-declared-existing-families-overlap',not unexpected)
new=own-overlap;require('new-classFQN-overlap0',not(new&actual))
newRows=[];merge=[]
for p in [g.PATHS[6],g.PATHS[11],g.PATHS[5],g.PATHS[12],g.PATHS[13],g.PATHS[14]]:
 r=retained[p];target=dict(path=p,sha256=r['sha256Bytes'],features=['stable-danmaku-list-menu'],mode='selected')
 (merge if r['existingRegistry'] else newRows).append(dict(action='MERGE_EXISTING' if r['existingRegistry'] else 'ADD',row=target,sourceSelection='See generated inventory; full original retained; never emit unsupported Android renderer/class'))
save(L/'registry-delta.json',dict(fixedCommit=g.COMMIT,newCount=len(newRows),new=newRows,merge=merge,addNoDuplicateDirectViewportOrWeightedModel=True))
audit=dict(status='PASS',checkCount=len(checks),checks=checks,localHunkCount=len(hunks),newClassCount=len(new),newClassOverlap=[],declaredExistingClassOverlap=sorted(overlap),actualSnapshot=22,prospectiveOnly=True,wholeRootCallersNotCompiled=True)
save(L/'source-ownership-audit.json',audit)
payload=[]
for p in sorted(safe(L/'prepared').rglob('*')):
 if p.is_file():payload.append(dict(source=str(p),target=p.relative_to(safe(L/'prepared')).as_posix(),sha256Bytes=byteSha(p)))
require('two-payloads-only',len(payload)==2)
evidence=[]
for p in sorted(safe(L).rglob('*')):
 if not p.is_file() or p.name=='frozen-handoff.json':continue
 evidence.append(dict(path=str(p),relative=p.relative_to(safe(L)).as_posix(),sha256Bytes=byteSha(p)))
frozen=dict(status='READY_SOURCE_ONLY',fixedCommit=g.COMMIT,payload=payload,payloadCount=len(payload),newSelectedOutputs=7,fullProducerOutputCount=16,sourceIdentityCount=len(inventory['sources']),productionLocalHunks=len(hunks),localHunksSHA256=byteSha(L/'local-hunks.json'),registryDeltaSHA256=byteSha(L/'registry-delta.json'),newRegistryIdentities=len(newRows),actualImmutableSnapshot=22,actualSnapshotManifest=byteSha(SNAP/'manifest.json'),relocatedSameByte92CP='65e50623a5e52a7a4f0c7421e55dbd20bae752b5c53add8208be9c1f8c9772ae',canonicalSettings179='51acaae885d7cf690f2b17aaf4ea856b02aae81e8b25c0086a5ffb01dddeb6a8',canonicalRootConsumers515='51551c0d5070f6980e7511c052816fee4e5b0a1cb517e82f4f60208dce5eb01f',compiledProspectiveProductionSources=13,compiledProofOnlySources=1,compiledJarSHA256=byteSha(jar),compileResultSHA256=byteSha(L/'compile-06/compile-result.json'),pureAWTProof=proof,existingTestMethods=11,sourceAuditCheckCount=len(checks),newClassFQNOverlap=[],noSharedGradleAndroidNativeOrRuntimeMutation=True,wholeRootCallersCompiled=False,wholeRootAndRuntimeAcceptanceOwner='Root',pending=inventory['pending'],evidence=evidence)
save(L/'frozen-handoff.json',frozen)
print('FROZEN',byteSha(L/'frozen-handoff.json'),'payload2/hunks',len(hunks),'sources13+1/newregistry',len(newRows),'audit',len(checks),'artifacts',len(evidence))
