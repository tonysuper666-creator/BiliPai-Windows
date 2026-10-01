from pathlib import Path
import hashlib,json,zipfile
HERE=Path(__file__).resolve().parent
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
 output=HERE/'frozen-handoff.json';assert not output.exists()
 result=json.loads((HERE/'runs/04/compile-result.json').read_text(encoding='utf-8'));assert result['status']=='PASS'
 checks=json.loads((HERE/'source-checks.json').read_text(encoding='utf-8'));assert checks['status']=='PASS' and len(checks['preparedPayloadSources'])==6
 for row in result['sourceInputs']:
  p=Path(row['path']);assert sha(p)==row['sha256Bytes'];q=HERE/'prepared'/p.relative_to(HERE/'runs/04/source-inputs');assert sha(q)==sha(p)
 manifest=dict(frozen=True,preparedOnly=True,phase='source-only6-home-media-platform',upstreamCommit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',actualDependencyManifestSha256Bytes=result['actualMain30Manifest'],explicitPreparedHome332ClassDependency=result['explicitPreparedHomeClassOverlay'],compileResult=dict(path='runs/04/compile-result.json',sha256Bytes=sha(HERE/'runs/04/compile-result.json'),jarSha256Bytes=result['jarSha256Bytes'],candidateClasses=result['classCount']),sourceChecksSha256Bytes=sha(HERE/'source-checks.json'),contract=dict(path='ROOT-INTEGRATION.md',sha256Bytes=sha(HERE/'ROOT-INTEGRATION.md')),manualNewSources=4,existingSoleFileHunks=2,nativePlaybackExecuted=False,actualHwndOrExternalAppsUsed=False,externalApiAccountHttpUsed=False,officialPinnedHeaderDownloadOnly=True,originalParentUiAndVmProducersChanged=False,pending=['actual DLL software-render ABI/startup/paused first-frame acceptance','actual Compose video modifier/haze/crop/scrim sample','CPU realtime performance/HDR/GPU zero-copy','same sole parent Home caller subject-key delta','Root retained Home/data/media installation and direct shared service wiring','Windows local wallpaper picker/content URI migration; remote GIF outside local-document adapter'],artifacts=[])
 for p in sorted(HERE.rglob('*')):
  if p.is_file() and '__pycache__' not in p.parts and p!=output:manifest['artifacts'].append(dict(path=str(p.relative_to(HERE)).replace('\\','/'),sha256Bytes=sha(p),bytes=p.stat().st_size))
 output.write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
 print(json.dumps(dict(path=str(output),sha256Bytes=sha(output),artifacts=len(manifest['artifacts']),contractSha256Bytes=manifest['contract']['sha256Bytes'],compileJarSha256Bytes=result['jarSha256Bytes']),indent=2))
if __name__=='__main__':main()
