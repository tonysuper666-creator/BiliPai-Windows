"""Original BlockedListContent, filename and repository pacing constants for Windows."""
from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import argparse,hashlib,importlib.util,json
BASE='app/src/main/java/com/android/purebilibili/'
SOURCES=[BASE+'feature/settings/screen/BlockedListScreen.kt',BASE+'feature/settings/screen/BlockedListFileService.kt',
         BASE+'feature/settings/ui/SettingsPageScaffold.kt',BASE+'data/repository/BlockedUpRepository.kt',BASE+'data/repository/BilibiliBlockedListSyncRepository.kt',
         BASE+'core/ui/components/UserLevelBadge.kt']
DISSOLVE_CALLER_LEAVES=[('import com.android.purebilibili.core.ui.animation.jiggleOnDissolve', 'import com.bilipai.desktop.ui.jiggleOnDissolve'), ('com.android.purebilibili.core.ui.animation.MaybeDissolvableVideoCard(', 'com.bilipai.desktop.ui.DesktopReplyDissolvableContainer('), ('com.android.purebilibili.core.ui.animation.DissolveAnimationPreset.', 'com.bilipai.desktop.ui.DissolveAnimationPreset.')]

def original(repo,name):return (_desktop_canonical_source(repo, name)).read_text(encoding='utf-8').replace('\r\n','\n')
def write(output,package,name,body):
    path=output/package.replace('.','/')/name;path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(body,encoding='utf-8',newline='\n');return path
def generate(repo,output):
    spec=importlib.util.spec_from_file_location('blocked_ui_parser',repo/'desktop/tools/sync-upstream.py')
    parser=importlib.util.module_from_spec(spec);spec.loader.exec_module(parser)
    spec=importlib.util.spec_from_file_location('blocked_ui_selector',repo/'desktop/tools/extract-discovery-platform.py')
    selector=importlib.util.module_from_spec(spec);spec.loader.exec_module(selector)
    source=original(repo,SOURCES[0]);start=source.index('@Composable\nfun BlockedListContent(')
    imports=[line for line in source[:start].splitlines() if line.startswith('import ') and not any(x in line for x in [
        'androidx.activity','androidx.lifecycle','LocalContext','ShareUtils','BlockedUpRepository','BilibiliBlockedListSyncRepository',
        'buildBlockedUpShareText','parseBlockedUpShareText','SettingsPageScaffold','rememberAppBackIcon','kotlinx.coroutines'])]
    content='package com.android.purebilibili.feature.settings\n'+ '\n'.join(imports)+'\n\n'+source[start:]
    original_content=content;leaves=[]
    for before,after in DISSOLVE_CALLER_LEAVES:
        assert content.count(before)==1,before
        at=content.index(before);leaves.append((at,before,after));content=content[:at]+after+content[at+len(before):]
    inverse=content
    for at,before,after in reversed(leaves):
        assert inverse[at:at+len(after)]==after;inverse=inverse[:at]+before+inverse[at+len(after):]
    assert inverse==original_content
    paths=[write(output,'com.android.purebilibili.feature.settings','DesktopUpstreamBlockedListContent.kt',content)]
    # The complete original SettingsPageScaffold now owns its padding local/helper.
    # Retire only the exact old generated selection; unexpected edits are never deleted.
    scaffold=original(repo,SOURCES[2]);start=scaffold.index('internal val LocalSettingsTopContentPadding');end=scaffold.index('@Composable\ninternal fun SettingsBottomBarScrollEffect',start)
    previous_padding=output/'com/android/purebilibili/feature/settings/ui/DesktopUpstreamBlockedListPadding.kt'
    if previous_padding.exists():
        expected='package com.android.purebilibili.feature.settings.ui\nimport androidx.compose.foundation.layout.PaddingValues\nimport androidx.compose.runtime.*\nimport androidx.compose.ui.unit.dp\n\n'+scaffold[start:end]
        assert not previous_padding.is_symlink() and previous_padding.resolve().is_relative_to(output.resolve())
        assert previous_padding.read_text(encoding='utf8').replace('\r\n','\n')==expected
        previous_padding.unlink()
    file=original(repo,SOURCES[1]);filename=selector.selected(file,'fun','buildBlockedListJsonFileName',parser)
    paths.append(write(output,'com.android.purebilibili.feature.settings','DesktopUpstreamBlockedListFilename.kt',
        'package com.android.purebilibili.feature.settings\n\n'+filename+'\n'))
    pacing='package com.android.purebilibili.data.repository\n\n'
    for sourceName,mappings in [(SOURCES[3],[('BLOCKED_UP_PROFILE_REFRESH_DELAY_MS','originalBlockedProfileRefreshDelay')]),(SOURCES[4],[
        ('BLOCKED_LIST_PAGE_SIZE','originalBlockedListPageSize'),('BLOCKED_LIST_MAX_PAGES','originalBlockedListMaxPages'),
        ('BLOCKED_LIST_PAGE_DELAY_MS','originalBlockedListPageDelay')])]:
        text=original(repo,sourceName)
        for constant,function in mappings:
            line=next(line for line in text.splitlines() if line.startswith('private const val '+constant+' = '))
            pacing+=line+'\ninternal fun '+function+'() = '+constant+'\n'
    paths.append(write(output,'com.android.purebilibili.data.repository','DesktopUpstreamBlockedListPacing.kt',pacing))
    badge=original(repo,SOURCES[5]).replace('import com.android.purebilibili.R\n','').replace('import androidx.compose.ui.res.imageResource\n',
        'import androidx.compose.runtime.remember\nimport androidx.compose.ui.graphics.toComposeImageBitmap\nimport javax.imageio.ImageIO\n')
    badge=badge.replace('private fun resolveUserLevelBadgeResId(asset: UserLevelBadgeAsset): Int',
        'private fun resolveUserLevelBadgeResId(asset: UserLevelBadgeAsset): String')
    for asset in ['lv0','lv1','lv2','lv3','lv4','lv5','lv6','lv6_s']:
        badge=badge.replace('R.drawable.'+asset+'\n','"blocked-up-badges/'+asset+'.png"\n')
    badge=badge.replace('ImageBitmap.imageResource(id = resolveUserLevelBadgeResId(badgeAsset))',
        'rememberBlockedUpBadgeBitmap(resolveUserLevelBadgeResId(badgeAsset))')
    badge+='''
@Composable
private fun rememberBlockedUpBadgeBitmap(path: String): ImageBitmap = remember(path) {
    requireNotNull(UserLevelBadgeAsset::class.java.getResourceAsStream("/$path")) { "等级图片资源缺失" }.use {
        requireNotNull(ImageIO.read(it)) { "等级图片资源无法解码" }.toComposeImageBitmap()
    }
}
'''
    paths.append(write(output,'com.android.purebilibili.core.ui.components','DesktopUpstreamUserLevelBadge.kt',badge))
    return paths
def inventory(repo):return [dict(path=p,mode='policy-extract',features=['settings-blocked-up'],sha256=hashlib.sha256(original(repo,p).encode()).hexdigest()) for p in SOURCES]
if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--repo',type=Path,required=True);p.add_argument('--output',type=Path);p.add_argument('--inventory',action='store_true');a=p.parse_args()
    if a.output:print('Generated',len(generate(a.repo.resolve(),a.output.resolve())))
    if a.inventory:print(json.dumps(inventory(a.repo.resolve()),indent=2))
