"""Complete fixed v035 Story projections on the existing retained Windows owners.
Build-time source adaptation only; no runtime VM, queue, HTTP/client or permission.
"""
from pathlib import Path
import hashlib,json,os
COMMIT="46eaf277e83512061785be9759d1f07aba3c4839"
MANIFEST_SHA="d193f904ae5dae695eeabb12f38943c48b3a090d6ed6f2362b2a3b716509acd1"
def wide(path):
 path=Path(path).resolve();prefix=chr(92)*2+'?'+chr(92)
 return Path(prefix+str(path)) if os.name=='nt' and not str(path).startswith(prefix) else path
def sha(raw):return hashlib.sha256(raw).hexdigest()
def load():
 root=Path(__file__).resolve().parents[1]/'upstream-slices'/'v035-story'
 raw=wide(root/'manifest.json').read_bytes();assert sha(raw)==MANIFEST_SHA
 m=json.loads(raw);assert m['schemaVersion']==1 and m['upstreamCommit']==COMMIT
 return root,m
def source(name):
 root,m=load();record=next(x for x in m['sources'] if x['file']==name)
 raw=wide(root/name).read_bytes();assert len(raw)==record['bytes'] and sha(raw)==record['sha256']
 assert hashlib.sha1(('blob '+str(len(raw))+'\0').encode()+raw).hexdigest()==record['gitBlob']
 return raw.decode(),record,m['projections'][name]
def patch(text,edits,inverse=False):
 for e in reversed(edits) if inverse else edits:
  before,after=(e['after'],e['before']) if inverse else (e['before'],e['after'])
  at=e['offsetChars'];assert text[at:at+len(before)]==before,'exact original projection span'
  text=text[:at]+after+text[at+len(before):]
 return text
def proof(output,name,data):
 data=dict(schemaVersion=1,upstreamCommit=COMMIT,sourceManifest=MANIFEST_SHA,**data)
 wide(Path(output)/name).write_bytes((json.dumps(data,ensure_ascii=False,indent=2)+'\n').encode())
def project_story(name,output):
 original,record,p=source(name);assert sha(original.encode())==p['originalSha256LF']
 adapted=patch(original,p['adapters']);assert sha(adapted.encode())==p['adaptedSha256LF']
 assert patch(adapted,p['adapters'],inverse=True)==original
 emitted=p['writerHeader']+adapted.strip()+'\n';assert sha(emitted.encode())==p['emittedSha256']
 proof(output,'original-v035-'+name.removesuffix('.kt')+'-inverse.json',dict(originalSource=record,projection=p,
  completeOriginalInverseExact=True,emittedSha256=p['emittedSha256']))
 return original,adapted
def emit_story_models(output):
 results=[]
 for name in ('StoryFeedPolicy.kt','StoryModels.kt'):
  original,record,p=source(name);raw=original.encode();assert sha(raw)==p['emittedSha256']
  target=wide(Path(output)/p['output']);target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(raw);results.append(target)
 proof(output,'original-v035-story-direct-inverse.json',dict(sources=[source(x)[1] for x in ('StoryFeedPolicy.kt','StoryModels.kt')],completeRawCopies=True))
 return results
def story_protocol(output):
 original,record,p=source('StoryRepository.kt');at=p['originalOffsetChars'];method=p['originalMethod']
 assert original[at:at+len(method)]==method and sha(method.encode())==p['originalMethodSha256LF']
 adapted=patch(method,p['adapters']);assert sha(adapted.encode())==p['adaptedMethodSha256LF']
 assert patch(adapted,p['adapters'],inverse=True)==method
 body=p['prefix']+adapted+p['suffix'];assert sha(body.encode())==p['emittedSha256']
 target=wide(Path(output)/p['output']);target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(body.encode())
 result=dict(targetCommit=COMMIT,originalPath=record['originalPath'],originalSHA256LF=sha(original.encode()),
  originalStartLine=original[:at].count('\n')+1,originalEndLine=original[:at+len(method)].count('\n')+1,
  originalBodySHA256LF=p['originalMethodSha256LF'],adaptedBodySHA256LF=p['adaptedMethodSha256LF'],
  output=p['output'],outputSHA256LF=p['emittedSha256'],bodyInverseByteEqual=True,platformEdits=p['adapters'],
  originalMethod=method,outputCount=1,schemaEmission=False,completeRepositoryUpgrade=False)
 proof(output,'original-v035-story-protocol-inverse.json',dict(originalSource=record,projection=p,**result))
 return result
def adapt_story_api(text,output):
 original,record,p=source('ApiClient.kt');at=p['originalOffsetChars'];selected=p['originalDeclaration']
 assert original[at:at+len(selected)]==selected and sha(selected.encode())==p['originalDeclarationSha256LF']
 before=p['beforeDeclaration'].replace('\r\n','\n');assert text.count(before)==1,'sole original StoryApi interface'
 result=text.replace(before,selected,1);assert result.count(selected)==1 and result.replace(selected,before,1)==text
 proof(output,'original-v035-story-api-inverse.json',dict(originalSource=record,selectedOriginalDeclaration=selected,
  selectedOriginalSha256LF=p['originalDeclarationSha256LF'],beforeDeclaration=before,wholeInputSha256LF=sha(text.encode()),
  wholeOutputSha256LF=sha(result.encode()),wholeInputInverseExact=True,completeApiUpgrade=False))
 return result
