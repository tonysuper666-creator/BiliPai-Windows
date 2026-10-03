"""Original shared detail entries and canonical playback category calls only."""
from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import argparse
import hashlib
import importlib.util
import json
import re
import textwrap

SOURCE='app/src/main/java/com/android/purebilibili/feature/settings/ui/SettingsSections.kt'

def read(repo):return (_desktop_canonical_source(repo, SOURCE)).read_text(encoding='utf-8').replace('\r\n','\n')
def helpers(repo):
    spec=importlib.util.spec_from_file_location('settings_entry_helpers',repo/'desktop/tools/extract-upstream-plugins.py')
    host=importlib.util.module_from_spec(spec);spec.loader.exec_module(host)
    media=host.media_extractor(repo)
    return host,media,media.parser_for(repo)

def block_end(tokens,start):
    assert tokens[start][0]=='{'
    end=start;depth=1
    while depth:
        end+=1;depth+=(tokens[end][0]=='{')-(tokens[end][0]=='}')
    return end

def original_playback_branch(repo):
    _,media,parser=helpers(repo)
    source=media.function(read(repo),'SettingsRootCategoryContent',parser)
    matches=list(re.finditer(r'SettingsRootCategory\.PLAYBACK_QUALITY\s*->\s*\{',source))
    if len(matches)!=1:raise ValueError('Canonical original playback branch missing or ambiguous')
    tokens=parser.kotlin_tokens(source)
    start=next(i for i,t in enumerate(tokens) if t[1]==matches[0].end()-1)
    end=block_end(tokens,start)
    return source[tokens[start][2]:tokens[end][1]]

def playback_body(repo):
    _,_,parser=helpers(repo)
    branch=original_playback_branch(repo)
    tokens=parser.kotlin_tokens(branch)
    wrappers=[i for i,t in enumerate(tokens[:-1]) if t[0]=='SettingsRootCategoryEntranceSection' and tokens[i+1][0]=='{']
    if len(wrappers)!=2:raise ValueError('Original playback category entrance shape changed')
    removals=[]
    for index in wrappers:
        end=block_end(tokens,index+1)
        removals.extend([(tokens[index][1],tokens[index+1][2]),(tokens[end][1],tokens[end][2])])
    for begin,end in sorted(removals,reverse=True):branch=branch[:begin]+branch[end:]
    if branch.count('actions.onPlaybackClick')!=2:raise ValueError('Original playback callback shape changed')
    branch=branch.replace('actions.onPlaybackClick','onPlaybackClick')
    if re.search(r'\b(?:actions|state)\.',branch):raise ValueError('Playback entries gained a new dependency')
    if branch.count('SettingsDetailGroup(title =')!=2 or branch.count('SettingsDetailEntry(')!=2:
        raise ValueError('Original two-group playback entries changed')
    # Whitespace only: body syntax/copy/order/focus stays original. No entrance animation substitute.
    return textwrap.dedent(branch).strip()

def generate(repo,output):
    host,media,parser=helpers(repo);original=read(repo)
    output.mkdir(parents=True,exist_ok=True)
    data=media.data_class(original,'SettingsDetailEntry',parser)
    group=media.function(original,'SettingsDetailGroup',parser)
    entries=media.function(original,'SettingsDetailEntrySection',parser)
    entries=host.substitute(entries,'painterResource(id = it)',
        'rememberVectorPainter(DesktopSettingsVectors.vector(it))')
    imports='''package com.android.purebilibili.feature.settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import com.android.purebilibili.core.ui.components.AppPreferenceSectionTitle as SettingsSectionTitle
import com.android.purebilibili.core.ui.components.AppPreference as SettingClickableItem
import com.bilipai.desktop.settings.DesktopSettingsVectors
'''
    files=[host.write(output,SOURCE,original,imports+'\n'+data+'\n\n@Composable\n'+group+'\n\n@Composable\n'+entries,'SettingsDetailEntries.kt')]
    imports='''package com.android.purebilibili.feature.settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
'''
    source=imports+'\n@Composable\ninternal fun SettingsPlaybackCategoryEntrySection(onPlaybackClick: () -> Unit) {\n    Column {\n'+textwrap.indent(playback_body(repo),'        ')+'\n    }\n}\n'
    files.append(host.write(output,SOURCE,original,source,'SettingsPlaybackCategoryEntries.kt'))
    return files

def inventory(repo):
    return [dict(path=SOURCE,mode='policy-extract',features=['settings-playback-entry-parity'],
        sha256=hashlib.sha256(read(repo).encode()).hexdigest())]

if __name__=='__main__':
    cli=argparse.ArgumentParser(description=__doc__)
    cli.add_argument('--repo',type=Path,required=True);cli.add_argument('--output',type=Path)
    cli.add_argument('--inventory',action='store_true')
    args=cli.parse_args()
    if args.inventory:print(json.dumps(inventory(args.repo.resolve()),indent=2))
    if args.output:print('Generated',len(generate(args.repo.resolve(),args.output.resolve())))
