"""Original follow event and home unfollow reducers; no persistent Android ViewModel replica."""
from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import argparse,hashlib,importlib.util,json
BASE='app/src/main/java/com/android/purebilibili/'
PATHS=[BASE+p for p in ['data/repository/ActionRepository.kt','feature/dynamic/DynamicViewModel.kt',
 'feature/dynamic/DynamicScreenStatePolicy.kt']]
FUNCTIONS=['resolveDynamicStateAfterAuthorUnfollow','resolveFollowedUsersAfterAuthorUnfollow','timelinePage',
 'updateDynamicTimelinePage','mapDynamicTimelineItems','copyActiveTimelinePage']
def module(repo,name,path):
 spec=importlib.util.spec_from_file_location(name,repo/path);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
def read(repo,path):return (_desktop_canonical_source(repo, path)).read_text(encoding='utf-8').replace('\r\n','\n')
def inventory(repo):return [dict(path=p,mode='policy-extract',features=['dynamic-follow-observer-parity'],
 sha256=hashlib.sha256(read(repo,p).encode()).hexdigest()) for p in PATHS]
def generate(repo,output,standalone=False):
 media=module(repo,'follow_media','desktop/tools/extract-upstream-media.py');parser=media.parser_for(repo)
 action=read(repo,PATHS[0]);vm=read(repo,PATHS[1]);policy=read(repo,PATHS[2]);files=[]
 package='com/android/purebilibili/'
 body='package com.android.purebilibili.data.repository\n\n'+media.data_class(action,'FollowStateChange',parser)
 files.append(media.write(output,package+'data/repository/DesktopOriginalFollowStateChange.kt',PATHS[0],action,body))
 body='package com.android.purebilibili.feature.dynamic\nimport com.android.purebilibili.data.model.response.DynamicItem\nimport kotlinx.collections.immutable.*\n\n'
 body+=media.data_class(vm,'DynamicUiState',parser)+'\n\n'+'\n\n'.join(media.function(policy,n,parser) for n in FUNCTIONS)
 files.append(media.write(output,package+'feature/dynamic/DesktopOriginalDynamicFollowStatePolicy.kt',PATHS[2],policy,body))
 return files
def main():
 parser=argparse.ArgumentParser();parser.add_argument('--repo',type=Path,required=True);parser.add_argument('--output',type=Path,required=True)
 parser.add_argument('--standalone',action='store_true');a=parser.parse_args();files=generate(a.repo.resolve(),a.output.resolve(),a.standalone)
 print(json.dumps(dict(generated=len(files),files=[str(p) for p in files])))
if __name__=='__main__':main()
