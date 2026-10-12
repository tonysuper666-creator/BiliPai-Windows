"""Whole stable NextWatch source + exact original VM method adapter. No new queue/player."""
from pathlib import Path
import hashlib,json,os
COMMIT="a69d4181a073aa3207def3fca43318dcf7ab2c70"
MANIFEST_SHA="d428fe76e065c9f85c80d9e8c14157b6243e91bfc5b35152cccaa56ca41b4a72"
def wide(p):
 p=Path(p).resolve()
 prefix=chr(92)*2+'?'+chr(92)
 return Path(prefix+str(p)) if os.name=='nt' and not str(p).startswith(prefix) else p
def sha(raw):return hashlib.sha256(raw).hexdigest()
def apply_and_record(path,text,output):
 if path!='com/android/purebilibili/feature/video/viewmodel/VideoPlaybackViewModel.kt':return text,None
 root=Path(__file__).resolve().parents[1]/'upstream-slices'/'v034-next-watch'
 raw=wide(root/'manifest.json').read_bytes();assert sha(raw)==MANIFEST_SHA
 m=json.loads(raw);assert m['upstreamCommit']==COMMIT
 source=m['sources'][0];original=wide(root/source['file']).read_bytes()
 assert len(original)==source['bytes'] and sha(original)==source['sha256']
 assert hashlib.sha1(('blob '+str(len(original))+'\0').encode()+original).hexdigest()==source['gitBlob']
 vm=m['originalVmReference'];raw=wide(Path(__file__).resolve().parents[1]/vm['path'].removeprefix('desktop/')).read_bytes()
 assert len(raw)==vm['bytes'] and sha(raw)==vm['sha256']
 assert hashlib.sha1(('blob '+str(len(raw))+'\0').encode()+raw).hexdigest()==vm['gitBlob']
 block=m['originalVmMethodBlock'];originalMethods=raw.decode()[block['offset']:block['offset']+len(block['text'])]
 assert originalMethods==block['text'] and sha(originalMethods.encode())==block['sha256LF']
 adapted=originalMethods
 for edit in m['methodAdapters']:
  assert adapted.count(edit['before'])==edit['count'];adapted=adapted.replace(edit['before'],edit['after'])
 recipe=m['outputs'][path];before=text;assert sha(text.encode())==recipe['beforeSha256LF']
 for edit in recipe['edits']:
  i=edit['offset'];assert text[i:i+len(edit['before'])]==edit['before']
  assert edit['after']==adapted+edit['before'];text=text[:i]+edit['after']+text[i+len(edit['before']):]
 assert sha(text.encode())==recipe['afterSha256LF'];inverse=text
 for edit in reversed(recipe['edits']):
  i=edit['offset'];assert inverse[i:i+len(edit['after'])]==edit['after'];inverse=inverse[:i]+edit['before']+inverse[i+len(edit['after']):]
 assert inverse==before
 policyPath='com/android/purebilibili/feature/video/playback/next/NextWatchSuggestion.kt'
 target=wide(Path(output)/policyPath);target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(original)
 proof=dict(schemaVersion=1,upstreamCommit=COMMIT,sourceManifest=MANIFEST_SHA,originalSource=source,originalVmReference=vm,
  originalVmMethodBlockSha256LF=block['sha256LF'],methodAdapters=m['methodAdapters'],inverseEdits=recipe['edits'],
  beforeSha256LF=recipe['beforeSha256LF'],afterSha256LF=recipe['afterSha256LF'],directPolicyPath=policyPath,directPolicySha256=source['sha256'])
 wide(Path(output)/'original-v034-next-watch-inverse.json').write_bytes((json.dumps(proof,ensure_ascii=False,indent=2)+'\n').encode())
 return text,proof
