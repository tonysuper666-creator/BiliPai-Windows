from pathlib import Path
import hashlib, importlib.util, json, os, subprocess, sys

sys.stdout.reconfigure(encoding='utf-8',errors='replace')
HERE=Path(__file__).resolve().parent; ROOT=HERE.parents[2]
def ext(p):
    s=str(p.absolute()); return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def sha(p):return hashlib.sha256(ext(p).read_bytes()).hexdigest()
def write(name,text):(HERE/name).write_text(text,encoding='utf-8',newline='\n')

manifest=ROOT/'desktop/.local/blocked-up-foundation-product-snapshot/manifest.json'
assert sha(manifest)=='74165f272e012c1a13e9171e963bf76127dbd71e7003da9e2576b7af2a25e2e6'
deps=json.loads((ROOT/'desktop/.local/settings-blocked-up-parity/phase2/dependency-identities.json').read_text())
for d in deps:assert sha(Path(d['path']))==d['sha256Bytes'],d['path']
cp=[d['path'] for d in deps]
write('dependency-identities.json',json.dumps(deps,indent=2)+'\n')
spec=importlib.util.spec_from_file_location('backup_compiler',ROOT/'desktop/.local/source9-appearance/compile-miuix.py')
c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)

newtest=HERE/'prepared/desktop/src/test/kotlin/com/bilipai/desktop/backup/DesktopBackupRestoreBarrierTest.kt'
prefix=newtest.read_text().split('class DesktopBackupRestoreBarrierTest {')[0]
write('BaselineReproduction.kt',prefix+(HERE/'BaselineReproduction.tail.kt').read_text())
def compile_run(label,sources,names):
    report=HERE/(label+'-report.json')
    if report.exists(): report.unlink()
    output=HERE/('classes-'+label);output.mkdir(exist_ok=True)
    args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(cp),
          '-Xfriend-paths='+','.join(cp[:3]),'-module-name','backup_restore_'+label,'-d',str(output),*map(str,sources)]
    write(label+'-compiler.args','\n'.join('"'+a.replace('\\','/')+'"' for a in args))
    run=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-Xmx2g','-cp',';'.join(map(str,c.COMPILER)),
        'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(HERE/(label+'-compiler.args'))],cwd=ROOT,
        capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=80)
    write(label+'-compile.log',run.stdout+run.stderr);print((run.stdout+run.stderr)[-9000:]);run.check_returncode()
    env=os.environ.copy();appdata=HERE/('appdata-'+label);appdata.mkdir(exist_ok=True)
    env['LOCALAPPDATA']=str(appdata)
    args=['-Dfile.encoding=UTF-8','-Djava.awt.headless=true','--add-modules','jdk.httpserver',
          '-cp',str(output)+';'+';'.join(cp),'com.bilipai.desktop.backup.RunFixtureKt',str(report),*names]
    write(label+'-runtime.args','\n'.join('"'+a.replace('\\','/')+'"' for a in args))
    run=subprocess.run([str(c.JAVA),'@'+str(HERE/(label+'-runtime.args'))],cwd=ROOT,env=env,
        capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=65)
    write(label+'-runtime.log',run.stdout+run.stderr);print((run.stdout+run.stderr)[-10000:]);run.check_returncode()
    return dict(label=label,passed=True,reportSha256Bytes=sha(report),
       sourceIdentities=[dict(path=str(p.relative_to(ROOT)) if p.is_relative_to(ROOT) else str(p),sha256Bytes=sha(p)) for p in sources],
       classes=str(output.relative_to(HERE)),productionOverrides=[] if label=='baseline' else [r['path'] for r in json.loads((HERE/'owned-base-files.json').read_text())[:2]]+
           ['generated/settings/com/android/purebilibili/feature/settings/webdav/WebDavBackupService.kt'],
       actualAnnotatedMethods=json.loads(report.read_text())['actualAnnotatedMethods'])
results=[]
results.append(compile_run('baseline',[HERE/'BaselineReproduction.kt',HERE/'RunFixture.kt'],
    ['com.bilipai.desktop.backup.BaselineRestoreCancellationTest']))
patched=[HERE/'prepared/desktop/src/main/kotlin/com/bilipai/desktop/backup/DesktopBackupArchive.kt',
 HERE/'prepared/desktop/src/main/kotlin/com/bilipai/desktop/backup/DesktopBackupCoordinator.kt',
 HERE/'generated/com/android/purebilibili/feature/settings/webdav/WebDavBackupService.kt',newtest,HERE/'RunFixture.kt',
 ROOT/'desktop/src/test/kotlin/com/bilipai/desktop/backup/DesktopBackupArchiveTest.kt',
 ROOT/'desktop/src/test/kotlin/com/bilipai/desktop/backup/DesktopWebDavTest.kt']
results.append(compile_run('patched',patched,['com.bilipai.desktop.backup.DesktopBackupRestoreBarrierTest',
 'com.bilipai.desktop.backup.DesktopBackupArchiveTest','com.bilipai.desktop.backup.DesktopWebDavTest']))
for d in deps:assert sha(Path(d['path']))==d['sha256Bytes'],d['path']
write('compile-evidence.json',json.dumps(dict(passed=True,sharedGradleInvoked=False,mainEdited=False,
    productSnapshotManifestSha256Bytes=sha(manifest),runs=results),indent=2)+'\n')
