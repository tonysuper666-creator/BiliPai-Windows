"""Full original CDN transfer bodies with pinned minimal Windows cache/platform ports."""
from pathlib import Path
import argparse,hashlib,json,os,subprocess
def wide(p):
 s=os.path.abspath(p);prefix=chr(92)*2+'?'+chr(92)
 return Path(s if s.startswith(prefix)else prefix+s)if os.name=='nt'else Path(s)
def sha(t):return hashlib.sha256(t.encode()).hexdigest()
def generate(repo,output):
 spec=json.loads(wide(Path(__file__).with_name('upstream-cdn-transfer-adaptations.json')).read_bytes());rows=[]
 for item in spec['recipes']:
  raw=wide(repo/item['originalPath']).read_text(encoding='utf8').replace('\r\n','\n')
  assert sha(raw)==item['originalSha256LF'],item['originalPath']
  blob=subprocess.check_output(['git','-C',str(repo),'rev-parse',spec['officialCommit']+':'+item['originalPath']],text=True).strip()
  assert blob==item['gitBlob']
  lines=raw.splitlines(keepends=True);parts=[];inverse=[];last=0
  for e in item['edits']:
   i,j=e['startLine'],e['endLineExclusive'];assert i==last;last=j
   before=''.join(lines[i:j]);assert sha(before)==e['beforeSha256LF']
   parts.append(before if e['kind']=='equal'else e['after']);inverse.append(before)
  assert last==len(lines)and ''.join(inverse)==raw
  body=''.join(parts);assert sha(body)==item['outputSha256LF']
  target=wide(output/item['output']);target.parent.mkdir(parents=True,exist_ok=True);target.write_text(body,encoding='utf8',newline='\n')
  rows.append(dict(path=item['output'],origin=item['originalPath'],sha256LF=sha(body),mode='complete-original-cdn-neutral-platform',generated=True,inverseOriginalBodyExact=True))
 return rows
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--repo',required=True,type=Path);p.add_argument('--output',required=True,type=Path);p.add_argument('--audit',type=Path);a=p.parse_args();rows=generate(a.repo.resolve(),a.output.resolve())
 if a.audit:wide(a.audit.parent).mkdir(parents=True,exist_ok=True);wide(a.audit).write_text(json.dumps(rows,indent=2)+'\n',encoding='utf8')
 print('Generated original CDN transfer',len(rows),'families; inverse PASS')
