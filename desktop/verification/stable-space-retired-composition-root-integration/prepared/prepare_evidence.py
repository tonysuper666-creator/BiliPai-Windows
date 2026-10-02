from pathlib import Path
import difflib,hashlib,json
root=Path(__file__).resolve().parent;main=root.parents[2];candidate=main.parent/'BiliPai-v023'
packet=root/'packet';packet.mkdir(exist_ok=False)
def sha(b):return hashlib.sha256(b).hexdigest()
d=json.loads((root/'installation.json').read_bytes());build=json.loads((root/'actual-build01.json').read_bytes())
assert build['exitCode']==0 and build['junitTests']==15 and build['actualProductOverrides']==0
rows=[]
for name in ['RootSpaceFixture.kt','compile-result.json','compile.log','acceptance-result.json','runtime.log','pins-before.json','pins-after.json']:
 p=main/'desktop/.local/stable-space-root-integration-acceptance/runs/actual91-01'/name
 b=p.read_bytes();dest='actual91-01/'+name;t=packet/dest;t.parent.mkdir(parents=True,exist_ok=True);t.write_bytes(b);rows.append(dict(path=dest,bytes=len(b),sha256Bytes=sha(b)))
name='prepare_evidence.py';b=Path(__file__).read_bytes();(packet/name).write_bytes(b);rows.append(dict(path=name,bytes=len(b),sha256Bytes=sha(b)))
r=d['sourceTargets'][0];b=(candidate/r['path']).read_bytes();assert sha(b)==r['afterSha256Bytes']
old=(root/'before'/r['path']).read_bytes();patch=''.join(difflib.unified_diff(old.decode().replace('\r\n','\n').splitlines(keepends=True),b.decode().splitlines(keepends=True),fromfile='a/'+r['path'],tofile='b/'+r['path']))
(root/'source-diff.patch').write_text(patch,encoding='utf8',newline='\n')
summary=dict(baseCommit=d['baseCommit'],sourceTargets=d['sourceTargets'],normalMainAndTestCompilePassed=True,
 actualProductOverrides=0,junitMethods=15,normalLogSha256Bytes=build['logSha256Bytes'],priorFailedActualWindowRetained=True,
 rootRuntimeAccepted=False,realAccountAccepted=False,nativePlaybackAccepted=False,v025Accepted=False,newEXEDeployed=False)
(root/'integration-summary.json').write_text(json.dumps(summary,indent=2)+'\n',encoding='utf8',newline='\n')
(root/'build_actual.py').write_bytes((main/'desktop/.local/root-integration-tools/build_actual.py').read_bytes())
(root/'README.md').write_text('''The actual physical Root window completed seventeen Space/rank navigation checks before exposing a removed-entry recomposition during the outgoing animation. The strict owner lookup correctly refused revival but throwing from the outgoing composable stopped the window. The UI now returns without rendering when its physical entry is retired; strict request/action admission remains unchanged. An account race is suppressed only when the actual Root/entry has also become retired.

Normal product classes and fifteen original Space JUnit methods passed with zero production overlays. The failed actual91 window log, fixture, immutable origins and receipts remain preserved. A fresh actual-window run is required; successful business HTTP, real account, native decoding, v025 and new EXE are not accepted by this slice.
''',encoding='utf8',newline='\n')
frozen=dict(rawArtifacts=rows,rawFiles=len(rows),actualPriorWindowPassed=False,normalCompilePassed=True,rootRuntimeAccepted=False)
b=(json.dumps(frozen,indent=2)+'\n').encode();(packet/'frozen-handoff.json').write_bytes(b);print(sha(b))
