"""Select the alpha.9 image-save row and complete dialog; existing controls stay unique."""
from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import argparse, hashlib, importlib.util, json, sys, textwrap
sys.dont_write_bytecode = True
SECTIONS = 'app/src/main/java/com/android/purebilibili/feature/settings/ui/SettingsSections.kt'
SCREEN = 'app/src/main/java/com/android/purebilibili/feature/settings/screen/SettingsScreen.kt'
def helpers(repo):
    spec=importlib.util.spec_from_file_location('image_path_storage',repo/'desktop/tools/extract-upstream-settings-storage-entries.py')
    mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod)
    return mod, mod.helpers(repo)
def read(repo,path):return (_desktop_canonical_source(repo, path)).read_text(encoding='utf-8').replace('\r\n','\n')
def select_row(repo):
    storage,(_,media,parser)=helpers(repo)
    source=media.function(read(repo,SECTIONS),'DataStorageSection',parser)
    tokens=parser.kotlin_tokens(source)
    calls=[i for i,t in enumerate(tokens[:-1]) if t[0]=='SettingClickableItem' and tokens[i+1][0]=='(']
    if len(calls)!=5:raise ValueError('Original storage clickable shape changed')
    begin=calls[3]; end=storage.closing(tokens,begin+1,'(',')')
    row=textwrap.dedent(source[source.rfind('\n',0,tokens[begin][1])+1:tokens[end][2]]).strip()
    if row.count('onClick = onImageSavePathClick')!=1:raise ValueError('Image row callback changed')
    row=row.replace('painterResource(id = it)','rememberVectorPainter(DesktopSettingsVectors.vector(it))')
    row=row.replace('value = if (customImageSavePath != null)', 'subtitle = if (customImageSavePath != null)')
    declarations='''val imageSavePathVisual = rememberSettingsEntryVisual(SettingsSearchTarget.IMAGE_SAVE_PATH)
val siblingTints = remember { resolveSettingsSiblingIconTints(7, paletteOffset = 2) }
val showExplicitActionChevron =
    rememberAdaptiveListVisualCapabilities().showExplicitActionChevron'''
    if any(line.strip() not in source for line in declarations.splitlines()):raise ValueError('Original row visuals changed')
    return declarations, row
def select_dialog(repo):
    storage,(host,_,parser)=helpers(repo)
    source=read(repo,SCREEN); tokens=parser.kotlin_tokens(source)
    found=[i for i in range(len(tokens)-4) if [t[0] for t in tokens[i:i+5]]==['if','(','showImageSavePathDialog',')','{']]
    if len(found)!=1:raise ValueError('Original image dialog condition changed')
    begin=found[0]+4;end=storage.closing(tokens,begin,'{','}')
    body=textwrap.dedent(source[tokens[begin][2]:tokens[end][1]]).strip()
    body=host.substitute(body,'showImageSavePathDialog = false','onDismiss()',count=3)
    body=host.substitute(body,'imageSaveFolderPicker.launch(null)','onSelectDirectory()')
    body=host.substitute(body,'''scope.launch {
                SettingsManager.setImageSaveTreeUri(context, null)
            }''','onRestoreDefault()')
    body=host.substitute(body,'Toast.makeText(context, "已恢复默认图片保存位置", Toast.LENGTH_SHORT).show()','')
    # Android album/persistable-URI-grant wording is platform copy only, never a fake capability.
    body=host.substitute(body,'默认保存到系统相册的 BiliPai 文件夹。','默认保存到系统图片目录的 BiliPai 文件夹。')
    body=host.substitute(body,'可通过系统文件夹授权选择动态图片、头像和评论图片的保存目录。','可通过系统文件夹选择动态图片、头像和评论图片的保存目录。')
    return body
def generate(repo,output):
    _,(host,_,_)=helpers(repo)
    declarations,row=select_row(repo)
    row_source='''package com.android.purebilibili.feature.settings
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import com.android.purebilibili.core.ui.components.AppPreference as SettingClickableItem
import com.android.purebilibili.core.ui.components.rememberAdaptiveListVisualCapabilities
import com.bilipai.desktop.settings.DesktopSettingsVectors
@Composable
internal fun SettingsImageSavePathEntry(customImageSavePath: String?, onImageSavePathClick: () -> Unit) {
'''+textwrap.indent(declarations,'    ')+'\n    SettingsCardGroup {\n'+textwrap.indent(row,'        ')+'\n    }\n}\n'
    dialog_source='''package com.android.purebilibili.feature.settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.components.AppText
@Composable
internal fun DesktopOriginalImageSavePathDialog(
    imageSaveTreeUri: String?, onDismiss: () -> Unit,
    onSelectDirectory: () -> Unit, onRestoreDefault: () -> Unit,
) {
'''+textwrap.indent(select_dialog(repo),'    ')+'\n}\n'
    return [host.write(output,SECTIONS,read(repo,SECTIONS),row_source,'SettingsImageSavePathEntry.kt'),
        host.write(output,SCREEN,read(repo,SCREEN),dialog_source,'DesktopOriginalImageSavePathDialog.kt')]
def inventory(repo):return [dict(path=p,mode='policy-extract',features=['settings-image-save-path-ui'],sha256=hashlib.sha256(read(repo,p).encode()).hexdigest(),existingIdentityMerge=True) for p in (SECTIONS,SCREEN)]
if __name__=='__main__':
    cli=argparse.ArgumentParser(description=__doc__);cli.add_argument('--repo',type=Path,required=True);cli.add_argument('--output',type=Path);cli.add_argument('--inventory',action='store_true');args=cli.parse_args()
    if args.inventory:print(json.dumps(inventory(args.repo.resolve()),indent=2))
    if args.output:print('Generated',len(generate(args.repo.resolve(),args.output.resolve())))
