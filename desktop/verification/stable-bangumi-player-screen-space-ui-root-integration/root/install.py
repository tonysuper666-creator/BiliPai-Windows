from pathlib import Path
import hashlib, json, subprocess, sys
sys.stdout.reconfigure(encoding='utf8')
root=Path(__file__).resolve().parent
main=root.parents[2]
candidate=main.parent/'BiliPai-v023'
packet=main/'desktop/.local/stable-original-bangumi-player-screen-root-parity'
def wide(p):
 s=str(p.absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def read(p): return wide(p).read_bytes()
def sha(b): return hashlib.sha256(b).hexdigest()
def lf(b): return b.replace(b'\r\n',b'\n')
def write(p,b): wide(p.parent).mkdir(parents=True,exist_ok=True);wide(p).write_bytes(b)
def git(*a): return subprocess.check_output(['git','-c','core.longpaths=true',*a],cwd=candidate)
assert not (root/'installation.json').exists()
assert not git('status','--porcelain','-z').strip()
assert git('rev-parse','HEAD').decode().strip()=='cd8317d54fff02c2d920e295397aef502333f69b'
frozen_raw=read(packet/'frozen-handoff.json')
assert sha(frozen_raw)=='ff948da7b16aec02be43f8819e5b3756374bf6bb4da5f54fdc8a5657711e172b'
frozen=json.loads(frozen_raw)
for r in frozen['files']:
 p=Path(r['path']);assert not p.is_absolute() and '..' not in p.parts
 b=read(packet/p);assert sha(b)==r['sha256Bytes'] and len(b)==r['bytes'],r['path']
contract_raw=read(packet/'install-contract.json')
assert sha(contract_raw)=='2c6477685176a9ee4258552e916902d9a4af2595dc520f915defaa1106eea284'
c=json.loads(contract_raw)
def metadata(k):
 r=c[k];b=read(Path(r['path']));assert sha(b)==r['sha256Bytes'];return json.loads(b)
hunks=metadata('existingExactHunks');targets=metadata('existingTargets')['targets']
delta=metadata('registryDelta');audit=metadata('sourceInverseAudit')
assert all(r['exactReverseWholeBody'] for r in audit['originalBodyInverses'])
assert len(audit['originalBodyInverses'])==5
before={};after={}
for r in targets:
 p=r['path'];b=read(candidate/p);before[p]=b;s=lf(b).decode()
 assert sha(s.encode())==r['baseSha256LF'],p
 for h in [h for h in hunks if h['path']==p]:
  assert s.count(h['before'])==h['beforeCount']==1,p
  s=s.replace(h['before'],h['after'],1)
 assert sha(s.encode())==r['preparedSha256LF'],p
 after[p]=s.encode()
for r in c['newFileCopyWhitelist']:
 p=r['target'];assert not wide(candidate/p).exists();b=read(Path(r['source']));assert sha(b)==r['sha256Bytes'];before[p]=b'';after[p]=b
p='desktop/upstream-sources.json';b=read(candidate/p);before[p]=b
assert sha(lf(b))==delta['baseSha256LF']
registry=json.loads(b);assert len(registry['sources'])==delta['sourcesBefore']==1229
for r in delta['sourceDelta']:
 matches=[x for x in registry['sources'] if x['path']==r['path']]
 if r['operation']=='add-original-identity':
  assert not matches;registry['sources'].append(r['row'])
 else:
  assert len(matches)==1,r
  x=matches[0];assert x==r['before'],r['path']
  assert {k:v for k,v in r['before'].items() if k!='features'}=={k:v for k,v in r['after'].items() if k!='features'}
  assert set(r['before']['features'])<set(r['after']['features'])
  x['features']=r['after']['features']
assert len(registry['sources'])==delta['sourcesAfter']==1234
registry['sources'].sort(key=lambda r:r['path'])
after[p]=(json.dumps(registry,ensure_ascii=False,indent=2)+'\n').encode()
# Actual full Space composable consumes the shared original BackToTop policy.
# Supply the existing global preferences and actual measured viewport, guarded
# by the same retained Space entry admission for each original preference write.
p='desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalSpacePagesRoot.kt'
b=read(candidate/p);before[p]=b;s=lf(b).decode()
a='    active:Boolean,\n';z='    active:Boolean,\n    backToTopPreferences:DesktopFavoritePreferences,\n'
assert s.count(a)==1;s=s.replace(a,z,1)
a='    CompositionLocalProvider(LocalDesktopOriginalSpacePlatform provides platform) {\n'
z='''    val window=androidx.compose.ui.platform.LocalWindowInfo.current.containerSize
    val density=androidx.compose.ui.platform.LocalDensity.current
    val viewport=with(density) { DesktopFavoriteViewport(window.width.toDp().value.toInt(),window.height.toDp().value.toInt()) }
    val backToTop=DesktopBackToTopBindings(backToTopPreferences.backToTopEnabled,backToTopPreferences.initialBackToTopEnabled(),
        backToTopPreferences.backToTopOffset,backToTopPreferences.initialBackToTopOffset(),
        { x,y ->
            val caller=currentCoroutineContext()
            fun checkWrite() { caller.ensureActive();environment.requireVisibleAction() }
            val store=home.pluginContext.store
            store.updateOriginalFromSnapshot("settings",::checkWrite,
                { environment.preferenceWritePermit(store) }) {
                Unit to mapOf("back_to_top_button_offset_x_dp" to kotlinx.serialization.json.JsonPrimitive(x),
                    "back_to_top_button_offset_y_dp" to kotlinx.serialization.json.JsonPrimitive(y))
            }
        },
        { x,y -> environment.requireVisibleAction();backToTopPreferences.updateBackToTopOffset(x,y) },viewport)
    CompositionLocalProvider(LocalDesktopOriginalSpacePlatform provides platform,
        LocalDesktopBackToTopBindings provides backToTop) {
'''
assert s.count(a)==1;s=s.replace(a,z,1);after[p]=s.encode()
p='desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt'
s=after[p].decode();a='                                    desktopDetailRenderEffectsSupported(), active) }\n'
z='                                    desktopDetailRenderEffectsSupported(), active, personalLists.preferences) }\n'
assert s.count(a)==1;s=s.replace(a,z,1);after[p]=s.encode()
for p,b in before.items():
 assert read(candidate/p)==b if wide(candidate/p).exists() else b==b'',p
for p,b in after.items():
 write(root/'before'/p,before[p]);write(candidate/p,b)
rows=[{'path':p,'beforeSha256Bytes':sha(before[p]),'afterSha256Bytes':sha(b)} for p,b in sorted(after.items())]
result=dict(candidateBase=c['candidateBase'],sourceCount=1234,resourceCount=244,
    frozenHandoffSha256Bytes=sha(frozen_raw),sourceTargets=rows,allFrozenFilesVerified=len(frozen['files']),
    exactPgcFragmentsApplied=len(hunks),newPgcFiles=6,spaceBackToTopBindingRepair=True,
    actualProductCompile=False,actualRootWindowAccepted=False,nativePlaybackAccepted=False,newEXEDeployed=False)
write(root/'installation.json',(json.dumps(result,ensure_ascii=False,indent=2)+'\n').encode())
print(json.dumps({k:v for k,v in result.items() if k!='sourceTargets'},indent=2))
