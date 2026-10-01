from pathlib import Path
import hashlib, json, sys
HERE=Path(__file__).resolve().parent
def safe(path):return Path('\\\\?\\'+str(Path(path).absolute()))
def sha(path):return hashlib.sha256(safe(path).read_bytes()).hexdigest()
def main():
 target=HERE/'frozen-handoff.json';assert not safe(target).exists()
 checks=json.loads(safe(HERE/'source-checks.json').read_text(encoding='utf-8'));assert checks['status']=='PASS'
 rows=[dict(path=path.relative_to(HERE).as_posix(),bytes=safe(path).stat().st_size,sha256Bytes=sha(path))
   for path in sorted(HERE.rglob('*')) if safe(path).is_file() and '__pycache__' not in path.parts and path!=target]
 value=dict(frozen=True,formatVersion=1,scope='Prepared complete stable Subscription source closure; no actual-product/UI acceptance',
  upstreamCommit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',snapshotManifestSha256Bytes='a94cf303442cc285a81650237f0cc1a3910c47e4f3b1a43e42a23e36e0c63883',
  orderedRuntimeCpSha256Bytes='e76eb70649106c7acc0d446c539e6b971fcf578daaca68221a5897fda2fdb09d',runtimeEntries=97,
  artifactCount=len(rows),artifacts=rows,installSources=[
   'prepared/desktop/tools/extract-upstream-subscription-page.py',
   'prepared/desktop/src/main/kotlin/com/bilipai/desktop/plugins/DesktopSubscriptionWriteAdmission.kt',
   'prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopSubscriptionPageBindings.kt',
   'prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopSubscriptionPredictiveBack.kt'],
  exactExistingHunks=['hunks/DesktopSubscriptionRepository.json','hunks/DesktopPluginServices.json','hunks/DesktopPluginStore.json'],
  sourceContract='ROOT-INTEGRATION.md',registryMerge='registry-recipe.json',actualProductRuntimeAccepted=False,HTTP=False,GUI=False,HWND=False,Gradle=False)
 safe(target).write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
 for row in rows:assert sha(HERE/row['path'])==row['sha256Bytes']
 print(json.dumps(dict(manifest=str(target),sha256Bytes=sha(target),artifacts=len(rows),contractSha256Bytes=sha(HERE/'ROOT-INTEGRATION.md'),sourceChecksSha256Bytes=sha(HERE/'source-checks.json')),indent=2))
if __name__=='__main__':main()
