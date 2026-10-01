from pathlib import Path
import urllib.request,hashlib,json,xml.etree.ElementTree as ET
LANE=Path(__file__).resolve().parent;OUT=LANE/'dependency-evidence';BASE='https://repo.maven.apache.org/maven2/';VERSION='1.0.0-rc14'
OUT.mkdir(exist_ok=True);rows=[]
def fetch(group,name,version,extension):
 path=group.replace('.','/')+'/'+name+'/'+version+'/'+name+'-'+version+'.'+extension
 url=BASE+path;p=OUT/(name+'-'+version+'.'+extension)
 if not p.exists():
  with urllib.request.urlopen(url,timeout=45)as r:data=r.read()
  p.write_bytes(data)
 else:data=p.read_bytes()
 rows.append(dict(url=url,path=str(p),bytes=len(data),sha256Bytes=hashlib.sha256(data).hexdigest()))
 return p
for name in ['richeditor-compose','richeditor-compose-desktop']:
 fetch('com.mohamedrejeb.richeditor',name,VERSION,'pom');fetch('com.mohamedrejeb.richeditor',name,VERSION,'module')
fetch('com.mohamedrejeb.richeditor','richeditor-compose-desktop',VERSION,'jar')
module=json.loads((OUT/('richeditor-compose-desktop-'+VERSION+'.module')).read_text())
published=next(v for v in module['variants']if v['name']=='desktopApiElements-published')
artifact=published['files'][0]
actual=(OUT/('richeditor-compose-desktop-'+VERSION+'.jar')).read_bytes()
assert len(actual)==artifact['size'] and hashlib.sha256(actual).hexdigest()==artifact['sha256']
assert published['attributes']['org.jetbrains.kotlin.platform.type']=='jvm'
ns={'m':'http://maven.apache.org/POM/4.0.0'};pom=ET.parse(OUT/('richeditor-compose-desktop-'+VERSION+'.pom')).getroot()
deps=[]
for d in pom.findall('m:dependencies/m:dependency',ns):
 def field(n):return d.findtext('m:'+n,namespaces=ns)
 deps.append(dict(group=field('groupId'),name=field('artifactId'),version=field('version'),scope=field('scope')))
result=dict(scope='Official MavenCentral dependency-only read. Prospective narrow compile; not installed product runtime acceptance.',originalCoordinate='com.mohamedrejeb.richeditor:richeditor-compose:'+VERSION,jvmCoordinate='com.mohamedrejeb.richeditor:richeditor-compose-desktop:'+VERSION,originalDeclaration='app/build.gradle.kts:400 at fixed stable commit 3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',rows=rows,dependencies=deps,variants=[dict(name=v['name'],attributes=v.get('attributes'),dependencies=v.get('dependencies'),files=v.get('files'))for v in module['variants']])
(OUT/'dependency-manifest.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
print(json.dumps(result,indent=2))
