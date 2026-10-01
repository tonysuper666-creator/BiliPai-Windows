from pathlib import Path
import hashlib,importlib.util,json,subprocess,sys,zipfile
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent
MAIN=next(p for p in HERE.parents if (p/'.git').exists())
FIRST=MAIN/'desktop/.local/stable-dynamic-reply-image-composer-parity'
SNAP=MAIN/'desktop/.local/image-save-main-integration/main-product-snapshot-04'
def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(p,s):safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(s,encoding='utf-8',newline='\n')
def save(p,s):write(p,json.dumps(s,ensure_ascii=False,indent=2)+'\n')
def main():
 out=HERE/('compile-'+(sys.argv[1] if len(sys.argv)>1 else '01'));assert not safe(out).exists();safe(out).mkdir()
 cp=json.loads(safe(SNAP/'ordered-runtime-cp.json').read_text());assert len(cp)==92
 parent=FIRST/'compile-01/prepared-comment-images.jar';assert sha(parent)=='0aab1fb8991e21af7968d2e6fdc7e061343f23fa3211b54c28926e761cc61e61'
 def pins():
  r=[dict(path=x['path'],expected=x['sha256Bytes'],actual=sha(x['path'])) for x in cp]
  assert all(x['expected']==x['actual'] for x in r);return r
 save(out/'runtime-pins-before.json',pins())
 spec=importlib.util.spec_from_file_location('existing_compiler',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
 inputs=[]
 for name in ['DesktopOriginalDynamicReplySession.kt','DesktopOriginalDynamicInlineReplyUi.kt','DesktopOriginalDynamicCommentPanel.kt','DesktopOriginalDynamicDetailLayout.kt']:
  found=list(safe(HERE/'generated').rglob(name));assert len(found)==1;inputs+=found
 sources=[];rows=[]
 for p in inputs:
  copy=out/'source-inputs'/p.name;safe(copy.parent).mkdir(parents=True,exist_ok=True);safe(copy).write_bytes(safe(p).read_bytes());sources.append(copy);rows.append(dict(source=str(p),compileInput=str(copy),sha256Bytes=sha(copy)))
 target=out/'cancel-completion.jar';runtime=[str(parent)]+[r['path'] for r in cp]
 args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(runtime),'-Xfriend-paths='+str(parent)+','+str(SNAP/'main-kotlin.jar'),'-Xplugin='+str(c.PLUGIN),'-module-name','prepared_cancel_completion','-d',str(target)]+list(map(str,sources))
 write(out/'compiler.args','\n'.join('"'+str(a).replace('\\','/')+'"' for a in args)+'\n')
 p=subprocess.run([str(c.JAVA),'-Xmx3g','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compiler.args')],capture_output=True,text=True,encoding='utf-8',timeout=180)
 write(out/'compiler.log',p.stdout+p.stderr);save(out/'runtime-pins-after.json',pins())
 r=dict(status='PASS' if p.returncode==0 else 'FAIL',sourceInputs=rows,exitCode=p.returncode,previousPrepared109JarSha256Bytes=sha(parent),actualMain04CpEntries=92,preparedOnly=True,noMainEdits=True,noGradle=True,noHTTP=True,noHWND=True)
 if p.returncode==0:
  with zipfile.ZipFile(safe(target)) as z:r.update(artifactSha256Bytes=sha(target),declaredOwnClassEntries=sorted(n for n in z.namelist() if n.endswith('.class')))
 save(out/'compile-result.json',r);print(json.dumps(dict(status=r['status'],resultSha256Bytes=sha(out/'compile-result.json'))));print((p.stdout+p.stderr)[-10000:]);return p.returncode
if __name__=='__main__':raise SystemExit(main())
