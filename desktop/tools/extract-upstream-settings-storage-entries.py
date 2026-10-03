"""Select the two actual original DataStorage backup rows; shared detail/group helpers stay unique."""
from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import argparse, hashlib, importlib.util, json, re, textwrap

SOURCE = 'app/src/main/java/com/android/purebilibili/feature/settings/ui/SettingsSections.kt'
def read(repo): return (_desktop_canonical_source(repo, SOURCE)).read_text(encoding='utf-8').replace('\r\n','\n')
def helpers(repo):
    spec=importlib.util.spec_from_file_location('storage_source_helpers',repo/'desktop/tools/extract-upstream-plugins.py')
    host=importlib.util.module_from_spec(spec);spec.loader.exec_module(host)
    media=host.media_extractor(repo)
    return host,media,media.parser_for(repo)
def closing(tokens,start,left,right):
    assert tokens[start][0] == left
    end=start;depth=1
    while depth:
        end+=1;depth+=(tokens[end][0]==left)-(tokens[end][0]==right)
    return end
def selected_body(repo):
    _,media,parser=helpers(repo)
    source=media.function(read(repo),'DataStorageSection',parser)
    variables=[]
    for name,target in [('settingsShareVisual','SETTINGS_SHARE'),('webDavVisual','WEBDAV_BACKUP')]:
        pattern=rf'val {name} = rememberSettingsEntryVisual\(SettingsSearchTarget\.{target}\)'
        match=re.search(pattern,source)
        if match is None: raise ValueError('Original storage visual declaration changed: '+name)
        variables.append(match.group())
    match=re.search(r'val siblingTints = remember \{ resolveSettingsSiblingIconTints\(7, paletteOffset = 2\) \}',source)
    if match is None: raise ValueError('Original storage sibling count/offset changed')
    variables.append(match.group())
    tokens=parser.kotlin_tokens(source)
    calls=[i for i,t in enumerate(tokens[:-1]) if t[0]=='SettingClickableItem' and tokens[i+1][0]=='(']
    if len(calls)!=5: raise ValueError('Original storage clickable row count changed')
    ends=[closing(tokens,index+1,'(',')') for index in calls[:2]]
    selected=source[tokens[calls[0]][1]:tokens[ends[-1]][2]]
    if selected.count('onClick = onSettingsShareClick')!=1 or selected.count('onClick = onWebDavBackupClick')!=1:
        raise ValueError('Original first two storage callbacks changed')
    if selected.count('SettingsAdaptiveDivider()')!=1 or selected.count('SettingClickableItem(')!=2:
        raise ValueError('Original storage selected row shape changed')
    line_start=source.rfind('\n',0,tokens[calls[0]][1])+1
    selected=textwrap.dedent(source[line_start:tokens[calls[0]][1]]+selected).strip()
    # Platform bindings only. Preserve original destination copy, visuals, tint count/offset and row order.
    selected=selected.replace('painterResource(id = it)','rememberVectorPainter(DesktopSettingsVectors.vector(it))')
    selected=re.sub(r'\bvalue = ', 'subtitle = ',selected)
    return '\n'.join(variables), selected
def generate(repo,output):
    host,_,_=helpers(repo);original=read(repo);variables,selected=selected_body(repo)
    imports='''package com.android.purebilibili.feature.settings
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import com.android.purebilibili.core.ui.components.AppPreference as SettingClickableItem
import com.bilipai.desktop.settings.DesktopSettingsVectors
'''
    source=imports+'''
@Composable
internal fun SettingsStorageBackupCategoryEntrySection(onSettingsShareClick: () -> Unit, onWebDavBackupClick: () -> Unit) {
    SettingsDetailGroup(title = "存储与备份") {
        SettingsStorageBackupEntrySection(onSettingsShareClick, onWebDavBackupClick)
    }
}

@Composable
internal fun SettingsStorageBackupEntrySection(onSettingsShareClick: () -> Unit, onWebDavBackupClick: () -> Unit) {
'''+textwrap.indent(variables,'    ')+ '\n    SettingsCardGroup {\n'+textwrap.indent(selected,'        ')+ '\n    }\n}\n'
    original_branch='SettingsDetailGroup(title = "存储与备份")'
    if read(repo).count(original_branch)!=1: raise ValueError('Original storage group changed')
    return [host.write(output,SOURCE,original,source,'SettingsStorageBackupCategoryEntries.kt')]
def inventory(repo):
    return [dict(path=SOURCE,mode='policy-extract',features=['settings-storage-backup-entries'],
        sha256=hashlib.sha256(read(repo).encode()).hexdigest())]
if __name__=='__main__':
    cli=argparse.ArgumentParser(description=__doc__);cli.add_argument('--repo',type=Path,required=True)
    cli.add_argument('--output',type=Path);cli.add_argument('--inventory',action='store_true');args=cli.parse_args()
    if args.inventory: print(json.dumps(inventory(args.repo.resolve()),indent=2))
    if args.output: print('Generated',len(generate(args.repo.resolve(),args.output.resolve())))
