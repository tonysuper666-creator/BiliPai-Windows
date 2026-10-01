from pathlib import Path
import hashlib,json,sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent
def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def read(p):return safe(p).read_text(encoding='utf-8')
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def main():
 target=HERE/'frozen-handoff.json';assert not safe(target).exists()
 compileResult=json.loads(read(HERE/'compile-03/compile-result.json'));assert compileResult['status']=='PASS' and not compileResult['classOverlapWithActual']
 proof=json.loads(read(HERE/'ui-proof-02/accepted-result.json'));assert proof['status']=='PASS' and proof['caseCount']==2 and proof['assertions']==22
 audit=json.loads(read(HERE/'source-and-symbol-audit.json'));assert audit['status']=='PASS' and audit['checkCount']==131
 production=json.loads(read(HERE/'production-byte-equality.json'));assert production['status']=='PASS' and production['byteEqualGeneratedCount']==32
 artifacts=[]
 for path in sorted(safe(HERE).rglob('*')):
  if not path.is_file():continue
  relative=path.relative_to(safe(HERE)).as_posix();assert '__pycache__' not in relative
  artifacts.append(dict(path=relative,byteSize=path.stat().st_size,sha256Bytes=sha(path)))
 result=dict(status='FROZEN_PREPARED',artifactCount=len(artifacts),artifacts=artifacts,originalCommit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',
  actualStable11ManifestSha256Bytes=compileResult['actualStable11ManifestSha256Bytes'],ordered92CpSha256Bytes=compileResult['actualCp92Sha256Bytes'],
  sourceCount=32,platformSourceCount=2,sourceCheckCount=131,classOverlapWithActual=0,topLevelMethodOverlapWithActual=0,candidateTopLevelMethodCount=205,actualTopLevelMethodCount=915,
  sourceAuditSha256Bytes=sha(HERE/'source-and-symbol-audit.json'),productionByteEqualitySha256Bytes=sha(HERE/'production-byte-equality.json'),
  compileResultSha256Bytes=sha(HERE/'compile-03/compile-result.json'),candidateJarSha256Bytes=sha(HERE/'compile-03/prepared-shared-liquid-tabs.jar'),
  uiAcceptedSha256Bytes=sha(HERE/'ui-proof-02/accepted-result.json'),themeCellCount=2,assertions=22,pointerPairs=4,actualRecordedLayerSamples=4,
  installPayload=['prepared/desktop/tools/extract-upstream-shared-liquid-tabs.py','prepared/desktop/src/main/kotlin/com/bilipai/desktop/settings/DesktopLiquidTabSettings.kt','prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopLiquidReadabilityPlatform.kt'],
  recipe='contract.txt',forbidInstalling=['original-source','compile-01','compile-02','compile-03','ui-proof-01','ui-proof-02'],
  requiredRootBindings=['LocalDesktopLiquidTabSettings: same existing applicationPluginStore','LocalAppThemeConfig: copy enabled from same original preference','LocalLiquidGlassRenderConfig: same original tuning/preset','LocalDesktopLiquidReadabilityEnvironment: actual independent page GraphicsLayer/window geometry/foreground-page owner'],
  noNewStore=True,noNewHomeSettings=True,noNewSettingsManager=True,noNewClient=True,noMainOrSiblingEdits=True,noSharedGradle=True,noHTTP=True,noHWND=True,
  preparedOnly=True,notRootNativeWindowOrAndroidPixelEquivalence=True,adaptiveRootIntegrationPending=True,failedAttemptHistoryRetained=True)
 safe(target).write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n');print(json.dumps(dict(path=str(target),sha256Bytes=sha(target),artifactCount=len(artifacts))))
if __name__=='__main__':main()
