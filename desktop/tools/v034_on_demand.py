"""Exact stable034 request-coalescing sources and bounded immutable-owner adaptations.
No network/global TokenManager/second request owner; only called by existing sole producers."""
from pathlib import Path
import hashlib,json,os
COMMIT = "a69d4181a073aa3207def3fca43318dcf7ab2c70"
MANIFEST_SHA256 = "39667458272722950e2cbbabacdee2f127998051c8605001604ed361b3e44947"
def wide(p):
 p=Path(p).absolute();s=str(p)
 return Path(chr(92)*2+'?'+chr(92)+s) if os.name=='nt' and not s.startswith(chr(92)*2+'?'+chr(92)) else p
def sha(raw): return hashlib.sha256(raw).hexdigest()
def fixed_sources():
 root=Path(__file__).resolve().parents[1]/'upstream-slices/v034-on-demand'
 raw=wide(root/'manifest.json').read_bytes();assert sha(raw)==MANIFEST_SHA256
 manifest=json.loads(raw);assert manifest['upstreamCommit']==COMMIT
 sources={}
 for item in manifest['sources']:
  raw=wide(root/item['file']).read_bytes()
  assert len(raw)==item['bytes'] and sha(raw)==item['sha256']
  assert hashlib.sha1(('blob '+str(len(raw))+'\0').encode()+raw).hexdigest()==item['gitBlob']
  sources[item['file']]=raw.decode('utf8').replace('\r\n','\n')
 return manifest,sources
def apply_and_record(path,text,output):
 manifest,_=fixed_sources();recipe=manifest['outputs'].get(path)
 if recipe is None:return text,None
 original=text;assert sha(text.encode())==recipe['beforeSha256LF'],path+' exact pre-cache output'
 for edit in recipe['edits']:
  assert text.count(edit['before'])==edit['count'];text=text.replace(edit['before'],edit['after'],edit['count'])
 assert sha(text.encode())==recipe['afterSha256LF'],path+' exact stable request cache output'
 inverse=text
 for edit in reversed(recipe['edits']):
  assert inverse.count(edit['after'])==edit['count'];inverse=inverse.replace(edit['after'],edit['before'],edit['count'])
 assert inverse==original
 proof=dict(schema=1,upstreamCommit=COMMIT,manifestSha256=MANIFEST_SHA256,path=path,recipe=recipe,fullInverse=True,scope=manifest['boundary'])
 p=wide(Path(output)/(Path(path).name+'.v034-on-demand-inverse.json'));p.parent.mkdir(parents=True,exist_ok=True)
 p.write_bytes((json.dumps(proof,ensure_ascii=False,indent=2)+'\n').encode('utf8'))
 return text,proof
def generate_auxiliary(repo,output,emit):
 manifest,sources=fixed_sources()
 for path,recipe in manifest['auxiliary'].items():
  source=sources[recipe['file']]
  if recipe['mode']=='whole-original':text=source
  else:
   pure=source[source.index('internal fun buildVideoRequestAccountScope('):]
   text='package com.android.purebilibili.data.repository\n\nimport java.security.MessageDigest\n\n'+pure
   assert text==recipe['body']
  origin=next(row['originalPath'] for row in manifest['sources'] if row['file']==recipe['file'])
  emit(path,text,origin,'fixed-stable-v034-owned-request-helper')
