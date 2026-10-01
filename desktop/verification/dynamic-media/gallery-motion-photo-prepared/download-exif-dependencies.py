"""Fetch only Root-approved Commons Imaging alpha6 into this task-owned lane."""
from pathlib import Path
import hashlib, json, re, sys, urllib.request, zipfile
sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
OUT = HERE / 'dependencies'; OUT.mkdir(exist_ok=True)
receipts = []
def fetch(url, name):
    target = OUT / name
    assert not target.exists(), 'Do not overwrite dependency bytes: ' + name
    with urllib.request.urlopen(url, timeout=45) as response:
        body = response.read(); target.write_bytes(body)
        receipts.append({'url': url, 'finalUrl': response.url, 'path': name, 'bytes': len(body),
            'sha256Bytes': hashlib.sha256(body).hexdigest(), 'sha512Bytes': hashlib.sha512(body).hexdigest()})
    return target
base = 'https://downloads.apache.org/commons/imaging/'
for kind, folder, expected in [
    ('bin', 'binaries', '283fa5241edb5b6e68224368779b67da0a11dcd79089fe81bc6a3dbe59db93664aaca2a0ca3ef7a1f3936119a6a9c08421b4f4cd563f77e5d2f162723c7b5ec7'),
    ('src', 'source', '2afcfe1a2732f7731a9c669583dc593daf1b6f38426757d9df380123085c083ada68c0d2954854f288d7729c619a8282213920cc8ecd04f74975d4e9dd7f2022')]:
    name = 'commons-imaging-1.0.0-alpha6-' + kind + '.zip'
    sumfile = fetch(base + folder + '/' + name + '.sha512', name + '.sha512')
    advertised = re.search(r'\b[0-9a-fA-F]{128}\b', sumfile.read_text()).group(0).lower()
    assert advertised == expected
    archive = fetch(base + folder + '/' + name, name)
    assert hashlib.sha512(archive.read_bytes()).hexdigest() == advertised
    with zipfile.ZipFile(archive) as jar:
        wanted = [entry for entry in jar.namelist() if entry.endswith(('/LICENSE.txt', '/NOTICE.txt', '/pom.xml'))]
        if kind == 'bin': wanted += [entry for entry in jar.namelist() if entry.endswith('/commons-imaging-1.0.0-alpha6.jar')]
        if kind == 'src': wanted += [entry for entry in jar.namelist() if entry.endswith('/WriteExifMetadataExample.java')]
        for entry in wanted:
            target = OUT / ('official-' + kind + '-' + Path(entry).name)
            if target.exists(): continue
            target.write_bytes(jar.read(entry))
    print('Verified official ' + kind + ' archive SHA-512', flush=True)
prefix = 'https://repo.maven.apache.org/maven2/org/apache/commons/commons-imaging/1.0.0-alpha6/'
maven_jar = fetch(prefix + 'commons-imaging-1.0.0-alpha6.jar', 'commons-imaging-1.0.0-alpha6.jar')
pom = fetch(prefix + 'commons-imaging-1.0.0-alpha6.pom', 'commons-imaging-1.0.0-alpha6.pom')
official = OUT / 'official-bin-commons-imaging-1.0.0-alpha6.jar'
assert maven_jar.read_bytes() == official.read_bytes(), 'Maven JAR differs from official authenticated archive'
print('Verified Maven Imaging JAR equals official binary archive JAR', flush=True)
(OUT / 'imaging-download-receipt.json').write_text(json.dumps({'passed': True, 'rootApproved': True,
    'version': '1.0.0-alpha6', 'officialArchiveSha512Verified': True, 'mavenJarEqualsOfficialArchiveJar': True,
    'downloads': receipts, 'transitiveResolutionPending': True}, indent=2) + '\n', encoding='utf-8', newline='\n')
print('POM downloaded; inspect required compile transitive graph before runtime acceptance', flush=True)
