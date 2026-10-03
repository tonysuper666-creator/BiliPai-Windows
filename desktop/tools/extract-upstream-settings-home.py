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

def generate(repo,output,standalone=False):
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
    slider=call(section,'SettingSliderItem',parser)
    if 'title = "首页推荐来源"' not in choice or 'title = "首页刷新数量"' not in slider:
        raise ValueError('Original home controls changed identity')
    # Keep the exact original palette declaration and these two icon declarations;
    # the rest of the original body is neither copied nor silently enabled.
    begin=section.index('    val siblingTints = remember')
    end=section.index('    val visibilityIcon',begin)
    prelude=section[begin:end].rstrip()
    imports='''package com.android.purebilibili.feature.settings
import androidx.compose.runtime.*
import com.android.purebilibili.core.store.DesktopFeedSettings
import com.android.purebilibili.core.store.MIN_HOME_REFRESH_COUNT
import com.android.purebilibili.core.store.MAX_HOME_REFRESH_COUNT
import com.android.purebilibili.core.ui.components.AppSliderDialogPreference as SettingSliderItem
import kotlin.math.roundToInt
'''
    body=imports+'''\n/** Partial original FeedApiSection: only controls backed by the actual shared recommendation store. */
@Composable
internal fun DesktopHomeRecommendationFields(
    feedApiType: DesktopFeedSettings.FeedApiType,
    onFeedApiTypeChange: (DesktopFeedSettings.FeedApiType) -> Unit,
    homeRefreshCount: Int,
    onHomeRefreshCountChange: (Int) -> Unit,
) {
'''+prelude+'\n    SettingsCardGroup {\n'+textwrap.indent(choice,'        ')+'\n        SettingsAdaptiveDivider()\n'+textwrap.indent(slider,'        ')+'\n    }\n}\n\n'
    body+='\n\n'.join(media.function(original,name,parser) for name in ['resolveHomeRefreshCountSummary','resolveHomeRefreshSliderRange','resolveHomeRefreshSliderSteps'])
    files.append(host.write(output,path,original,body,'DesktopHomeRecommendationFields.kt'))
    path=BASE+'PlaybackSettingsSelectionPolicy.kt';original=read(repo,path)
    function=media.function(original,'resolveFeedApiSegmentOptions',parser)
    function=function.replace('SettingsManager.FeedApiType','DesktopFeedSettings.FeedApiType')
    body='package com.android.purebilibili.feature.settings\nimport com.android.purebilibili.core.store.DesktopFeedSettings\nimport com.android.purebilibili.core.ui.components.AppSegmentOption\n\n'+function
    files.append(host.write(output,path,original,body,'DesktopFeedApiSegmentOptions.kt'))
    return files

def inventory(repo):return [dict(path=path,mode=mode,features=['settings-home-section-parity'],
    sha256=hashlib.sha256(read(repo,path).encode()).hexdigest()) for path,mode in SOURCES.items()]

if __name__=='__main__':
    cli=argparse.ArgumentParser(description=__doc__);cli.add_argument('--repo',type=Path,required=True)
    cli.add_argument('--output',type=Path);cli.add_argument('--standalone',action='store_true');cli.add_argument('--inventory',action='store_true')
    args=cli.parse_args()
    if args.inventory:print(json.dumps(inventory(args.repo.resolve()),indent=2))
    if args.output:print('Generated',len(generate(args.repo.resolve(),args.output.resolve(),args.standalone)))
