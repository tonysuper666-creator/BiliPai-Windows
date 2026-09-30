"""Original alpha.9 dynamic editor, Windows native-theme slice.

This prepared producer does not install Main. Original liquid dock/segmented
renderers and the ReplyItemView/detail closure remain explicit pending sources.
No replacement liquid renderer, HomeSettings schema, API or draft model is emitted.
"""
from pathlib import Path
import hashlib, importlib.util, json, sys
sys.dont_write_bytecode = True
BASE = 'app/src/main/java/com/android/purebilibili/'
COMP = BASE + 'feature/dynamic/components/'
EDITOR = [COMP + n + '.kt' for n in ['DynamicPublishComposer', 'DynamicPublishPickers', 'DynamicCreateVoteDialog', 'DynamicCreateReserveDialog']]
DIRECT = [BASE + 'feature/dynamic/DynamicCommentReplyPolicy.kt', COMP + 'DynamicCommentActionPolicy.kt', BASE + 'feature/dynamic/DynamicCommentLoadPolicy.kt', BASE + 'feature/video/viewmodel/SubReplySortPolicy.kt', BASE + 'feature/video/ui/components/VideoCommentAppearance.kt']
DIRECT += ['design-system/src/main/java/com/android/purebilibili/core/ui/OfficialVerifyBadgePolicy.kt']
REFERENCES = [BASE + n + '.kt' for n in ['data/model/response/DynamicCreateModels', 'data/repository/DynamicCreateRepository', 'data/repository/CommentRepository', 'feature/dynamic/DynamicDetailScreen', 'feature/dynamic/DynamicCommentLoadPolicy', 'feature/dynamic/components/DynamicCommentSheet', 'feature/video/ui/components/ReplyComponents', 'feature/video/ui/components/SubReplyDetailComponents', 'feature/video/viewmodel/VideoCommentViewModel', 'feature/video/viewmodel/SubReplySortPolicy', 'feature/home/components/BottomBarMatchedLiquidChrome', 'feature/home/components/BottomBarLiquidSegmentedControl', 'feature/home/components/FloatingBottomBarGeometry']]

# Actual Main dynamic-tabs owns this exact original top-level renderer.
REFERENCES += [COMP + 'DynamicAdaptiveSegmentedControl.kt', BASE + 'feature/dynamic/DynamicViewModel.kt']

def read(repo, p): return (repo / p).read_text(encoding='utf-8').replace('\r\n', '\n')
def load(repo, name, path):
    spec = importlib.util.spec_from_file_location(name, repo / path)
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module); return module
def inventory(repo):
    selected = EDITOR + [BASE + 'feature/video/viewmodel/VideoCommentViewModel.kt', COMP + 'DynamicCommentSheet.kt', BASE + 'feature/video/ui/components/ReplyComponents.kt', BASE + 'data/repository/DynamicCreateRepository.kt', BASE + 'data/repository/CommentRepository.kt']
    unique = list(dict.fromkeys(EDITOR + DIRECT + REFERENCES))
    return [dict(path=p, mode='direct' if p in DIRECT else 'policy-extract' if p in selected else 'reference-only', features=['dynamic-editor-detail-parity'], sha256=hashlib.sha256(read(repo,p).encode()).hexdigest()) for p in unique]

