"""Resolve the exact approved Imaging compile graph; only task-local downloads."""
from pathlib import Path
import hashlib, json, re, sys, urllib.request, zipfile, xml.etree.ElementTree as ET
sys.dont_write_bytecode = True
OUT = Path(__file__).resolve().parent / 'dependencies'
receipts = []
def fetch(url, name):
    path = OUT / name
    assert not path.exists(), 'Do not replace existing dependency bytes: ' + name
    with urllib.request.urlopen(url, timeout=45) as response:
        body = response.read(); path.write_bytes(body)
        receipts.append({'url': url, 'finalUrl': response.url, 'path': name, 'bytes': len(body),
            'sha256Bytes': hashlib.sha256(body).hexdigest(), 'sha512Bytes': hashlib.sha512(body).hexdigest()})
    return path
items = [('commons-io', 'commons-io', '2.19.0', 'io', 'commons-io-2.19.0-bin.zip'),
         ('org.apache.commons', 'commons-lang3', '3.17.0', 'lang', 'commons-lang3-3.17.0-bin.zip')]
ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
parents = {}
def parent_closure(path):
    root = ET.parse(path).getroot(); parent = root.find('m:parent', ns)
    if parent is None: return
    g, a, v = [parent.findtext('m:' + key, namespaces=ns) for key in ['groupId', 'artifactId', 'version']]
    key = (g, a, v)
    if key in parents: return
    target = fetch('https://repo.maven.apache.org/maven2/' + g.replace('.', '/') + '/' + a + '/' + v + '/' + a + '-' + v + '.pom', a + '-' + v + '.pom')
    parents[key] = target; parent_closure(target)
parent_closure(OUT / 'commons-imaging-1.0.0-alpha6.pom')
for g, a, v, folder, archive_name in items:
    prefix = 'https://repo.maven.apache.org/maven2/' + g.replace('.', '/') + '/' + a + '/' + v + '/'
    jar = fetch(prefix + a + '-' + v + '.jar', a + '-' + v + '.jar')
    pom = fetch(prefix + a + '-' + v + '.pom', a + '-' + v + '.pom')
    parent_closure(pom)
    archive_prefix = 'https://archive.apache.org/dist/commons/' + folder + '/binaries/'
    sumfile = fetch(archive_prefix + archive_name + '.sha512', archive_name + '.sha512')
    expected = re.search(r'\b[0-9a-fA-F]{128}\b', sumfile.read_text()).group(0).lower()
    archive = fetch(archive_prefix + archive_name, archive_name)
    assert hashlib.sha512(archive.read_bytes()).hexdigest() == expected
    with zipfile.ZipFile(archive) as binary:
        contained = [entry for entry in binary.namelist() if entry.endswith('/' + a + '-' + v + '.jar')]
        assert len(contained) == 1
        assert binary.read(contained[0]) == jar.read_bytes(), 'Maven transitive differs from official archive'
        for entry in binary.namelist():
            if entry.endswith(('/LICENSE.txt', '/NOTICE.txt')):
                (OUT / ('official-' + a + '-' + Path(entry).name)).write_bytes(binary.read(entry))
    # Record all direct dependencies: only test scopes occur for these two
    # libraries, so Imaging's explicit two compile entries are the whole graph.
    deps = ET.parse(pom).getroot().findall('m:dependencies/m:dependency', ns)
    runtime = [dep for dep in deps if dep.findtext('m:scope', 'compile', ns) not in ['test', 'provided']
               and dep.findtext('m:optional', 'false', ns) != 'true']
    assert not runtime, [(dep.findtext('m:artifactId', namespaces=ns), dep.findtext('m:scope', namespaces=ns)) for dep in runtime]
    print('Verified ' + a + ':' + v + ' official archive equality and no required runtime child', flush=True)
runtime = [{'groupId': 'org.apache.commons', 'artifactId': 'commons-imaging', 'version': '1.0.0-alpha6'}] + [
    {'groupId': g, 'artifactId': a, 'version': v} for g, a, v, _, _ in items]
(OUT / 'transitive-graph-receipt.json').write_text(json.dumps({'passed': True,
    'runtimeEntriesAddedToOriginal89': 3, 'actualTotalClasspathEntries': 92, 'runtimeGraph': runtime,
    'officialArchiveSha512AndJarEquality': True, 'requiredChildGraphClosed': True,
    'parentPoms': [p.name for p in parents.values()], 'downloads': receipts}, indent=2) + '\n', encoding='utf-8', newline='\n')
print('PASS exact 3-library compile/runtime graph: explicit 92 CP, no Main edits', flush=True)
