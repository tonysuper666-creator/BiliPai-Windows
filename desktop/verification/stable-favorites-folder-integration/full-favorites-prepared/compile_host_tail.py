from pathlib import Path
import importlib.util,json,subprocess,shutil,hashlib
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2]
source=HERE/'platform/com/bilipai/desktop/ui/DesktopOriginalFavoritesHost.kt'
target=HERE/'prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalFavoritesHost.kt'
shutil.copyfile(source,target)
snap=MAIN/'desktop/.local/stable-product-snapshot-15';cp=json.loads((snap/'ordered-runtime-cp.json').read_text())
spec=importlib.util.spec_from_file_location('compiler',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');cc=importlib.util.module_from_spec(spec);spec.loader.exec_module(cc)
args=['-no-stdlib','-no-reflect','-jvm-target','21','-Xplugin='+str(cc.PLUGIN),'-module-name','com_bilipai_desktop_bilipai_windows',
 '-Xfriend-paths='+str(snap/'main-kotlin.jar'),'-cp',str(HERE/'classes-install-final')+';'+ ';'.join(r['path'] for r in cp),
 '-d',str(HERE/'host-tail-classes'),str(target)]
argfile=HERE/'compile-host-tail.args';argfile.write_text('\n'.join('"'+a.replace('\\','/')+'"' for a in args),encoding='utf-8')
r=subprocess.run([str(cc.JAVA),'-Dfile.encoding=UTF-8','-Xmx1g','-cp',';'.join(map(str,cc.COMPILER)),
 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(argfile)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=60)
(HERE/'compile-host-tail.log').write_text(r.stdout+r.stderr,encoding='utf-8');r.check_returncode()
(HERE/'compile-host-tail-evidence.json').write_text(json.dumps(dict(exitCode=r.returncode,
 compiledFile=str(target),sha256LfUtf8=hashlib.sha256(target.read_bytes()).hexdigest(),
 preparedOnly=True,actualUIRuntime=False,change='Required Root retained VM and original channel/navigation visibility tail'),indent=2),encoding='utf-8')
print('Final required UI host seam standalone compile PASS')
