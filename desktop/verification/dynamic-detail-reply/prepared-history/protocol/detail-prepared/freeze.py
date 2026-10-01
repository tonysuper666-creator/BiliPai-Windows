from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent
def safe(path):
    value=str(path.absolute());return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def sha(path):return hashlib.sha256(safe(path).read_bytes()).hexdigest()
def read(path):return safe(path).read_text(encoding='utf-8')
old=json.loads(read(HERE.parent/'frozen-manifest.json'))
assert sha(HERE.parent/'frozen-manifest.json')=='7823571e997d514383efbf2faf7244b42853f47afd2bcd82149b466c9d8f9d18'
for item in old['frozenArtifacts']:assert sha(HERE.parent/item['path'])==item['sha256Bytes'],item['path']
proof=HERE/'proof-attempt4';evidence=json.loads(read(proof/'evidence.json'));result=json.loads(read(proof/'result.json'))
assert evidence['compilePassed'] and evidence['runtimePassed'] and len(result['cases'])==6
files=[HERE/n for n in ['produce.py','compile-run.py','freeze.py','detail-contract.txt','source-inventory.json',
    'review-top-level.py','top-level-uniqueness.json','actual-repository-top-methods.txt',
    'DesktopDynamicDetailOperations.fragment.kt','DynamicDetailProtocolFixture.kt']]
files+=[Path(str(p).removeprefix('\\\\?\\')) for p in safe(HERE/'generated').rglob('*') if p.is_file()]
files+=[Path(str(p).removeprefix('\\\\?\\')) for p in safe(proof).rglob('*') if p.is_file()]
manifest=dict(frozen=True,phase='Task-only original detail fallback protocol actual-editor-Main base',
    MainIntegration=False,snapshotManifestSha256Bytes=evidence['snapshotManifestSha256Bytes'],
    orderedRuntimeCpSha256Bytes=evidence['orderedRuntimeCpSha256Bytes'],orderedRuntimeCpCount=89,
    compiledSources=8,groupedCases=6,preparedProductOverride=evidence['preparedProductOverride'],
    sourceBaseRetainedInOwnProof=True,newAPIOrModel=False,newSeedCache=False,
    originalHistoryCallbackNullIsUnbound=True,
    originalRankingPreservedIncludingWordFirstTieAndPlaceholderDoubleDesktop=True,
    previousFrozenCommentArtifactsRecheckedUnchanged=len(old['frozenArtifacts']),
    retainedFailedAttempts=['proof-attempt1','proof-attempt2','proof-attempt3'],
    frozenArtifacts=[dict(path=p.relative_to(HERE).as_posix(),sha256Bytes=sha(p),byteLength=safe(p).stat().st_size) for p in sorted(files)])
safe(HERE/'frozen-manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
print(json.dumps(dict(manifestSha256Bytes=sha(HERE/'frozen-manifest.json'),artifacts=len(files),
    evidenceSha256Bytes=sha(proof/'evidence.json'),resultSha256Bytes=sha(proof/'result.json'),
    fragmentSha256Bytes=sha(HERE/'DesktopDynamicDetailOperations.fragment.kt'))))

