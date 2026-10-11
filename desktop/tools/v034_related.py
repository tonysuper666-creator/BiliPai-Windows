"""Pinned complete beta2 sources; original deferred-related behavior over the existing Windows owner."""
from pathlib import Path
import hashlib,json,os
COMMIT="b2091b1de636e499df80af7e700796b4f52b7f92"
MANIFEST_SHA256="2bb2c674f997413f6ff64fb6dd2d98d799eb5f602ad7a59140d92271b33ef5e9"
_FIXED=None
def wide(p):
 p=Path(p).resolve()
 return Path('\\\\?\\'+str(p)) if os.name=='nt' and not str(p).startswith('\\\\?\\') else p
def sha(raw):return hashlib.sha256(raw).hexdigest()
def fixed_sources():
 global _FIXED
 if _FIXED is not None:return _FIXED
 root=Path(__file__).resolve().parents[1]/'upstream-slices'/'v034-related'
 raw=wide(root/'manifest.json').read_bytes()
 assert sha(raw)==MANIFEST_SHA256,'fixed beta2 related manifest'
 manifest=json.loads(raw)
 assert manifest['upstreamCommit']==COMMIT
 sources={}
 for item in manifest['sources']:
  raw=wide(root/item['file']).read_bytes()
  assert len(raw)==item['bytes'] and sha(raw)==item['sha256'],item['originalPath']
  blob=hashlib.sha1(('blob '+str(len(raw))+'\0').encode()+raw).hexdigest()
  assert blob==item['gitBlob'],item['originalPath']+' complete git blob'
  sources[item['file']]=raw.decode('utf8').replace('\r\n','\n')
 _FIXED=(manifest,sources)
 return _FIXED
def related_response():
 return fixed_sources()[1]['RelatedResponse.kt']
def apply_and_record(path,body,output):
 manifest,_=fixed_sources()
 recipe=manifest['outputs'].get(path)
 if recipe is None:return body,None
 original=body
 assert sha(body.encode())==recipe['beforeSha256LF'],path+' actual beta2 input'
 audit=[]
 for edit in recipe['edits']:
  before,after=edit['before'],edit['after']
  assert body.count(before)==1,path+' actual beta2 seam'
  audit.append(dict(offset=body.index(before),before=before,after=after))
  body=body.replace(before,after,1)
 assert sha(body.encode())==recipe['afterSha256LF'],path+' beta2 output'
 inverse=body
 for edit in reversed(audit):
  assert inverse.count(edit['after'])==1,path+' beta2 inverse seam'
  inverse=inverse.replace(edit['after'],edit['before'],1)
 assert inverse==original,path+' full beta2 inverse'
 proof=dict(schemaVersion=1,upstreamCommit=COMMIT,sourceManifest=MANIFEST_SHA256,
  path=path,beforeSha256LF=recipe['beforeSha256LF'],afterSha256LF=recipe['afterSha256LF'],inverseEdits=audit)
 target=wide(Path(output)/(path.rsplit('/',1)[-1]+'.v034-related-inverse.json'))
 target.parent.mkdir(parents=True,exist_ok=True)
 target.write_bytes((json.dumps(proof,ensure_ascii=False,indent=2)+'\n').encode('utf8'))
 return body,proof
