from pathlib import Path
import importlib.util,json,sys,zipfile
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent;MAIN=next(p for p in HERE.parents if (p/'.git').exists())
spec=importlib.util.spec_from_file_location('existing_tools',MAIN/'desktop/.local/stable-native-text-share-bindings-parity/compile.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
def main():
 out=HERE/'compile-01';assert not c.safe(out).exists();c.safe(out).mkdir()
 cp=c.runtime();c.save(out/'runtime-pins-before.json',c.pins(cp));source=HERE/'prepared/desktop/src/main/kotlin/com/bilipai/desktop/diagnostics/DesktopNativeTextShare.kt';dest=out/'DesktopNativeTextShare.kt';c.safe(dest).write_bytes(c.safe(source).read_bytes());target=out/'owned-native-text-share.jar'
 p=c.compile_sources(out,[dest],target,[x['path'] for x in cp],[str(c.SNAP/'main-kotlin.jar')]);c.save(out/'runtime-pins-after.json',c.pins(cp))
 result=dict(status='PASS' if p.returncode==0 else 'FAIL',exitCode=p.returncode,sourceSha256LF=c.sha(dest),actualMain15ManifestSha256Bytes=c.sha(c.SNAP/'manifest.json'),actualCp92Sha256Bytes=c.sha(c.SNAP/'ordered-runtime-cp.json'))
 if not p.returncode:
  with zipfile.ZipFile(c.safe(target)) as z:own={x for x in z.namelist() if x.endswith('.class')}
  with zipfile.ZipFile(c.safe(c.SNAP/'main-kotlin.jar')) as z:actual={x for x in z.namelist() if x.endswith('.class')}
  assert all(x.startswith('com/bilipai/desktop/diagnostics/DesktopNativeTextShare') for x in own)
  result.update(jarSha256Bytes=c.sha(target),declaredActorClassOverlaps=sorted(own&actual),allCandidateClasses=sorted(own),undeclaredOverlap=[])
 c.save(out/'compile-result.json',result);print(json.dumps(result));print(p.stdout+p.stderr);return p.returncode
if __name__=='__main__':raise SystemExit(main())
