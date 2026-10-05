"""Original raw Reply render closure; task-only prepared producer.

The existing editor producer owns rich/layout policy declarations and public
format helpers. This producer owns only their original UI consumers, Android
platform seams and original thread renderer. Never emit that policy twice.
"""
from v025_source_paths import canonical_source as _desktop_canonical_source
from v029_comment_time import apply as apply_original_comment_time
from v029_comment_search import charged_delta
from pathlib import Path
import hashlib
import importlib.util
import json
import re
import sys

sys.dont_write_bytecode = True
BASE = 'app/src/main/java/com/android/purebilibili/'
REPLY = BASE + 'feature/video/ui/components/ReplyComponents.kt'
SUB = BASE + 'feature/video/ui/components/SubReplyDetailComponents.kt'
SAVER = BASE + 'feature/video/ui/components/ReplyCommentImageSaver.kt'
SHEET = BASE + 'feature/dynamic/components/DynamicCommentSheet.kt'
SOURCES = [REPLY, SUB, SAVER, SHEET,
           BASE + 'core/ui/skeleton/ContentLoadingSkeletons.kt',
           BASE + 'feature/video/ui/components/CommentInputBar.kt',
           BASE + 'core/ui/skeleton/SkeletonBreathing.kt',
           BASE + 'core/store/SkeletonSettingsStore.kt',
           BASE + 'feature/home/components/BottomBar.kt',
           BASE + 'core/ui/common/Modifiers.kt',
           BASE + 'core/store/SettingsManager.kt',
           BASE + 'feature/aicu/AicuNavigationPolicy.kt',
           BASE + 'feature/dynamic/DynamicDetailScreen.kt',
           BASE + 'feature/video/ui/components/SubReplySheet.kt',
           BASE + 'core/ui/animation/ParticleDissolveEffect.kt']


def read(repo, path):
    return (_desktop_canonical_source(repo, path)).read_text(encoding='utf-8').replace('\r\n', '\n')


