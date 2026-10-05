"""Full stable Home VM source body with explicit pinned Windows platform edits.
Production skips the three DIRECT pure sources copied by prepareUpstreamSources.
No DTO, HTTP graph, item authority or second recommendation planner is generated.
"""
from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import argparse,hashlib,json,importlib.util
from v029_home_load import home_vm_delta, sources as home_load_sources

SOURCE_PINS={'app/src/main/java/com/android/purebilibili/feature/home/HomeViewModel.kt': 'acfeb63539acaf710a4070f59220a1585a38fd1e034d6a7a56a82970998d4352', 'app/src/main/java/com/android/purebilibili/core/util/EasterEggs.kt': '1a4758cc65806db893da7942433a4ba44fd2b91c1396bf1653dbdcd59dc6827f', 'app/src/main/java/com/android/purebilibili/feature/home/TodayWatchRefreshPolicy.kt': '14aed865e65aa2a8f79db3760abd9931b36c5b719676eb577241601cd90be454', 'app/src/main/java/com/android/purebilibili/feature/home/HomeFollowFeedMappingPolicy.kt': '45dc3e2eb12e82a5dc2d0fec4bf82a03154444fd4519f6b2256c98f19ab2d630', 'app/src/main/java/com/android/purebilibili/feature/plugin/BiliPaiFeedFilterPlugin.kt': '10c8ec1f6ae455fc6e488908c948a8e424080faa3c772788194dcdd3bd2adff3', 'app/src/main/java/com/android/purebilibili/feature/message/MessageCenterPolicy.kt': 'b341edc17ccbe4d802341635279bc98e7cd308c067d96ab2f4e549b175703605'}
BASE='app/src/main/java/com/android/purebilibili/'
DIRECT=[BASE+'core/util/EasterEggs.kt',BASE+'feature/home/TodayWatchRefreshPolicy.kt',BASE+'feature/home/HomeFollowFeedMappingPolicy.kt']
def sha(text):return hashlib.sha256(text.encode()).hexdigest()
def safe(path):
 p=Path(path);s=str(p.absolute())
 return Path(s if s.startswith(chr(92)*2+'?'+chr(92)) else chr(92)*2+'?'+chr(92)+s)
def read(path):return safe(path).read_text(encoding='utf-8').replace('\r\n','\n')
def write(path,text):
 p=safe(path);p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf-8',newline='\n')
def load(path,name):
 spec=importlib.util.spec_from_file_location(name,path);mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod);return mod


def owned_today_watch_feedback_delta(vm):
 assert vm.count('    private fun recordTodayWatchNegativeFeedback(\n')==1
 vm=vm.replace('    private fun recordTodayWatchNegativeFeedback(\n','    private suspend fun recordTodayWatchNegativeFeedback(\n',1)
 assert vm.count('        if (!ownedCommit { TodayWatchFeedbackStore.saveSnapshot(environment.recommendationContext, snapshot) }) throw CancellationException("Home entry retired")\n')==1
 vm=vm.replace('        if (!ownedCommit { TodayWatchFeedbackStore.saveSnapshot(environment.recommendationContext, snapshot) }) throw CancellationException("Home entry retired")\n','        environment.todayWatchFeedback.saveSnapshot(snapshot)\n',1)
 assert vm.count('    private fun persistTodayWatchFeedback() {\n')==1
 vm=vm.replace('    private fun persistTodayWatchFeedback() {\n','    private suspend fun persistTodayWatchFeedback() {\n',1)
 assert vm.count('        if (!ownedCommit { TodayWatchFeedbackStore.saveSnapshot(\n            context = environment.recommendationContext,\n')==1
 vm=vm.replace('        if (!ownedCommit { TodayWatchFeedbackStore.saveSnapshot(\n            context = environment.recommendationContext,\n','        environment.todayWatchFeedback.saveSnapshot(\n',1)
 assert vm.count('        ) }) throw CancellationException("Home entry retired")\n')==1
 vm=vm.replace('        ) }) throw CancellationException("Home entry retired")\n','        )\n',1)
 return vm

