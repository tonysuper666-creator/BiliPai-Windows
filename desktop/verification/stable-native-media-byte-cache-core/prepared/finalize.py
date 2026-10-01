from pathlib import Path
import hashlib,importlib.util,json,subprocess,sys
sys.dont_write_bytecode=True
H=Path(__file__).resolve().parent;MAIN=H.parents[2];C=MAIN.parent/'BiliPai-v023'
def wide(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix)else prefix+s)
def data(p):return wide(p).read_bytes()
def sha(b):return hashlib.sha256(b).hexdigest()
def row(p):
 b=data(p);return dict(path=str(p.relative_to(H)),sha256Bytes=sha(b),size=len(b))
def dump(p,v):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes((json.dumps(v,ensure_ascii=False,indent=2)+'\n').encode())
prod=sorted((H/'prepared/desktop/src/main/kotlin').rglob('*.kt'));assert len(prod)==4
producer=H/'prepared/desktop/tools/extract-upstream-media-byte-cache-policy.py'
generated=next((H/'generated').rglob('*.kt'))
for name in ['proof-09','mpd-03']:
 run=H/'runs'/name
 receipt=json.loads(data(run/'receipt.json'));assert receipt['compilePASS']and receipt['runtimeExit']==0 and receipt['pinsUnchanged']and receipt['productionOverrides']==0 and receipt['entries']==101
 for p in prod+[generated]:assert data(p)==data(run/'inputs'/p.name),(name,p)
 overlap=json.loads(data(run/'overlap.json'));assert overlap['classOverlap']==[]
 runtime=json.loads(data(run/'runtime-result.json'));assert runtime['nativeBefore']==runtime['nativeAfter']
spec=importlib.util.spec_from_file_location('policy',producer);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
origin=subprocess.run(['git','show',m.COMMIT+':'+m.SOURCE],cwd=C,capture_output=True,check=True).stdout
blob=subprocess.run(['git','rev-parse',m.COMMIT+':'+m.SOURCE],cwd=C,capture_output=True,text=True,check=True).stdout.strip()
assert sha(origin)==m.SOURCE_SHA
selected=m.selected(origin.decode())
assert data(generated).decode()=='package com.android.purebilibili.core.player\n\nimport java.net.URI\n\n'+selected
dump(H/'source-audit.json',dict(commit=m.COMMIT,originalPath=m.SOURCE,originalSha256LF=sha(origin),gitBlob=blob,
 selectedBodySha256=sha(selected.encode()),mechanicalBodyChanges=0,reverseEqual=True,
 generated=row(generated),newPlatformSources=[row(p)for p in prod],originalDeclarations=['resolvePlaybackMediaCacheMaxBytes','shouldUsePlaybackMediaCache(rawUri:String)','buildPlaybackCacheKey(rawUri:String,explicitKey:String?)'],
 existingCdnOptimizationPolicy='reference-only existing DIRECT; no second producer',
 cacheIo='Windows platform implementation, not copied Android Media3 SDK object',existingDiagnostics='PlayerFailure.kt PlayerDiagnostics.sanitize already removes URL paths and headers'))
save=H/'original-stable'/Path(m.SOURCE).name;wide(save).parent.mkdir(parents=True,exist_ok=True);wide(save).write_bytes(origin)
patches=json.loads(data(H/'exact-hunks.json'));assert len(patches['hunks'])==21 and patches['reverseCheck'];assert len({r['path']for r in patches['hunks']})==9
install=[dict(source=row(p),target=p.relative_to(H/'prepared').as_posix(),kind='manual-platform-source')for p in prod]
install.append(dict(source=row(producer),target=producer.relative_to(H/'prepared').as_posix(),kind='sole-pinned-source-producer'))
dump(H/'install-contract.json',dict(schema=1,payloadWhitelist=install,exactHunks=row(H/'exact-hunks.json'),
 registryMerge=row(H/'registry-merge-recipe.json'),recipe=row(H/'ROOT-INTEGRATION.md'),sourceAudit=row(H/'source-audit.json'),
 generatedByProducer=[generated.relative_to(H/'generated').as_posix()],newDependencies=0,newHttpClients=0,newAccountStores=0,newPlayers=0,newCastServers=0,
 requiredRootPorts=['one applicationIO cache/Repository/scope','effective playback partition sameStore getter','initial resolver captured tracks+keys+headers+Job+entry gate',
 'same Bound in Invocation/CdnPrefetcher/Portrait and native prepare','accepted Rootsource lifetime admission callback; transient guard until same native ACK',
 'same epoch/account/newload/route retirement; same native snapshot carrier object during adoption','original direct-source cache-error recovery'],
 forbiddenInstall=['baseline','review-only','runs','fixture sources','classes/JARs','synthetic media','full shared-file replacements'],
 rootInstalledCarrierAccepted=False,proofExistingClassOverrides=0))
