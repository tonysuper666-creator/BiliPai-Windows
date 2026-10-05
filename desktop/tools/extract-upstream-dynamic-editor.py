"""Sole fixed-source dynamic editor UI producer with existing Windows bindings.

The original full-window sheet, draft controls and dialogs are retained.
Source drift rejects the build by manifest LF hash and exact fixed Git blob.
"""
from v025_source_paths import canonical_source as _desktop_canonical_source
from v029_comment_time import apply as apply_original_comment_time
from v029_reply_renderer import rich_link_policy as advance_reply_link_policy
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

def read(repo, p): return (_desktop_canonical_source(repo, p)).read_text(encoding='utf-8').replace('\r\n', '\n')
def load(repo, name, path):
    spec = importlib.util.spec_from_file_location(name, repo / path)
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module); return module
def pinned(repo):
    identity = load(Path(__file__).resolve().parent, 'editor_fixed_identity', 'extract-upstream-dynamic-reply-protocol.py')
    return identity.load_pinned_sources(repo, list(dict.fromkeys(EDITOR + DIRECT + REFERENCES)))

def inventory(repo):
    source_texts, source_identities = pinned(repo)
    selected = EDITOR + [BASE + 'feature/video/viewmodel/VideoCommentViewModel.kt', COMP + 'DynamicCommentSheet.kt', BASE + 'feature/video/ui/components/ReplyComponents.kt', BASE + 'data/repository/DynamicCreateRepository.kt', BASE + 'data/repository/CommentRepository.kt']
    unique = list(dict.fromkeys(EDITOR + DIRECT + REFERENCES))
    return [dict(path=p, mode='direct' if p in DIRECT else 'policy-extract' if p in selected else 'reference-only', features=['dynamic-editor-detail-parity'], sha256=hashlib.sha256(source_texts[p].encode()).hexdigest()) for p in unique]

def generate(repo, output, standalone=False):
    source_texts, source_identities = pinned(repo)
    host = load(repo, 'editor_host', 'desktop/tools/extract-upstream-plugins.py')
    media = host.media_extractor(repo); parser = media.parser_for(repo)
    output.mkdir(parents=True, exist_ok=True); files=[]
    def emit(p,s,n): files.append(host.write(output,p,source_texts[p],s,n))
    def sub(s,a,b): return host.substitute(s,a,b)
    for p in DIRECT:
        if standalone: emit(p,source_texts[p],'DesktopOriginal'+Path(p).name)
    appearance=load(repo,'editor_declarations','desktop/tools/extract-appearance-platform.py')
    p=BASE+'feature/dynamic/DynamicViewModel.kt';s=source_texts[p]
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
    p=BASE+'feature/video/viewmodel/VideoCommentViewModel.kt';s=source_texts[p]
    body=appearance.declarations(parser,s,['CommentSortMode','SubReplyUiState'])
    emit(p,'package com.android.purebilibili.feature.video.viewmodel\nimport com.android.purebilibili.data.model.response.ReplyItem\nimport kotlinx.collections.immutable.*\n'+body,'DesktopOriginalDynamicCommentModels.kt')
    p=COMP+'DynamicCommentSheet.kt';s=source_texts[p]
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
    p=BASE+'feature/video/ui/components/ReplyComponents.kt';s=source_texts[p]
    s, time_selection = apply_original_comment_time(repo, p, s)
    s, link_selection = advance_reply_link_policy(repo, s)
    (output / 'v029-reply-link-selection.json').write_text(
        json.dumps(link_selection, ensure_ascii=True, indent=2) + '\n', encoding='utf8')
    (output / 'v029-comment-time-selection.json').write_text(
        json.dumps(time_selection, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    # Exact pure rich-text/layout/model policies needed by the future ReplyItemView
    # producer. This deliberately emits neither a fake ReplyItemView nor its
    # Android bitmap/gallery, translation, block-user or cached-title consumer.
    a=s.index('internal val EMOTE_TOKEN_PATTERN');b=s.index('// 标题缓存有界化：',a)
    c=s.index('internal data class ReplyItemLayoutPolicy',b);d=s.index('@Composable\nfun ReplyHeader',c)
    body=s[a:b]+s[c:d]
    body+=appearance.declarations(parser,s,['normalizeHttpImageUrl','resolveDecorationImageUrl','parseHexColorOrNull'])
    emit(p,'''package com.android.purebilibili.feature.video.ui.components
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.foundation.text.appendInlineContent
import com.android.purebilibili.core.util.BilibiliUrlParser
import com.android.purebilibili.core.util.FormatUtils
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
        s=source_texts[p]
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
            # Retain the complete original stable full-window Material sheet.
            # Only Android's activity result launcher maps to the same owned
            # Windows selection callback. The original 18-item cap is retained.
            s=sub(s,'    var text by remember(initialDraft)',
                '    val platform = LocalDesktopDynamicEditorBindings.current\n    var text by remember(initialDraft)')
            a=s.index('    val picker = rememberLauncherForActivityResult(')
            b=s.index('\n    val canPublish =',a)
            original_picker=s[a:b]
            expected='    val picker = rememberLauncherForActivityResult(\n        PickMultipleGalleryVisualMedia(maxItems = MAX_DYNAMIC_IMAGES)\n    ) { uris ->\n        if (uris.isNotEmpty()) {\n            imageUris = (imageUris + uris.map { it.toString() }).distinct().take(MAX_DYNAMIC_IMAGES)\n        }\n    }\n'
            if original_picker!=expected: raise ValueError('Original full composer gallery result changed')
            s=s[:a]+s[b:]
            picker_call='platform.pickImages(MAX_DYNAMIC_IMAGES) { uris ->\n' +                 '                                            if (platform.isOwned() && uris.isNotEmpty()) {\n' +                 '                                                imageUris = (imageUris + uris.map { it.toString() }).distinct().take(MAX_DYNAMIC_IMAGES)\n' +                 '                                            }\n                                        }'
            s=sub(s,'picker.launch(\n                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)\n                                        )',picker_call)
            s=sub(s,'picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))',picker_call)
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
        # Preserve every original UI renderer and branch outside the explicit platform seams.
        emit(p,s,'DesktopOriginal'+Path(p).name)
    (output / 'source-identity.json').write_text(json.dumps(source_identities, indent=2) + '\n', encoding='utf-8')
    (output / 'original-editor-source-bodies.json').write_text(json.dumps(
        [dict(path=p, sha256Lf=hashlib.sha256(source_texts[p].encode()).hexdigest(), originalText=source_texts[p]) for p in EDITOR],
        ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    return files

if __name__=='__main__':
    import argparse
    a=argparse.ArgumentParser(); a.add_argument('--repo',type=Path,required=True); a.add_argument('--output',type=Path,required=True); a.add_argument('--standalone',action='store_true');args=a.parse_args()
    generate(args.repo,args.output,args.standalone)
