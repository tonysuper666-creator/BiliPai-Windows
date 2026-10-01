"""Independent prepared 9-image closure; actual immutable Main04 92CP.
Declared adapters/stream dependency overrides only. No shared Gradle or runtime.
"""
from pathlib import Path
import hashlib, importlib.util, json, subprocess, sys, zipfile
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent
MAIN=next(p for p in HERE.parents if (p/'.git').exists())
SNAP=MAIN/'desktop/.local/image-save-main-integration/main-product-snapshot-04'
def safe(p):
    s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(p,s):
    safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(s,encoding='utf-8',newline='\n')
def save(p,s):write(p,json.dumps(s,ensure_ascii=False,indent=2)+'\n')
def entries(p):
    with zipfile.ZipFile(safe(p)) as z:return {n for n in z.namelist() if n.endswith('.class')}
def main():
    attempt=sys.argv[1] if len(sys.argv)>1 else '01'
    out=HERE/('compile-'+attempt);assert not safe(out).exists();safe(out).mkdir()
    assert sha(SNAP/'manifest.json')=='7bc5ac8875dadf38510120db6619bd6e748e7f790a47afc11374e14114f25865'
    assert sha(SNAP/'ordered-runtime-cp.json')=='7a3141310fa355d8618c5bc2be90f36a2c13c8ed36f2d981bf4a14c2232165e2'
    cp=json.loads(safe(SNAP/'ordered-runtime-cp.json').read_text())
    assert len(cp)==92
    def pins():
        p=[dict(path=r['path'],expected=r['sha256Bytes'],actual=sha(r['path'])) for r in cp]
        assert all(r['expected']==r['actual'] for r in p);return p
    save(out/'runtime-pins-before.json',pins())
    safe(out/'input-main04-manifest.json').write_bytes(safe(SNAP/'manifest.json').read_bytes())
    safe(out/'input-ordered-runtime-cp.json').write_bytes(safe(SNAP/'ordered-runtime-cp.json').read_bytes())
    spec=importlib.util.spec_from_file_location('existing_narrow_compiler',MAIN/'desktop/.local/source9-appearance/compile-miuix.py')
    c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
    inputs=list(safe(HERE/'prepared/desktop/src/main/kotlin').rglob('*.kt'))
    inputs+=list(safe(HERE/'proof-only').rglob('*.kt'))
    ui=HERE/'base-inputs/desktop/src/main/kotlin/com/bilipai/desktop/ui'
    inputs+=[safe(ui/n) for n in ('DesktopDynamicEditorSelectedImages.kt','DesktopDynamicEditorWindowsPickers.kt','DesktopDynamicGallerySelection.kt')]
    for n in ('DesktopOriginalDynamicReplySession.kt','DesktopOriginalDynamicInlineReplyUi.kt','DesktopOriginalDynamicCommentPanel.kt'):
        matches=list(safe(HERE/'generated').rglob(n));assert len(matches)==1;inputs+=matches
    rows=[];sources=[]
    for p in inputs:
        copy=out/'source-inputs'/p.name
        safe(copy.parent).mkdir(parents=True,exist_ok=True);safe(copy).write_bytes(safe(p).read_bytes())
        sources.append(copy);rows.append(dict(preparedSource=str(p),compileInput=str(copy),sha256Bytes=sha(copy)))
    target=out/'prepared-comment-images.jar'
    args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(r['path'] for r in cp),
        '-Xfriend-paths='+str(SNAP/'main-kotlin.jar'),'-Xplugin='+str(c.PLUGIN),'-module-name','prepared_comment_images',
        '-d',str(target)]+list(map(str,sources))
    write(out/'compiler.args','\n'.join('"'+str(a).replace('\\','/')+'"' for a in args)+'\n')
    save(out/'compiler-inputs.json',[dict(path=str(p),sha256Bytes=sha(p)) for p in [c.JAVA,*c.COMPILER,c.PLUGIN]])
    p=subprocess.run([str(c.JAVA),'-Xmx3g','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compiler.args')],capture_output=True,text=True,encoding='utf-8',timeout=180)
    write(out/'compiler.log',p.stdout+p.stderr);save(out/'runtime-pins-after.json',pins())
    result=dict(status='PASS' if p.returncode==0 else 'FAIL',exitCode=p.returncode,sourceInputs=rows,
        dependencySnapshot='verified alpha.9 actual Main04 92CP, not stable installed acceptance',
        streamOperationsDependencySha256LF='833678b38f32928f0cbdc837e7bf380b7208f7ef62575fe654af402e09d0fda2',
        originalCommit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',preparedOnly=True,
        noMainEdits=True,noGradle=True,noRuntime=True,noHTTP=True,noHWND=True)
    if p.returncode==0:
        own=entries(target);baseline=set().union(*(entries(r['path']) for r in cp))
        result.update(artifactSha256Bytes=sha(target),ownClassCount=len(own),declaredProductClassOverrides=sorted(own&baseline),newPreparedClasses=sorted(own-baseline))
    save(out/'compile-result.json',result)
    print(json.dumps(dict(status=result['status'],sources=len(sources),resultSha256Bytes=sha(out/'compile-result.json'))))
    print((p.stdout+p.stderr)[-15000:]);return p.returncode
if __name__=='__main__':raise SystemExit(main())
