"""Current original physical-page paging gates on the existing list producer."""
from pathlib import Path
import hashlib,json,re
from v044_favorite_detail import fixed_sources,inverse_proof,COMMIT
from v044_favorite_subscription import parser,span,need

ARCHIVE='desktop/upstream-slices/v044-list-foreground'
MANIFEST_SHA256='bd892bfffc777d1ca57931d0bfffd4a7c43ec76877cbdb876dd0f6ea4af725e0'
BEFORE_UI_SHA256='a22a30218e8cb9e8632a9a69430987b28b48768a7ecfaf74f45a99084bcc25b0'
TEST='CommonListPaginationPolicyTest.kt'
def sha(value):return hashlib.sha256(value.encode('utf8') if isinstance(value,str) else value).hexdigest()

def fixed_test(repo):
 root=Path(repo)/ARCHIVE
 need(root.is_dir() and not root.is_symlink(),'Missing original list pagination tests')
 need({p.name for p in root.iterdir()}=={TEST,'manifest.json'},'Original list pagination test leaves changed')
 raw=(root/'manifest.json').read_bytes();need(sha(raw)==MANIFEST_SHA256,'Original list pagination test manifest changed')
 manifest=json.loads(raw)
 need((manifest['repository'],manifest['tag'],manifest['commit'],manifest['hashNormalization'])==
      ('jay3-yy/BiliPai','v0.4.4',COMMIT,'raw'),'Original list pagination test identity changed')
 need(len(manifest['files'])==1,'Original list pagination test inventory changed')
 row=manifest['files'][0];file=root/TEST
 need(file.is_file() and not file.is_symlink() and row['filename']==TEST and Path(row['path']).name==TEST,'Original list pagination test path changed')
 data=file.read_bytes()
 need(len(data)==row['bytes'] and sha(data)==row['sha256'],'Original list pagination test bytes changed')
 need(hashlib.sha1(b'blob '+str(len(data)).encode()+b'\0'+data).hexdigest()==row['gitBlob'],'Original list pagination test Git blob changed')
 return data.decode('utf8')

def validate_outputs(repo,*outputs):
 fixed_test(repo)
 root=(Path(repo)/ARCHIVE).resolve()
 for output in outputs:
  if output is None:continue
  target=Path(output).resolve()
  need(not(target==root or target.is_relative_to(root) or root.is_relative_to(target)),
       'List pagination output overlaps archived originals')

def advance_ui(repo,before):
 need(sha(before)==BEFORE_UI_SHA256,'Physical list foreground predecessor changed')
 original=fixed_sources(repo)[0]['CommonListScreen.kt'];syntax=parser(repo)
 a,b=span(before,'fun','CommonListContent',syntax);x,y=span(original,'fun','CommonListContent',syntax)
 old_part=before[a:b];part=old_part;raw=original[x:y];segments=[]
 def imported(text,label):
  need(original.count(text)>=1,'Original foreground segment changed: '+label)
  segments.append(dict(label=label,originalOffset=original.index(text),originalText=text,originalSHA256=sha(text)))
 def replace(old,new):
  nonlocal part
  need(part.count(old)==1,'Physical list foreground seam changed: '+old)
  part=part.replace(old,new,1)
 parameter='    allowAutomaticLoadMore: Boolean = true,\n'
 need(raw.count(parameter)==1 and parameter not in old_part,'Original foreground parameter changed')
 anchor='    items: List<com.android.purebilibili.data.model.response.VideoItem>,\n'
 replace(anchor,anchor+parameter);imported(parameter,'whole original physical-foreground parameter')
 start='        LaunchedEffect(\n            searchPaginationFallbackEnabled,'
 end='\n\n        if (filteredItems.isEmpty() && searchQuery.isNotEmpty())'
 p=part.index(start);q=part.index(end,p);r=raw.index(start);s=raw.index(end,r)
 old_effect=part[p:q];effect=raw[r:s]
 need(effect.replace('            allowAutomaticLoadMore,\n','').replace('allowAutomaticLoadMore && searchPaginationFallbackEnabled','searchPaginationFallbackEnabled')==old_effect,
      'Search fallback contains unrelated platform changes')
 replace(old_effect,effect);imported(effect,'whole original search-fallback effect and physical-page gate')
 start='            LaunchedEffect(shouldLoadMore.value,';end='\n\n            LazyVerticalGrid('
 p=part.index(start);q=part.index(end,p);r=raw.index(start);s=raw.index(end,r)
 old_effect=part[p:q];effect=raw[r:s]
 need(effect.replace(', allowAutomaticLoadMore)',')').replace('allowAutomaticLoadMore && ','')==old_effect,
      'Scroll paging contains unrelated platform changes')
 replace(old_effect,effect);imported(effect,'whole original scroll effect and physical-page gate')
 prefix=before[:a];original_prefix=original[:x]
 pattern=r'(?m)^([ ]*)CommonListContent\(\n'
 calls=list(re.finditer(pattern,prefix));original_calls=list(re.finditer(pattern,original_prefix))
 need(len(calls)==len(original_calls)==3,'Physical list caller count changed')
 for match in reversed(calls):
  addition=match[1]+'    allowAutomaticLoadMore = isCurrentPage,\n'
  need(addition in original_prefix,'Original physical-page caller argument changed')
  imported(addition,'whole original current-page argument at actual list caller')
  prefix=prefix[:match.end()]+addition+prefix[match.end():]
 body=prefix+part+before[b:]
 need(body.count('onFirstReadAuthenticationRequired')==before.count('onFirstReadAuthenticationRequired'),
      'Root first-read authentication caller changed')
 return body,dict(upstreamCommit=COMMIT,existingSoleProducer=True,actualOriginalCallers=3,wholeOriginalPagingEffects=2,
  originalForegroundParameter=True,physicalForegroundOwnsAutomaticPaging=True,manualRetryAndRootAdmissionUnchanged=True,
  originalSegments=segments,retainedToCurrent=inverse_proof(before,body))

def restore_predecessor(body,proof):
 proof=proof['retainedToCurrent'];need(sha(body)==proof['afterSHA256'],'Physical list output differs from its receipt')
 lines=body.splitlines(keepends=True)
 for hunk in reversed(proof['hunks']):
  first,last=hunk['afterLines'];need(''.join(lines[first:last])==hunk['after'],'Physical list inverse span changed')
  lines[first:last]=hunk['before'].splitlines(keepends=True)
 restored=''.join(lines);need(sha(restored)==proof['beforeSHA256'],'Physical list predecessor inverse changed')
 return restored

def emit_tests(repo,output):
 validate_outputs(repo,output);body=fixed_test(repo);need(body.count('@Test')==4,'Original list pagination test count changed')
 target=Path(output)/'com/android/purebilibili/feature/list'/TEST
 target.parent.mkdir(parents=True,exist_ok=True);target.write_text(body,encoding='utf8',newline='\n')
 return dict(upstreamCommit=COMMIT,sourceManifestSHA256=MANIFEST_SHA256,wholeOriginalSuite=True,casesSelected=4,testsExecuted=False)