dump(H/'acceptance.json',dict(snapshot=66,runtimeEntries=101,productOverrides=0,
 cohorts=[dict(run='proof-09',groups=5,assertions=34,receipt=row(H/'runs/proof-09/receipt.json')),dict(run='mpd-03',groups=1,assertions=8,receipt=row(H/'runs/mpd-03/receipt.json'))],
 actualNativeWarmBytes=True,actualSameMpvPublicationAdoption=True,completeThreeRepresentationMpdVideoAndAudio=True,
 actualRootCarrierFieldInstalled=False,actualRootFinalHeaderInterceptorInstalled=False,actualRootNamespaceGetterInstalled=False,
 nativeMpdLayout='complete standards MPD SegmentList; SegmentBase ranges structurally retained',
 realAccount=False,externalHttp=False,window=False,rootMountAccepted=False,
 historicalFailures={'compile-01':'NanoHTTPD has no BAD_GATEWAY enum/private Reader constructor','proof-01':'fixture nullable player smartcast','proof-02':'fixture first frame preceded actual native ready/codec poll; stdout console codec failure after raw log retained','proof-03':'real socket cancellation escaped as IOException; production preserves actual caller/source CancellationException thereafter'},
 warnings='NanoHTTPD peer socket close on retirement and secure XML DOCTYPE rejection retained in raw logs'))
# Original fixture media command is reproducible with the actual bundled LGPL FFmpeg;
# no user wallpaper/account/media is copied. MPD binary outputs remain task-only.
ff=MAIN/'desktop/native/windows-x64/ffmpeg.exe';clip=MAIN/'desktop/.local/stable-home-native-media-proof/runs/actual33-01/local-media/moving.mp4'
dump(H/'mpd-media/generator-receipt.json',dict(ffmpeg=dict(path=str(ff),sha256Bytes=sha(data(ff)),size=len(data(ff))),
 input=dict(path=str(clip),sha256Bytes=sha(data(clip)),size=len(data(clip))),
 command=['ffmpeg','-nostdin','-hide_banner','-n','-i','<frozen actual33 moving.mp4>','-f','lavfi','-i','sine=frequency=440:sample_rate=48000','-t','4','-map','0:v','-map','0:v','-map','1:a','-c:v','mpeg4','-b:v:0','90k','-b:v:1','45k','-filter:v:1','scale=80:46','-g','15','-c:a','aac','-b:a','64k','-f','dash','-single_file','1','-use_template','0','-use_timeline','0','-seg_duration','1','-adaptation_sets','id=0,streams=v id=1,streams=a','<owned output>/dash.mpd'],
 outputs=[row(p)for p in sorted((H/'mpd-media').iterdir())if p.suffix in ['.mp4','.mpd']],
 failedInitialEncoderAttempt='libx264 absent from bundled LGPL FFmpeg; no output/native proof. Final recipe uses actual builtin MPEG4 encoder.'))
raws=[];excluded=[]
for p in sorted(H.rglob('*')):
 if not p.is_file()or p.name in ['frozen-handoff.json','excluded-artifacts.json']:continue
 rel=p.relative_to(H);parts=rel.parts
 if 'classes'in parts or 'owned-data'in parts or p.suffix in ['.mp4','.jar','.kotlin_module','.class']:
  excluded.append(dict(**row(p),reason='fixture-owned/rebuildable binary output; not a production payload or user media'));continue
 if '__pycache__'in parts or p.suffix=='.pyc':continue
 raws.append(row(p))
dump(H/'excluded-artifacts.json',excluded);raws.append(row(H/'excluded-artifacts.json'))
dump(H/'frozen-handoff.json',dict(schema=1,status='source-only installable core; required actualRoot consumer integration pending',
 fixedOriginalCommit=m.COMMIT,actualProofSnapshot=66,actualProofRuntimeEntries=101,productOverrides=0,
 payloadCount=len(install),exactHunkCount=21,exactHunkFileCount=9,registryIdentityRowsAdded=0,
 currentCompile='proof-09 five prospective sources, class overlap0',
 groups=6,assertions=42,rawCount=len(raws),artifacts=raws,exclusions=row(H/'excluded-artifacts.json'),
 installation=row(H/'install-contract.json'),rootInstalledAccepted=False))
print('FROZEN',len(raws),'raw',sha(data(H/'frozen-handoff.json')))
