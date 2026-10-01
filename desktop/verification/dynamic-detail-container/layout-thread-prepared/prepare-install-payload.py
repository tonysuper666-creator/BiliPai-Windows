"""Prepare a source-only install producer. Never writes Main or frozen raw445."""
from pathlib import Path
import hashlib, importlib.util, json, sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent
REPO=next(p for p in HERE.parents if (p/'.git').exists())

def safe(path):
    value=str(Path(path).absolute())
    return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def write(path,text):
    safe(path.parent).mkdir(parents=True,exist_ok=True)
    safe(path).write_text(text,encoding='utf-8',newline='\n')
def sha(path):return hashlib.sha256(safe(path).read_bytes()).hexdigest()
def replace_once(text,old,new):
    assert text.count(old)==1,old
    return text.replace(old,new)

text=safe(HERE/'prepare-container.py').read_text(encoding='utf-8')
text=replace_once(text,'"""Task-only original detail chrome/layout closure. Never runs the frozen raw producer."""',
    '"""Original detail chrome/layout producer; platform interfaces are supplied by Root.\nDirect sources are emitted only in --standalone and copied once by the source registry in production.\n"""')
text=replace_once(text,"HERE = Path(__file__).resolve().parent", "HERE = Path(__file__).resolve().parent\nOUTPUT = HERE / 'generated'\nSTANDALONE = False")
text=replace_once(text,"    original = subprocess.run(['git','show',TAG+':'+path],cwd=REPO,capture_output=True,check=True).stdout.decode().replace('\\r\\n','\\n')\n    assert current == original, path", "    if STANDALONE:\n        original = subprocess.run(['git','show',TAG+':'+path],cwd=REPO,capture_output=True,check=True).stdout.decode().replace('\\r\\n','\\n')\n        assert current == original, path")
text=replace_once(text,"    source = read(path)\n    package = re.search(r'(?m)^package (\\S+)',source).group(1)\n    target = HERE/'generated'/package.replace('.','/')/(name or Path(path).name)",
    "    if mode == 'direct' and not STANDALONE:\n        return\n    source = read(path)\n    package = re.search(r'(?m)^package (\\S+)',source).group(1)\n    target = OUTPUT/package.replace('.','/')/(name or Path(path).name)")
text=text.replace('target.relative_to(HERE)','target.relative_to(OUTPUT)')
text=replace_once(text,'def main():','def _generate_body():')
text=replace_once(text,"    write(HERE/'original-source-inventory.json',json.dumps(inventory,indent=2)+'\\n')",
    "    if STANDALONE:\n        write(OUTPUT/'original-source-inventory.json',json.dumps(inventory,indent=2)+'\\n')")
text=replace_once(text,"if __name__=='__main__': main()", """def generate(repo, output, *, standalone=False):
    global REPO, OUTPUT, STANDALONE, appearance, parser, inventory
    REPO = Path(repo)
    OUTPUT = Path(output)
    STANDALONE = standalone
    appearance = load('detail_declarations', 'desktop/tools/extract-appearance-platform.py')
    parser = load('detail_parser', 'desktop/tools/sync-upstream.py')
    inventory = []
    _generate_body()

if __name__ == '__main__':
    import argparse
    arguments = argparse.ArgumentParser()
    arguments.add_argument('--repo', type=Path, required=True)
    arguments.add_argument('--output', type=Path, required=True)
    arguments.add_argument('--standalone', action='store_true')
    options = arguments.parse_args()
    generate(options.repo, options.output, standalone=options.standalone)
""")
target=HERE/'prepared/desktop/tools/extract-upstream-dynamic-detail-container.py'
write(target,text)
spec=importlib.util.spec_from_file_location('detail_install_producer',target)
producer=importlib.util.module_from_spec(spec);spec.loader.exec_module(producer)
standalone=HERE/'install-generator-proof/standalone'
production=HERE/'install-generator-proof/production'
producer.generate(REPO,standalone,standalone=True)
producer.generate(REPO,production)
expected=[Path(str(p).removeprefix('\\\\?\\')) for p in safe(HERE/'generated').rglob('*.kt')]
for path in expected:
    relative=path.relative_to(HERE/'generated')
    assert sha(path)==sha(standalone/relative),relative
direct={Path(row['output'].replace('\\','/')).relative_to('generated') for row in json.loads(safe(HERE/'original-source-inventory.json').read_text()) if row['mode']=='direct'}
production_files={Path(str(path).removeprefix('\\\\?\\')).relative_to(production) for path in safe(production).rglob('*.kt')}
all_files={path.relative_to(HERE/'generated') for path in expected}
assert production_files==all_files-direct
write(HERE/'install-generator-proof/receipt.json',json.dumps(dict(passed=True,
    sourceOnlyProducer=str(target.relative_to(HERE)),producerSha256Bytes=sha(target),
    generatedStandalone=len(expected),generatedProduction=len(production_files),
    directCopiedOnceByRegistry=len(direct),standaloneOutputBytesEqualPrepared=True,
    MainWritten=False,sharedGradle=False),indent=2)+'\n')
print(safe(HERE/'install-generator-proof/receipt.json').read_text())
