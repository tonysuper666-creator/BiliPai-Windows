from pathlib import Path
import hashlib, json, importlib.util, sys
sys.dont_write_bytecode=True
P=Path(__file__).resolve().parent;MAIN=P.parents[2];REPO=MAIN.parent/'BiliPai-v023'
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def read(p):return wide(p).read_bytes()
def sha(p):return hashlib.sha256(read(p)).hexdigest()
def save(p,v):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
inv=json.loads(read(P/'source-inventory.json'));compileResult=json.loads(read(P/'runs/11/compile-result.json'));assert compileResult['passed']
audit=json.loads(read(P/'runs/11/symbol-audit.json'));assert audit['passed']
sourceChecks=json.loads(read(P/'production-proof/02/source-checks.json'));assert sourceChecks['passed']
spec=importlib.util.spec_from_file_location('package_controls',P/'prepared/desktop/tools/extract-upstream-video-player-full-controls.py');tool=importlib.util.module_from_spec(spec);spec.loader.exec_module(tool)
spec=importlib.util.spec_from_file_location('package_tokens',REPO/'desktop/tools/sync-upstream.py');parser=importlib.util.module_from_spec(spec);spec.loader.exec_module(parser);tool.parser=parser
def text(p):return read(p).decode().replace('\r\n','\n')
before=text(P/'original-stable/app/src/main/java/com/android/purebilibili/core/store/player/PlayerSettingsStore.kt')
body=before[before.index('{',before.index('object PlayerSettingsStore'))+1:before.rfind('}')]
after=text(P/'generated/com/android/purebilibili/core/store/player/DesktopOriginalVideoPlayerSettings.kt')
generated=after[after.index('{',after.index('object DesktopOriginalVideoPlayerSettings'))+1:after.rfind('}')]
a,b=tool.function_range(body,'getLongPressSpeed')
delegate='''    fun getLongPressSpeed(context: Context): Flow<Float> =
        DesktopOriginalLongPressSpeedSettings.getLongPressSpeed(context.pluginContext)
'''
assert generated.count(delegate)==1
restored=generated.replace(delegate,body[a:b],1)
# The adapter's object wrapper adds one newline on each side; the complete canonical object
# body is otherwise byte-identical, with only the sole external long-press getter delegation.
assert restored=='\n'+body+'\n'
save(P/'player-settings-body-audit.json',dict(passed=True,sourceSha256LF=hashlib.sha256(before.encode()).hexdigest(),completeObjectBodyReverseExact=True,onlyMemberReplacement='getLongPressSpeed delegates sole Offline producer',wrapperNewlines=2,mathKeysDefaultsMigrationUnchanged=True))
current=json.loads(read(REPO/'desktop/upstream-sources.json'));existing={r['path']:r for r in current['sources']};rows=[]
for source in inv['sources']:
 path=source['path'];outputs=[o for o in inv['outputs']if o['origin']==path]
 mode='direct'if any(o['mode']=='direct-complete-original'for o in outputs)else'policy-extract'if outputs else'reference'
 old=existing.get(path)
 if old:assert old['sha256']==source['sha256LF'],path
 rows.append(dict(path=path,sha256=source['sha256LF'],gitBlob=source['gitBlob'],feature='stable-video-player-full-controls',proposedMode=mode,existingMode=old.get('mode')if old else None,outputs=[r['path']for r in outputs],mergeOnly=True,modeOwnerDecisionRequired=bool(old and old.get('mode')!=mode)))
save(P/'registry-delta.json',dict(commit=inv['commit'],identities=len(rows),mergeExistingIdentityFeatureUnion=True,doNotReplaceRegistry=True,sources=rows))
manual=[]
for p in sorted(wide(P/'prepared/manual').rglob('*.kt')):
 manual.append(dict(path=p.relative_to(wide(P/'prepared/manual')).as_posix(),sha256LF=sha(p),bytesLF=len(read(p))))
tools=[]
for name in ['extract-upstream-video-player-full-controls.py','verify-upstream-video-player-full-controls.py']:
 p=P/'prepared/desktop/tools'/name;tools.append(dict(path=p.relative_to(P).as_posix(),sha256LF=sha(p)))
recipe=dict(stage='Complete original ordinary controls/settings/menu/overlay; full ordinary player page assembly next',commit=inv['commit'],baseline='immutable actual47/97 plus frozen prospective Stage1 foundation, not Main runtime acceptance',generator='generate(repo, output, standalone=False)',soleTools=tools,generatedDirectory='build/generated/original-video-player-full-controls',selectedOutputs=[r for r in inv['outputs']if r['mode']!='direct-complete-original'],directCopyOnce=[r for r in inv['outputs']if r['mode']=='direct-complete-original'],manualOutputs=manual,registryDelta='registry-delta.json',requiredRootContract='ROOT-CONTRACT.md',noWholeControllerOrOpsOrShell=True,externalSoleReference='DesktopOriginalLongPressSpeedSettings from frozen Offline family; do not install its proof source copy',validation=['runs/11/compile-result.json','runs/11/symbol-audit.json','production-proof/02/source-checks.json','player-settings-body-audit.json'],sourceDirs='Keep all existing source roots/tasks; add one generated directory and its source exact verifier. Original direct26 are sole source-sync outputs.',pending=['Full ordinary VideoPlayerSection/StateHolder/VideoContent Root assembly','Actual original gestures/native input/fullscreen/portrait/PiP/viewport aspect consumer','Actual cast receipt/proxy/plugin publication and full Share composable consumer','Battery native ABI OS probe and mounted controls UI'],noHTTP=True,noHWND=True,noSharedGradle=True,noLiveEdits=True)
save(P/'INSTALL-RECIPE.json',recipe)
save(P/'history.json',dict(historicalFailuresPreserved=True,compile07='Prepare generation failed, but shell launched a compile on partial old sources; its PASS is not final46-output proof.',productionAudit01='Generation completed; character-based inverse diff audit was terminated as unnecessarily quadratic before any result. Partial artifacts retained; audit02 uses line patches and reverse-exact byte restoration.',finalCompile='11',finalProductionAudit='02',productRuntimeAccepted=False))
rows=[];excluded=[]
for p in sorted(wide(P).rglob('*')):
 if not p.is_file():continue
 rel=p.relative_to(wide(P)).as_posix()
 if rel=='frozen-stage2.json':continue
 if p.suffix.lower() in ['.jar','.class','.pyc'] or '__pycache__'in p.parts:excluded.append(dict(path=rel,reason='binary/private build product'));continue
 rows.append(dict(path=rel,bytes=len(read(p)),sha256Bytes=sha(p)))
save(P/'frozen-stage2.json',dict(frozen=True,stage='source-only full original ordinary controls closure',commit=inv['commit'],rawArtifacts=rows,excluded=excluded,sourceIdentities=48,standaloneOutputs=46,productionSelectedOutputs=20,directCopyOnce=26,manualOutputs=8,compileSources=55,compilePassed=True,classAndTopLevelDescriptorIntersections=0,illegalNonLocalReturnClasses=0,actualRuntimeAccepted=False,noHTTP=True,noHWND=True,noGradle=True,noLiveMutation=True))
print(json.dumps(dict(path=str(P/'frozen-stage2.json'),sha256Bytes=sha(P/'frozen-stage2.json'),rawRows=len(rows),excluded=len(excluded),recipeSha256Bytes=sha(P/'INSTALL-RECIPE.json'))))
