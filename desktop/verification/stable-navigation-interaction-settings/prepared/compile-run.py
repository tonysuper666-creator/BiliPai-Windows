from pathlib import Path
import hashlib, importlib.util, json, subprocess, sys, zipfile, os
sys.dont_write_bytecode = True
sys.stdout.reconfigure(encoding='utf-8')
HERE = Path(__file__).resolve().parent
MAIN = HERE.parents[2]
SNAP = MAIN / 'desktop/.local/stable-product-snapshot-83'
def wide(path):
    value = str(path.absolute())
    return Path(value if value.startswith('\\\\?\\') else '\\\\?\\' + value)
def sha(path): return hashlib.sha256(wide(path).read_bytes()).hexdigest()
def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    value = importlib.util.module_from_spec(spec); spec.loader.exec_module(value); return value
def main():
    attempt = sys.argv[1]
    cp = json.loads((SNAP / 'ordered-runtime-cp.json').read_text(encoding='utf-8-sig'))
    assert len(cp) == 101
    for row in cp: assert sha(Path(row['path'])) == row['sha256Bytes'], row['path']
    c = load('nav_compile', MAIN / 'desktop/.local/source9-appearance/compile-miuix.py')
    run = HERE / 'runs' / attempt
    run.mkdir(parents=True, exist_ok=False)
    sources = sorted(wide(HERE / 'prepared').rglob('*.kt')) + [HERE / 'NavigationSettingsFixture.kt']
    if (HERE / 'UiFixture.kt').exists(): sources.append(HERE / 'UiFixture.kt')
    inputs = run / 'inputs'; inputs.mkdir()
    actual = []
    identities = []
    for i, path in enumerate(sources):
        target = inputs / path.name
        assert not wide(target).exists()
        wide(target).write_bytes(wide(path).read_bytes()); actual.append(wide(target))
        identities.append(dict(source=str(path),sha256Bytes=sha(path), compilerInput=str(target)))
    product = next(row['path'] for row in cp if Path(row['path']).name == 'main-kotlin.jar')
    test = list((MAIN.parent / 'toolchain/gradle-home/caches/modules-2/files-2.1/org.junit.jupiter/junit-jupiter-api').rglob('*.jar'))
    test += list((MAIN.parent / 'toolchain/gradle-home/caches/modules-2/files-2.1/org.opentest4j/opentest4j').rglob('*.jar'))
    test += list((MAIN.parent / 'toolchain/gradle-home/caches/modules-2/files-2.1/org.junit.platform/junit-platform-commons').rglob('*.jar'))
    test += list((MAIN.parent / 'toolchain/gradle-home/caches/modules-2/files-2.1/org.apiguardian/apiguardian-api').rglob('*.jar'))
    paths = [row['path'] for row in cp] + list(map(str, test))
    classes = run / 'classes.jar'
    args = ['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','com_bilipai_desktop_bilipai_windows',
        '-Xfriend-paths=' + product,'-Xplugin=' + str(c.PLUGIN),'-cp',';'.join(paths),'-d',str(classes)] + list(map(str,actual))
    argsfile = run / 'compiler.args'
    argsfile.write_text('\n'.join('"' + str(arg).replace('\\','/') + '"' for arg in args) + '\n')
    compile = subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-Xmx2g','-cp',';'.join(map(str,c.COMPILER)),
        'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@' + str(argsfile)],capture_output=True,timeout=160)
    (run / 'compile.log').write_bytes(compile.stdout + compile.stderr)
    print((compile.stdout + compile.stderr).decode('utf-8',errors='replace')[-5000:])
    compile.check_returncode()
    with zipfile.ZipFile(wide(classes)) as jar: entries = set(jar.namelist())
    with zipfile.ZipFile(wide(Path(product))) as jar: overlap = sorted(entries.intersection(jar.namelist()))
    assert all(p.startswith(('com/bilipai/desktop/settings/DesktopSettingsTreeKt',
        'com/bilipai/desktop/settings/ComposableSingletons$DesktopSettingsTreeKt')) for p in overlap), overlap
    java = [str(c.JAVA),'-Dfile.encoding=UTF-8','-cp',str(classes) + ';' + ';'.join(paths)]
    fixture = subprocess.run(java + ['com.bilipai.desktop.settings.NavigationSettingsFixtureKt'],capture_output=True,timeout=40)
    (run / 'fixture.log').write_bytes(fixture.stdout + fixture.stderr)
    print((fixture.stdout + fixture.stderr).decode('utf-8',errors='replace')); assert fixture.returncode == 0, 'Focused fixture failed; see fixture.log'
    ui = None
    if (HERE / 'UiFixture.kt').exists():
        app = run / 'ui' / 'task-localappdata'; app.mkdir(parents=True)
        env = os.environ.copy(); env['LOCALAPPDATA'] = str(app.absolute())
        ui = subprocess.run(java + ['com.bilipai.desktop.settings.UiFixtureKt',str((run / 'ui').absolute())],capture_output=True,timeout=100,env=env)
        (run / 'ui.log').write_bytes(ui.stdout + ui.stderr)
        print((ui.stdout + ui.stderr).decode('utf-8',errors='replace')); assert ui.returncode == 0, 'UI fixture failed; see ui.log'
    for row in cp: assert sha(Path(row['path'])) == row['sha256Bytes']
    result = dict(passed=True, actualSnapshotManifestSha256Bytes=sha(SNAP/'manifest.json'), actualClasspath=cp,
        sources=identities, declaredProductOverrides=overlap, compileExitCode=compile.returncode,
        focusedExecutableMethods=6, fixtureExitCode=fixture.returncode, uiExitCode=ui.returncode if ui else None,
        scope='immutable actual83 product + explicit prospective Tree overlay and selected original settings/platform sources; no whole Main/Runtime/HWND/account/network/packaging')
    (run / 'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
if __name__ == '__main__': main()
