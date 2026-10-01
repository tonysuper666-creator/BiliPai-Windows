from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent
def safe(path):
    value=str(path.absolute())
    return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def sha(path):return hashlib.sha256(safe(path).read_bytes()).hexdigest()
def read(path):return safe(path).read_text(encoding='utf-8')
evidence=json.loads(read(HERE/'proof-attempt5/evidence.json'))
result=json.loads(read(HERE/'proof-attempt5/result.json'))
assert evidence['compilePassed'] and evidence['runtimePassed'] and result['passed']
assert len(result['cases'])==12
payload=['produce.py','compile-run.py','freeze.py','protocol-contract.txt','source-inventory.json',
    'DesktopDynamicCommentOperations.fragment.kt','ReplyProtocolTransportFixture.kt','RawReplyParserFixture.kt']
files=[HERE/name for name in payload]
files+=[Path(str(p).removeprefix('\\\\?\\')) for p in safe(HERE/'generated').rglob('*') if p.is_file()]
files+=[Path(str(p).removeprefix('\\\\?\\')) for p in safe(HERE/'proof-attempt5').rglob('*') if p.is_file()]
identities=[dict(path=p.relative_to(HERE).as_posix(),sha256Bytes=sha(p),byteLength=safe(p).stat().st_size) for p in sorted(files)]
manifest=dict(formatVersion=1,frozen=True,scope='Task-only original raw comment protocol and independent fixture proof-attempt5',
    MainIntegration=False,sharedGradle=False,HTTP=False,HWND=False,realAccount=False,
    snapshotManifestSha256Bytes=evidence['snapshotManifestSha256Bytes'],
    orderedRuntimeCpSha256Bytes=evidence['orderedRuntimeCpSha256Bytes'],orderedRuntimeCpCount=89,
    compiledSources=6,groupedCases=12,
    explicitPreparedProductOverride=evidence['explicitPreparedProductOverride'],
    originalAPIAndModelsReused=True,
    unboundSideEffects=['Original CommentFraud background store: optional owned callback remains null unless supplied'],
    retainedEarlierFailedAttempts=['proof-attempt1','proof-attempt2','proof-attempt3','proof-attempt4'],
    frozenArtifacts=identities)
safe(HERE/'frozen-manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
print(json.dumps(dict(manifestSha256Bytes=sha(HERE/'frozen-manifest.json'),artifacts=len(identities),
    resultSha256Bytes=sha(HERE/'proof-attempt5/result.json'),evidenceSha256Bytes=sha(HERE/'proof-attempt5/evidence.json'))))

