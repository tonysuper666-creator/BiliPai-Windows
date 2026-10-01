from pathlib import Path
import hashlib,json,importlib.util,subprocess,sys
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];CANDIDATE=MAIN.parent/'BiliPai-v023'
PREFIX=chr(92)*2+'?'+chr(92)
def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith(PREFIX) else PREFIX+s)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
snapshot=MAIN/'desktop/.local/stable-product-snapshot-33'
assert sha(snapshot/'manifest.json')=='a94cf303442cc285a81650237f0cc1a3910c47e4f3b1a43e42a23e36e0c63883'
assert sha(snapshot/'ordered-runtime-cp.json')=='e76eb70649106c7acc0d446c539e6b971fcf578daaca68221a5897fda2fdb09d'
cp=json.loads(safe(snapshot/'ordered-runtime-cp.json').read_text())
for r in cp:assert sha(r['path'])==r['sha256Bytes'],r['path']
vm=MAIN/'desktop/.local/stable-home-viewmodel-parity/classes-vm-06'
ports=MAIN/'desktop/.local/stable-home-request-ports-parity/classes-ports-05'
dynamic=safe(CANDIDATE/'desktop/build/generated/dynamic-settings/com/android/purebilibili/data/repository/DesktopOriginalDynamicTimelineRepository.kt').read_text(encoding='utf-8')
raw=safe(CANDIDATE/'app/src/main/java/com/android/purebilibili/data/repository/DynamicRepository.kt').read_text(encoding='utf-8')
marker='    fun currentUpdateBaseline(';ending='    ): String = feedPagination.updateBaseline(scope, type)'
start=raw.index(marker);end=raw.index(ending,start)+len(ending)
# Exact original declaration; the sole installed dynamic generator owns its production copy.
baseline=raw[start:end]
start=raw.index('    fun hasMoreData(');end=raw.index('\n    }',start)+len('\n    }')
has_more=raw[start:end]
assert 'fun currentUpdateBaseline' not in dynamic and 'fun hasMoreData' not in dynamic
marker='    fun syncPaginationAfterRefresh('
dynamic=dynamic.replace(marker,baseline+'\n\n'+has_more+'\n\n'+marker,1)
out=HERE/'prepared/follow-getters-reference/DesktopOriginalDynamicTimelineRepository.kt'
safe(out.parent).mkdir(parents=True,exist_ok=True);safe(out).write_text(dynamic,encoding='utf-8')
files=list((HERE/'prepared/manual').rglob('*.kt'))+[out]+list((HERE/'prepared/blocked-delta').rglob('*.kt'))+list((HERE/'prepared/generated').rglob('*.kt'))
number=1+len(list(HERE.glob('compile-owner-??.log')))
dest=HERE/f'classes-owner-{number:02}'
args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','com_bilipai_desktop_bilipai_windows',
 '-Xfriend-paths='+','.join(map(str,[snapshot/'main-kotlin.jar',vm,ports])),
 '-cp',';'.join(map(str,[ports,vm]))+';'+';'.join(r['path'] for r in cp),'-d',str(dest)]+list(map(str,files))
spec=importlib.util.spec_from_file_location('cc',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');cc=importlib.util.module_from_spec(spec);spec.loader.exec_module(cc)
identities=[{'path':str(p),'sha256Bytes':sha(p)} for p in files]
safe(HERE/f'compile-owner-{number:02}-sources.json').write_text(json.dumps(identities,indent=2)+'\n',encoding='utf-8')
argfile=HERE/f'compile-owner-{number:02}.args';safe(argfile).write_text('\n'.join('"'+a.replace('\\','/')+'"' for a in args),encoding='utf-8')
r=subprocess.run([str(cc.JAVA),'-Dfile.encoding=UTF-8','-Xmx2g','-cp',';'.join(map(str,cc.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(argfile)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=180)
safe(HERE/f'compile-owner-{number:02}.log').write_text(r.stdout+r.stderr,encoding='utf-8')
for row in identities:assert sha(row['path'])==row['sha256Bytes']
print((r.stdout+r.stderr).encode('ascii','backslashreplace').decode());print('exit',r.returncode,'sources',len(files));sys.exit(r.returncode)
