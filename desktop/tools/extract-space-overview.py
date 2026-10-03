#!/usr/bin/env python3
"""Original aggregate seed, route dispatch and Space header/playback policies for Windows."""
from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import argparse, hashlib, importlib.util, json, textwrap
BASE = 'app/src/main/java/com/android/purebilibili/'
SOURCES = {BASE+'feature/space/SpaceLoadPolicy.kt':'policy-extract',
    BASE+'feature/space/SpaceScreen.kt':'policy-extract',
    BASE+'feature/space/SpaceViewModel.kt':'policy-extract',
    BASE+'feature/space/SpaceHeaderPresentationPolicy.kt':'direct',
    BASE+'feature/space/SpacePlaybackPolicy.kt':'direct',
    BASE+'feature/space/SpaceChargeBadgePolicy.kt':'direct'}

def inventory(repo):
    return [dict(path=p,mode=m,features=['space-overview'],sha256=hashlib.sha256((_desktop_canonical_source(repo, p)).read_bytes().replace(b'\r\n',b'\n')).hexdigest()) for p,m in SOURCES.items()]

def generate(repo, output, policy_only=False):
    spec=importlib.util.spec_from_file_location('space_overview_parser',repo/'desktop/tools/sync-upstream.py')
    parser=importlib.util.module_from_spec(spec);spec.loader.exec_module(parser)
    def read(suffix): return (_desktop_canonical_source(repo, Path(BASE) / suffix)).read_text(encoding='utf-8')
    def declaration(source, name, kind='fun'):
        tokens=parser.kotlin_tokens(source)
        starts=[]
        for i,t in enumerate(tokens[:-1]):
            if t[0]!=kind: continue
            opening=next((j for j in range(i+1,len(tokens)) if tokens[j][0]=='('),None)
            if opening is not None and tokens[opening-1][0]==name: starts.append(i)
        if len(starts)!=1: raise ValueError(name)
        start=starts[0]; end=start
        while tokens[end][0]!='(': end+=1
        depth=1
        while depth: end+=1; depth+=(tokens[end][0]=='(')-(tokens[end][0]==')')
        if kind=='class':
            begin=source.rfind('\n',0,tokens[start][1])+1
            return textwrap.dedent(source[begin:tokens[end][2]])
        while tokens[end][0]!='{': end+=1
        depth=1
        while depth: end+=1; depth+=(tokens[end][0]=='{')-(tokens[end][0]=='}')
        begin=source.rfind('\n',0,tokens[start][1])+1
        return textwrap.dedent(source[begin:tokens[end][2]])
    def write(package,name,text):
        target=output/package.replace('.','/')/name;target.parent.mkdir(parents=True,exist_ok=True)
        target.write_text(text,encoding='utf-8',newline='\n')
    policy=read('feature/space/SpaceLoadPolicy.kt')
    names=['resolveSpaceAggregateTopPhoto','parseTopImageDy','resolveSpaceTopImageItems','resolveSpaceRelationState',
        'resolveSpaceInitialSeedFromAggregate','resolveSpaceAggregateDefaultSelection','mapSpaceAggregateVideoItem',
        'mapSpaceAggregateFavoriteFolder','resolveSpaceAggregateVideoId','resolveSpaceAggregateLazyItemKey']
    pieces=['// GENERATED selected original pure declarations; no seed or routing algorithm replacement.',
        'package com.android.purebilibili.feature.space','import com.android.purebilibili.data.model.response.*',
        'import com.android.purebilibili.core.util.BilibiliNavigationTarget',
        'import com.android.purebilibili.core.util.BilibiliNavigationTargetParser', declaration(policy,'SpaceInitialSeed','class')]
    pieces += [declaration(policy,name) for name in names]
    # Complete initial SpaceViewModel load closure, reused by original Tablet ownerUploads.
    pieces += [declaration(policy,'SpaceUserCardVisuals','class')]
    pieces += [declaration(policy,name) for name in ['shouldApplySpaceLoadResult', 'applySpaceSupplementalData', 'mergeArchiveMapsByLargestList', 'resolveEmbeddedSeasonArchives', 'resolveEmbeddedSeriesArchives', 'buildInitialSpaceSuccessState', 'shouldHydrateSpaceContributionVideos', 'shouldApplySpaceVideoResult', 'extractSpaceVideoCategories']]
    enum_start,enum_end=parser.kotlin_structure(parser.kotlin_tokens(policy),'class','SpaceContributionVideoLayoutMode')
    enum_tokens=parser.kotlin_tokens(policy)
    enum_begin=policy.rfind('\n',0,enum_tokens[enum_start][1])+1
    pieces += [policy[enum_begin:enum_tokens[enum_end][2]]]
    pieces += [declaration(policy,name) for name in ['defaultSpaceContributionVideoLayoutMode','toggleSpaceContributionVideoLayoutMode','resolveSpaceContributionVideoGridSpan','resolveSpaceContributionVideoItemKey']]
    screen=read('feature/space/SpaceScreen.kt')
    pieces += [declaration(screen,'handleAggregateArchiveClick').replace('private fun ','internal fun ',1)]
    vm=read('feature/space/SpaceViewModel.kt')
    pieces += [declaration(vm,'extractCategories').replace('private fun extractCategories','internal fun desktopOriginalSpaceVideoCategories',1)]
    write('com.android.purebilibili.feature.space','DesktopOriginalSpaceOverview.kt','\n\n'.join(pieces)+'\n')
    if not policy_only:
        for path,mode in SOURCES.items():
            if mode=='direct':
                original=(_desktop_canonical_source(repo, path)).read_text(encoding='utf-8')
                write('com.android.purebilibili.feature.space',Path(path).name,original)

if __name__=='__main__':
    cli=argparse.ArgumentParser(description=__doc__);cli.add_argument('--repo',type=Path,required=True)
    cli.add_argument('--output',type=Path);cli.add_argument('--inventory',action='store_true');cli.add_argument('--policy-only',action='store_true')
    args=cli.parse_args()
    if args.inventory: print(json.dumps(inventory(args.repo),indent=2))
    elif args.output: generate(args.repo,args.output,args.policy_only)
    else: cli.error('--output is required')