def load(repo, name, path):
    spec = importlib.util.spec_from_file_location(name, repo / path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def replace_once(body, old, new):
    assert body.count(old) == 1, old[:100]
    return body.replace(old, new, 1)


def adapt_common(body):
    # All image requests use Coil's actual multiplatform context. The account and
    # file/clipboard/network authority stays in the mounted same-session binding.
    body = body.replace('import androidx.compose.ui.platform.LocalContext',
                        'import coil3.compose.LocalPlatformContext as LocalContext')
    body = body.replace('import coil3.imageLoader', 'import coil3.SingletonImageLoader')
    body = body.replace('context.imageLoader', 'SingletonImageLoader.get(context)')
    body = body.replace('import com.android.purebilibili.core.ui.AppModalBottomSheet',
                        'import com.bilipai.desktop.ui.DesktopDynamicTextSelectionSheet as AppModalBottomSheet')
    body = body.replace('import com.android.purebilibili.core.ui.animation.MaybeDissolvableVideoCard',
                        'import com.bilipai.desktop.ui.DesktopReplyDissolvableContainer as MaybeDissolvableVideoCard')
    lines = ['import android.content.Intent', 'import android.graphics.Bitmap',
             'import android.widget.Toast',
             'import com.android.purebilibili.core.store.SettingsManager',
             'import com.android.purebilibili.core.util.rememberStoragePermissionState',
             'import com.android.purebilibili.data.repository.BlockedUpRepository',
             'import com.android.purebilibili.data.repository.VideoRepository']
    body = '\n'.join(line for line in body.splitlines() if line not in lines) + '\n'
    body = body.replace('package com.android.purebilibili.feature.video.ui.components\n',
        'package com.android.purebilibili.feature.video.ui.components\n\n'
        'import com.bilipai.desktop.ui.LocalDesktopCommentBindings\n'
        'import com.bilipai.desktop.ui.DesktopReplyTransparentBoundsCropTransformation as TransparentBoundsCropTransformation\n'
        'import kotlinx.coroutines.CancellationException\nimport kotlinx.coroutines.ensureActive\n')
    body = body.replace('    val sheetContext = LocalContext.current', '    val sheetContext = LocalContext.current\n    val sheetPlatform = LocalDesktopCommentBindings.current')
    body = body.replace('SettingsManager\n        .getDetailedCommentTimeEnabled(sheetContext)', 'com.android.purebilibili.core.store.DesktopOriginalReplySettings\n        .getDetailedCommentTimeEnabled(sheetPlatform.context)')
    body = body.replace('SettingsManager.setDetailedCommentTimeEnabled(sheetContext, next)', 'if (sheetPlatform.isOwned()) com.android.purebilibili.core.store.DesktopOriginalReplySettings.setDetailedCommentTimeEnabled(sheetPlatform.context, next)')
    body = body.replace('Toast.makeText(\n                                    sheetContext,', 'Toast.makeText(\n                                    context,')
    body = body.replace('Toast.LENGTH_LONG', 'Toast.LENGTH_SHORT')
    body = body.replace('    val context = LocalContext.current\n    val scope = rememberCoroutineScope()',
                        '    val context = LocalContext.current\n    val platform = LocalDesktopCommentBindings.current\n    val scope = rememberCoroutineScope()')
    body = body.replace('    val context = LocalContext.current\n    val detailedCommentTimeEnabled = LocalDetailedCommentTimeEnabled.current\n    val scope = rememberCoroutineScope()',
                        '    val context = LocalContext.current\n    val platform = LocalDesktopCommentBindings.current\n    val detailedCommentTimeEnabled = LocalDetailedCommentTimeEnabled.current\n    val scope = rememberCoroutineScope()')
    if 'var wasRefreshing by remember(rootReply.rpid)' in body:
        body = body.replace('    val context = LocalContext.current\n    val showLoadedReplyCount', '    val context = LocalContext.current\n    val platform = LocalDesktopCommentBindings.current\n    val showLoadedReplyCount')
    body = body.replace('    val blockedUpRepository = remember { BlockedUpRepository.getInstance(context) }\n', '')
    body = body.replace('blockedUpRepository.blockUpWithBilibiliSync(', 'platform.blockUser(')
    body = body.replace('import androidx.lifecycle.compose.collectAsStateWithLifecycle\n',
                        'import androidx.compose.runtime.collectAsState\n')
    body = body.replace('.collectAsStateWithLifecycle(initialValue', '.collectAsState(initial')
    body = body.replace('com.android.purebilibili.data.repository.CommentGrpcRepository.translateReply(',
                        'platform.translateReply(')
    body = body.replace('saveReplyCommentImageToGallery(context, reply)',
                        'platform.saveCommentImage(buildReplyCommentImageSpec(reply))')
    # File selection/permission belongs to the explicit Windows save callback;
    # no pretend Android storage permission is exposed on Windows.
    body = body.replace('    var pendingSaveReply by remember(item.rpid) { mutableStateOf<ReplyItem?>(null) }\n', '')
    if '    val storagePermission = rememberStoragePermissionState' in body:
        start = body.index('    val storagePermission = rememberStoragePermissionState')
        end = body.index('    fun shareReplyComment()', start)
        body = body[:start] + '''    fun requestSaveReplyCommentImage() {
        if (platform.isOwned()) launchSaveReplyCommentImage(item)
    }
''' + body[end:]
    pattern = re.compile(r'    fun shareReplyComment\(\) \{\n        val sendIntent = Intent\(Intent.ACTION_SEND\)[\s\S]*?\n    \}')
    body, count = pattern.subn('''    fun shareReplyComment() {
        if (platform.isOwned()) platform.shareText(buildReplyCommentShareText(item), "分享评论")
    }''', body)
    assert count == 1
    body, count = re.subn(r'Toast\.makeText\(\s*context,\s*([\s\S]*?),\s*Toast\.LENGTH_SHORT\s*\)\.show\(\)',
                         lambda m: ('if (sheetPlatform.isOwned()) sheetPlatform.showFeedback(' + m.group(1) + ')' if '已切换为绝对时间' in m.group(1) else 'if (platform.isOwned()) platform.showFeedback(' + m.group(1) + ')'), body)
    assert count >= 3
    # Propagate cancellation and suppress late UI publications. Owner verification
    # is an adapter requirement; the original translated/original state remains.
    body = body.replace('                                            result.onSuccess { translated ->',
                        '                                            ensureActive()\n'
                        '                                            if (!platform.isOwned()) throw CancellationException("Reply owner retired")\n'
                        '                                            result.onSuccess { translated ->')
    body = body.replace('"${e.javaClass.simpleName}: ${e.message}"', '"翻译失败，请重试"')
    body = body.replace('                                            isTranslating = true\n',
                        '                                            isTranslating = true\n                                            try {\n')
    body = body.replace('                                            isTranslating = false\n',
                        '                                            } catch (cancelled: CancellationException) { throw cancelled\n'
                        '                                            } catch (failure: Exception) {\n'
                        '                                                ensureActive()\n'
                        '                                                if (platform.isOwned()) platform.showFeedback("翻译失败，请重试")\n'
                        '                                            } finally { isTranslating = false }\n')
    body = body.replace('            val success = platform.saveCommentImage(buildReplyCommentImageSpec(reply))',
                        '            val success = try { platform.saveCommentImage(buildReplyCommentImageSpec(reply)) }\n'
                        '                catch (cancelled: CancellationException) { throw cancelled }\n'
                        '                catch (failure: Exception) { ensureActive(); false }\n'
                        '            ensureActive()')
    block_begin = body.index('    fun blockReplyUser() {')
    block_end = body.index('\n    }\n', block_begin) + len('\n    }')
    block = body[block_begin:block_end]
    assert block.count('        scope.launch {') == 1
    block = block.replace('        scope.launch {', '        scope.launch {\n            try {', 1)
    block = block.replace('            if (platform.isOwned()) platform.showFeedback(result.message)',
                          '            ensureActive()\n            if (platform.isOwned()) platform.showFeedback(result.message)')
    assert block.count('\n        }\n    }') == 1
    block = block.replace('\n        }\n    }',
                          '\n            } catch (cancelled: CancellationException) { throw cancelled\n'
                          '            } catch (failure: Exception) {\n                ensureActive()\n'
                          '                if (platform.isOwned()) platform.showFeedback("屏蔽失败，请重试")\n'
                          '            }\n        }\n    }', 1)
    body = body[:block_begin] + block + body[block_end:]
    assert not any(token in body for token in ['Toast.', 'Intent(', 'BlockedUpRepository.', 'SettingsManager.'])
    return body


def generate(repo, output):
    host = load(repo, 'reply_original_host', 'desktop/tools/extract-upstream-plugins.py')
    media = host.media_extractor(repo)
    parser = media.parser_for(repo)
    appearance = load(repo, 'reply_original_declarations', 'desktop/tools/extract-appearance-platform.py')
    output.mkdir(parents=True, exist_ok=True)
    emitted = []

    def emit(path, body, filename):
        emitted.append(host.write(output, path, read(repo, path), body, filename))

    original = read(repo, REPLY)
    original, reply_time_selection = apply_original_comment_time(repo, REPLY, original)
    original, charged_selection = charged_delta(repo, original)
    (output / "v029-charged-reply-source.json").write_text(json.dumps(charged_selection,ensure_ascii=False,indent=2)+"\n",encoding="utf8")
    imports = original[:original.index('internal val EMOTE_TOKEN_PATTERN')]
    # Only private file helpers are repeated. All public/internal policies and
    # types are the editor's single existing source-owned producers.
    private_helpers = appearance.declarations(parser, original, [
        'COMMENT_INLINE_UP_BADGE_ID', 'COMMENT_INLINE_CHARGED_BADGE_ID',
        'COMMENT_INLINE_VERIFY_PERSONAL_BADGE_ID', 'COMMENT_INLINE_VERIFY_ORGANIZATION_BADGE_ID',
        'REPLY_VIDEO_TITLE_CACHE_MAX_ENTRIES', 'replyVideoTitleCache',
        'resolveReplyActionSheetLabel', 'isReplyActionDestructive',
        'isReplyDynamicNavigationUrl', 'parseHexColorOrNull'])
    # The declaration selector intentionally stops at a class member; this
    # anonymous object must be selected by its exact original token boundary.
    cache_begin = original.index('private val replyVideoTitleCache = object')
    cache_end = original.index('\n\n/**', cache_begin)
    assert 'private val replyVideoTitleCache' not in private_helpers
    private_helpers += original[cache_begin:cache_end] + '\n\n'
    ui = original[original.index('@Composable\nfun ReplyHeader'):]
    for name in ('normalizeHttpImageUrl', 'resolveDecorationImageUrl'):
        declaration = appearance.declarations(parser, original, [name])
        assert declaration.strip() in ui
        ui = ui.replace(declaration.strip(), '', 1)
    # The formatter belongs to the existing public formatTime policy file.
    ui = re.sub(r'private val replyPublishDayFormatter = [^\n]+\n', '', ui)
    # This private helper is shared with the UI file itself, once only.
    parse_hex = appearance.declarations(parser, original, ['parseHexColorOrNull']).strip()
    assert parse_hex in ui
    ui = ui.replace(parse_hex, '', 1)
    ui = replace_once(ui,
        '        SettingsManager.getCommentCollapsedReplyPreviewLimitSync(context)',
        '        platform.collapsedReplyPreviewLimit')
    ui = ui.replace('                VideoRepository.getVideoTitle(bvid).getOrNull()',
                    '                LocalDesktopCommentBindings.current.videoTitle(bvid).getOrNull()')
    # A CompositionLocal may not be read inside a suspend callback. Capture the
    # immutable mounted owner before launching the original title request.
    ui = replace_once(ui, '    val videoReference = remember(text) { resolveReplyVideoReference(text) }',
                      '    val titlePlatform = LocalDesktopCommentBindings.current\n'
                      '    val videoReference = remember(text) { resolveReplyVideoReference(text) }')
    ui = ui.replace('LocalDesktopCommentBindings.current.videoTitle(bvid)', 'titlePlatform.videoTitle(bvid)')
    # Desktop text has no Android font-padding option; retain the original
    # font size, optical offset and explicit line-height trimming.
    ui = replace_once(ui,
        'platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),',
        'platformStyle = null,')
    shared=load(repo,'reply_new_log_boundary','desktop/tools/extract-upstream-dynamic-reply-protocol.py')
    emit(REPLY, shared.drop_logs(adapt_common(imports + private_helpers + ui)), 'DesktopOriginalReplyComponents.kt')

    original = read(repo, SUB)
    original, sub_time_selection = apply_original_comment_time(repo, SUB, original)
    (output / 'v029-comment-time-selection.json').write_text(
        json.dumps([reply_time_selection, sub_time_selection], ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    # The actual sole full generic VideoCommentVM is now installed. Preserve
    # its full original video inline wrapper in this sole existing producer.
    body = original
    body = body.replace('    val showLoadedReplyCount by com.android.purebilibili.core.store.SettingsManager\n        .getSubReplyLoadedCountEnabled(context)',
                        '    val showLoadedReplyCount by LocalDesktopCommentBindings.current.subReplyLoadedCountEnabled')
    emit(SUB, adapt_common(body), 'DesktopOriginalSubReplyDetailComponents.kt')

    original = read(repo, SAVER)
    declarations = appearance.declarations(parser, original, ['ReplyCommentImageSpec', 'buildReplyCommentImageSpec'])
    emit(SAVER, '''package com.android.purebilibili.feature.video.ui.components
import com.android.purebilibili.data.model.response.ReplyItem
import com.android.purebilibili.core.util.FormatUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
''' + declarations, 'DesktopOriginalReplyCommentImageSpec.kt')
    body = '\n\n'.join(media.function(original, name, parser) for name in
        ('renderReplyCommentImage', 'breakTextIntoLines', 'generateQrBitmap'))
    body = body.replace('private fun renderReplyCommentImage', 'internal fun renderDesktopReplyCommentImage')
    body = body.replace('Bitmap.createBitmap(', 'createDesktopCommentBitmap(')
    body = body.replace('Bitmap.Config.ARGB_8888', 'BufferedImage.TYPE_INT_ARGB')
    body = body.replace('Bitmap.Config.RGB_565', 'BufferedImage.TYPE_USHORT_565_RGB')
    body = body.replace(': Bitmap', ': BufferedImage')
    body = body.replace('bitmap.setPixel(', 'bitmap.setRGB(')
    body = body.replace('android.graphics.Typeface.DEFAULT_BOLD', 'java.awt.Font.BOLD')
    body = body.replace('    return bitmap\n}', '    canvas.close()\n    return bitmap\n}', 1)
    # Windows AWT footer URL glyphs must not paint over the original QR bitmap.
    # Keep the complete original spec/QR payload; only this printed URL is clipped.
    body = replace_once(body, 'canvas.drawText(spec.qrUrl, footerLeft, footerBaseline + 84f, tinyPaint)',
                        'canvas.drawFooterTextBeforeQr(spec.qrUrl, footerLeft, footerBaseline + 84f, tinyPaint, qrLeft)')
    emit(SAVER, '''package com.android.purebilibili.feature.video.ui.components
import java.awt.image.BufferedImage
import com.bilipai.desktop.ui.DesktopCommentPaint as Paint
import com.bilipai.desktop.ui.DesktopCommentCanvas as Canvas
import com.bilipai.desktop.ui.DesktopCommentColors as Color
import com.bilipai.desktop.ui.DesktopCommentRect as RectF
import com.bilipai.desktop.ui.createDesktopCommentBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
''' + body, 'DesktopOriginalReplyImageRenderer.kt')

    original = read(repo, SHEET)
    declarations = media.function(original, 'dynamicInlineCommentItems', parser) + '\n\n' + appearance.declarations(parser, original,
        ['DynamicInlineCommentComposer', 'DynamicCommentComposer'])
    # Android Uri is represented by the existing owner-selected file URI on Windows.
    declarations = declarations.replace('List<Uri>', 'List<String>')
    picker_begin = declarations.index('    val picker = rememberLauncherForActivityResult(')
    picker_end = declarations.index('    LaunchedEffect(onClearReplyTarget != null)', picker_begin)
    picker_original = declarations[picker_begin:picker_end]
    assert 'maxItems = 9' in picker_original and '.distinct().take(9)' in picker_original
    declarations = declarations[:picker_begin] + '    val platform = LocalDesktopCommentBindings.current\n    val onImagesSelected by rememberUpdatedState<(List<String>) -> Unit> { uris ->\n        if (!isSending && onClearReplyTarget == null) {\n            selectedImages = (selectedImages + uris).distinct().take(9)\n        }\n    }\n    fun pickImages() {\n        if (!platform.isOwned()) return\n        platform.pickCommentImages(9) { uris ->\n            if (platform.isOwned()) onImagesSelected(uris)\n        }\n    }\n' + declarations[picker_end:]
    declarations = replace_once(declarations,
        'picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))', 'pickImages()')
    # Keep the source's selection/send/success flow. A retired submit callback
    # must never clear the replacement submit's busy/selected-image state.
    declarations = replace_once(declarations,
        '    var isSending by remember { mutableStateOf(false) }',
        '    var isSending by remember { mutableStateOf(false) }\n    var activeSubmission by remember { mutableStateOf<Any?>(null) }')
    declarations = replace_once(declarations,
        '        isSending = true\n        onSubmit(value.trim(), selectedImages) { success ->',
        '        isSending = true\n        val submission = Any()\n        activeSubmission = submission\n        onSubmit(value.trim(), selectedImages) { success ->\n            if (!platform.isOwned() || activeSubmission !== submission) return@onSubmit\n            activeSubmission = null')
    declarations = declarations.replace('val keyboardController = LocalSoftwareKeyboardController.current',
                                        '// Physical Windows keyboard; original focus and IME action are retained.')
    declarations = declarations.replace('                keyboardController?.show()\n', '')
    declarations = declarations.replace('            keyboardController?.hide()\n', '')
    # The actual original native branch keeps its shape, height, focus, IME and
    # field contents. Full reusable liquid dock rendering is separately pending.
    start = declarations.index('        BottomBarMatchedReusableLiquidDock(')
    native_body = declarations.index('            val fieldColor = if (liquidChromeActive)', start)
    declarations = declarations[:start] + '''        Box(
            modifier = Modifier.weight(1f).height(composerHeight).clip(dockShape).background(commentFieldContainerColor)
        ) {
            val liquidChromeActive = false // Actual Windows native fallback capability.
''' + declarations[native_body:]
    declarations = declarations.replace('    val composerLensIntensity = resolveFloatingDockGeometryScale(composerHeight.value)\n', '')
    emit(SHEET, '''package com.android.purebilibili.feature.dynamic.components
import androidx.compose.runtime.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import coil3.compose.AsyncImage
import com.bilipai.desktop.ui.LocalDesktopCommentBindings
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.*
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.core.ui.skeleton.CommentListColumnSkeleton
import com.android.purebilibili.data.model.response.ReplyItem
import com.android.purebilibili.feature.dynamic.*
import com.android.purebilibili.feature.video.ui.components.*
import com.android.purebilibili.feature.home.components.resolveSharedBottomBarCapsuleShape
import top.yukonga.miuix.kmp.blur.Backdrop as MiuixBackdrop
import kotlinx.coroutines.delay
''' + declarations, 'DesktopOriginalDynamicInlineReplyUi.kt')

    # The chosen raw Reply renderer's report dialog reasons are kept distinct
    # from the Dynamic card report schema/reasons.
    path = BASE + 'feature/video/ui/components/CommentInputBar.kt'
    original = read(repo, path)
    body = media.function(original, 'ReportReasonDialog', parser)
    emit(path, '''package com.android.purebilibili.feature.video.ui.components
import androidx.compose.runtime.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.*
import com.android.purebilibili.core.ui.components.*
@Composable
''' + body, 'DesktopOriginalReplyReportReasonDialog.kt')

    path = BASE + 'core/ui/skeleton/ContentLoadingSkeletons.kt'
    original = read(repo, path)
    body = appearance.declarations(parser, original,
        ['rememberContentSkeletonPulse', 'rememberContentSkeletonBlockColor', 'ContentSkeletonBlock',
         'CommentListItemSkeleton', 'CommentListColumnSkeleton',
         'CONTENT_SKELETON_PULSE_DURATION_MILLIS', 'CONTENT_SKELETON_LIGHT_MIN_ALPHA',
         'CONTENT_SKELETON_LIGHT_MAX_ALPHA', 'CONTENT_SKELETON_DARK_MIN_ALPHA', 'CONTENT_SKELETON_DARK_MAX_ALPHA'])
    emit(path, '''package com.android.purebilibili.core.ui.skeleton
import androidx.compose.runtime.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.unit.*
import androidx.compose.material3.MaterialTheme
import com.android.purebilibili.core.ui.*
''' + body, 'DesktopOriginalReplyLoadingSkeletons.kt')
    path = BASE + 'core/ui/skeleton/SkeletonBreathing.kt'
    original = read(repo, path)
    body = original.replace('import androidx.compose.ui.platform.LocalContext',
        'import com.bilipai.desktop.settings.LocalDesktopDynamicTimelinePreferences')
    body = body.replace('import androidx.lifecycle.compose.collectAsStateWithLifecycle\n', '')
    body = body.replace('val context = LocalContext.current.applicationContext',
                        'val context = checkNotNull(LocalDesktopDynamicTimelinePreferences.current).context')
    body = body.replace('.collectAsStateWithLifecycle(initialValue', '.collectAsState(initial')
    body = body.replace('com.android.purebilibili.core.ui.motion.rememberSystemReduceMotion()',
                        'com.bilipai.desktop.ui.rememberDesktopDynamicReduceMotion()')
    body = body.replace('import androidx.compose.runtime.Composable',
                        'import androidx.compose.runtime.collectAsState\nimport androidx.compose.runtime.Composable')
    emit(path, body, 'DesktopOriginalReplySkeletonBreathing.kt')
    path = BASE + 'core/store/SkeletonSettingsStore.kt'
    original = read(repo, path)
    body = original.replace('import android.content.Context',
                            'import com.bilipai.desktop.plugins.DesktopPluginContext as Context')
    body = body.replace('import androidx.datastore.preferences.core.booleanPreferencesKey',
        'import com.bilipai.desktop.plugins.booleanPreferencesKey')
    body = body.replace('import androidx.datastore.preferences.core.edit',
                        'import com.bilipai.desktop.settings.dynamicSettingsDataStore as settingsDataStore')
    emit(path, body, 'DesktopOriginalReplySkeletonSettingsStore.kt')
    path = BASE + 'feature/home/components/BottomBar.kt'
    original = read(repo, path)
    body = appearance.declarations(parser, original, ['resolveSharedBottomBarCapsuleShape'])
    emit(path, 'package com.android.purebilibili.feature.home.components\nimport androidx.compose.foundation.shape.RoundedCornerShape\n'+body,
         'DesktopOriginalReplyCapsuleShape.kt')
    path = BASE + 'core/ui/common/Modifiers.kt'
    original = read(repo, path)
    body = media.function(original, 'rememberClipboardCopyHandler', parser)
    body = body.replace('val context = LocalContext.current',
                        'val context = LocalDesktopCommentBindings.current')
    body = body.replace('copyPlainTextToClipboard(context, text, label ?: "BiliPai")',
                        'if (context.isOwned()) context.copyText(text, label ?: "BiliPai")')
    begin = body.index('                if (Build.VERSION.SDK_INT')
    end = body.index('\n            }', begin)
    body = body[:begin] + '                if (context.isOwned()) context.showFeedback(toastMsg)' + body[end:]
    emit(path, '''package com.android.purebilibili.core.ui.common
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import com.bilipai.desktop.ui.LocalDesktopCommentBindings
@Composable
''' + body, 'DesktopOriginalReplyClipboard.kt')
    # Full AicuNavigationPolicy is mode=direct; prepareUpstreamSources is its sole producer.
    legacy_aicu=output/'com/android/purebilibili/feature/aicu/DesktopOriginalReplyAicuNavigation.kt'
    if legacy_aicu.exists():
        assert not legacy_aicu.is_symlink() and legacy_aicu.resolve().is_relative_to(output.resolve())
        assert hashlib.sha256(legacy_aicu.read_bytes().replace(b"\r\n",b"\n")).hexdigest()=="62516206102de9ff99b64c6228e2360cfda7830f4a2751d4608a27dac48a934c"
        legacy_aicu.unlink()
    path = BASE + 'feature/dynamic/DynamicDetailScreen.kt'
    original = read(repo, path)
    begin = original.index('                val commentContent:')
    end = original.index('                val floatingCommentComposer', begin)
    body = original[begin:end]
    body = body.replace('interactionViewModel', 'session').replace('state.item', 'item')
    body = replace_once(body, 'session.postComment(item.id_str, message, images) {',
        'session.postComment(item.id_str, message, images, onSubmissionCancelled = { onResult(false) }) {')
    body = body.replace('com.android.purebilibili.core.store.TokenManager.midCache', 'currentMid')
    body = body.replace('liquidGlassEnabled = liquidGlassEnabled,', 'liquidGlassEnabled = false,')
    body = body.replace('backdrop = detailCommentBackdrop,', 'backdrop = null,')
    body = re.sub(r'android\.widget\.Toast\.makeText\(context, ([^\n]+), android\.widget\.Toast\.LENGTH_SHORT\)\.show\(\)',
                  r'if (platform.isOwned()) platform.showFeedback(\1)', body)
    assert 'Toast.' not in body and 'TokenManager.' not in body
    import textwrap
    body = textwrap.dedent(body)
    # Only a raw comment viewport is emitted here. Main's unique card/editor and
    # the subsequent full original detail chrome remain owned by their hosts.
    emit(path, '''package com.bilipai.desktop.ui
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import com.android.purebilibili.core.theme.LocalAppUiStyle
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.ui.*
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.dynamic.DesktopOriginalDynamicReplySession
import com.android.purebilibili.feature.dynamic.components.*
import kotlinx.coroutines.ensureActive

/** Source-selected raw comment contents/composer; no Root card/items/cache or
 * account is constructed. The caller supplies a finite viewport and opens the
 * separately exposed thread content in its actual native container. */
@Composable internal fun DesktopOriginalDynamicCommentPanel(
    item: DynamicItem,
    session: DesktopOriginalDynamicReplySession,
    currentMid: Long?,
    onUserClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val platform = LocalDesktopCommentBindings.current
    if (!platform.isOwned() || !session.isOwned()) return
    val comments by session.comments.collectAsState()
    val commentsLoading by session.commentsLoading.collectAsState()
    val commentsLoadingMore by session.commentsLoadingMore.collectAsState()
    val commentTotalCount by session.commentTotalCount.collectAsState()
    val commentSortMode by session.dynamicCommentSortMode.collectAsState()
    val commentReplyTarget by session.commentReplyTarget.collectAsState()
    var showImagePreview by remember { mutableStateOf(false) }
    var previewImages by remember { mutableStateOf<List<String>>(emptyList()) }
    var previewInitialIndex by remember { mutableIntStateOf(0) }
    var previewSourceRect by remember { mutableStateOf<ImagePreviewSourceAnchor?>(null) }
    var previewTextContent by remember { mutableStateOf<ImagePreviewTextContent?>(null) }
''' + body + '''
    Column(modifier = modifier) {
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) { commentContent() }
        AppHorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        AppSurface(modifier = Modifier.fillMaxWidth(),
            color = resolveDesktopOriginalReplyBottomBarColor(LocalAppUiStyle.current, MaterialTheme.colorScheme),
            tonalElevation = 0.dp, shadowElevation = 0.dp) {
            commentComposer(Modifier.fillMaxWidth().padding(horizontal = AppSpacingTokens.Large, vertical = AppSpacingTokens.Medium))
        }
    }
    if (showImagePreview && previewImages.isNotEmpty()) {
        ImagePreviewDialog(images = previewImages, initialIndex = previewInitialIndex,
            sourceRect = previewSourceRect?.rect, sourceRects = previewSourceRect?.galleryRects.orEmpty(),
            sourceCornerRadiusDp = previewSourceRect?.cornerRadiusDp ?: AppShapes.containerCornerDp(ContainerLevel.Field).value,
            textContent = previewTextContent, onDismiss = { showImagePreview = false; previewTextContent = null })
    }
}
''' + appearance.declarations(parser, original, ['resolveDynamicDetailBottomBarColor'])
       .replace('resolveDynamicDetailBottomBarColor', 'resolveDesktopOriginalReplyBottomBarColor'),
         'DesktopOriginalDynamicCommentPanel.kt')
    path = BASE + 'feature/video/ui/components/SubReplySheet.kt'
    original = read(repo, path)
    begin = original.index('                SubReplyDetailContent(')
    end = original.index('\n            }\n        }', begin)
    body = textwrap.dedent(original[begin:end])
    body = body.replace('headerDragModifier = threadDrag.headerModifier,', 'headerDragModifier = Modifier,')
    emit(path, '''package com.bilipai.desktop.ui
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.dynamic.DesktopOriginalDynamicReplySession
import com.android.purebilibili.feature.dynamic.components.*
import com.android.purebilibili.feature.video.ui.components.SubReplyDetailContent
import com.android.purebilibili.feature.video.viewmodel.*
import kotlinx.coroutines.ensureActive

/** Complete original thread contents. Android predictive-back/window dragging
 * is deliberately a separate native-container integration, not fake progress. */
@Composable internal fun DesktopOriginalDynamicThreadContent(
    session: DesktopOriginalDynamicReplySession,
    currentMid: Long,
    onUserClick: (Long) -> Unit,
    onImagePreview: ((List<String>, Int, ImagePreviewSourceAnchor?, ImagePreviewTextContent?) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val platform = LocalDesktopCommentBindings.current
    val state by session.subReplyState.collectAsState()
    val rootReply = state.rootReply ?: return
    if (!state.visible || !session.isOwned() || !platform.isOwned()) return
    val catalog = platform.emotes
    val key = catalog.currentSessionKey()
    var emoteMap by remember(key) { mutableStateOf(catalog.snapshot()) }
    LaunchedEffect(key) {
        val loaded = catalog.ensureLoaded()
        ensureActive()
        if (session.isOwned() && platform.isOwned()) emoteMap = loaded
    }
    val showUpFlag = false
    val onLoadMore = session::loadMoreSubReplies
    val onRefresh = session::refreshSubReplies
    val onSortModeChange = session::setSubReplySortMode
    val onDismiss = session::closeSubReply
    val onRootCommentClick: (() -> Unit)? = null
    val onTimestampClick: ((Long) -> Unit)? = null
    val onReplyClick: ((ReplyItem) -> Unit)? = { session.closeSubReply(); session.startCommentReply(it) }
    val onDissolveStart: ((Long) -> Unit)? = session::startSubDissolve
    val onDeleteComment: ((Long) -> Unit)? = { session.deleteDynamicComment(it) { _, message -> if (platform.isOwned()) platform.showFeedback(message) } }
    val onCommentLike: ((Long) -> Unit)? = { session.likeComment(it) }
    val likedComments: Set<Long> = emptySet()
    val onCommentHate: ((Long) -> Unit)? = { session.hateComment(it) }
    val hatedComments: Set<Long> = emptySet()
    val onUrlClick: ((String) -> Unit)? = null
    val showIdentityDecorations = true
    val onAvatarClick: ((String) -> Unit)? = { it.toLongOrNull()?.let(onUserClick) }
''' + body.replace('targetReplyId = state.targetReplyId', 'targetReplyId = state.targetReplyId,\n    modifier = modifier') + '\n}\n',
         'DesktopOriginalDynamicThreadContent.kt')
    path = BASE + 'core/store/SettingsManager.kt'
    original = read(repo, path)
    names = ['KEY_SUB_REPLY_LOADED_COUNT_ENABLED', 'KEY_COMMENT_COLLAPSED_REPLY_PREVIEW_LIMIT',
             'DEFAULT_COMMENT_COLLAPSED_REPLY_PREVIEW_LIMIT', 'COMMENT_PREVIEW_CACHE_PREFS',
             'CACHE_KEY_COMMENT_COLLAPSED_REPLY_PREVIEW_LIMIT',
             'normalizeCommentCollapsedReplyPreviewLimit', 'getCommentCollapsedReplyPreviewLimit',
             'setCommentCollapsedReplyPreviewLimit', 'getCommentCollapsedReplyPreviewLimitSync',
             'getSubReplyLoadedCountEnabled', 'setSubReplyLoadedCountEnabled',
             'KEY_DETAILED_COMMENT_TIME_ENABLED', 'getDetailedCommentTimeEnabled', 'setDetailedCommentTimeEnabled',
             'KEY_COMMENT_DEFAULT_SORT_MODE', 'getCommentDefaultSortMode',
             'getCommentDefaultSortModeSync', 'KEY_COMMENT_FRAUD_DETECTION_ENABLED',
             'getCommentFraudDetectionEnabled', 'KEY_COMMENT_MEMBER_DECORATIONS_ENABLED',
             'getCommentMemberDecorationsEnabled']
    manager_body = original[original.index('object SettingsManager {') + len('object SettingsManager {'):
                            original.rfind('}')]
    body = appearance.declarations(parser, manager_body, names)
    emit(path, '''package com.android.purebilibili.core.store
import com.bilipai.desktop.plugins.DesktopPluginContext as Context
import com.bilipai.desktop.plugins.booleanPreferencesKey
import com.bilipai.desktop.settings.dynamicIntPreferencesKey as intPreferencesKey
import com.bilipai.desktop.settings.dynamicSettingsDataStore as settingsDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
internal object DesktopOriginalReplySettings {
''' + body + '\n}\n', 'DesktopOriginalReplySettings.kt')
    path = BASE + 'core/ui/animation/ParticleDissolveEffect.kt'
    original = read(repo, path)
    helpers = original[original.index('object DissolveAnimationManager'):
                       original.index('@Composable\nfun Modifier.jiggleOnDissolve')]
    dissolve = media.function(original, 'DissolvableVideoCard', parser)
    original_dissolve_sha = hashlib.sha256(dissolve.encode()).hexdigest()
    if original_dissolve_sha != '7f7d0d3d2a8724dd1a03496c857ea1367c5d9e63d483b81cb081f60f8a4b0440':
        raise ValueError('Original full dissolve body changed')
    changes = [{'before': '    val context = LocalContext.current\n', 'after': ''}, {'before': '    var cardWindowBounds by remember(cardId) { mutableStateOf<RectF?>(null) }\n', 'after': ''}, {'before': '    var effectView by remember(cardId) { mutableStateOf<ThanosEffectView?>(null) }\n', 'after': ''}, {'before': '        effectView?.dispose()\n        effectView = null\n', 'after': ''}, {'before': '    LaunchedEffect(isDissolving, cardId) {\n        if (!isDissolving) {\n            effectView?.dispose()\n            effectView = null\n            shouldCollapse = false\n            keepContentHidden = false\n            return@LaunchedEffect\n        }\n        hasCompletedCurrentDissolve = false\n        val window = findWindow(context)\n        if (window == null || !isThanosEffectSupported(context)) {\n            finishEffect()\n            return@LaunchedEffect\n        }\n        val ready = withTimeoutOrNull(500L) {\n            snapshotFlow { hasRecordedContent && cardWindowBounds != null }\n                .first { it }\n        }\n        if (ready != true) {\n            finishEffect()\n            return@LaunchedEffect\n        }\n        // Capture the actual card subtree, including its transparent corners, without\n        // the window background or action sheet. This replaces upstream View.draw(Canvas).\n        val bitmap: Bitmap? = try {\n            withTimeoutOrNull(500L) {\n                contentLayer.toImageBitmap().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)\n            }\n        } catch (cancelled: CancellationException) {\n            throw cancelled\n        } catch (error: RuntimeException) {\n            android.util.Log.w("ThanosEffect", "Cannot capture video card", error)\n            null\n        }\n        val bounds = cardWindowBounds\n        if (bitmap == null || bounds == null) {\n            bitmap?.recycle()\n            finishEffect()\n            return@LaunchedEffect\n        }\n        effectView = ThanosEffectView.attach(\n            window = window,\n            bitmap = bitmap,\n            windowBounds = bounds,\n            onFirstFrame = {\n                keepContentHidden = true\n                if (publishGlobalState && cardId.isNotEmpty()) {\n                    DissolveAnimationManager.startDissolving(cardId)\n                }\n            },\n            onComplete = { finishEffect() },\n            onFinalTail = {\n                if (reflowDuringFinalTail && collapseAfterDissolve) beginCollapse()\n            },\n        )\n        if (effectView == null) {\n            if (!bitmap.isRecycled) bitmap.recycle()\n            finishEffect()\n            return@LaunchedEffect\n        }\n        // Covers missing SurfaceTexture callbacks/device failures without a stuck removal.\n        // Normal upstream lifetime is (1.5 + 0.9) / 1.15, about 2.09 seconds.\n        delay(5000L)\n        if (!hasCompletedCurrentDissolve && !shouldCollapse) finishEffect()\n    }\n\n', 'after': '    // Existing Windows consumer follows the original unavailable/failed-capture branch.\n    // Collapse, global publication, reflow notification and once-only completion remain original.\n    LaunchedEffect(isDissolving, cardId) {\n        if (!isDissolving) {\n            shouldCollapse = false\n            keepContentHidden = false\n            return@LaunchedEffect\n        }\n        hasCompletedCurrentDissolve = false\n        finishEffect()\n    }\n\n'}, {'before': '            effectView?.dispose()\n            effectView = null\n', 'after': ''}, {'before': '            .onGloballyPositioned { coordinates ->\n                val position = coordinates.positionInWindow()\n                cardWindowBounds = RectF(\n                    position.x, position.y,\n                    position.x + coordinates.size.width,\n                    position.y + coordinates.size.height,\n                )\n            }\n', 'after': ''}]
    for change in changes:
        if dissolve.count(change['before']) != 1:
            raise ValueError('Original Windows failed-capture seam changed')
        dissolve = dissolve.replace(change['before'], change['after'], 1)
    maybe = media.function(original, 'MaybeDissolvableVideoCard', parser)
    if maybe.count('fun MaybeDissolvableVideoCard(') != 1:
        raise ValueError('Original maybe dissolve signature changed')
    maybe = maybe.replace('fun MaybeDissolvableVideoCard(', 'internal fun DesktopReplyDissolvableContainer(', 1)
    emit(path, 'package com.bilipai.desktop.ui\nimport androidx.compose.animation.core.*\nimport androidx.compose.foundation.layout.*\nimport androidx.compose.runtime.*\nimport androidx.compose.ui.Modifier\nimport androidx.compose.ui.draw.alpha\nimport androidx.compose.ui.draw.drawWithContent\nimport androidx.compose.ui.graphics.graphicsLayer\nimport androidx.compose.ui.graphics.layer.drawLayer\nimport androidx.compose.ui.graphics.rememberGraphicsLayer\nimport androidx.compose.ui.layout.layout\nimport androidx.compose.ui.layout.onSizeChanged\nimport androidx.compose.ui.unit.IntSize\nimport kotlin.math.roundToInt\n' + helpers + '\n@Composable\n' + dissolve + '\n@Composable\n' + maybe,
         'DesktopOriginalReplyFailedCaptureDissolve.kt')
    return emitted


if __name__ == '__main__':
    import argparse
    cli = argparse.ArgumentParser()
    cli.add_argument('--repo', type=Path, required=True)
    cli.add_argument('--output', type=Path, required=True)
    args = cli.parse_args()
    generate(args.repo, args.output)
