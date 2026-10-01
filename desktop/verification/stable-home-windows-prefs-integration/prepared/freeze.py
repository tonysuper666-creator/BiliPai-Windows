from pathlib import Path
import hashlib,json,sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent
def safe(path):
 value=str(path.absolute());return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def sha(path):return hashlib.sha256(safe(path).read_bytes()).hexdigest()
def main():
 target=HERE/'frozen-handoff.json';assert not target.exists()
 checks=json.loads((HERE/'source-checks.json').read_text())
 assert checks['productionClassIntersection']==[] and checks['actual33BinaryDeclarationNameMatches']==[]
 inventory=json.loads((HERE/'source-inventory.json').read_text())
 assert inventory['nativeExactHunks']['allOriginalNativeBytesReverseNormalizedEqual']
 for item in inventory['newManualSource']:assert sha(HERE/item['path'])==item['sha256Bytes']
 files=[p for p in sorted(HERE.rglob('*')) if p.is_file() and '__pycache__' not in p.parts]
 value=dict(status='FROZEN_SOURCE_ONLY_HOME_WINDOWS_PREFERENCE_PORTS',artifactCount=len(files),artifacts=[dict(path=str(p.relative_to(HERE)).replace('\\','/'),bytes=safe(p).stat().st_size,sha256Bytes=sha(p)) for p in files],manualInstallFiles=['prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopHomeWindowsPreferencesPlatform.kt'],exactLocalHunks=['native-local.patch','sole-classification-producer-hunk.json'],sameExistingRegistryMerge='registry-merge-recipe.json',proofOnlyNotInstall=['native-candidate','runs','prepared/desktop/generated'],compilePass=dict(kotlinClasses=checks['compileClasses'],productionClassIntersection=[],sameNativeDll=checks['nativeDll']),nativeOrGuiRuntimeAccepted=False,appHTTP=False,officialSourceDownloadsOnly=True,MainOrSharedModified=False)
 safe(target).write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
 print(json.dumps(dict(path=str(target),sha256Bytes=sha(target),artifacts=len(files),manualInstallFiles=1,exactLocalHunks=2),indent=2))
if __name__=='__main__':main()
