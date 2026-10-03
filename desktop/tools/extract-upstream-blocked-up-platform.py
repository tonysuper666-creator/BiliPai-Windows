"""Original BlockedUp data fields and pure import/share policies, without Room/Android runtime."""
from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import argparse,hashlib,importlib.util,json
BASE='app/src/main/java/com/android/purebilibili/'
SOURCES=[BASE+'core/database/entity/BlockedUp.kt',BASE+'data/repository/BlockedUpRepository.kt',BASE+'data/repository/BilibiliBlockedListSyncRepository.kt']
def original(repo,name):return (_desktop_canonical_source(repo, name)).read_text(encoding='utf-8').replace('\r\n','\n')
def load(repo):
    spec=importlib.util.spec_from_file_location('blocked_discovery',repo/'desktop/tools/extract-discovery-platform.py')
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    spec=importlib.util.spec_from_file_location('blocked_parser',repo/'desktop/tools/sync-upstream.py')
    parser=importlib.util.module_from_spec(spec);spec.loader.exec_module(parser)
    return module,parser
def write(output,package,name,text):
    target=output/package.replace('.','/')/name;target.parent.mkdir(parents=True,exist_ok=True)
    target.write_text(text,encoding='utf-8',newline='\n');return target
def generate(repo,output):
    discovery,parser=load(repo)
    model=original(repo,SOURCES[0])
    for old,new in [('import androidx.room.Entity\nimport androidx.room.PrimaryKey','import kotlinx.serialization.Serializable'),
                    ('@Entity(tableName = "blocked_ups")','@Serializable'),('    @PrimaryKey\n','')]:
        if model.count(old)!=1:raise ValueError('Original BlockedUp platform annotation changed')
        model=model.replace(old,new,1)
    paths=[write(output,'com.android.purebilibili.core.database.entity','BlockedUp.kt',model)]
    source=original(repo,SOURCES[1]);prefix=source[:source.index('class BlockedUpRepository(')]
    for name in ['BilibiliBlockedListRemoteStatus','BlockedUpRelationSource']:
        declaration=discovery.selected_enum(source,name,parser);assert prefix.count(declaration)==1;prefix=prefix.replace(declaration,'',1)
    for kind,name,is_data in [('class','BlockedUpWriteResult',True),('fun','resolveBlockedUpRelationReSrc',False),('fun','buildBlockedUpWriteMessage',False)]:
        declaration=discovery.selected(source,kind,name,parser,is_data);assert prefix.count(declaration)==1;prefix=prefix.replace(declaration,'',1)
    for line in ['import android.content.Context','import com.android.purebilibili.core.database.AppDatabase',
                 'import com.android.purebilibili.core.network.BilibiliApi','import com.android.purebilibili.core.network.NetworkModule',
                 'import com.android.purebilibili.core.store.TokenManager','import kotlinx.coroutines.Dispatchers',
                 'import kotlinx.coroutines.delay','import kotlinx.coroutines.flow.Flow','import kotlinx.coroutines.withContext']:
        prefix=prefix.replace(line+'\n','')
    paths.append(write(output,'com.android.purebilibili.data.repository','DesktopBlockedUpImportSharePolicy.kt',prefix))
    sync=original(repo,SOURCES[2]);mapper=discovery.selected(sync,'fun','buildBlockedUpImportItemsFromRemoteBlacks',parser)
    paths.append(write(output,'com.android.purebilibili.data.repository','DesktopBlockedUpRemoteImportPolicy.kt',
        'package com.android.purebilibili.data.repository\nimport com.android.purebilibili.data.model.response.FollowingUser\n\n'+mapper+'\n'))
    return paths
def inventory(repo):return [dict(path=p,mode='policy-extract',features=['settings-blocked-up'],sha256=hashlib.sha256(original(repo,p).encode()).hexdigest()) for p in SOURCES]
if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--repo',type=Path,required=True)
    parser.add_argument('--output',type=Path);parser.add_argument('--inventory',action='store_true');args=parser.parse_args()
    if args.output:print('Generated',len(generate(args.repo.resolve(),args.output.resolve())))
    if args.inventory:print(json.dumps(inventory(args.repo.resolve()),indent=2))
