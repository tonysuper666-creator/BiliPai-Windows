"""Whole original NextWatch card, bounded platform adaptation, sole full-controls producer."""
from pathlib import Path
import hashlib,json,os
COMMIT="a69d4181a073aa3207def3fca43318dcf7ab2c70"
MANIFEST_SHA="dfd799f017c955faf0ccca24c177d3d84f000866e8556caa4cbbf8865f2fc236"
def sha(raw):return hashlib.sha256(raw).hexdigest()
def wide(path):
 path=Path(path).resolve();prefix=chr(92)*2+'?'+chr(92)
 return Path(prefix+str(path)) if os.name=='nt' and not str(path).startswith(prefix) else path
def emit_original_ui(repo,output):
 root=Path(repo)/'desktop/upstream-slices/v034-next-watch-ui'
 raw=wide(root/'manifest.json').read_bytes();assert sha(raw)==MANIFEST_SHA
 m=json.loads(raw);assert m['upstreamCommit']==COMMIT
 originals={}
 for row in m['sources']:
  b=wide(root/row['file']).read_bytes()
  assert len(b)==row['bytes'] and sha(b)==row['sha256']
  assert hashlib.sha1(('blob '+str(len(b))+'\0').encode()+b).hexdigest()==row['gitBlob']
  originals[row['file']]=b.decode('utf-8')
 placement=m['originalPlacement'];t=originals[placement['source']]
 assert t[placement['offset']:placement['offset']+len(placement['text'])]==placement['text']
 assert sha(placement['text'].encode())==placement['sha256']
 for path,recipe in m['outputs'].items():
  source=originals[recipe['source']]
  if 'edits' in recipe:
   assert sha(source.encode())==recipe['beforeSha256'];text=source
   for e in recipe['edits']:
    i=e['offset'];assert text[i:i+len(e['before'])]==e['before']
    text=text[:i]+e['after']+text[i+len(e['before']):]
   inverse=text
   for e in reversed(recipe['edits']):
    i=e['offset'];assert inverse[i:i+len(e['after'])]==e['after']
    inverse=inverse[:i]+e['before']+inverse[i+len(e['after']):]
   assert inverse==source
  else:
   rows=recipe['originalDeclarationRanges']
   for row in rows:assert source[row['offset']:row['offset']+len(row['text'])]==row['text']
   body=rows[0]['text']+'\n'+rows[1]['text'];assert body==recipe['originalDeclarations']
   for e in recipe['declarationAdapters']:
    assert body.count(e['before'])==e['count'];body=body.replace(e['before'],e['after'])
   text=recipe['output'];assert text.count(body)==1
   inverse=body
   for e in reversed(recipe['declarationAdapters']):
    assert inverse.count(e['after'])==e['count'];inverse=inverse.replace(e['after'],e['before'])
   assert inverse==recipe['originalDeclarations']
  assert sha(text.encode())==recipe['afterSha256']
  p=wide(Path(output)/path);p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(text.encode())
 proof={'schemaVersion':1,'upstreamCommit':COMMIT,'sourceManifestSha256':MANIFEST_SHA,
  'sources':m['sources'],'outputs':m['outputs'],'originalPlacement':placement,'wholeOriginalInverse':True}
 wide(Path(output)/'original-v034-next-watch-ui-inverse.json').write_bytes((json.dumps(proof,ensure_ascii=False,indent=2)+'\n').encode())