def generate(repo, output, standalone=False):
    host = load(repo, 'editor_host', 'desktop/tools/extract-upstream-plugins.py')
    media = host.media_extractor(repo); parser = media.parser_for(repo)
    output.mkdir(parents=True, exist_ok=True); files=[]
    def emit(p,s,n): files.append(host.write(output,p,read(repo,p),s,n))
    def sub(s,a,b): return host.substitute(s,a,b)
    for p in DIRECT:
        if standalone: emit(p,read(repo,p),'DesktopOriginal'+Path(p).name)
    appearance=load(repo,'editor_declarations','desktop/tools/extract-appearance-platform.py')
    p=BASE+'feature/dynamic/DynamicViewModel.kt';s=read(repo,p)
    # Keep the original post-publish AUTH check, delay, warning and cancellation.
    # Root supplies the current page's read boundary after dismissing the composer.
    import re, textwrap
    marker='                if (createdId.isBlank()) return@launch'
    if s.count(marker)!=1: raise ValueError('Original publish verification boundary changed')
    a=s.index(marker);b=s.index('\n            } catch (e: CancellationException)',a)
    body=textwrap.dedent(s[a:b]).replace('return@launch','return')
    body=sub(body,'NetworkModule.dynamicApi.getDynamicDetail(id = createdId)','fetchDetail(createdId)')
    constants=re.findall(r'private const val DYNAMIC_CREATE_ANTIFRAUD_DELAY_MS = [^\n]+',s)
    if len(constants)!=1: raise ValueError('Original publish verification delay changed')
    emit(p,'''package com.android.purebilibili.feature.dynamic
import com.android.purebilibili.data.model.response.DynamicDetailResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
'''+constants[0]+'''
internal suspend fun verifyDesktopOriginalDynamicPublish(
    createdId: String, fetchDetail: suspend (String) -> DynamicDetailResponse,
    onResult: (Boolean, String) -> Unit,
) {
'''+textwrap.indent(body,'    ')+'}\n','DesktopOriginalDynamicPublishVerification.kt')
    p=BASE+'feature/video/viewmodel/VideoCommentViewModel.kt';s=read(repo,p)
    body=appearance.declarations(parser,s,['CommentSortMode','SubReplyUiState'])
    emit(p,'package com.android.purebilibili.feature.video.viewmodel\nimport com.android.purebilibili.data.model.response.ReplyItem\nimport kotlinx.collections.immutable.*\n'+body,'DesktopOriginalDynamicCommentModels.kt')
    p=COMP+'DynamicCommentSheet.kt';s=read(repo,p)
    body=appearance.declarations(parser,s,['DynamicCommentSortControl','DynamicCommentSortControlSpec','resolveDynamicCommentSortControlSpec','DynamicInlineCommentHeader'])
    emit(p,'''package com.android.purebilibili.feature.dynamic.components
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.AppChromeSizeTokens
import com.android.purebilibili.core.ui.AppSpacingTokens
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.feature.dynamic.resolveDynamicCommentCountLabel
import com.android.purebilibili.feature.video.viewmodel.CommentSortMode
import top.yukonga.miuix.kmp.blur.Backdrop as MiuixBackdrop
'''+body,'DesktopOriginalDynamicInlineCommentHeader.kt')
    p=BASE+'feature/video/ui/components/ReplyComponents.kt';s=read(repo,p)
    # Exact pure rich-text/layout/model policies needed by the future ReplyItemView
    # producer. This deliberately emits neither a fake ReplyItemView nor its
    # Android bitmap/gallery, translation, block-user or cached-title consumer.
    a=s.index('private val EMOTE_TOKEN_PATTERN');b=s.index('// 标题缓存有界化：',a)
    c=s.index('internal data class ReplyItemLayoutPolicy',b);d=s.index('@Composable\nfun ReplyHeader',c)
    body=s[a:b]+s[c:d]
    body+=appearance.declarations(parser,s,['normalizeHttpImageUrl','resolveDecorationImageUrl','parseHexColorOrNull','replyPublishDayFormatter','formatTime'])
    emit(p,'''package com.android.purebilibili.feature.video.ui.components
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.foundation.text.appendInlineContent
import com.android.purebilibili.core.util.BilibiliUrlParser
import com.android.purebilibili.core.theme.calculateContrastRatio
import com.android.purebilibili.core.ui.OfficialVerifyBadgeTone
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.dynamic.components.*
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.*
'''+body,'DesktopOriginalDynamicRichCommentPolicy.kt')
    for p in EDITOR:
        s=read(repo,p)
        # One required platform captures the current existing operations/session.
        s='\n'.join(l for l in s.splitlines() if not any(x in l for x in [
            'import androidx.activity.', 'import androidx.compose.ui.platform.LocalContext',
            'import androidx.lifecycle.compose.collectAsStateWithLifecycle',
            'import com.android.purebilibili.core.store.HomeSettings', 'import com.android.purebilibili.core.store.SettingsManager',
            'import com.android.purebilibili.core.util.PickMultipleGalleryVisualMedia',
            'import com.android.purebilibili.feature.home.components.',
            'import android.app.', 'import com.android.purebilibili.data.repository.CommentRepository',
            'import com.android.purebilibili.data.repository.DynamicCreateRepository']))+'\n'
        s=s.replace('package com.android.purebilibili.feature.dynamic.components\n', 'package com.android.purebilibili.feature.dynamic.components\n\nimport com.bilipai.desktop.ui.LocalDesktopDynamicEditorBindings\n')
        if p.endswith('DynamicPublishComposer.kt'):
            a=s.index('    val context = LocalContext.current');b=s.index('    var text by',a)
            s=s[:a]+'    val platform = LocalDesktopDynamicEditorBindings.current\n'+s[b:]
            a=s.index('    val picker = rememberLauncherForActivityResult(');b=s.index('\n    AppAlertDialog(',a)
            s=s[:a]+s[b:]
            s=sub(s,'            val publishChromeBackdrop = if (liquidGlassEnabled) rememberLayerBackdrop() else null\n','')
            s=sub(s,'            val visibilityLabels = remember { listOf("公开", "仅自己可见") }\n','')
            a=s.index('                if (liquidGlassEnabled) {');b=s.index('                Column(',a)
            s=s[:a]+s[b:]
            a=s.index('                    BottomBarMatchedReusableLiquidDock(');b=s.index('                        LazyRow(',a)
            s=s[:a]+'''                    // Original native toolbar body. The Android liquid dock is pending,
                    // not replaced by a shader or an invented HomeSettings value.
                    Box(modifier = Modifier.fillMaxWidth().height(AppSpacingTokens.TripleExtraLarge)) {
                        val liquidChromeActive = false
'''+s[b:]
            a=s.index('                                        picker.launch(');b=s.index('\n                                    }',a)
            s=s[:a]+'''                                        platform.pickImages(9) { uris ->
                                            if (platform.isOwned() && uris.isNotEmpty()) {
                                                imageUris = (imageUris + uris.map { it.toString() }).distinct().take(9)
                                            }
                                        }'''+s[b:]
            a=s.index('                    if (liquidGlassEnabled) {');b=s.index('                        AppNativeSegmentedControl(',a)
            c=s.index('\n                    }',b)
            native=s[b:c]
            s=s[:a]+native+s[c+len('\n                    }'):]
        elif p.endswith('DynamicPublishPickers.kt'):
            # Actual Main's catalog is shared with the card owner; no new cache.
            for needle in ['    var query by remember { mutableStateOf("") }','    var emotes by remember { mutableStateOf(DynamicEmoteCatalog.snapshot()) }']:
                s=s.replace(needle,'    val platform = LocalDesktopDynamicEditorBindings.current\n'+needle)
            s=s.replace('CommentRepository.searchMentionUsers','platform.searchMentionUsers').replace('DynamicCreateRepository.searchPublishTopics','platform.searchPublishTopics')
            s=s.replace('DynamicEmoteCatalog.','platform.emotes.')
        elif p.endswith('DynamicCreateVoteDialog.kt'):
            s=sub(s,'    val scope = rememberCoroutineScope()','    val platform = LocalDesktopDynamicEditorBindings.current\n    val scope = rememberCoroutineScope()')
            s=s.replace('DynamicCreateRepository.createVote','platform.createVote').replace('durationSeconds = durationDays * 24 * 60 * 60','durationDays = durationDays')
        elif p.endswith('DynamicCreateReserveDialog.kt'):
            s=sub(s,'    val context = LocalContext.current','    val platform = LocalDesktopDynamicEditorBindings.current')
            s=s.replace('DynamicCreateRepository.createReserve','platform.createReserve')
            a=s.index('                        val calendar = Calendar.getInstance().apply { timeInMillis = startAtMillis }');b=s.index('\n                    }',a)
            s=s[:a]+'''                        platform.chooseDateAndTime(startAtMillis) { year, month, day, hour, minute ->
                            if (platform.isOwned()) {
                                startAtMillis = Calendar.getInstance().apply {
                                    set(year, month, day, hour, minute, 0)
                                    set(Calendar.MILLISECOND, 0)
                                }.timeInMillis
                            }
                        }'''+s[b:]
        # Miuix layerBackdrops in Vote are original and available; no fake renderer.
        # Composer no longer references its unavailable original liquid branch.
        if p.endswith('DynamicPublishComposer.kt'):
            s='\n'.join(l for l in s.splitlines() if 'import top.yukonga.miuix.kmp.blur.' not in l)+'\n'
        emit(p,s,'DesktopOriginal'+Path(p).name)
    return files

if __name__=='__main__':
    import argparse
    a=argparse.ArgumentParser(); a.add_argument('--repo',type=Path,required=True); a.add_argument('--output',type=Path,required=True); a.add_argument('--standalone',action='store_true');args=a.parse_args()
    generate(args.repo,args.output,args.standalone)
