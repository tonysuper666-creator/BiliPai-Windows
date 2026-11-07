"""Select the original FeedApiSection controls with real Windows recommendation stores.

The other seven original dynamic controls remain unbound; they are not emitted as
working switches. No parallel enum/store or simplified category layout is added.
"""
from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import argparse
import hashlib
import importlib.util
import json
import textwrap

BASE='app/src/main/java/com/android/purebilibili/feature/settings/'
SOURCES={BASE+'ui/SettingsSections.kt':'policy-extract',BASE+'SettingsSelectionComponents.kt':'direct',
         BASE+'PlaybackSettingsSelectionPolicy.kt':'policy-extract'}

def helper(repo):
    spec=importlib.util.spec_from_file_location('home_helpers',repo/'desktop/tools/extract-upstream-plugins.py')
    host=importlib.util.module_from_spec(spec);spec.loader.exec_module(host);return host

def read(repo,path):return (_desktop_canonical_source(repo, path)).read_text(encoding='utf-8').replace('\r\n','\n')

def call(source,name,parser):
    tokens=parser.kotlin_tokens(source)
    found=[i for i,t in enumerate(tokens[:-1]) if t[0]==name and tokens[i+1][0]=='(']
    if len(found)!=1:raise ValueError('Original home control no longer unique: '+name)
    start=found[0];end=start+1;depth=1
    while depth:
        end+=1;depth+=(tokens[end][0]=='(')-(tokens[end][0]==')')
    begin=source.rfind('\n',0,tokens[start][1])+1
    return textwrap.dedent(source[begin:tokens[end][2]])

def generate(repo,output,standalone=False,current_repo=None):
    host=helper(repo);media=host.media_extractor(repo);parser=media.parser_for(repo)
    output.mkdir(parents=True,exist_ok=True);files=[]
    path=BASE+'SettingsSelectionComponents.kt';original=read(repo,path)
    host.prune_old_direct(output,path,original)
    if standalone:files.append(host.write(output,path,original,original))
    path=BASE+'ui/SettingsSections.kt';original=read(repo,path)
    section=media.function(original,'FeedApiSection',parser)
    # The original has three different single-choice controls; select the first one
    # using its reviewed exact source-title boundary, then token-delimit the call.
    first=section[section.index('        SettingsSingleChoicePreference('):section.index('        SettingsAdaptiveDivider()',section.index('        SettingsSingleChoicePreference('))]
    choice=call(first,'SettingsSingleChoicePreference',parser)
    if 'title = "首页推荐来源"' not in choice:
        raise ValueError('Original home controls changed identity')
    # Keep the exact original palette declaration and these two icon declarations;
    # the rest of the original body is neither copied nor silently enabled.
    begin=section.index('    val siblingTints = remember')
    end=section.index('    val visibilityIcon',begin)
    prelude=section[begin:end].rstrip()
    imports='''package com.android.purebilibili.feature.settings
import androidx.compose.runtime.*
import com.android.purebilibili.core.store.SettingsManager
'''
    body=imports+'''\n/** Partial original FeedApiSection: only controls backed by the actual shared recommendation store. */
@Composable
internal fun DesktopHomeRecommendationFields(
    feedApiType: SettingsManager.FeedApiType,
    onFeedApiTypeChange: (SettingsManager.FeedApiType) -> Unit,
) {
'''+prelude+'\n    SettingsCardGroup {\n'+textwrap.indent(choice,'        ')+'\n    }\n}\n\n'
    files.append(host.write(output,path,original,body,'DesktopHomeRecommendationFields.kt'))
    # v0.7.8 removes the preference. Both desktop request paths use the actual
    # current upstream page-size policy, independent of legacy stored values.
    path='app/src/main/java/com/android/purebilibili/feature/home/HomeViewModel.kt'
    original=((current_repo or repo)/path).read_text(encoding='utf-8')
    body='package com.android.purebilibili.feature.home\n\n'+media.function(original,'resolveHomeFeedPageSize',parser)+'\n'
    files.append(host.write(output,path,original,body,'DesktopHomeFeedPageSizePolicy.kt'))
    path=BASE+'PlaybackSettingsSelectionPolicy.kt';original=read(repo,path)
    function=media.function(original,'resolveFeedApiSegmentOptions',parser)
    body='package com.android.purebilibili.feature.settings\nimport com.android.purebilibili.core.store.SettingsManager\nimport com.android.purebilibili.core.ui.components.AppSegmentOption\n\n'+function
    if standalone:
        files.append(host.write(output,path,original,body,'DesktopFeedApiSegmentOptions.kt'))
    else:
     obsolete=output/'com/android/purebilibili/feature/settings/DesktopFeedApiSegmentOptions.kt'
     if obsolete.exists():
      assert hashlib.sha256(obsolete.read_bytes()).hexdigest()=='495c1589c5b579e446b27032d170a56bd94fb8c9b0690f24c2d11cdde0c62cca', 'Stale selected policy output drifted'
      obsolete.unlink()
    return files

def inventory(repo):return [dict(path=path,mode=mode,features=['settings-home-section-parity'],
    sha256=hashlib.sha256(read(repo,path).encode()).hexdigest()) for path,mode in SOURCES.items()]

if __name__=='__main__':
    cli=argparse.ArgumentParser(description=__doc__);cli.add_argument('--repo',type=Path,required=True)
    cli.add_argument('--output',type=Path);cli.add_argument('--standalone',action='store_true');cli.add_argument('--current-repo',type=Path);cli.add_argument('--inventory',action='store_true')
    args=cli.parse_args()
    if args.inventory:print(json.dumps(inventory(args.repo.resolve()),indent=2))
    if args.output:print('Generated',len(generate(args.repo.resolve(),args.output.resolve(),args.standalone,args.current_repo)))
