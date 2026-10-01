from pathlib import Path
import hashlib,json,os
L=Path(__file__).resolve().parent
prefix=chr(92)*2+'?'+chr(92)
def safe(p):
 p=os.path.abspath(p);return Path(p if p.startswith(prefix) else prefix+p)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def out(name,value):safe(L/name).write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
assert not safe(L/'frozen-handoff.json').exists(),'Frozen proof is immutable; use a new lane.'
raw=[]
for directory in ['prepared','fixtures','producer-replay']:
 raw.extend(p for p in (L/directory).rglob('*') if p.is_file() and p.suffix in ['.py','.kt'])
raw.extend(p for p in L.iterdir() if p.is_file() and p.suffix in ['.py','.json','.patch','.md','.kts'] and p.name not in ['frozen-handoff.json'])
for kind in ['compile','proof']:
 for d in sorted(L.glob(kind+'-*')):
  if not d.is_dir(): continue
  for p in d.iterdir():
   if p.is_file() and p.suffix in ['.args','.log','.json']:raw.append(p)
for name in ['original-history-material3.png','original-liked-material3.png','result.json']:
 raw.append(L/'proof-05/temporary'/name)
raw=sorted(set(raw))
excluded=[]
for p in sorted(L.glob('compile-*/candidate.jar')):
 excluded.append({'path':p.relative_to(L).as_posix(),'sha256Bytes':sha(p),'reason':'task-only compiled source reference; not raw archive/install/runtime dependency'})
out('excluded-runtime-artifacts.json',{'artifacts':excluded,'otherExcluded':['compiled class directories','task temporary PluginStore/Library/session files','__pycache__'],'noInstallBinary':True})
raw.append(L/'excluded-runtime-artifacts.json')
rows=[{'path':p.relative_to(L).as_posix(),'sha256Bytes':sha(p),'size':safe(p).stat().st_size} for p in sorted(raw)]
out('frozen-handoff.json',{'schemaVersion':1,'targetTag':'v0.2.3','targetCommit':'3d5d19a2f994daccd0e2f8b5f522b6d82f43d589','scope':'prepared complete original History/HistorySearch/Liked/CoinArchive Root/queue/retention seams; installed original UI/VM/protocol reused','actualSnapshot48Manifest':'298fd01851352c496f011fef69f9091652c6ba41ea0223a418ee298762cab89d','actualSnapshot48OrderedCp':'2ae45d9e07b65c705807c27ace118cb0f9cdb320a54793b144e80e760f8ed7a1','assertions':19,'groups':3,'pointerPairs':2,'newCoreProductOverrides':0,'explicitWholeConsumerCompileOverrides':2,'MainIntegrated':False,'RootRuntime':False,'HTTPsocket':False,'HWND':False,'EXE':False,'installWhitelistSha':sha(L/'install-whitelist.json'),'artifacts':rows})
for r in rows:assert sha(L/r['path'])==r['sha256Bytes']
print('Frozen raw files',len(rows))
print('manifest',sha(L/'frozen-handoff.json'))
print('whitelist',sha(L/'install-whitelist.json'))
print('recipe',sha(L/'ROOT-INTEGRATION.md'))
