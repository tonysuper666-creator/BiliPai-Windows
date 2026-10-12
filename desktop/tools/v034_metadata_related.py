"""Pinned stable034 independent metadata related over actual Windows request/entry authority."""
from pathlib import Path
import hashlib,json,os
COMMIT="a69d4181a073aa3207def3fca43318dcf7ab2c70"
MANIFEST_SHA256="1d7b0b08e4bbb576c9bc9d667e5bd0be081d46533e1f2ea1066e411c877f8ccb"
_FIXED=None
def wide(p):
 p=Path(p).resolve()
 return Path('\\\\?\\'+str(p)) if os.name=='nt' and not str(p).startswith('\\\\?\\') else p
def sha(raw):return hashlib.sha256(raw).hexdigest()
def fixed_sources():
 global _FIXED
 if _FIXED is not None:return _FIXED
 root=Path(__file__).resolve().parents[1]/'upstream-slices'/'v034-metadata-related'
 raw=wide(root/'manifest.json').read_bytes();assert sha(raw)==MANIFEST_SHA256
 manifest=json.loads(raw);assert manifest['upstreamCommit']==COMMIT
 sources={}
 for row in manifest['sources']:
  raw=wide(root/row['file']).read_bytes()
  assert len(raw)==row['bytes'] and sha(raw)==row['sha256']
  assert hashlib.sha1(('blob '+str(len(raw))+'\0').encode()+raw).hexdigest()==row['gitBlob']
  sources[row['file']]=raw.decode('utf8').replace('\r\n','\n')
 _FIXED=manifest,sources
 return _FIXED
def source_text(filename):return fixed_sources()[1][filename]
def source_rows():
 return [dict(path=row['path'],originalPath=row['originalPath'],sha256LF=row['sha256'],commit=COMMIT) for row in fixed_sources()[0]['sources']]
def apply_and_record(path,body,output):
 manifest,_=fixed_sources();recipe=manifest['outputs'].get(path)
 if recipe is None:return body,None
 original=body;assert sha(body.encode())==recipe['beforeSha256LF'],path+' fixed metadata-related input'
 edits=[]
 for edit in recipe['edits']:
  before,after=edit['before'],edit['after'];assert body.count(before)==1
  i=body.index(before);edits.append(dict(offset=i,before=before,after=after))
  body=body[:i]+after+body[i+len(before):]
 assert sha(body.encode())==recipe['afterSha256LF']
 inverse=body
 for edit in reversed(edits):
  i=edit['offset'];assert inverse[i:i+len(edit['after'])]==edit['after']
  inverse=inverse[:i]+edit['before']+inverse[i+len(edit['after']):]
 assert inverse==original
 proof=dict(schemaVersion=1,upstreamCommit=COMMIT,sourceManifest=MANIFEST_SHA256,path=path,
  beforeSha256LF=recipe['beforeSha256LF'],afterSha256LF=recipe['afterSha256LF'],inverseEdits=edits,
  playableSuccessManufactured=False,metadataNetworkPermission='captured raw request + exact entry receipt',uiNavigation='entry-only witness, Root fresh destination capture')
 target=wide(Path(output)/(path.rsplit('/',1)[-1]+'.v034-metadata-related-inverse.json'))
 target.parent.mkdir(parents=True,exist_ok=True)
 target.write_bytes((json.dumps(proof,ensure_ascii=False,indent=2)+'\n').encode())
 return body,proof
