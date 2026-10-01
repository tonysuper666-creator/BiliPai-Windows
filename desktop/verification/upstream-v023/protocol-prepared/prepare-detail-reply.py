from pathlib import Path
import difflib, hashlib, importlib.util, json, os, subprocess, sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent
REPO=HERE.parents[2]
EXT=chr(92)*2+'?'+chr(92)
def safe(p):
 v=str(Path(p).absolute());return Path(v if v.startswith(EXT) else EXT+v)
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def write(p,s):safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(s,encoding='utf-8',newline='\n')
def dump(p,v):write(p,json.dumps(v,indent=2,ensure_ascii=False)+'\n')
def digest(s):return hashlib.sha256(s.encode()).hexdigest()
def module(n,p):
 spec=importlib.util.spec_from_file_location(n,p);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
def replace(s,a,b):assert s.count(a)==1,a;return s.replace(a,b)
shared='''def load_pinned_sources(repo: Path, paths):
    """One fixed manifest commit plus Git blobs; never silently accept a local edit."""
    def normalized(path):
        value = str(path.absolute())
        safe = Path(value if value.startswith('\\\\\\\\?\\\\') else '\\\\\\\\?\\\\' + value)
        return safe.read_text(encoding='utf-8').replace('\\r\\n', '\\n').replace('\\r', '\\n')
    manifest = json.loads(normalized(repo / 'desktop/upstream-sources.json'))
    commit = manifest['upstreamCommit']
    assert re.fullmatch(r'[0-9a-f]{40}', commit), 'upstreamCommit must be a full fixed commit'
    assert manifest['hashNormalization'] == 'lf'
    assert manifest['upstreamRepository'] == 'jay3-yy/BiliPai'
    pins = {}
    for row in manifest['sources']:
        assert row['path'] not in pins, 'duplicate source identity'
        pins[row['path']] = row['sha256']
    sources = {}; identities = []
    for path in paths:
        selected = repo / path
        assert not selected.is_symlink() and selected.resolve().is_relative_to(repo.resolve()), path
        text = normalized(selected)
        actual_sha = hashlib.sha256(text.encode('utf-8')).hexdigest()
        assert actual_sha == pins[path], path + ' differs from fixed source manifest'
        blob = subprocess.check_output(['git', '-c', 'core.longpaths=true', 'rev-parse', commit + ':' + path], cwd=repo, text=True).strip()
        current = subprocess.check_output(['git', '-c', 'core.longpaths=true', 'hash-object', '--path=' + path, path], cwd=repo, text=True).strip()
        assert blob == current, path + ' differs from fixed Git commit blob'
        sources[path] = text
        identities.append(dict(path=path, pinnedCommit=commit, pinnedTag=manifest['upstreamTag'],
            pinnedGitBlob=blob, currentGitBlob=current, sha256LfUtf8=actual_sha, matchesPinnedCommit=True))
    return sources, identities

'''
rows=[];patches=[]
names=['extract-upstream-dynamic-reply-protocol.py','extract-upstream-dynamic-detail-protocol.py',
       'verify-upstream-dynamic-detail-reply-protocol.py']
for name in names:
 path='desktop/tools/'+name;old=read(REPO/path);new=old
 if name.startswith('extract-upstream-dynamic-reply'):
  new=replace(new,"TAG = 'v0.2.3-alpha.9'\n",shared)
  start=new.index('    sources = {}\n    source_ids = []\n');stop=new.index('    repo_path = paths[0]',start)
  new=new[:start]+'    sources, source_ids = load_pinned_sources(ROOT, paths)\n'+new[stop:]
  new=replace(new,"    return sorted(HERE.rglob('generated/**/*.kt'))", "    write(HERE / 'source-identity.json', json.dumps(source_ids, indent=2) + '\\n')\n    write(HERE / 'selected-source-bodies.json', json.dumps(records, indent=2) + '\\n')\n    return sorted(HERE.rglob('generated/**/*.kt'))")
 elif name.startswith('extract-upstream-dynamic-detail'):
  new=replace(new,"TAG = 'v0.2.3-alpha.9'\n",'')
  start=new.index('    sources = {}\n    ids = []\n');stop=new.index('    def adapt(block)',start)
  new=new[:start]+'    sources, ids = protocol.load_pinned_sources(ROOT, paths)\n\n'+new[stop:]
  new=replace(new,"    return sorted(HERE.rglob('generated/**/*.kt'))", "    write(HERE / 'source-identity.json', json.dumps(ids, indent=2) + '\\n')\n    write(HERE / 'selected-source-bodies.json', json.dumps(records, indent=2) + '\\n')\n    return sorted(HERE.rglob('generated/**/*.kt'))")
 # The verifier compares sole generated fragments byte-for-byte and already has
 # no static alpha pin. Keep its bytes unchanged; do not weaken its assertion.
 write(HERE/'original-tools'/name,old);write(HERE/'prepared'/path,new)
 if new!=old:patches+=list(difflib.unified_diff(old.splitlines(True),new.splitlines(True),fromfile='a/'+path,tofile='b/'+path))
 rows.append({'path':path,'baseLfSha256':digest(old),'candidateLfSha256':digest(new),'changed':old!=new})
