from pathlib import Path
import hashlib, json, sys
sys.dont_write_bytecode = True
HERE=Path(__file__).resolve().parent
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def main():
    target=HERE/'actual33-frozen-handoff.json'; assert not target.exists()
    files=[HERE/name for name in ['prepared-fixture-handoff.json','verify-actual33.py','actual33-readonly-verification.json','ACTUAL33-RESULT.md','freeze-actual33.py']]
    files.extend(path for path in sorted((HERE/'runs/actual33-01').rglob('*')) if path.is_file())
    result=json.loads((HERE/'runs/actual33-01/proof/result.json').read_text(encoding='utf-8'))
    accepted=json.loads((HERE/'runs/actual33-01/accepted.json').read_text(encoding='utf-8'))
    assert result['status']=='PASS' and result['assertions']==19
    assert accepted['productionClassOverrides']==0 and accepted['runtimeEntries']==97
    artifacts=[dict(path=str(path.relative_to(HERE)).replace('\\','/'),bytes=path.stat().st_size,sha256Bytes=sha(path)) for path in files]
    value=dict(status='FROZEN_ACTUAL_MAIN33_NATIVE_AND_OFFSCREEN_PROOF',artifactCount=len(artifacts),artifacts=artifacts,assertions=result['assertions'],productionClassOverrides=0,actualClassSourcesVerified=9,runtimeEntries=97,snapshotManifestSha256Bytes=accepted['snapshotManifestSha256Bytes'],orderedCpSha256Bytes=accepted['orderedCpSha256Bytes'],sourcePreparationManifestSha256Bytes=sha(HERE/'prepared-fixture-handoff.json'),nativeLocalCpuFrames=True,isolatedOffscreenCompose=True,nativeWindow=False,RootUiAccountPlaybackProof=False,screenMonitorPresentation=False,performanceBenchmark=False,sourceOrNativeBinaryModified=False)
    target.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
    print(json.dumps(dict(path=str(target),sha256Bytes=sha(target),artifacts=len(artifacts),assertions=19),indent=2))
if __name__=='__main__': main()
