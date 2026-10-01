from pathlib import Path
import importlib.util,json,hashlib,sys,subprocess
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2]
EXT=chr(92)*2+'?'+chr(92)
def safe(p):
 v=str(Path(p).absolute());return Path(v if v.startswith(EXT) else EXT+v)
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def write(p,t):safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(t,encoding='utf-8',newline='\n')
def save(p,x):write(p,json.dumps(x,ensure_ascii=False,indent=2)+'\n')
path=HERE/'prepared/desktop/tools/extract-upstream-linked-dock.py';write(path,read(HERE/'producer.pyfrag'))
s=importlib.util.spec_from_file_location('linked_dock_producer',path);m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
manifest=json.loads(read(REPO/'desktop/upstream-sources.json'));assert manifest['upstreamCommit']==m.PIN
current={r['path']:r for r in manifest['sources']};inv=m.inventory(REPO);append=[r for r in inv if r['path'] not in current]
manifest['sources']+=append
shadow=HERE/'source-shadow';gitdir=subprocess.check_output(['git','rev-parse','--absolute-git-dir'],cwd=REPO,text=True).strip();write(shadow/'.git','gitdir: '+gitdir.replace('\\','/')+'\n')
for p in m.SOURCES+['app/src/main/res/drawable/'+asset+'.xml' for asset in m.ASSETS]:write(shadow/p,read(REPO/p))
save(shadow/'desktop/upstream-sources.json',manifest)
# Source-verified original selector/vector backend helpers, no generated fake declarations.
for name in ['extract-upstream-dynamic-reply-protocol.py','sync-upstream.py','extract-appearance-platform.py']:
 write(shadow/'desktop/tools'/name,read(REPO/'desktop/tools'/name))
m.generate(shadow,HERE/'generated')
save(HERE/'registry-recipe.json',dict(commit=m.PIN,appendRows=append,featureMerge=[dict(path=r['path'],sha256=r['sha256'],preserveMode=current[r['path']]['mode'],appendFeatures=[f for f in r['features'] if f not in current[r['path']].get('features',[])]) for r in inv if r['path'] in current],originalVectorResources=[dict(path='app/src/main/res/drawable/'+a+'.xml',sha256=hashlib.sha256(read(REPO/'app/src/main/res/drawable'/(a+'.xml')).encode()).hexdigest()) for a in m.ASSETS]))
print('Generated complete original linked Dock and controls; registry append '+str(len(append)))