write(HERE/'detail-reply.patch',''.join(patches))
candidate=HERE/'prepared/desktop/tools'
reply=module('prepared_stable_reply',candidate/'extract-upstream-dynamic-reply-protocol.py')
detail=module('prepared_stable_detail',candidate/'extract-upstream-dynamic-detail-protocol.py')
reply_out=HERE/'generated/reply';detail_out=HERE/'generated/detail'
reply_files=reply.generate(REPO,reply_out);detail_files=detail.generate(REPO,detail_out)
verify=subprocess.run([sys.executable,str(candidate/'verify-upstream-dynamic-detail-reply-protocol.py'),'--repo',str(REPO),
 '--comment-output',str(reply_out),'--detail-output',str(detail_out),'--output',str(HERE/'detail-reply-members-verification.json')],capture_output=True,text=True,encoding='utf-8')
write(HERE/'verify-detail-reply.log',verify.stdout+verify.stderr);verify.check_returncode()
method_delta=json.loads(read(HERE/'method-token-original-diff.json'))
selected=[]
for owner,out in [('reply',reply_out),('detail',detail_out)]:
 for record in json.loads(read(out/'selected-source-bodies.json')):
  key=record['name']+'#1'
  matched=[r for r in method_delta['methods'] if r['path']==record['source'] and r['method']==key]
  assert len(matched)==1,(record,matched)
  selected.append({'producer':owner,'path':record['source'],'function':record['name'],'tokenStatus':matched[0]['status'],
    'alpha9LfSha256':matched[0]['alpha9']['sha256Lf'],'stableLfSha256':matched[0]['stable']['sha256Lf'],
    'generatedSelectedSha256Lf':record['sha256LfUtf8']})
dump(HERE/'detail-reply-source-review.json',{'scope':'detail/reply only, no editor upload completeness claim','sourceCandidates':rows,
 'selectedBodyCount':len(selected),'selectedBodies':selected,
 'fullCopiedPolicies':['DynamicDetailFallbackPolicy (unchanged)','CommentReadAccessPolicy (two stable behavior changes)'],
 'fullCopiedGrpc':'Stable CommentGrpcRepository includes parseMainListReply field23 + added parseVoteCard and exact ReplyVoteCard model references',
 'modelOwner':'ResponseModels.kt pinned/imported by Root; no second model producer',
 'detailContextSources':'ApiClient selected detail endpoints unchanged; DynamicDetailScreen changed outside these protocol bodies and remains other UI producer responsibility',
 'soleOperationsFragments':'Existing Operations comment/detail blocks already exactly equal to stable generated fragments; verifier passed without Operations edits',
 'generatedKotlinFiles':len(reply_files)+len(detail_files),'MainChanged':False,'sharedGradle':False,'newDependencies':False})
dump(HERE/'detail-reply-handoff.json',{'scope':'prepared stable detail/reply producer rebase only','sourceCandidates':rows,
 'stableCommit':'3d5d19a2f994daccd0e2f8b5f522b6d82f43d589','sourceManifestSha256Lf':digest(read(REPO/'desktop/upstream-sources.json')),
 'generatorPythonPassed':True,'soleOperationsFragmentVerifierPassed':True,'MainChanged':False,'MainKotlinCompiled':False,
 'editorUploadStillSeparate':True,'generatedKotlinFiles':len(reply_files)+len(detail_files),'selectedBodyCount':len(selected),
 'changedSelectedBodies':[{k:r[k] for k in ['producer','path','function','tokenStatus']} for r in selected if r['tokenStatus']!='unchanged']})
print(json.dumps({'candidateFiles':rows,'selectedBodyCount':len(selected),'changedSelectedBodies':[r['function'] for r in selected if r['tokenStatus']!='unchanged'],
 'generatedKotlinFiles':len(reply_files)+len(detail_files),'soleOpsVerifierPassed':True},indent=2))
