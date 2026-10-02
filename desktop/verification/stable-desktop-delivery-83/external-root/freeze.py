from pathlib import Path
import hashlib,json
P=Path(__file__).resolve().parent
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
final=P/'runs/actual83-02'
result=json.loads((final/'acceptance-result.json').read_text(encoding='utf8'))
assert result['status']=='PASS_ACTUAL_AUTHORIZED_EXTERNAL_ROOT' and result['assertions']==17
assert result['productSourceOverrides']==0 and result['loadedOriginsBytesAllActual'] and result['immutableEntries']==101
assert result['externalFirstFrameAccepted'] and result['externalTwoSourceAccepted'] and result['PiPTransferAccepted']
assert not result['detailSuccessAccepted'] and not result['VideoToVideoCarrierAccepted'] and result['semanticCallbackOnly']
assert (final/'pins-before.json').read_bytes()==(final/'pins-after.json').read_bytes()
assert sha(P/'RootExternalFixture.kt')==json.loads((final/'pins-before.json').read_text(encoding='utf8'))['sourceSHA']
initial=json.loads((P/'runs/actual83-01/acceptance-result.json').read_text(encoding='utf8'))
assert initial['status']=='FAIL' and initial['externalFirstFrameAccepted']
files=[P/name for name in ('RootExternalFixture.kt','prepare.py','run.py','README.md','freeze.py')]
excluded=[]
for number in ('01','02'):
    run=P/f'runs/actual83-{number}'
    files.extend(f for f in run.iterdir() if f.is_file() and f.suffix!='.jar')
    files.append(run/'fixture-owned-runtime/root-external-proof.json')
    basic=run/'fixture-owned-runtime/isolated-plugin-store/logs/basic.log'
    if basic.exists():files.append(basic)
    files.extend((run/'fixture-owned-runtime').glob('*.png'))
    excluded.append(dict(path=(run/'root-external-fixture.jar').relative_to(P).as_posix(),sha256Bytes=sha(run/'root-external-fixture.jar'),reason='fixture-only compiled test artifact'))
clip=P/'local-fixture-media.mp4'
excluded.append(dict(path=clip.name,sha256Bytes=sha(clip),reason='generated loopback-only fixture input; not product payload'))
artifacts=[dict(path=f.relative_to(P).as_posix(),sha256Bytes=sha(f),bytes=f.stat().st_size) for f in sorted(set(files))]
proof=dict(status='PASS_ACTUAL83_AUTHORIZED_EXTERNAL_BRANCH',artifacts=artifacts,binaryExcluded=excluded,
    actualSnapshot=83,checksPassed=17,copyWhitelist=[],productionPayloads=[],productSourceOverrides=0,
    immutableEntries=101,loadedProductOrigins=8,seededSuccess=False,realAccount=False,globalInput=False,
    permissionApprovedThroughActualRepository=True,semanticCallbacks=True,physicalPointerAccepted=False,
    nativeFirstFrame=True,externalTwoSource=True,externalPiPAndRestore=True,rootShutdownDrained=True,
    ordinaryVideoDetailSuccess=False,ordinaryVideoToVideo=False,controlsVisibilityAccepted=False,
    nativeDecodedPixelCapture=False,SMTCButtonAccepted=False,settingsVisualOcclusionAccepted=False,
    failedAttempt01Preserved=True,failedAttempt01Cause='Fixture incorrectly assumed ordinary Assembly retention across original ExternalMedia acquire/stop')
(P/'frozen-handoff.json').write_text(json.dumps(proof,ensure_ascii=False,indent=2)+'\n',encoding='utf8',newline='\n')
print('raw='+str(len(artifacts))+' manifestSHA='+sha(P/'frozen-handoff.json'))
