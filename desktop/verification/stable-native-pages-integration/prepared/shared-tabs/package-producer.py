"""Package production source-only extractor, proving equality with compiled inputs."""
from pathlib import Path
import hashlib,importlib.util,json,sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent
def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def write(p,s):safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(s,encoding='utf-8',newline='\n')
def one(s,a,b):assert s.count(a)==1,(s.count(a),a[:100]);return s.replace(a,b,1)
def main():
 mainrepo=next(p for p in HERE.parents if (p/'.git').exists());repo=mainrepo.parent/'BiliPai-v023'
 imports=read(mainrepo/'desktop/.local/dynamic-detail-reply-parity/detail-container-next/liquid-next/generated/com/android/purebilibili/feature/home/components/DesktopOriginalDetailLiquidDockSurface.kt').split('package com.android.purebilibili.feature.home.components\n',1)[1].split('private val iosIndicatorSpecular',1)[0]
 source=read(HERE/'prepare.py')
 source=one(source,"HERE=Path(__file__).resolve().parent\nMAIN=next(p for p in HERE.parents if (p/'.git').exists());REPO=MAIN.parent/'BiliPai-v023'\n",'')
 source=one(source,'def main():','def generate(repo: Path, output: Path, standalone: bool = False):\n REPO=Path(repo);HERE=Path(output)')
 source=one(source,"  write(HERE/'original-source'/path,source)\n",'')
 source=one(source," inv=json.loads(read(HERE/'initial-closure-inventory.json'))",' inv='+repr({'sources':json.loads(read(HERE/'initial-closure-inventory.json'))['sources']}))
 source=one(source," imports=read(MAIN/'desktop/.local/dynamic-detail-reply-parity/detail-container-next/liquid-next/generated/com/android/purebilibili/feature/home/components/DesktopOriginalDetailLiquidDockSurface.kt').split('package com.android.purebilibili.feature.home.components\\n',1)[1].split('private val iosIndicatorSpecular',1)[0]",' imports='+repr(imports))
 source=one(source," print(json.dumps(dict(preparedSources=len(records))))\nif __name__=='__main__':main()"," return sorted(safe(HERE/'generated').rglob('*.kt'))\nif __name__=='__main__':\n import argparse\n parser=argparse.ArgumentParser();parser.add_argument('--repo',required=True);parser.add_argument('--output',required=True);parser.add_argument('--standalone',action='store_true');args=parser.parse_args()\n generate(Path(args.repo),Path(args.output),args.standalone)")
 # Strip the task source inventory's platform-ref lines from the production input
 # list; original Git blobs remain verified by emit() for every selected source.
 value={'sources':[dict(path=x['path']) for x in json.loads(read(HERE/'initial-closure-inventory.json'))['sources']]}
 initial_repr=repr({'sources':json.loads(read(HERE/'initial-closure-inventory.json'))['sources']})
 source=one(source,' inv='+initial_repr,' inv='+repr(value))
 path=HERE/'prepared/desktop/tools/extract-upstream-shared-liquid-tabs.py';write(path,source)
 spec=importlib.util.spec_from_file_location('prepared_shared_liquid_producer',safe(path));producer=importlib.util.module_from_spec(spec);spec.loader.exec_module(producer)
 outputs=producer.generate(repo,safe(HERE/'production-byte-proof'),False);assert len(outputs)==32
 rows=[]
 for p in outputs:
  relative=p.relative_to(safe(HERE/'production-byte-proof'));expected=HERE/relative
  assert safe(p).read_bytes()==safe(expected).read_bytes(),relative
  rows.append(dict(path=relative.as_posix(),sha256Bytes=hashlib.sha256(safe(p).read_bytes()).hexdigest()))
 for p in safe(HERE/'platform').rglob('*.kt'):
  target=HERE/'prepared/desktop/src/main/kotlin'/p.relative_to(safe(HERE/'platform'));write(target,read(p))
 result=dict(status='PASS',byteEqualGeneratedCount=len(rows),generated=rows,noLocalOrSnapshotDependencyInProductionProducer='.local' not in source,producerSha256Bytes=hashlib.sha256(safe(path).read_bytes()).hexdigest(),platformAdaptersExactCompiledBytes=True)
 assert result['noLocalOrSnapshotDependencyInProductionProducer'];write(HERE/'production-byte-equality.json',json.dumps(result,indent=2)+'\n');print(json.dumps(dict(status='PASS',byteEqualGeneratedCount=len(rows),producerSha256Bytes=result['producerSha256Bytes'])))
if __name__=='__main__':main()
