"""Read primary vendor source at the actual runtime release SOURCE commit, not master."""
from pathlib import Path
import hashlib,json,urllib.request
LANE=Path(__file__).resolve().parent
def safe(p):
    s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92)
    return Path(s if s.startswith(prefix) else prefix+s)
def put(n,b):
    p=LANE/n;safe(p).parent.mkdir(parents=True,exist_ok=True);safe(p).write_bytes(b)
    return {'path':str(p),'sha256Bytes':hashlib.sha256(b).hexdigest(),'size':len(b)}
def get(u):
    with urllib.request.urlopen(urllib.request.Request(u,headers={'User-Agent':'BiliPai-Windows-source-audit'}),timeout=35) as r:return r.read()
repo='adoptium/jdk21u';prefix='1c417fbfc2f7'
u='https://api.github.com/repos/'+repo+'/commits/'+prefix
b=get(u);info=json.loads(b);commit=info['sha'];assert commit.startswith(prefix)
records=[{'url':u,**put('native/vendor-commit.json',b)}]
for f in ['awt_Window.cpp','awt_Frame.cpp','awt_Win32GraphicsDevice.cpp','awt_Component.cpp','awt_Window.h']:
    u='https://raw.githubusercontent.com/'+repo+'/'+commit+'/src/java.desktop/windows/native/libawt/windows/'+f
    records.append({'url':u,**put('native/'+f,get(u))})
put('native/source-provenance.json',(json.dumps({'repository':repo,'runtimeReleaseSourcePrefix':prefix,'fullCommit':commit,
    'sourceAssociation':'Runtime release SOURCE=.:git:1c417fbfc2f7, SOURCE_REPO=https://github.com/adoptium/jdk21u.git',
    'rawArtifacts':records},indent=2)+'\n').encode())
print(json.dumps({'commit':commit,'files':5},indent=2))
