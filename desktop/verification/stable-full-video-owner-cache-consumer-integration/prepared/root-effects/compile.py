from pathlib import Path
import hashlib, importlib.util, json, subprocess, sys, zipfile

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
MAIN = HERE.parents[2]
SNAPSHOT = MAIN / 'desktop/.local/stable-product-snapshot-71'

def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()

def wide(path):
    value = str(Path(path).absolute())
    return Path(value if value.startswith('\\\\?\\') else '\\\\?\\' + value)

def main():
    manifest = SNAPSHOT / 'manifest.json'
    assert sha(manifest) == '416f1ec5fdf577f13c607d52248ca3c97d39420d363bac78f500d119053e6ad1'
    cpfile = SNAPSHOT / 'ordered-runtime-cp.json'
    assert sha(cpfile) == '6ec3765f3ced6095ac01d2c2908550e9487bfcd586c1008ff4a582920f84f55f'
    cp = json.loads(cpfile.read_text(encoding='utf-8-sig'))
    assert len(cp) == 101
    def pins():
        values = []
        for row in cp:
            actual = sha(wide(row['path']))
            assert actual == row['sha256Bytes'], (row['path'], actual)
            values.append({'path': row['path'], 'sha256Bytes': actual})
        return values
    before = pins()
    spec = importlib.util.spec_from_file_location('compiler', MAIN / 'desktop/.local/source9-appearance/compile-miuix.py')
    compiler = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(compiler)
    sources = sorted((HERE / 'prepared/manual').rglob('*.kt'))
    assert len(sources) == 2
    out = HERE / 'compile/01'
    out.mkdir(parents=True, exist_ok=False)
    classes = out / 'classes'
    module = 'com_bilipai_desktop_bilipai_windows'
    product = next(Path(row['path']) for row in cp if Path(row['path']).name == 'main-kotlin.jar')
    args = ['-no-stdlib', '-no-reflect', '-jvm-target', '21', '-module-name', module,
            '-Xfriend-paths=' + str(product), '-cp', ';'.join(row['path'] for row in cp), '-d', str(classes)]
    args += [str(path) for path in sources]
    argfile = out / 'compiler.args'
    argfile.write_text('\n'.join('"' + str(arg).replace('\\', '/') + '"' for arg in args) + '\n', encoding='utf-8')
    command = [str(compiler.JAVA), '-Xmx2g', '-cp', ';'.join(map(str, compiler.COMPILER)),
               'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '@' + str(argfile)]
    process = subprocess.run(command, capture_output=True, text=True, encoding='utf-8', timeout=180)
    (out / 'compile.log').write_text(process.stdout + process.stderr, encoding='utf-8')
    after = pins()
    assert before == after
    families = {path.relative_to(classes).as_posix() for path in classes.rglob('*.class')}
    overlap = set()
    for row in cp:
        with zipfile.ZipFile(wide(row['path'])) as archive:
            overlap |= families.intersection(archive.namelist())
    result = {'passed': process.returncode == 0 and not overlap, 'compilerExitCode': process.returncode,
              'scope': 'source-ready two NEW manual classes; no runtime/native/Root/UI acceptance',
              'productOverrides': [], 'sourceInputs': [{'path': str(path), 'sha256LF': hashlib.sha256(path.read_bytes().replace(b'\r\n', b'\n')).hexdigest()} for path in sources],
              'classCount': len(families), 'classEntries': sorted(families), 'actualProductClassOverlap': sorted(overlap),
              'manifestSha256Bytes': sha(manifest), 'orderedCPsha256Bytes': sha(cpfile),
              'runtimeCPCount': len(cp), 'cpPinsBefore': before, 'cpPinsAfter': after}
    (out / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({key: result[key] for key in ['passed', 'compilerExitCode', 'classCount', 'actualProductClassOverlap']}, ensure_ascii=False))
    print((process.stdout + process.stderr)[-4000:])
    assert result['passed']

if __name__ == '__main__':
    main()
