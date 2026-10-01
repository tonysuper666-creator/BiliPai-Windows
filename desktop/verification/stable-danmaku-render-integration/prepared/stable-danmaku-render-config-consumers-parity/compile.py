from pathlib import Path
import hashlib,importlib.util,json,subprocess,sys,zipfile
sys.dont_write_bytecode=True
L=Path(__file__).resolve().parent;MAIN=L.parents[3]/'BiliPai';SNAP=MAIN/'desktop/.local/stable-product-snapshot-22';PANEL=MAIN/'desktop/.local/stable-danmaku-settings-panel-parity/compile-06/original-danmaku-settings.jar'
def safe(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def save(p,v):safe(p).write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
def pins(cp):
 rows=[dict(path=x['path'],expected=x['sha256Bytes'],actual=sha(x['path'])) for x in cp];assert all(x['expected']==x['actual'] for x in rows);return rows
def main():
 assert sha(SNAP/'manifest.json')=='90df091a72910d314030cac755dcc980a8fc711111b8e5d20886fbf22e182cba'
 relocated=MAIN/'desktop/.local/stable-miuix5157-original-runtime/ordered-runtime-cp-22.json'
 assert sha(relocated)=='65e50623a5e52a7a4f0c7421e55dbd20bae752b5c53add8208be9c1f8c9772ae'
 assert sha(relocated.parent/'relocation-ledger.json')=='68b9a804eabd84eedaf40c8b8e2487daad060616a4bac754c1f2f7d3be4fafec'
 cp=json.loads(safe(relocated).read_text());old=json.loads(safe(SNAP/'ordered-runtime-cp.json').read_text());assert len(cp)==len(old)==92
 changes=[]
 for i,(a,b) in enumerate(zip(old,cp)):
  assert {k:v for k,v in a.items() if k!='path'}=={k:v for k,v in b.items() if k!='path'}
  if a['path']!=b['path']:changes.append(i)
 assert len(changes)==1 and cp[changes[0]]['sha256Bytes']=='78e22c70dd152058f2f7570f59906607e253231f1db346494820bd7e9db7b215'
 assert sha(PANEL)=='549caaf0e59e8cfb46ab07be964b0b163b5f4358e61cfc152167961e46dac63e'
 out=L/('compile-'+(sys.argv[1] if len(sys.argv)>1 else '01'));assert not out.exists();out.mkdir();save(out/'runtime-before.json',pins(cp))
 spec=importlib.util.spec_from_file_location('compiler',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
 serial=c.jar('org.jetbrains.kotlin','kotlin-serialization-compiler-plugin-embeddable','2.4.0');serialPin=sha(serial)
 chosen=[L/'generated'/p for p in ['com/android/purebilibili/feature/video/danmaku/DanmakuConfig.kt','com/android/purebilibili/danmaku/engine/DanmakuRenderConfig.kt','com/android/purebilibili/feature/video/danmaku/DanmakuDisplayBand.kt','com/android/purebilibili/feature/video/danmaku/DesktopOriginalDanmakuLayerPolicy.kt','com/android/purebilibili/feature/video/danmaku/DesktopOriginalLiveDanmakuRenderConfig.kt','com/android/purebilibili/feature/video/danmaku/DesktopOriginalLiveDanmakuAdmission.kt','com/android/purebilibili/feature/video/danmaku/DesktopOriginalDanmakuEngineBudgets.kt']]
 chosen+=list(safe(L/'prepared/desktop/src/main/kotlin').rglob('*.kt'))+list(safe(L/'review-only').rglob('*.kt'))
 if safe(L/'proof/ConstructorReceipt.kt').exists():chosen.append(L/'proof/ConstructorReceipt.kt')
 sources=[];rows=[]
 for i,p in enumerate(chosen):
  copied=out/'source-inputs'/str(i)/p.name;safe(copied).parent.mkdir(parents=True,exist_ok=True);safe(copied).write_bytes(safe(p).read_bytes());sources.append(copied);rows.append(dict(source=str(p),input=str(copied),sha256Bytes=sha(copied)))
 target=out/'original-danmaku-render-config-consumers.jar';cpPaths=[str(PANEL)]+[x['path'] for x in cp]
 args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(cpPaths),'-Xplugin='+str(c.PLUGIN),'-Xplugin='+str(serial),'-Xfriend-paths='+str(SNAP/'main-kotlin.jar')+','+str(PANEL),'-module-name','prepared_original_danmaku_render_config_consumers','-d',str(target)]+list(map(str,sources))
 safe(out/'compiler.args').write_text('\n'.join('"'+a.replace('\\','/')+'"' for a in args)+'\n',encoding='utf-8',newline='\n')
 run=subprocess.run([str(c.JAVA),'-Xmx3g','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compiler.args')],capture_output=True,text=True,encoding='utf-8',timeout=210)
 safe(out/'compiler.log').write_text(run.stdout+run.stderr,encoding='utf-8',newline='\n');save(out/'runtime-after.json',pins(cp));assert sha(PANEL)=='549caaf0e59e8cfb46ab07be964b0b163b5f4358e61cfc152167961e46dac63e'
 assert sha(serial)==serialPin
 result=dict(status='PASS' if run.returncode==0 else 'FAIL',exitCode=run.returncode,sourceInputs=rows,actualSnapshot=22,actualRelocatedCP=sha(relocated),prospectiveSettings179Jar=sha(PANEL),sameByteLibraryRelocation=changes,serializationCompilerPlugin=dict(path=str(serial),sha256Bytes=serialPin),preparedOnly=True,wholeCallersNotCompiled='Only actual constructor argument ABI receipt; Root owns full Gradle')
 if not run.returncode:result['jarSha256Bytes']=sha(target)
 save(out/'compile-result.json',result);print(json.dumps(dict(status=result['status'],sources=len(sources),resultSha256Bytes=sha(out/'compile-result.json'))));print((run.stdout+run.stderr)[-9000:]);return run.returncode
if __name__=='__main__':raise SystemExit(main())
