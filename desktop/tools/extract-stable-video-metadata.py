"""Original honor/declaration/team renderers, policy and status-query bodies."""
from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import hashlib, importlib.util, json, re, sys
sys.dont_write_bytecode=True
APP='app/src/main/java/com/android/purebilibili/'
def module(name,path):
    spec=importlib.util.spec_from_file_location(name,path);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
def generate(repo,output):
    parser=module('metadata_parser',repo/'desktop/tools/sync-upstream.py')
    selector=module('metadata_selector',repo/'desktop/tools/extract-appearance-platform.py')
    media=module('metadata_functions',repo/'desktop/tools/extract-upstream-media.py')
    pins={r['path']:r['sha256'] for r in json.loads((repo/'desktop/upstream-sources.json').read_text(encoding='utf-8'))['sources']}
    records=[]
    def read(path):
        text=(_desktop_canonical_source(repo, path)).read_text(encoding='utf-8').replace('\r\n','\n');assert hashlib.sha256(text.encode()).hexdigest()==pins[path];return text
    def emit(path,body,name):
        package=re.search(r'(?m)^package (\S+)',body).group(1);target=output/package.replace('.','/')/name
        target.parent.mkdir(parents=True,exist_ok=True)
        target.write_text('// GENERATED from '+path+'; do not edit.\n// LF-normalized SHA-256: '+pins[path]+'\n'+body,encoding='utf-8',newline='\n')
        records.append(dict(original=path,originalSha256Lf=pins[path],output=str(target.relative_to(output)),generatedSha256Bytes=hashlib.sha256(target.read_bytes()).hexdigest()))
    path=APP+'feature/video/ui/section/VideoInfoSection.kt';original=read(path)
    beginning=original.index('    val videoBadges = remember(')
    ending=original.index('    //  尝试获取共享元素作用域',beginning)
    badge_state=original[beginning:ending]
    begin=original.index('        if (videoBadges.isNotEmpty()) {')
    end=original.index('        // [新增] BGM Info Row',begin)
    original_block=original[begin:end]
    bodies=[]
    for name in ['VideoDetailBadgeChip','VideoArgueMsgRow','VideoHonorChip','CreatorTeamSection','CreatorTeamMemberChip']:
        body=media.function(original,name,parser)
        if name in ('VideoDetailBadgeChip','VideoArgueMsgRow','VideoHonorChip'):
            body=body.replace('private fun '+name,'internal fun '+name,1)
        if name=='CreatorTeamSection':
            assert body.count('com.android.purebilibili.data.repository.ActionRepository')==3
            body=body.replace('com.android.purebilibili.data.repository.ActionRepository','ActionRepository')
            body=body.replace('private fun CreatorTeamSection','internal fun CreatorTeamSection',1)
            body=body.replace('    if (staff.isEmpty()) return','    val ActionRepository = LocalDesktopCreatorTeamBindings.current\n    if (staff.isEmpty()) return',1)
        if name=='CreatorTeamMemberChip':
            assert body.count('ImageRequest.Builder(LocalContext.current)')==1
            body=body.replace('ImageRequest.Builder(LocalContext.current)','ImageRequest.Builder(LocalPlatformContext.current)')
        bodies.append('@Composable\n'+body)
    header='''package com.android.purebilibili.feature.video.ui.section
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.*
import com.android.purebilibili.core.ui.*
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.core.util.FormatUtils
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.video.ui.*
import com.bilipai.desktop.ui.LocalDesktopCreatorTeamBindings
import kotlinx.coroutines.launch
'''
    wrapper='''@Composable
internal fun DesktopOriginalVideoHonors(info:ViewInfo,argueMsgShown:Boolean,onDescriptionUrlClick:((String)->Unit)?) {
'''+badge_state+'    Column {\n'+original_block+'    }\n}\n'
    emit(path,header+wrapper+'\n'+'\n\n'.join(bodies),'DesktopOriginalVideoMetadata.kt')
    path=APP+'feature/video/ui/section/VideoInfoDisplayPolicy.kt';original=read(path)
    names=['resolveVideoHonorChipText','resolveVideoHonorJumpUrl','shouldShowCreatorTeamSection','resolveVideoDetailBadges']
    emit(path,'package com.android.purebilibili.feature.video.ui.section\nimport com.android.purebilibili.data.model.response.ViewInfo\n'+
         selector.declarations(parser,original,names),'DesktopOriginalVideoMetadataPolicy.kt')
    path=APP+'core/store/SettingsManager.kt';original=read(path)
    manager=original[original.index('object SettingsManager {')+len('object SettingsManager {'):original.rfind('}')]
    emit(path,'''package com.android.purebilibili.core.store
import com.bilipai.desktop.plugins.DesktopPluginContext as Context
import com.bilipai.desktop.plugins.booleanPreferencesKey
import com.bilipai.desktop.settings.dynamicSettingsDataStore as settingsDataStore
import kotlinx.coroutines.flow.*
internal object DesktopOriginalVideoMetadataSettings {
'''+selector.declarations(parser,manager,['KEY_VIDEO_ARGUE_MSG_SHOWN','getVideoArgueMsgShown','setVideoArgueMsgShown'])+'\n}\n',
         'DesktopOriginalVideoMetadataSettings.kt')
    path=APP+'data/repository/ActionRepository.kt';original=read(path)
    body=media.function(original,'checkFollowStatus',parser)
    body=re.sub(r'(?m)^\s*(?:com\.android\.purebilibili\.core\.util\.Logger\.d|android\.util\.Log\.e)\([^\n]+\)\n','\n',body)
    body=body.replace('} catch (e: Exception) {','} catch (cancelled:kotlinx.coroutines.CancellationException) {\n            throw cancelled\n        } catch (e: Exception) {')
    emit(path,'''package com.android.purebilibili.data.repository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
internal class DesktopOriginalCreatorStatus(private val api:com.android.purebilibili.core.network.BilibiliApi) {
'''+body+'\n}\n','DesktopOriginalCreatorStatus.kt')
    (output/'source-bindings.json').write_text(json.dumps(records,indent=2)+'\n',encoding='utf-8',newline='\n')
if __name__=='__main__':generate(Path(sys.argv[1]).resolve(),Path(sys.argv[2]).resolve())
