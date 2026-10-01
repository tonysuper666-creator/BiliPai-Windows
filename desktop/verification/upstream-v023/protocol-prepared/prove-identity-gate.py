from pathlib import Path
import hashlib, importlib.util, json, subprocess, sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent
REPO=HERE.parents[2]
EXT=chr(92)*2+'?'+chr(92)
def safe(p):
 v=str(Path(p).absolute());return Path(v if v.startswith(EXT) else EXT+v)
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def write(p,s):safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(s,encoding='utf-8',newline='\n')
def dump(p,v):write(p,json.dumps(v,indent=2)+'\n')
spec=importlib.util.spec_from_file_location('stable_identity_gate',HERE/'prepared/desktop/tools/extract-upstream-dynamic-reply-protocol.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
path='app/src/main/java/com/android/purebilibili/data/repository/CommentRepository.kt'
shadow=HERE/'identity-shadow';assert not shadow.exists()
gitdir=subprocess.check_output(['git','-c','core.longpaths=true','rev-parse','--absolute-git-dir'],cwd=REPO,text=True).strip()
# This fixture-only .git locator permits read-only rev-parse/hash-object against
# existing fixed blobs. No worktree registration, config, index or -w command.
write(shadow/'.git','gitdir: '+gitdir+'\n')
manifest=json.loads(read(REPO/'desktop/upstream-sources.json'))
write(shadow/'desktop/upstream-sources.json',json.dumps(manifest)+'\n')
original=read(REPO/path);write(shadow/path,original)
sources,ids=module.load_pinned_sources(shadow,[path]);assert sources[path]==original and ids[0]['matchesPinnedCommit']
checks=[{'case':'exact stable manifest and Git blob accepted','passed':True}]
changed=original+'\n// Declared task-only altered local source.\n';write(shadow/path,changed)
try:module.load_pinned_sources(shadow,[path]);raise RuntimeError('identity gate accepted local modification')
except AssertionError as failure:
 assert 'differs from fixed source manifest' in str(failure)
 checks.append({'case':'modified local source rejected by manifest LF SHA','passed':True})
for row in manifest['sources']:
 if row['path']==path:row['sha256']=hashlib.sha256(changed.encode()).hexdigest()
write(shadow/'desktop/upstream-sources.json',json.dumps(manifest)+'\n')
try:module.load_pinned_sources(shadow,[path]);raise RuntimeError('identity gate accepted rewritten manifest')
except AssertionError as failure:
 assert 'differs from fixed Git commit blob' in str(failure)
 checks.append({'case':'modified source plus matching altered manifest rejected by fixed Git blob','passed':True})
dump(HERE/'identity-gate-proof.json',{'passed':True,'cases':checks,'MainFilesChanged':False,'GitObjectWrites':False,
 'GitIndexWrites':False,'shadowFixtureOnly':True,'fixedCommit':ids[0]['pinnedCommit'],'pinnedBlob':ids[0]['pinnedGitBlob']})
print('PASS three source identity gate cases; task-only shadow, no Main/Git mutations')
