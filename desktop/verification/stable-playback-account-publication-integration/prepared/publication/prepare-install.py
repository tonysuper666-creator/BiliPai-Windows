from pathlib import Path
import hashlib,importlib.util,json,subprocess,sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent; MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
def safe(p):return Path('\\\\?\\'+str(Path(p).absolute()))
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
def write(p,s):safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(s,encoding='utf-8',newline='\n')
tool='desktop/tools/extract-cast-platform.py';before=read(REPO/tool)
anchor='    write(path, body)\n\n    path = BASE + "feature/plugin/dlna/DlnaCastPlugin.kt"'
rows=json.loads(read(HERE/'cast-generated-required.json'))['hunks']
addition=''.join('    body = substitute(body, '+repr(h['before'])+', '+repr(h['after'])+')\n' for h in rows)
assert before.count(anchor)==1
after=before.replace(anchor,addition+anchor)
write(HERE/'prepared'/tool,after)
hunks=[dict(path=tool,baseLF=sha(before),candidateLF=sha(after),hunks=[dict(before=anchor,after=addition+anchor,occurrences=1)])]
raw='app/src/main/java/com/android/purebilibili/feature/download/ResumableAssetDownloader.kt'
rawsha=sha(read(REPO/raw))
download_tool='''#!/usr/bin/env python3
"""Same original downloader body; Call.Factory final-publication seam only."""
from pathlib import Path
import argparse,hashlib,json
SOURCE = %r
SOURCE_SHA = %r
def generate(repo: Path, output: Path) -> None:
    source=(repo/SOURCE).read_text(encoding="utf-8").replace("\\r\\n","\\n")
    if hashlib.sha256(source.encode()).hexdigest()!=SOURCE_SHA:
        raise ValueError("Pinned original downloader identity changed")
    old="private val client: OkHttpClient"
    if source.count(old)!=1:raise ValueError("Original downloader transport boundary changed")
    body=source.replace(old,"private val client: okhttp3.Call.Factory")
    target=output/"com/android/purebilibili/feature/download/ResumableAssetDownloader.kt"
    target.parent.mkdir(parents=True,exist_ok=True)
    target.write_text(body,encoding="utf-8",newline="\\n")
def inventory(repo: Path):
    source=(repo/SOURCE).read_text(encoding="utf-8").replace("\\r\\n","\\n")
    return [dict(path=SOURCE,sha256=hashlib.sha256(source.encode()).hexdigest(),mode="platform-rewrite",features=["offline-download"])]
if __name__=="__main__":
    parser=argparse.ArgumentParser();parser.add_argument("--repo",type=Path,required=True);parser.add_argument("--output",type=Path);parser.add_argument("--inventory",action="store_true");args=parser.parse_args()
    if args.output:generate(args.repo,args.output)
    if args.inventory:print(json.dumps(inventory(args.repo),indent=2))
'''%(raw,rawsha)
write(HERE/'prepared/desktop/tools/extract-upstream-download-transport.py',download_tool)
write(HERE/'producer-hunks.json',json.dumps(hunks,ensure_ascii=False,indent=2)+'\n')
output=HERE/'producer-audit';output.mkdir(exist_ok=True)
checks=[]
for name,out in [('extract-cast-platform.py','cast'),('extract-upstream-download-transport.py','download')]:
    spec=importlib.util.spec_from_file_location(name,HERE/'prepared/desktop/tools'/name);module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    module.generate(REPO,output/out)
    target=output/out/('com/android/purebilibili/feature/cast/LocalProxyServer.kt' if out=='cast' else 'com/android/purebilibili/feature/download/ResumableAssetDownloader.kt')
    prepared=HERE/'prepared/generated'/target.relative_to(output/out)
    assert read(target)==read(prepared),(name,sha(read(target)),sha(read(prepared)))
    checks.append(dict(producer=name,path=target.relative_to(HERE).as_posix(),preparedLF=sha(read(prepared)),generatedLF=sha(read(target)),equal=True))
# The remaining cast outputs stay byte-identical to the original sole producer.
spec=importlib.util.spec_from_file_location('old_cast',REPO/tool);old=importlib.util.module_from_spec(spec);spec.loader.exec_module(old)
old.generate(REPO,output/'cast-baseline')
for p in (output/'cast').rglob('*.kt'):
    if p.name=='LocalProxyServer.kt':continue
    assert read(p)==read(output/'cast-baseline'/p.relative_to(output/'cast'))
    checks.append(dict(path=p.relative_to(output/'cast').as_posix(),unchangedExistingCastOutput=True,LF=sha(read(p))))
write(HERE/'producer-audit/result.json',json.dumps(dict(status='PASS',checks=checks,newClient=False,registryAction='merge same downloader identity as platform-rewrite and remove sole DIRECT emission; retain all existing features',sharedSourceEdited=False),ensure_ascii=False,indent=2)+'\n')
print(json.dumps(dict(status='PASS',checks=len(checks),newProducer='extract-upstream-download-transport.py',existingProducerHunks=1)))
