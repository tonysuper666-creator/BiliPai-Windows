"""Source-provenance coverage and configured Main04 input share; no build or product writes."""
from pathlib import Path
from collections import Counter
import hashlib, json, re, subprocess, zipfile
HERE = Path(__file__).resolve().parent
ROOT = next(p for p in HERE.parents if (p / '.git').exists())
SNAP = ROOT/'desktop/.local/image-save-main-integration/main-product-snapshot-04'
def safe(p):
    s = str(Path(p).absolute())
    return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def digest(data): return hashlib.sha256(data).hexdigest()
def sha(p): return digest(safe(p).read_bytes())
def write(p, data):
    safe(p.parent).mkdir(parents=True, exist_ok=True)
    safe(p).write_bytes(data)
def save(p, obj): write(p,(json.dumps(obj,ensure_ascii=False,indent=2)+'\n').encode())
def pct(n,d): return round(n*100/d,4)
manifest = HERE/'evidence-manifest.json'
assert not safe(manifest).exists()
assert sha(SNAP/'manifest.json') == '7bc5ac8875dadf38510120db6619bd6e748e7f790a47afc11374e14114f25865'
m = json.loads(safe(SNAP/'manifest.json').read_bytes())
pins = {row['path']: row for row in m['sourceFiles']}
registry_path = 'desktop/upstream-sources.json'
registry_lf = safe(ROOT/registry_path).read_bytes().replace(b'\r\n',b'\n')
assert digest(registry_lf) == pins[registry_path]['sha256Lf']
registry = json.loads(registry_lf)
assert len(registry['sources']) == len({r['path'] for r in registry['sources']}) == 622
write(HERE/'inputs/upstream-sources-main04.json',registry_lf)
build_path = 'desktop/build.gradle.kts'
build_lf = safe(ROOT/build_path).read_bytes().replace(b'\r\n',b'\n')
assert digest(build_lf) == pins[build_path]['sha256Lf']
write(HERE/'inputs/build-main04.gradle.kts',build_lf)
build = build_lf.decode()
start = build.index('kotlin.sourceSets.named("main")')
block = build[start:]
block = block[:block.index('\n}')+2]
roots = ['desktop/build/'+p for p in re.findall(r'kotlin.srcDir\(layout.buildDirectory.dir\("([^"]+)"\)\)',block)]
variable_roots = {}
for variable in ['generatedUpstream','jsWorkerGenerated','generatedAppearance','nativeDiagnosticShareOutput']:
    value = re.search(r'val '+variable+r' = layout.buildDirectory.dir\("([^"]+)"\)',build).group(1)
    if variable == 'nativeDiagnosticShareOutput': value += '/kotlin'
    variable_roots[variable] = 'desktop/build/'+value
roots += list(variable_roots.values())
assert len(roots) == len(set(roots)) == 54
save(HERE/'inputs/main-source-roots.json',dict(kotlinMainBlock=block,kotlinMainGeneratedRoots=roots,
    kotlinVariableRootResolution=variable_roots,implicitDesktopKotlinRoot='desktop/src/main/kotlin',
    implicitDesktopJavaRoot='desktop/src/main/java',thirdPartySourceProjectsExcluded=True,
    gradleNotInvoked=True,sourceRootSelectionDerivedFromPinnedMain04Build=True))

tag = registry['upstreamTag']
commit = subprocess.check_output(['git','-C',str(ROOT),'rev-parse',tag+'^{commit}']).decode().strip()
assert commit == registry['upstreamCommit'] == 'fcf84853b287662e8a9129ea0d38576c36522a34'
tree = subprocess.check_output(['git','-C',str(ROOT),'ls-tree','-r',tag]).decode().splitlines()
objects = {line.split('\t',1)[1]:line.split()[2] for line in tree if '\t' in line}
all_main = sorted(p for p in objects if '/src/main/' in p and Path(p).suffix in {'.kt','.java'})
product_main = [p for p in all_main if not p.startswith('baselineprofile/')]
core_modules = {'app','settings-core','network-core','design-system','plugin-sdk'}
core_main = [p for p in product_main if p.split('/')[0] in core_modules]
assert len(all_main) == 1543 and len(product_main) == 1534 and len(core_main) == 1479
registered = {row['path']:row for row in registry['sources']}
assert set(registered) <= set(product_main)
original = []
cat = subprocess.Popen(['git','-C',str(ROOT),'cat-file','--batch'],stdin=subprocess.PIPE,stdout=subprocess.PIPE)
try:
    for path in all_main:
        cat.stdin.write((objects[path]+'\n').encode());cat.stdin.flush()
        head=cat.stdout.readline().decode().strip();size=int(head.split()[-1])
        raw=cat.stdout.read(size);assert cat.stdout.read(1)==b'\n'
        lf=raw.replace(b'\r\n',b'\n');lines=lf.decode('utf-8').splitlines()
        entry=registered.get(path)
        if entry: assert digest(lf)==entry['sha256'],path
        original.append(dict(path=path,blob=objects[path],sha256Bytes=digest(raw),sha256Lf=digest(lf),
            extension=Path(path).suffix,module=path.split('/')[0],physicalLines=len(lines),
            nonBlankLines=sum(bool(line.strip()) for line in lines),
            productMain=path in product_main,coreMain=path in core_main,
            registeredMode=None if not entry else entry['mode']))
