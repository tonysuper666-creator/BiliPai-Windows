from pathlib import Path
import importlib.util, subprocess, sys
sys.dont_write_bytecode=True
exec((Path(__file__).resolve().parent/'story.py').read_text(encoding='utf8'))

vm_edits=[
 ('class StoryViewModel(application: Application) : AndroidViewModel(application) {',
  'internal class StoryViewModel(private val environment: com.bilipai.desktop.ui.DesktopOriginalStoryEnvironment) {\n    private val viewModelScope get() = environment.scope\n    private val VideoRepository get() = environment.requests\n    private fun <T> MutableStateFlow(initial:T): MutableStateFlow<T> =\n        com.bilipai.desktop.ui.DesktopHomeOwnedMutableStateFlow(initial, environment::commit)'),
 ('val result = VideoRepository.getHomeVideos(', 'environment.assertCurrent()\n            val result = VideoRepository.getHomeVideos('),
 ('            result.onSuccess', '            environment.assertCurrent()\n            result.onSuccess'),
]
functions='\ndef adapt_story_root_vm(source):\n body=source[source.index("class StoryViewModel("):]\n'
for before,after in vm_edits:
 functions+=' before='+repr(before)+'\n after='+repr(after)+'\n assert body.count(before)=='+str(original(vmpath)[start:].count(before))+'\n body=body.replace(before,after)\n'
functions+=' return body\n\ndef adapt_story_root_screen(source):\n'
for before,after in edits:
 functions+=' before='+repr(before)+'\n after='+repr(after)+'\n assert source.count(before)==1\n source=source.replace(before,after)\n'
functions+=' return source\n\n'
prodrel='desktop/tools/extract-upstream-story-topic.py'
prod=read(C/prodrel)
old='''            body = "package com.android.purebilibili.feature.story\\nimport com.android.purebilibili.data.model.response.StoryItem\\n\\n" + media.data_class(source, "StoryUiState", parser)
'''
new='''            body = "package com.android.purebilibili.feature.story\\nimport com.android.purebilibili.data.model.response.StoryItem\\nimport android.util.Log as Logger\\nimport kotlinx.coroutines.flow.*\\nimport kotlinx.coroutines.launch\\n\\n" + media.data_class(source, "StoryUiState", parser) + "\\n\\n" + adapt_story_root_vm(source)
'''
change(prodrel,[
 ('sole-story-adaptation', 'def generate(', functions+'def generate('),
 ('new-original-screen-selection','EXTRACT = [BASE + "feature/story/StoryViewModel.kt", BASE + "feature/search/TopicDetailViewModel.kt",','EXTRACT = [BASE + "feature/story/StoryViewModel.kt", BASE + "feature/story/StoryScreen.kt", BASE + "feature/search/TopicDetailViewModel.kt",'),
 ('complete-original-feed-owner',old,new),
 ('complete-original-screen','        elif path.endswith("TopicDetailViewModel.kt"):\n','        elif path.endswith("StoryScreen.kt"):\n            body = adapt_story_root_screen(source)\n            name = "StoryScreen.kt"\n        elif path.endswith("TopicDetailViewModel.kt"):\n'),
])
write(P/'exact-hunks.json',json.dumps(H,ensure_ascii=False,indent=2)+'\n')
# Installer only touches sole producers/manual sources, never generated previews.
installed_hunks=[h for h in H if '/build/generated/' not in h['path']]
write(P/'install-hunks.json',json.dumps(installed_hunks,ensure_ascii=False,indent=2)+'\n')
registry=json.loads(read(C/'desktop/upstream-sources.json'))
rows=[]
for path in (uipath,vmpath):
 actual=[r for r in registry['sources'] if r['path']==path]
 row=dict(path=path,sha256=sha(read(C/path)),features=['story-topic','story-pager-root'],mode=actual[0]['mode'] if actual else 'policy-extract',existing=bool(actual))
 if actual: assert actual[0]['sha256']==row['sha256']
 rows.append(row)
write(P/'registry-delta.json',json.dumps(rows,ensure_ascii=False,indent=2)+'\n')

# Real producer regeneration, with explicit prospective producer files only.
audit=[]
for kind,rel in [('story',prodrel),('vm','desktop/tools/extract-upstream-video-full-owner.py'),('pager','desktop/tools/extract-upstream-video-fullscreen-pager.py')]:
 spec=importlib.util.spec_from_file_location('prospective_'+kind,wide(P/'prepared/existing'/rel))
 mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod)
 out=wide(P/'producer-replay'/kind); out.mkdir(parents=True,exist_ok=True)
 mod.generate(C,out)
 if kind=='story':
  replay=read(out/'com/android/purebilibili/feature/story/StoryScreen.kt').split('\n',2)[2]
  assert replay==story
  state=read(out/'com/android/purebilibili/feature/story/StoryUiState.kt')
  assert storyvm[storyvm.index('internal class StoryViewModel('):] in state
  inversed=replay
  for b,a in reversed(edits):
   if a: assert inversed.count(a)==1; inversed=inversed.replace(a,b)
  removed_imports=''.join(b for b,a in edits if not a and b.startswith('import '))
  import_anchor='import com.android.purebilibili.feature.video.ui.pager.PortraitVideoPager\n'
  assert inversed.count(import_anchor)==1
  inversed=inversed.replace(import_anchor,removed_imports+import_anchor)
  inversed=inversed.replace('@Composable\nfun StoryScreen(', '@UnstableApi\n@Composable\nfun StoryScreen(',1)
  assert inversed==read(C/uipath)
  body=state[state.index('internal class StoryViewModel('):]
  for b,a in reversed(vm_edits): assert body.count(a)==original(vmpath)[start:].count(b); body=body.replace(a,b)
  assert body==read(C/vmpath)[start:]
  changed=['com/android/purebilibili/feature/story/StoryUiState.kt','com/android/purebilibili/feature/story/StoryScreen.kt']
 else:
  target=vmrel if kind=='vm' else pager
  # Existing producer manifest uses generated output paths; one changed body only.
  target=target.split('/com/',1)[1]
  output='com/'+target
  assert read(out/output)==read(P/'prepared/existing'/ (vmrel if kind=='vm' else pager))
  changed=[output]
 actual_root=C/('desktop/build/generated/story-topic' if kind=='story' else 'desktop/build/generated/original-video-full-owner' if kind=='vm' else 'desktop/build/generated/original-video-fullscreen-pager')
 for f in out.rglob('*.kt'):
  relative=str(f.relative_to(out)).replace('\\','/')
  if relative not in changed:
   assert read(actual_root/relative)==read(f),(kind,relative,'unexpected producer output change')
 audit.append(dict(producer=rel,outputs=[dict(path=str(f.relative_to(out)),sha256LF=sha(read(f)),changed=str(f.relative_to(out)).replace('\\','/') in changed) for f in out.rglob('*.kt')]))
write(P/'source-producer-proof.json',json.dumps(dict(passed=True,originalScreenInverse=True,completeOriginalVmClassInverse=True,actualTag=registry['upstreamCommit'],productionWrites=0,producers=audit),ensure_ascii=False,indent=2)+'\n')
print('Sole producer replay/inverse PASS; install',len(installed_hunks),'local edits, two required manual sources, 1 new identity.')
