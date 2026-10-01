from pathlib import Path
import hashlib, json, subprocess, importlib.util, zipfile
HERE = Path(__file__).resolve().parent
MAIN = HERE.parents[2]
LANE = MAIN / 'desktop/.local/stable-favorites-root-wiring'
OUT = MAIN / 'desktop/.local/stable-favorites-main22-integration-proof'
SNAP = MAIN / 'desktop/.local/stable-product-snapshot-22'
assert not OUT.exists()
def wide(p): return Path('\\\\?\\' + str(p.absolute()))
def read(p): return wide(p).read_bytes()
def sha(b): return hashlib.sha256(b).hexdigest()
def pin(p, digest):
    b = read(p)
    assert sha(b) == digest, p
    return b
manifest = pin(LANE / 'frozen-handoff.json', 'f936ef8fac172cf6e71d11b4cefd0bb257d99127656546d898c19e23e05ea38f')
rows = {r['path']: r for r in json.loads(manifest)['artifacts']}
source_path = 'fixture/RootFavoritesWiringFixture.kt'
fixture = pin(LANE / source_path, rows[source_path]['sha256Bytes'])
snapshot = pin(SNAP / 'manifest.json', '90df091a72910d314030cac755dcc980a8fc711111b8e5d20886fbf22e182cba')
cp_raw = pin(SNAP / 'ordered-runtime-cp.json', 'f871605f15d537b71a0981bc556b47979f68d413447dfc1d97cad373c43374fb')
cp = json.loads(cp_raw)
assert len(cp) == 92
for r in cp: pin(Path(r['path']), r['sha256Bytes'])
OUT.mkdir()
source = OUT / 'RootFavoritesWiringFixture.kt'
source.write_bytes(fixture)
spec = importlib.util.spec_from_file_location('compiler', MAIN / 'desktop/.local/source9-appearance/compile-miuix.py')
compiler = importlib.util.module_from_spec(spec)
spec.loader.exec_module(compiler)
args = ['-no-stdlib', '-no-reflect', '-jvm-target', '21', '-Xplugin=' + str(compiler.PLUGIN),
        '-module-name', 'com_bilipai_desktop_bilipai_windows', '-Xfriend-paths=' + str(SNAP / 'main-kotlin.jar'),
        '-cp', ';'.join(r['path'] for r in cp), '-d', str(OUT / 'classes'), str(source)]
argfile = OUT / 'compile.args'
argfile.write_text('\n'.join('"' + a.replace('\\', '/') + '"' for a in args), encoding='utf-8')
r = subprocess.run([str(compiler.JAVA), '-Dfile.encoding=UTF-8', '-Xmx2g', '-cp', ';'.join(map(str, compiler.COMPILER)),
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '@' + str(argfile)], capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=180)
(OUT / 'compile.log').write_text(r.stdout + r.stderr, encoding='utf-8')
r.check_returncode()
compiled = {str(p.relative_to(OUT / 'classes')).replace('\\', '/') for p in (OUT / 'classes').rglob('*.class')}
overlaps = []
for row in cp:
    with zipfile.ZipFile(wide(Path(row['path']))) as archive:
        overlaps.extend(dict(className=name, artifact=row['path']) for name in compiled.intersection(archive.namelist()))
assert not overlaps, overlaps
r = subprocess.run([str(compiler.JAVA), '-Djava.awt.headless=true', '-Dfile.encoding=UTF-8', '-cp',
    str(OUT / 'classes') + ';' + ';'.join(row['path'] for row in cp),
    'com.bilipai.desktop.ui.RootFavoritesWiringFixtureKt', str(OUT / 'task-store')],
    capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=90)
(OUT / 'run.log').write_text(r.stdout + r.stderr, encoding='utf-8')
r.check_returncode()
assert '4 groups / 28 assertions PASS' in r.stdout
code_sources = [line for line in r.stdout.splitlines() if line.startswith('CodeSource ')]
assert len(code_sources) == 6 and all('/stable-product-snapshot-22/main-kotlin.jar' in line for line in code_sources)
result = dict(passed=True, actualProductPhase=22, actualManifestSha256Bytes=sha(snapshot), actualCpSha256Bytes=sha(cp_raw),
    productionClassOverrides=0, classOverlaps=overlaps, fixtureSources=1, groups=4, assertions=28,
    exactPreviouslyFrozenFixtureSha256Bytes=sha(fixture), actualCodeSources=code_sources,
    actualRootMountedPointerAccepted=False, realHttpOrAccountMutation=False, actualHwndOrCodecAccepted=False)
(OUT / 'result.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
print(json.dumps(result))
