from pathlib import Path
import hashlib,json,zipfile
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2];PRIMARY=REPO.parent/'BiliPai'
EXT=chr(92)*2+'?'+chr(92)
def safe(p):
 v=str(Path(p).absolute());return Path(v if v.startswith(EXT) else EXT+v)
def sha(b):return hashlib.sha256(b).hexdigest()
def write(p,b):safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_bytes(b)
def save(p,x):write(p,(json.dumps(x,ensure_ascii=False,indent=2)+'\n').encode())
COMMIT='5157b503e86e2bfc2db61db00fff5df41326394a';ARCHIVE_SHA='e6b9f53eff9cfce98580d70a1a8e2ba447038af5f46e8392c96ec843ce0b9e6b'
archive=PRIMARY/'desktop/.local/source9-appearance/original'/(COMMIT+'.zip');assert sha(safe(archive).read_bytes())==ARCHIVE_SHA
names=['Search','Community','Contacts','ContactsCircle','Favorites','Folder','GridView','Home','Music','Notes','Play','Recent','Recording','Settings','Stopwatch','Store','Theme','TopDownloads']
base='miuix-icons/src/commonMain/kotlin/top/yukonga/miuix/kmp/icon/extended/'
provenance=json.loads(safe(REPO/'desktop/third-party/miuix5157/upstream-provenance.json').read_text(encoding='utf-8'));assert provenance['commit']==COMMIT
records=[]
with zipfile.ZipFile(safe(archive)) as z:
 for name in names:
  original=base+name+'.kt';data=z.read('miuix-'+COMMIT+'/'+original)
  assert b'package top.yukonga.miuix.kmp.icon.extended' in data
  storage=original.replace('top/yukonga/miuix/kmp/','');target=HERE/'prepared/desktop/third-party/miuix5157/upstream'/storage
  assert not any(r['path']==original for r in provenance['files'])
  write(target,data);row=dict(path=original,sha256=sha(data),storagePath=storage);provenance['files'].append(row);records.append(dict(**row,payload=str(target)))
existing=REPO/'desktop/third-party/miuix5157/build.gradle.kts';old=safe(existing).read_text(encoding='utf-8').replace('\r\n','\n')
before='val originalModules = listOf("miuix-core", "miuix-shader", "miuix-squircle", "miuix-ui", "miuix-preference", "miuix-blur")'
assert old.count(before)==1
save(HERE/'miuix-fork-hunk.json',dict(path='desktop/third-party/miuix5157/build.gradle.kts',baseSha256LF=sha(old.encode()),hunks=[dict(old=before,new=before[:-1]+', "miuix-icons")')],noNewArtifact=True))
save(HERE/'prepared/desktop/third-party/miuix5157/upstream-provenance.json',provenance)
save(HERE/'miuix-icons-source-evidence.json',dict(repository='https://github.com/compose-miuix-ui/miuix',commit=COMMIT,archive=str(archive),archiveSha256=ARCHIVE_SHA,selectedFullSources=records,sourceBodyChanges=0,upstreamStableAppMiuix='0.9.4-5c91d5e5-SNAPSHOT',actualCurrentWindowsMiuix='0.9.4-5157b503-windows-source1',versionAlignment='pending; this only extends existing actual pinned fork',uniqueExistingCoreMiuixIconsRequired=True))
print('Prepared 18 unchanged full Miuix icon sources; source-only existing-fork extension')
