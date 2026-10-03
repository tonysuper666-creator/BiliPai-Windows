#!/usr/bin/env python3
"""Original UP-space policies and request blocks; Android URI/logging only are bound to JVM."""
from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import argparse, hashlib, importlib.util, json, re, textwrap

BASE = 'app/src/main/java/com/android/purebilibili/'
SOURCES = {
    BASE+'feature/space/SpaceSupporterPolicy.kt': 'direct',
    BASE+'feature/space/SpaceArticlePresentationPolicy.kt': 'direct',
    BASE+'feature/space/SpaceProfileEnhancementPolicy.kt': 'policy-extract',
    BASE+'feature/space/SpaceDynamicNavigationPolicy.kt': 'policy-extract',
    BASE+'feature/space/SpaceSupporterScreens.kt': 'policy-extract',
    BASE+'feature/space/SpaceViewModel.kt': 'policy-extract',
    BASE+'feature/space/SpaceUpowerRankViewModel.kt': 'policy-extract',
    BASE+'feature/space/SpaceMemberGuardViewModel.kt': 'policy-extract',
    BASE+'core/util/BilibiliNavigationTargetParser.kt': 'direct',
    BASE+'core/util/BilibiliUrlParser.kt': 'platform-rewrite',
    BASE+'core/util/UrlDecodeCompat.kt': 'direct',
}

def inventory(repo):
    return [dict(path=p,mode=m,features=['space'],sha256=hashlib.sha256((_desktop_canonical_source(repo, p)).read_bytes().replace(b'\r\n',b'\n')).hexdigest()) for p,m in SOURCES.items()]