finally:
    cat.stdin.close();assert cat.wait()==0
save(HERE/'original-main-source-inventory.json',dict(tag=tag,commit=commit,rows=original))

selected = [row for row in m['generatedProductFiles'] if any(row['path'].startswith(root+'/') for root in roots)]
excluded = [row for row in m['generatedProductFiles'] if row not in selected]
desktop = [row for row in m['sourceFiles'] if row['path'].startswith('desktop/src/main/') and Path(row['path']).suffix in {'.kt','.java'}]
assert len(selected)==704 and len(desktop)==304
counted=[];partitions={};language=Counter()
with zipfile.ZipFile(safe(HERE/'reviewed-main04-source.zip'),'w',zipfile.ZIP_DEFLATED) as archive:
    for row in sorted(selected+desktop,key=lambda r:r['path']):
        path=row['path'];raw=safe(ROOT/path).read_bytes();lf=raw.replace(b'\r\n',b'\n')
        if 'sha256Lf' in row: assert digest(lf)==row['sha256Lf'],path
        else: assert digest(raw)==row['sha256Bytes'],path
        part='direct-original-copy' if path.startswith('desktop/build/generated/upstream/') else 'generated-extraction-adaptation-support' if row in selected else 'desktop-main-directory-platform-mixed-thirdparty'
        lines=lf.decode('utf-8').splitlines();nonblank=sum(bool(s.strip()) for s in lines)
        bucket=partitions.setdefault(part,dict(files=0,physicalLines=0,nonBlankLines=0))
        bucket['files']+=1;bucket['physicalLines']+=len(lines);bucket['nonBlankLines']+=nonblank
        entry=dict(path=path,partition=part,extension=Path(path).suffix,sha256Bytes=digest(raw),sha256Lf=digest(lf),
            physicalLines=len(lines),nonBlankLines=nonblank)
        counted.append(entry);language[Path(path).suffix]+=1
        if part=='direct-original-copy':
            original_path=path.removeprefix('desktop/build/generated/upstream/')
            assert registered[original_path]['mode']=='direct' and digest(lf)==registered[original_path]['sha256']
        info=zipfile.ZipInfo(path,(1980,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED
        archive.writestr(info,lf)
save(HERE/'counted-main-source-inventory.json',dict(rows=counted,excludedSnapshotGeneratedFiles=excluded))
mode_counts=dict(Counter(row['mode'] for row in registry['sources']))
reference_modes={'reference-only','platform-adapter-reference'}
active_registry=[row for row in registry['sources'] if row['mode'] not in reference_modes]
generated_lines=sum(r['physicalLines'] for r in counted if r['partition']!='desktop-main-directory-platform-mixed-thirdparty')
total_lines=sum(r['physicalLines'] for r in counted)
generated_nonblank=sum(r['nonBlankLines'] for r in counted if r['partition']!='desktop-main-directory-platform-mixed-thirdparty')
total_nonblank=sum(r['nonBlankLines'] for r in counted)
kotlin_lines=sum(r['physicalLines'] for r in counted if r['extension']=='.kt')
denominators={}
for name,paths in [('all-src-main-including-baseline-profile-generator',all_main),('product-src-main-excluding-baseline-profile-generator',product_main),('core-app-settings-network-design-plugin-sdk-src-main',core_main)]:
    registered_here=[r for r in registry['sources'] if r['path'] in paths]
    active_here=[r for r in active_registry if r['path'] in paths]
    direct_here=[r for r in registered_here if r['mode']=='direct']
    denominators[name]=dict(files=len(paths),kotlinJavaByExtension=dict(Counter(Path(p).suffix for p in paths)),
        modules=dict(Counter(p.split('/')[0] for p in paths)),registeredFileIdentities=len(registered_here),
        registryCoveragePercent=pct(len(registered_here),len(paths)),nonPureReferenceFileIdentities=len(active_here),
        nonPureReferenceCoveragePercent=pct(len(active_here),len(paths)),directVerbatimFiles=len(direct_here),
        directVerbatimCoveragePercent=pct(len(direct_here),len(paths)))
result=dict(schema='main04-source-reuse-measurement-v1',snapshotManifestSha256Bytes=sha(SNAP/'manifest.json'),
    upstreamTag=tag,upstreamCommit=commit,allRegisteredOriginalSourceLfPinsVerifiedAgainstGit=True,
    allCountedCurrentFilesVerifiedAgainstMain04Snapshot=True,registrySourceCount=622,
    registrySourceLanguages=dict(Counter(Path(r['path']).suffix for r in registry['sources'])),
    registryModes=mode_counts,registryResourceCount=len(registry['resources']),
    registryResourceExtensions=dict(Counter(Path(r['path']).suffix for r in registry['resources'])),
    denominators=denominators,configuredMainInputs=dict(files=len(counted),languages=dict(language),partitions=partitions,
        main04SnapshotGeneratedFiles=len(m['generatedProductFiles']),main03PreviousSnapshotGeneratedFiles=757,
        actualConfiguredGeneratedMainFiles=len(selected),excludedSnapshotGeneratedFiles=len(excluded),
        excludedGroups=dict(Counter('/'.join(r['path'].split('/')[:4]) for r in excluded)),
        totalLfPhysicalLines=total_lines,totalNonBlankLines=total_nonblank,
        generatedDirectoryLfPhysicalLines=generated_lines,generatedDirectoryNonBlankLines=generated_nonblank,
        generatedDirectoryPhysicalLineSharePercent=pct(generated_lines,total_lines),
        generatedDirectoryNonBlankLineSharePercent=pct(generated_nonblank,total_nonblank),
        generatedDirectoryFileSharePercent=pct(len(selected),len(counted)),
        kotlinOnlyGeneratedDirectoryPhysicalLineSharePercent=pct(generated_lines,kotlin_lines)),
    interpretation=[
        'Registry file identities are provenance coverage. They do not assert full-file copy, full source-line reuse, full feature implementation or end-to-end parity.',
        '389 direct upstream files were byte-equivalent after LF normalization both to their registry pins and to pinned alpha.9 Git blobs.',
        'Remaining modes include extracted/selected bodies, rewritten platform seams and pure references. Twenty pure-reference entries are excluded from the nonPureReference count.',
        'Physical source-line shares count LF lines including comments/blanks. Generated-directory code includes platform adapters/support; it is not an exact original-line reuse percentage.',
        'desktop/src/main is a location category, not entirely handwritten: it includes embedded extracted original Operations members, generated protobuf Java and AndroidX palette adaptations.',
        'The 760 Main04 snapshot generated files (prior757 plus3) are a broad on-disk inventory. Actual configured main source roots select704; 56 preview/reference/fragment files are excluded.',
        'Denominators include only Kotlin/Java under pinned upstream src/main. Tests, scripts, XML/assets, C/C++ and external third-party source/dependency projects are separate. Baselineprofile src/main contains benchmarks/generators and is excluded from the primary product denominator.',
        'No new Gradle invocation or class compilation occurred. Configured source inputs were derived from the exact Main04 build file and source pins; Root Main04 previously compiled them successfully.'
    ],boundaries=dict(MainChanged=False,GradleInvoked=False,HTTP=False,HWND=False,
        exactOriginalLineReusePercentEstablished=False,fullFunctionalParityPercentEstablished=False))
save(HERE/'measurement.json',result)
artifacts=[]
for p in sorted(safe(HERE).rglob('*')):
    if p.is_file():artifacts.append(dict(path=str(p.relative_to(safe(HERE))).replace('\\','/'),sha256Bytes=sha(p),byteCount=p.stat().st_size))
save(manifest,dict(schema='frozen-source-reuse-measurement-main04-v1',frozen=True,readOnlyMeasurement=True,
    measurement='measurement.json',measurementSha256Bytes=sha(HERE/'measurement.json'),
    snapshotManifestSha256Bytes=sha(SNAP/'manifest.json'),artifactCount=len(artifacts),artifacts=artifacts))
for row in artifacts:assert sha(HERE/row['path'])==row['sha256Bytes']
print(json.dumps(dict(manifest=str(manifest),manifestSha256Bytes=sha(manifest),
    measurementSha256Bytes=sha(HERE/'measurement.json'),primaryDenominator=denominators['product-src-main-excluding-baseline-profile-generator'],
    partitions=partitions,generatedPhysicalLineSharePercent=pct(generated_lines,total_lines))))
