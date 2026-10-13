"""Seven full v035 continuation methods over the existing Windows owner.
No new session/listener, HTTP, queue, store or native owner is created.
"""
from pathlib import Path
import hashlib,json,os
COMMIT="46eaf277e83512061785be9759d1f07aba3c4839"
MANIFEST_SHA="aa04a168c57721ee14e3fe2a5525d0ddbc2eb13eb6d62a6c5984f877756f6798"
VM_PATH="com/android/purebilibili/feature/video/viewmodel/VideoPlaybackViewModel.kt"
def wide(p):
 p=Path(p).resolve();prefix=chr(92)*2+'?'+chr(92)
 return Path(prefix+str(p)) if os.name=='nt' and not str(p).startswith(prefix) else p
def sha(raw):return hashlib.sha256(raw).hexdigest()
def patch(text,edits,inverse=False):
 for e in (reversed(edits) if inverse else edits):
  before,after=(e['after'],e['before']) if inverse else (e['before'],e['after'])
  at=e['offset'];assert text[at:at+len(before)]==before,'exact source span'
  text=text[:at]+after+text[at+len(before):]
 return text
def apply_and_record(path,text,output):
 if path!=VM_PATH:return text,None
 root=Path(__file__).resolve().parents[1]/'upstream-slices'/'v035-autoplay-continuation'
 manifestRaw=wide(root/'manifest.json').read_bytes();assert sha(manifestRaw)==MANIFEST_SHA
 m=json.loads(manifestRaw);assert m['schemaVersion']==1 and m['upstreamCommit']==COMMIT and m['output']==path
 source=m['originalVm'];raw=wide(root/source['file']).read_bytes()
 assert len(raw)==source['bytes'] and sha(raw)==source['sha256']
 assert hashlib.sha1(('blob '+str(len(raw))+'\0').encode()+raw).hexdigest()==source['gitBlob']
 original=raw.decode();assert sha(original.encode())==source['sha256LF'];methods={}
 for method in m['originalMethods']:
  block=method['originalText'];at=method['originalOffset']
  assert original[at:at+len(block)]==block and sha(block.encode())==method['originalSha256LF']
  adapted=patch(block,method['windowsAdapters']);assert sha(adapted.encode())==method['adaptedSha256LF']
  assert patch(adapted,method['windowsAdapters'],inverse=True)==block
  assert method['name'] not in methods;methods[method['name']]=adapted
 assert len(methods)==7 and sha(text.encode())==m['beforeSha256LF']
 for edit in m['edits']:assert methods[edit['method']]==edit['after']
 before=text;text=patch(text,m['edits'])
 assert sha(text.encode())==m['afterSha256LF'] and patch(text,m['edits'],inverse=True)==before
 proof=dict(schemaVersion=1,upstreamCommit=COMMIT,sourceManifest=MANIFEST_SHA,originalVm=source,
  originalMethodAdapters=m['originalMethods'],inverseEdits=m['edits'],beforeSha256LF=m['beforeSha256LF'],afterSha256LF=m['afterSha256LF'],fullRuntimeVmUpgrade=False)
 wide(Path(output)/'original-v035-autoplay-continuation-inverse.json').write_bytes((json.dumps(proof,ensure_ascii=False,indent=2)+'\n').encode())
 return text,proof
