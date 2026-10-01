from pathlib import Path
import hashlib,json,sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent
def safe(path):
 value=str(path.absolute());return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def sha(path):return hashlib.sha256(safe(path).read_bytes()).hexdigest()
def main():
 target=HERE/'frozen-handoff.json';assert not target.exists()
 files=[path for path in sorted(HERE.rglob('*')) if path.is_file() and '__pycache__' not in path.parts]
 result=json.loads((HERE/'runs/compile-02/compile-result.json').read_text(encoding='utf-8'))
 assert result['status']=='PASS' and result['productionClassIntersection']==[]
 value=dict(status='FROZEN_SOURCE_ONLY_WINDOW_GLOBALS_AND_LOTTIE',artifactCount=len(files),artifacts=[dict(path=str(path.relative_to(HERE)).replace('\\','/'),bytes=safe(path).stat().st_size,sha256Bytes=sha(path)) for path in files],installPayload=[dict(source=str(path.relative_to(HERE)).replace('\\','/'),destination=str(path.relative_to(HERE/'prepared')).replace('\\','/'),sha256Bytes=sha(path)) for path in sorted((HERE/'prepared').rglob('*.kt'))],sourceOnlyCompilePass=True,productionClassIntersection=[],runtimeOrAnimationAccepted=False,HTTP=False,nativeWindow=False,MainOrSharedSourceModified=False)
 safe(target).write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
 print(json.dumps(dict(path=str(target),sha256Bytes=sha(target),artifacts=len(files),manualInstallFiles=len(value['installPayload'])),indent=2))
if __name__=='__main__':main()