def generate(repo, output, policy_only=False):
    spec=importlib.util.spec_from_file_location('space_parser',repo/'desktop/tools/sync-upstream.py')
    parser=importlib.util.module_from_spec(spec);spec.loader.exec_module(parser)
    def read(suffix): return (_desktop_canonical_source(repo, Path(BASE) / suffix)).read_text(encoding='utf-8')
    def write(package, name, source):
        target=output/package.replace('.','/')/name;target.parent.mkdir(parents=True,exist_ok=True)
        target.write_text(source,encoding='utf-8',newline='\n')
    def declaration(source, kind, name):
        tokens=parser.kotlin_tokens(source)
        if kind=='fun':
            starts=[i for i,t in enumerate(tokens[:-1]) if t[0]=='fun' and tokens[i+1][0]==name]
            if len(starts)!=1: raise ValueError('Ambiguous original function: '+name)
            start=starts[0];end=start
            while tokens[end][0]!='(':end+=1
            depth=1
            while depth:end+=1;depth+=(tokens[end][0]=='(')-(tokens[end][0]==')')
            while tokens[end][0] not in ('{','='):end+=1
            if tokens[end][0]=='=':
                brace=next(i for i in range(end+1,len(tokens)) if tokens[i][0]=='{');end=brace
            depth=1
            while depth:end+=1;depth+=(tokens[end][0]=='{')-(tokens[end][0]=='}')
        else:
            start,end=parser.kotlin_structure(tokens,kind,name)
        begin=source.rfind('\n',0,tokens[start][1])+1
        return textwrap.dedent(source[begin:tokens[end][2]])
    def expression(source, start, open_char, close_char):
        tokens=parser.kotlin_tokens(source)
        opening=next(i for i,t in enumerate(tokens) if t[1]>=start and t[0]==open_char)
        end=opening;depth=1
        while depth:end+=1;depth+=(tokens[end][0]==open_char)-(tokens[end][0]==close_char)
        # Include the called function identifier (mapOf/buildMap), not the assignment.
        return textwrap.dedent(source[start:tokens[end][2]])
    for p, mode in SOURCES.items():
        if mode=='direct' and not policy_only:
            source=(_desktop_canonical_source(repo, p)).read_text(encoding='utf-8')
            package=re.search(r'^package (.+)$',source,re.M).group(1)
            write(package,Path(p).name,source)
    profile=read('feature/space/SpaceProfileEnhancementPolicy.kt')
    nav=read('feature/space/SpaceDynamicNavigationPolicy.kt')
    screens=read('feature/space/SpaceSupporterScreens.kt')
    pieces=['// GENERATED selected declarations verbatim; source inventory pins original files.',
            'package com.android.purebilibili.feature.space',
            'import com.android.purebilibili.data.model.response.*',
            'import com.android.purebilibili.core.util.BilibiliNavigationTarget',
            'import com.android.purebilibili.core.util.BilibiliNavigationTargetParser',
            declaration(profile,'fun','resolveSpaceFavoriteFoldersForDisplay'),
            declaration(nav,'interface','SpaceDynamicClickAction'),
            declaration(nav,'fun','resolveSpaceArticleClickAction'),
            next(line for line in nav.splitlines() if line.startswith('private const val OPUS_ID_MIN_VALUE')),
            declaration(screens,'fun','resolveSpaceGuardLevelLabel')]
    # Visibility binding only: the UI function was private in its Android screen file.
    pieces[-1]=pieces[-1].replace('private fun ', 'internal fun ',1)
    vm=read('feature/space/SpaceViewModel.kt')
    support_start=vm.index('val params = mapOf(',vm.index('private suspend fun fetchSpaceSupporters'))+len('val params = ')
    support=expression(vm,support_start,'(',')')
    article_start=vm.index('mapOf(',vm.index('private suspend fun fetchSpaceArticleList'))
    article=expression(vm,article_start,'(',')')
    rank=read('feature/space/SpaceUpowerRankViewModel.kt')
    rank_start=rank.index('buildMap {',rank.index('private fun load()'))
    rank_params=expression(rank,rank_start,'{','}')
    guard=read('feature/space/SpaceMemberGuardViewModel.kt')
    guard_start=guard.index('mapOf(',guard.index('spaceApi.getMemberGuard('))
    guard_params=expression(guard,guard_start,'(',')')
    guard_const=next(line.strip() for line in guard.splitlines() if 'const val PAGE_SIZE' in line)
    # The expression bodies below are copied byte-for-byte apart from indentation.
    pieces += ['internal fun desktopSpaceSupporterParams(mid: Long): Map<String,String> = '+support,
               'internal fun desktopSpaceArticleParams(mid: Long, page: Int): Map<String,String> = '+article,
               'internal fun desktopSpaceRankParams(upMid: Long, selectedType: Int?): Map<String,String> = '+rank_params,
               guard_const,
               'internal fun desktopSpaceGuardParams(ruid: Long, page: Int): Map<String,String> = '+guard_params]
    fallback_start=rank.index('elec.list.map {')
    fallback=expression(rank,fallback_start,'{','}')
    pieces += ['internal fun desktopSpaceElecItems(elec: SpaceElecBlock): List<SpaceUpowerRankItem> = '+fallback]
    write('com.android.purebilibili.feature.space','DesktopUpstreamSpaceDeclarations.kt','\n\n'.join(pieces)+'\n')
    url=read('core/util/BilibiliUrlParser.kt')
    url=url.replace('import android.net.Uri','import java.net.URI as Uri\nimport com.bilipai.desktop.data.DesktopSpaceRouteLog as Logger')
    write('com.android.purebilibili.core.util','BilibiliUrlParser.kt','// GENERATED: Android Uri bound to java.net.URI; debug/error URL logging discarded.\n'+url)

if __name__=='__main__':
    cli=argparse.ArgumentParser(description=__doc__);cli.add_argument('--repo',type=Path,required=True)
    cli.add_argument('--output',type=Path);cli.add_argument('--inventory',action='store_true');cli.add_argument('--policy-only',action='store_true')
    args=cli.parse_args()
    if args.inventory: print(json.dumps(inventory(args.repo),indent=2))
    else:
        if args.output is None:cli.error('--output is required')
        generate(args.repo,args.output,args.policy_only)
