from pathlib import Path
import hashlib,json
P=Path(__file__).resolve().parent
run=P/'runs/actual83-02'
result=json.loads((run/'acceptance-result.json').read_text(encoding='utf8'))
assert result['status']=='FAIL' and result['assertions']==2 and result['productSourceOverrides']==0
assert result['actualSnapshot']==83 and result['loadedOriginsBytesAllActual']
assert (run/'pins-before.json').read_bytes()==(run/'pins-after.json').read_bytes()
log=(run/'runtime.log').read_text(encoding='utf8')
basic=run/'fixture-owned-runtime/isolated-plugin-store/logs/basic.log'
text=basic.read_text(encoding='utf8')
assert 'HTTP 412' in text and 'CooldownManager' in text
assert 'Original request is required for a media operation' not in log
assert 'Actual guest ordinary protocol error: 网络连接失败，请检查网络后重试' in log
assert not any(result[field] for field in ('detailSuccessAccepted','VideoToVideoCarrierAccepted','PiPTransferAccepted'))
files=[f for f in run.iterdir() if f.is_file() and f.suffix!='.jar']+[
    run/'fixture-owned-runtime/root-media-proof.json',basic,
    P/'RootMediaFixture.kt',P/'run.py',P/'diagnosis-attempt02.md',P/'freeze-failed02.py']
artifacts=[dict(path=f.relative_to(P).as_posix(),sha256Bytes=hashlib.sha256(f.read_bytes()).hexdigest(),bytes=f.stat().st_size) for f in sorted(files)]
proof=dict(status='FAIL_ACTUAL_GUEST_MEDIA_ATTEMPT02_HTTP412',artifacts=artifacts,copyWhitelist=[],actualSnapshot=83,
    checksPassed=2,productSourceOverrides=0,loadedOriginsAllActual=True,immutableEntries=101,
    observedServerResponse=412,preciseEndpointObserved=False,throwableStackObserved=False,
    actualPublicBvids=['BV174Yc6JEZo','BV1raaG6QE2H'],
    optionalRequestConstructorExceptionObserved=False,
    nativeSuccess=False,videoDetailSuccess=False,videoToVideo=False,pip=False,realAccount=False,seededSuccess=False,
    productionPayloads=[],extraDiagnosticHttpRequests=0,
    next='No verification bypass or Success injection. Original guest playback remains unaccepted; any authorized ExternalMedia branch is separate.')
(P/'frozen-failed-attempt02.json').write_text(json.dumps(proof,ensure_ascii=False,indent=2)+'\n',encoding='utf8',newline='\n')
print(hashlib.sha256((P/'frozen-failed-attempt02.json').read_bytes()).hexdigest())