def generate(repo,output,standalone=False,test_output=None):
 repo=Path(repo);output=Path(output)
 for path,expected in SOURCE_PINS.items():assert sha(read(_desktop_canonical_source(repo, path)))==expected,path
 tool=Path(__file__)
 spec=json.loads(read(tool.with_name('extract-upstream-home-viewmodel-adaptations.json')))
 source=read(_desktop_canonical_source(repo, spec['source']));assert sha(source)==spec['sha256LF'],spec['source']
 lines=source.splitlines(keepends=True)
 for op in reversed(spec['operations']):
  start=op['originalStartLine']-1;end=op['originalEndLineExclusive']-1
  assert ''.join(lines[start:end])==op['expected'],op['originalStartLine']
  lines[start:end]=op['replacement'].splitlines(keepends=True)
 vm=''.join(lines);assert sha(vm)==spec['desiredSha256LF']
 # The complete original VM is now the sole owner of this exact converter. Existing Windows
 # TodayWatch callers use the same body until Root swaps the planner bridge in one cohort.
 visibility='private fun RecommendationResult.toTodayWatchPlan'
 assert vm.count(visibility)==1
 vm=vm.replace(visibility,'internal fun RecommendationResult.toTodayWatchPlan')
 vm=owned_today_watch_feedback_delta(vm)
 home_load_audits=[]
 vm=home_vm_delta(repo,vm,home_load_audits)
 write(output/spec['target'],vm)
 write(output/'home-load-v029-adaptations.json',json.dumps(dict(upstreamCommit='a4b77f894d0a2dd26c0b9fc144b8adb88ac05480',audits=home_load_audits),ensure_ascii=False,indent=2)+'\n')
 if test_output is not None:
  original_test=home_load_sources(repo)['app/src/test/java/com/android/purebilibili/feature/home/HomeLoadSequencePolicyTest.kt']
  write(Path(test_output)/'com/android/purebilibili/feature/home/HomeLoadSequencePolicyTest.kt',original_test)
 parser=load(repo/'desktop/tools/sync-upstream.py','home_vm_parser')
 media=load(repo/'desktop/tools/extract-upstream-media.py','home_vm_media')
 constSource=read(_desktop_canonical_source(repo, BASE + 'feature/plugin/BiliPaiFeedFilterPlugin.kt'))
 constLine='internal const val BILIPAI_FEED_FILTER_PLUGIN_ID = "bilipai_feed_filter"'
 assert constSource.count(constLine)==1
 constBody='package com.android.purebilibili.feature.plugin\n'+constLine+'\n'
 write(output/'com/android/purebilibili/feature/plugin/DesktopHomeFeedFilterId.kt',constBody)
 message=read(_desktop_canonical_source(repo, BASE + 'feature/message/MessageCenterPolicy.kt'))
 functions=[media.function(message,n,parser) for n in ['totalPrivateUnreadCount','totalMessageUnreadCount']]
 write(output/'com/android/purebilibili/feature/message/DesktopHomeTotalUnreadCount.kt','package com.android.purebilibili.feature.message\nimport com.android.purebilibili.data.model.response.*\n\n'+'\n\n'.join(functions)+'\n')
 if standalone:
  for path in DIRECT:
   relative=path.removeprefix('app/src/main/java/')
   write(output/relative,read(_desktop_canonical_source(repo, path)))
 return {'pinnedCommit':'79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40','vmOriginalSha256LF':spec['sha256LF'],'vmDesiredSha256LF':sha(vm),'preparedBaseVmDesiredSha256LF':spec['desiredSha256LF'],'solePlanConverterVisibilityAdaptation':True,'adaptationOperations':len(spec['operations']),'productionOutputs':3,'standaloneOnlyDirect':DIRECT}

if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--repo',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--standalone',action='store_true');p.add_argument('--test-output',type=Path);a=p.parse_args()
 print(json.dumps(generate(a.repo,a.output,a.standalone,a.test_output),ensure_ascii=False))
