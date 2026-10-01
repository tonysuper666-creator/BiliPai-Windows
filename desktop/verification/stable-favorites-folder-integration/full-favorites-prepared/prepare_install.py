from pathlib import Path
import hashlib,json,subprocess,sys,shutil
HERE=Path(__file__).resolve().parent
MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
tool=HERE/'prepared/desktop/tools/extract-upstream-favorites.py'
s=(HERE/'generate.py').read_text(encoding='utf-8')
a=s.index('HERE=');b=s.index('BASE=')
s=s[:a]+"HERE=Path(__file__).resolve().parent\nREPO=None\n"+s[b:]
s=s.replace("OUT=HERE/'generated'","OUT=None")
a=s.index("spec=importlib.util.spec_from_file_location");b=s.index('records=[]',a)
s=s[:a]+'parser=None\n'+s[b:]
old=" p=OUT/p;safe(p).parent.mkdir(parents=True,exist_ok=True);safe(p).write_text(s,encoding='utf-8',newline='\\n')"
new=""" relative=str(p).replace(chr(92),'/')
 row=records[-1]
 mode='direct' if hashlib.sha256(s.encode()).hexdigest()==row['sha256LfUtf8'] else 'selected'
 row.setdefault('outputs',[]).append(dict(path=relative,sha256LfUtf8=hashlib.sha256(s.encode()).hexdigest(),mode=mode))
 if mode=='direct' and not STANDALONE:return
 p=OUT/p;safe(p).parent.mkdir(parents=True,exist_ok=True);safe(p).write_text(s,encoding='utf-8',newline='\\n')"""
assert old in s;s=s.replace(old,new)
s=s.replace('def main():','def produce():',1)
a=s.index(" safe(HERE/'source-inventory.json')")
s=s[:a]+'''
def generate(repo:Path,output:Path,standalone=False):
 global REPO,OUT,parser,records,STANDALONE
 REPO=repo.resolve();OUT=output.resolve();STANDALONE=standalone;records=[]
 spec=importlib.util.spec_from_file_location('source_parser',REPO/'desktop/tools/extract-upstream-dynamic-reply-protocol.py')
 parser=importlib.util.module_from_spec(spec);spec.loader.exec_module(parser)
 produce()
 canonical={}
 for row in records:
  entry=canonical.setdefault(row['path'],dict(path=row['path'],upstreamCommit=row['upstreamCommit'],sha256LfUtf8=row['sha256LfUtf8'],outputs=[],selections=[]))
  if row.get('selection') and row['selection'] not in entry['selections']:entry['selections'].append(row['selection'])
  for emitted in row.get('outputs',[]):
   if emitted not in entry['outputs']:entry['outputs'].append(emitted)
 for row in canonical.values():
  row['mode']='selected' if any(x['mode']=='selected' for x in row['outputs']) else 'direct' if row['outputs'] else 'reference'
 safe(OUT.parent/'source-inventory.json').write_text(json.dumps(list(canonical.values()),ensure_ascii=False,indent=2)+'\\n',encoding='utf-8')
 return list(canonical.values())
if __name__=='__main__':
 import argparse
 ap=argparse.ArgumentParser();ap.add_argument('--repo',type=Path,required=True);ap.add_argument('--output',type=Path,required=True);ap.add_argument('--standalone',action='store_true')
 args=ap.parse_args();generate(args.repo,args.output,args.standalone)
'''
tool.parent.mkdir(parents=True,exist_ok=True);tool.write_text(s,encoding='utf-8',newline='\n')
platform=HERE/'prepared/desktop/src/main/kotlin'
for p in (HERE/'platform').rglob('*.kt'):
 target=platform/p.relative_to(HERE/'platform');target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(p,target)
subprocess.run([sys.executable,str(tool),'--repo',str(REPO),'--output',str(HERE/'prepared/generated'),'--standalone'],check=True)
records=json.loads((HERE/'prepared/source-inventory.json').read_text(encoding='utf-8'))
registered={r['path']:r for r in json.loads((REPO/'desktop/upstream-sources.json').read_text(encoding='utf-8'))['sources']}
delta=[]
for row in records:
 if row['mode']=='reference':continue
 existing=registered.get(row['path'])
 if existing:assert existing['sha256']==row['sha256LfUtf8']
 delta.append(dict(path=row['path'],sha256=row['sha256LfUtf8'],mode=existing['mode'] if existing else row['mode'],
  requestedFeature='stable-favorites',operation='merge-feature' if existing else 'append-identity',
  preserveExistingMode=bool(existing),outputs=row['outputs']))
(HERE/'prepared/registry-delta.json').write_text(json.dumps(delta,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print('Prepared sole producer, '+str(len(records))+' source identities / '+str(sum(len(r['outputs']) for r in records))+' generated outputs')
