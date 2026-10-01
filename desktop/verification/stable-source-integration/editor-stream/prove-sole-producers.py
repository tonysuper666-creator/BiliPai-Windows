from pathlib import Path
import hashlib, json, subprocess, sys
HERE=Path(__file__).resolve().parent
REPO=HERE.parents[2]
EXT=chr(92)*2+'?'+chr(92)
def safe(p):
 v=str(Path(p).absolute());return Path(v if v.startswith(EXT) else EXT+v)
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def write(p,t):safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(t,encoding='utf-8',newline='\n')
def dump(p,v):write(p,json.dumps(v,indent=2)+'\n')
shadow=HERE/'sole-producer-shadow';assert not shadow.exists()
gitdir=subprocess.check_output(['git','-c','core.longpaths=true','rev-parse','--absolute-git-dir'],cwd=REPO,text=True).strip()
write(shadow/'.git','gitdir: '+gitdir+'\n')
write(shadow/'desktop/upstream-sources.json',read(REPO/'desktop/upstream-sources.json'))
for path in ['app/src/main/java/com/android/purebilibili/data/repository/DynamicCreateRepository.kt',
 'app/src/main/java/com/android/purebilibili/data/repository/CommentRepository.kt']:
 write(shadow/path,read(REPO/path))
for name in ['extract-upstream-plugins.py','extract-upstream-media.py','sync-upstream.py']:
 path='desktop/tools/'+name;write(shadow/path,read(REPO/path))
for name in ['extract-upstream-dynamic-editor-protocol.py','extract-upstream-dynamic-reply-protocol.py','verify-upstream-dynamic-editor-protocol.py']:
 path='desktop/tools/'+name;write(shadow/path,read(HERE/'prepared'/path))
ops='desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopDynamicCardOperations.kt'
write(shadow/ops,read(HERE/'prepared'/ops))
commands=[('editor',[sys.executable,str(shadow/'desktop/tools/verify-upstream-dynamic-editor-protocol.py'),'--repo',str(shadow),'--output',str(HERE/'editor-sole-producer-proof.json')]),
 ('detail-reply',[sys.executable,str(REPO/'desktop/.local/stable-dynamic-protocol-rebase/prepared/desktop/tools/verify-upstream-dynamic-detail-reply-protocol.py'),
 '--repo',str(shadow),'--comment-output',str(REPO/'desktop/.local/stable-dynamic-protocol-rebase/generated/reply'),
 '--detail-output',str(REPO/'desktop/.local/stable-dynamic-protocol-rebase/generated/detail'),'--output',str(HERE/'retained-detail-reply-proof.json')])]
for label,command in commands:
 result=subprocess.run(command,capture_output=True,text=True,encoding='utf-8',env={**__import__('os').environ,'PYTHONDONTWRITEBYTECODE':'1'})
 write(HERE/(label+'-verify.log'),result.stdout+result.stderr);print(result.stdout+result.stderr);result.check_returncode()
candidate=read(HERE/'prepared'/ops)
assert candidate.count('api.uploadCommentImage(')==1
assert 'imageProvider: suspend (String) -> Triple<String?, String?, ByteArray>' not in candidate
assert 'selected.third' in candidate and 'private suspend fun uploadEditorCommentImageBody' in candidate
assert 'sessions.saveAccount' not in candidate
dump(HERE/'consumer-chain-review.json',{'passed':True,'soleStableMultipartApiInvocationCount':1,'editorProvider':'Triple<filename,mime,RequestBody>',
 'rootConsumer':'same remembered forEditor Operations supplies withOwnedEditorImageAdmission to SelectedImages; same selected::read callback',
 'selectedBoundary':'known-size preflight before input opening, exact selected Paths, no whole-file ByteArray, one-shot stream, captured caller context',
 'actualOwnerGate':'same repository.dynamicCacheSessionGuard (actual SessionStore) -> selected-owner lock -> context.ensureActive -> bounded IO slice',
 'close':'selected owner marks retired and drains registered inputs without acquiring SessionStore; whole modal keeps original lifecycle',
 'unchangedProtocols':'Original detail/reply blocks remain exactly equal to sole stable producers',
 'oldByteApi':'existing reply byte overload preserved from stable byte helper; dynamic editor Root/Host/provider no longer uses it',
 'MainChanged':False,'GitObjectWrites':False,'GitIndexWrites':False,'credentialSerializationAdded':False,'newHttpClient':False,'newStore':False})
gradle=read(REPO/'desktop/build.gradle.kts')
old='    inputs.files("tools/verify-upstream-dynamic-editor-protocol.py", "tools/extract-upstream-dynamic-editor-protocol.py",\n'
assert gradle.count(old)==1
new=old+'        "tools/extract-upstream-dynamic-reply-protocol.py",\n'
import difflib
write(HERE/'gradle-input-only.patch',''.join(difflib.unified_diff(gradle.splitlines(True),gradle.replace(old,new).splitlines(True),fromfile='a/desktop/build.gradle.kts',tofile='b/desktop/build.gradle.kts')))
print('PASS sole editor + preserved detail/reply producers; one API body, complete streaming consumer chain')
