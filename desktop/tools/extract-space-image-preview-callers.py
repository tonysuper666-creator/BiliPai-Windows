"""Source-preserving stable Space callers. The sole preview producer owns its renderer."""
from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import hashlib,importlib.util,sys
sys.dont_write_bytecode=True
SOURCE='app/src/main/java/com/android/purebilibili/feature/space/SpaceScreen.kt'
PIN='4fa39d30048c5d3c371c0a7b44258370825c93b1e60e321bc8663e92e9311a2e'
def generate(repo:Path, output:Path):
    original=(_desktop_canonical_source(repo, SOURCE)).read_text(encoding='utf-8').replace('\r\n','\n').replace('\r','\n')
    assert hashlib.sha256(original.encode()).hexdigest()==PIN
    spec=importlib.util.spec_from_file_location('space_image_caller_parser',repo/'desktop/tools/sync-upstream.py')
    parser=importlib.util.module_from_spec(spec);spec.loader.exec_module(parser)
    tokens=parser.kotlin_tokens(original)
    def block(anchor):
        a=original.index(anchor);start=next(i for i,t in enumerate(tokens) if t[1]>=a and t[0]=='if');end=start
        while tokens[end][0]!='{':end+=1
        depth=1
        while depth:end+=1;depth+=(tokens[end][0]=='{')-(tokens[end][0]=='}')
        return original[tokens[start][1]:tokens[end][2]]
    avatar=block('    if (showAvatarPreview && avatarPreviewUrl.isNotBlank()) {')
    top=block('    if (showTopPhotoPreview && topPhotoPreviewEnabled) {')
    assert avatar.count('onDismiss = { showAvatarPreview = false }')==1
    assert top.count('onDismiss = { showTopPhotoPreview = false }')==1
    a=original.index('    val density = LocalDensity.current',original.index('    val avatarPreviewUrl ='))
    b=original.index('    // 装扮头图',a)
    c=original.index('    val topPhotoPreviewImages = remember(',b)
    d=original.index('    if (showTopPhotoPreview && topPhotoPreviewEnabled)',c)
    corner=original[a:b]
    selection=original[c:d].replace('currentSuccessState?.userInfo?.topImages','topImages')
    def declaration(anchor):
        a=original.index(anchor); start=next(i for i,t in enumerate(tokens) if t[1]>=a and t[0]=='private')
        end=start
        while tokens[end][0]!='(': end+=1
        parameter_depth=1
        while parameter_depth:
            end+=1; parameter_depth+=(tokens[end][0]=='(')-(tokens[end][0]==')')
        while tokens[end][0]!='{': end+=1
        depth=1
        while depth: end+=1; depth+=(tokens[end][0]=='{')-(tokens[end][0]=='}')
        return original[tokens[start][1]:tokens[end][2]]
    banner=declaration('private fun SpaceHeaderBanner(')
    badge=declaration('private fun SpaceHeaderTitleBadge(')
    banner=banner.replace('private fun SpaceHeaderBanner(', 'internal fun DesktopOriginalSpaceHeaderBanner(', 1)
    # The original Coil builder receives desktop PlatformContext. The renderer/body,
    # pager, branch order, title, alignment, progress and request keys stay intact.
    banner=banner.replace('LocalContext.current', 'LocalPlatformContext.current')
    text='// GENERATED from '+SOURCE+'; do not edit.\n// LF-normalized SHA-256: '+PIN+'\npackage com.android.purebilibili.feature.space\n'+\
      'import androidx.compose.runtime.*\nimport androidx.compose.ui.geometry.Rect\nimport androidx.compose.ui.platform.LocalDensity\n'+\
      'import androidx.compose.foundation.background\nimport androidx.compose.foundation.layout.*\nimport androidx.compose.foundation.pager.HorizontalPager\nimport androidx.compose.foundation.pager.rememberPagerState\n'+\
      'import androidx.compose.material3.MaterialTheme\nimport androidx.compose.ui.Modifier\nimport androidx.compose.ui.Alignment\nimport androidx.compose.ui.graphics.Brush\nimport androidx.compose.ui.graphics.Color\n'+\
      'import androidx.compose.ui.layout.ContentScale\nimport androidx.compose.ui.text.font.FontWeight\nimport androidx.compose.ui.text.style.TextOverflow\nimport androidx.compose.ui.unit.dp\n'+\
      'import coil3.compose.AsyncImage\nimport coil3.compose.LocalPlatformContext\nimport coil3.request.ImageRequest\nimport coil3.request.crossfade\n'+\
      'import com.android.purebilibili.core.ui.components.AppText\nimport com.android.purebilibili.core.ui.components.AppLinearProgressIndicator\n'+\
      'import com.android.purebilibili.feature.dynamic.components.resolveImagePreviewPlaceholderCacheKey\n'+\
      'import com.android.purebilibili.data.model.response.SpaceTopImageItem\nimport com.android.purebilibili.feature.dynamic.components.ImagePreviewDialog\n'+\
      '@Composable\ninternal fun DesktopOriginalSpaceAvatarPreview(showAvatarPreview:Boolean, avatarPreviewUrl:String, avatarSourceRect:Rect?, sourceKey:String?=null, onDismiss:()->Unit) {\n'+\
      corner+'    '+avatar.replace('onDismiss = { showAvatarPreview = false }','sourceKey = sourceKey,\n            onDismiss = onDismiss')+'\n}\n'+\
      '@Composable\ninternal fun DesktopOriginalSpaceTopPhotoPreview(showTopPhotoPreview:Boolean, topPhotoBannerUrl:String?, topImages:List<SpaceTopImageItem>?, previewUrl:String, topPhotoSourceRect:Rect?, sourceKey:String?=null, onDismiss:()->Unit) {\n'+\
      selection+'    '+top.replace('onDismiss = { showTopPhotoPreview = false }','sourceKey = sourceKey,\n            onDismiss = onDismiss')+'\n}\n'+\
      '@Composable\n'+banner+'\n\n@Composable\n'+badge+'\n'
    destination=output/'com/android/purebilibili/feature/space/DesktopOriginalSpaceImagePreviewCallers.kt'
    destination.parent.mkdir(parents=True,exist_ok=True);destination.write_text(text,encoding='utf-8',newline='\n')
    return destination
if __name__=='__main__':
    assert len(sys.argv)==3
    generate(Path(sys.argv[1]).resolve(),Path(sys.argv[2]).resolve())
