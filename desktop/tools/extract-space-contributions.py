#!/usr/bin/env python3
"""Selected original space pagination/merge and interaction mapping, with UI/network bindings removed."""
from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import argparse, hashlib, importlib.util, json, textwrap
BASE='app/src/main/java/com/android/purebilibili/'
SOURCES={BASE+'feature/space/SpaceLoadPolicy.kt':'policy-extract',BASE+'feature/space/SpaceViewModel.kt':'policy-extract',
         BASE+'data/repository/LikedVideosRepository.kt':'policy-extract',BASE+'feature/list/ListViewModel.kt':'policy-extract',
         BASE+'feature/space/SpaceProfileEnhancementPolicy.kt':'policy-extract',BASE+'core/network/ApiClient.kt':'policy-extract'}
def inventory(repo):
    return [dict(path=p,mode=m,features=['space-contributions'],sha256=hashlib.sha256((_desktop_canonical_source(repo, p)).read_bytes().replace(b'\r\n',b'\n')).hexdigest()) for p,m in SOURCES.items()]
def generate(repo,output):
    spec=importlib.util.spec_from_file_location('space_contribution_parser',repo/'desktop/tools/sync-upstream.py')
    parser=importlib.util.module_from_spec(spec);spec.loader.exec_module(parser)
    def read(suffix):return (_desktop_canonical_source(repo, Path(BASE) / suffix)).read_text(encoding='utf-8')
    def declaration(source,name):
        tokens=parser.kotlin_tokens(source);starts=[i for i,t in enumerate(tokens[:-1]) if t[0]=='fun' and tokens[i+1][0]==name]
        if len(starts)!=1:raise ValueError(name)
        start=starts[0];end=start
        while tokens[end][0]!='(':end+=1
        depth=1
        while depth:end+=1;depth+=(tokens[end][0]=='(')-(tokens[end][0]==')')
        while tokens[end][0]!='{':end+=1
        depth=1
        while depth:end+=1;depth+=(tokens[end][0]=='{')-(tokens[end][0]=='}')
        begin=source.rfind('\n',0,tokens[start][1])+1
        return textwrap.dedent(source[begin:tokens[end][2]])
    def write(package,name,text):
        target=output/package.replace('.','/')/name;target.parent.mkdir(parents=True,exist_ok=True);target.write_text(text,encoding='utf-8',newline='\n')
    policy=read('feature/space/SpaceLoadPolicy.kt');vm=read('feature/space/SpaceViewModel.kt')
    names=['resolveInitialSpaceVideoPage','resolveNextSpaceVideoPage','normalizeSpaceVideoPage','resolveSpaceVideoLastPage','shouldContinueSpaceBangumiPagination']
    pieces=['// GENERATED original declarations; only private visibility is adapted.', 'package com.android.purebilibili.feature.space',
            'import com.android.purebilibili.data.model.response.*']+[declaration(policy,n) for n in names]
    pieces += [declaration(vm,n).replace('private fun ','internal fun ',1) for n in ['mergeSpaceBangumiItems','mergeSpaceCheeseItems']]
    # Original ViewModel audio page handling is a pure block, including duplicate/empty-page termination.
    start=vm.index('val existingIds = currentState.audios.mapTo');end=vm.index('\n\n',vm.index('dedupedNew.size >= SPACE_AUDIO_PAGE_SIZE_CONST',start))
    audio=textwrap.dedent(vm[start:end]).replace('currentState.audios','existing').replace('result.data?.totalSize','data?.totalSize')
    const=next(line.strip() for line in vm.splitlines() if 'const val SPACE_AUDIO_PAGE_SIZE_CONST' in line)
    pieces += [const,'internal fun desktopSpaceAudioMerge(existing: List<SpaceAudioItem>, data: SpaceAudioData?, refresh: Boolean): Triple<List<SpaceAudioItem>,Int,Boolean> {\n'+
               '    val newItems = data?.data.orEmpty()\n'+textwrap.indent(audio,'    ')+'\n    return Triple(allItems,totalCount,hasMore)\n}']
    write('com.android.purebilibili.feature.space','DesktopUpstreamSpaceContributionDeclarations.kt','\n\n'.join(pieces)+'\n')
    # The first slice already supplies the one folder-display function. All remaining pure
    # tab/header declarations keep the original file body, relying on its original SubTab enum.
    profile=read('feature/space/SpaceProfileEnhancementPolicy.kt')
    folder=declaration(profile,'resolveSpaceFavoriteFoldersForDisplay')
    assert profile.count(folder)==1
    write('com.android.purebilibili.feature.space','DesktopOriginalSpaceProfileDeclarations.kt',
          '// GENERATED original pure file, except the folder-display declaration supplied by slice 1.\n'+profile.replace(folder,''))
    tokens=parser.kotlin_tokens(vm);start,end=parser.kotlin_structure(tokens,'class','SpaceSubTab')
    begin=vm.rfind('\n',0,tokens[start][1])+1
    write('com.android.purebilibili.feature.space','DesktopOriginalSpaceSubTab.kt',
          'package com.android.purebilibili.feature.space\n'+vm[begin:tokens[end][2]]+'\n')
    api=read('core/network/ApiClient.kt')
    write('com.android.purebilibili.core.network','DesktopOriginalSpaceAggregateParams.kt',
          'package com.android.purebilibili.core.network\nimport com.android.purebilibili.core.network.DesktopTokenPlatform as TokenManager\n'+
          declaration(api,'buildSpaceAggregateParams')+'\n')
    liked=read('data/repository/LikedVideosRepository.kt')
    begin=liked.index('            val detailedItems =');end=liked.index('\n        }',begin)
    mapping=textwrap.dedent(liked[begin:end]);mapping=mapping.replace('\nPage(', '\nreturn Page(',1)
    # Wrapper scope replaces NetworkModule transport; the original mapping block is otherwise retained.
    write('com.android.purebilibili.data.repository','DesktopOriginalSpaceInteraction.kt',
          'package com.android.purebilibili.data.repository\nimport com.android.purebilibili.data.model.response.*\nimport com.android.purebilibili.core.util.IdUtils\n'+
          'internal object DesktopOriginalSpaceInteraction {\n    data class Page(val items: List<VideoItem>,val total: Int)\n'+
          '    fun fromData(data: LikedVideosData?): Page {\n'+textwrap.indent(mapping,'        ')+'\n    }\n}\n')
    # Exact original page-full predicate from LikedVideosViewModel (totals are informational there).
    listing=read('feature/list/ListViewModel.kt')
    assert listing.count('hasMore = page.items.size >= pageSize')==2
    write('com.android.purebilibili.feature.list','DesktopOriginalSpaceInteractionPagination.kt',
          'package com.android.purebilibili.feature.list\nimport com.android.purebilibili.data.repository.DesktopOriginalSpaceInteraction\n'+
          'internal fun desktopSpaceInteractionHasMore(page: DesktopOriginalSpaceInteraction.Page,pageSize: Int): Boolean {\n'+
          '    val hasMore = page.items.size >= pageSize\n    return hasMore\n}\n')
if __name__=='__main__':
    cli=argparse.ArgumentParser(description=__doc__);cli.add_argument('--repo',type=Path,required=True);cli.add_argument('--output',type=Path);cli.add_argument('--inventory',action='store_true');args=cli.parse_args()
    if args.inventory:print(json.dumps(inventory(args.repo),indent=2))
    elif args.output:generate(args.repo,args.output)
    else:cli.error('--output is required')
