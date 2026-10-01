from pathlib import Path
import hashlib,json
P=Path(__file__).resolve().parent
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def read(p):return wide(p).read_bytes()
def sha(p):return hashlib.sha256(read(p)).hexdigest()
def save(p,v):wide(p).write_bytes((json.dumps(v,ensure_ascii=False,indent=2)+'\n').encode())
result=json.loads(read(P/'runs/01/compile-result.json'));assert result['passed'] and result['sourceInputs']==1 and result['classes']==23
binding=json.loads(read(P/'local-hunks/binding.json'));visitor=json.loads(read(P/'local-hunks/repository-visitor.json'))
paths=[P/'prepare.py',P/'compile.py',P/'freeze.py',P/'ROOT-INTEGRATION.md',P/'source-contract.json',P/'local-hunks/binding.json',P/'local-hunks/repository-visitor.json']
for directory in ['baseline','prepared']:
 paths+=list(wide(P/directory).rglob('*.kt'))
paths += [P/'runs/01'/n for n in['DesktopOriginalVideoRepositoryBinding.kt','compile.log','compile.args','compile-result.json','pins-before.json','pins-after.json']]
rows=[dict(path=str(p).removeprefix('\\\\?\\').replace('\\','/').removeprefix(str(P).replace('\\','/')+'/'),sha256Bytes=sha(p),bytes=len(read(p)))for p in paths]
manifest=dict(frozen=True,status='SOURCE_READY_BINDING_FACET_COMPILED',actualBase=67,runtimeEntries=101,existingBindingCaptureAndConstructorAbiUnchanged=True,exactBindingHunks=4,exactRepositoryVisibilityHunks=1,parentMetadataProtocolAndWbiSourceOwnerReferencedOnly=True,newStoreClientCacheOrModels=False,compiledSourceInputs=1,compiledClasses=23,explicitProspectiveOverride='DesktopOriginalVideoRepositoryBinding family only',repositoryVisibilityAccessorNotCompiledOverride=True,runtimeOrHttpAccountGuiNativeRun=False,baseBindingSHA256LF=binding['baseSHA256LF'],candidateBindingSHA256LF=binding['candidateSHA256LF'],repositoryBaseSHA256LF=visitor['baseSHA256LF'],repositoryCandidateSHA256LF=visitor['candidateSHA256LF'],installation='ROOT-INTEGRATION.md',pending=['Parent sole metadata/core getWbiKeys visibility installation','Full request factory forwarding actual primaryApi/playbackCalls and required visitor getter','VIP change may invalidate old receipt; explicit request retirement/recapture policy required'],artifacts=rows,rawArtifactCount=len(rows),excluded=['runs/01/candidate.jar','*.class','all actual product dependency binaries'],installWholeFile=False)
save(P/'frozen-handoff.json',manifest)
print(json.dumps(dict(path=str(P/'frozen-handoff.json'),sha256Bytes=sha(P/'frozen-handoff.json'),rawRows=len(rows),bindingCandidateSHA256LF=binding['candidateSHA256LF'])))
