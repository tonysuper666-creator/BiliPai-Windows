from pathlib import Path
import urllib.request,json,hashlib,xml.etree.ElementTree as ET
P=Path(__file__).resolve().parent;D=P/'dependencies';D.mkdir(exist_ok=True)
BASE='https://repo.maven.apache.org/maven2/org/jetbrains/compose/material3/adaptive/'
records=[]
for artifact in ['adaptive-desktop','adaptive-layout-desktop']:
 url=BASE+artifact+'/maven-metadata.xml';data=urllib.request.urlopen(url,timeout=20).read();(D/(artifact+'-metadata.xml')).write_bytes(data)
 versions=[e.text for e in ET.fromstring(data).findall('./versioning/versions/version')]
 version='1.3.0-rc01' # Deliberate JVM port version, distinct from original Android 1.3.0.
 assert version in versions
 for ext in ['pom','jar']:
  url=BASE+artifact+'/'+version+'/'+artifact+'-'+version+'.'+ext;data=urllib.request.urlopen(url,timeout=30).read();path=D/(artifact+'-'+version+'.'+ext);path.write_bytes(data)
  records.append(dict(coordinate='org.jetbrains.compose.material3.adaptive:'+artifact+':'+version,artifact=artifact,version=version,type=ext,path=str(path),url=url,sha256Bytes=hashlib.sha256(data).hexdigest()))
(D/'identities.json').write_text(json.dumps(records,indent=2),encoding='utf8')
for ext in ['pom','jar']:
 url='https://repo.maven.apache.org/maven2/org/jetbrains/androidx/window/window-core-desktop/1.5.0/window-core-desktop-1.5.0.'+ext
 data=urllib.request.urlopen(url,timeout=30).read();path=D/('window-core-desktop-1.5.0.'+ext);path.write_bytes(data)
 records.append(dict(coordinate='org.jetbrains.androidx.window:window-core-desktop:1.5.0',type=ext,path=str(path),url=url,sha256Bytes=hashlib.sha256(data).hexdigest()))
(D/'identities.json').write_text(json.dumps(records,indent=2),encoding='utf8')
print(json.dumps(records,indent=2))
