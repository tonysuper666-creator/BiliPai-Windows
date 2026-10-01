"""Prepare a stable original banner/caller and actual Main04 consumer seam.

Only this task directory is written. Historical frozen cohorts remain read-only.
"""
from pathlib import Path
import hashlib, importlib.util, json, shutil, sys
sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
PRIOR = HERE.parent / 'space-avatar-save-v023-parity'
PREFIX = chr(92)*2+'?'+chr(92)
def safe(p):
    s=str(Path(p).absolute()); return Path(s if s.startswith(PREFIX) else PREFIX+s)
def read(p): return safe(p).read_text(encoding='utf-8').replace('\r\n','\n').replace('\r','\n')
def sha(p): return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(p,text):
    p=safe(p); p.parent.mkdir(parents=True,exist_ok=True); p.write_text(text,encoding='utf-8',newline='\n')
def once(text,a,b):
    assert text.count(a)==1,(a,text.count(a)); return text.replace(a,b,1)

assert sha(PRIOR/'frozen-handoff.json')=='424ef334084c32f067bcfa0df976fc22ee5ea500e7894a21a261391a61f8ee0d'
prepared=HERE/'prepared/desktop'
owner=read(PRIOR/'prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopSpaceImagePreviews.kt')
owner=once(owner,'prepareImagePreviewSourceTransition(rect)\n            openedAvatarUrl',
    'prepareImagePreviewSourceTransition(rect, avatarPreviewUrl)\n            openedAvatarUrl')
owner=once(owner,'prepareImagePreviewSourceTransition(rect)\n            // Original topImages',
    'prepareImagePreviewSourceTransition(rect, currentBannerUrl)\n            // Original topImages')
owner=once(owner,'openedTopImages, openedFallbackTopPhotoUrl, topPhotoSourceRect) { showTopPhotoPreview = false }',
    'openedTopImages, openedFallbackTopPhotoUrl, topPhotoSourceRect, sourceKey = topPhotoBannerUrl) { showTopPhotoPreview = false }')
owner=once(owner,'DesktopOriginalSpaceAvatarPreview(showAvatarPreview, openedAvatarUrl, avatarSourceRect) {',
    'DesktopOriginalSpaceAvatarPreview(showAvatarPreview, openedAvatarUrl, avatarSourceRect, sourceKey = openedAvatarUrl) {')
write(prepared/'src/main/kotlin/com/bilipai/desktop/ui/DesktopSpaceImagePreviews.kt',owner)

generator=read(PRIOR/'prepared/desktop/tools/extract-space-image-preview-callers.py')
generator=once(generator,'    text=', '''    def declaration(anchor):
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
    text=''')
generator=once(generator,"'import androidx.compose.runtime.*\\nimport androidx.compose.ui.geometry.Rect\\nimport androidx.compose.ui.platform.LocalDensity\\n'+\\",
    "'import androidx.compose.runtime.*\\nimport androidx.compose.ui.geometry.Rect\\nimport androidx.compose.ui.platform.LocalDensity\\n'+\\\n      'import androidx.compose.foundation.background\\nimport androidx.compose.foundation.layout.*\\nimport androidx.compose.foundation.pager.HorizontalPager\\nimport androidx.compose.foundation.pager.rememberPagerState\\n'+\\\n      'import androidx.compose.material3.MaterialTheme\\nimport androidx.compose.ui.Modifier\\nimport androidx.compose.ui.Alignment\\nimport androidx.compose.ui.graphics.Brush\\nimport androidx.compose.ui.graphics.Color\\n'+\\\n      'import androidx.compose.ui.layout.ContentScale\\nimport androidx.compose.ui.text.font.FontWeight\\nimport androidx.compose.ui.text.style.TextOverflow\\nimport androidx.compose.ui.unit.dp\\n'+\\\n      'import coil3.compose.AsyncImage\\nimport coil3.compose.LocalPlatformContext\\nimport coil3.request.ImageRequest\\nimport coil3.request.crossfade\\n'+\\\n      'import com.android.purebilibili.core.ui.components.AppText\\nimport com.android.purebilibili.core.ui.components.AppLinearProgressIndicator\\n'+\\\n      'import com.android.purebilibili.feature.dynamic.components.resolveImagePreviewPlaceholderCacheKey\\n'+\\")
generator=once(generator,'avatarSourceRect:Rect?, onDismiss:()->Unit)', 'avatarSourceRect:Rect?, sourceKey:String?=null, onDismiss:()->Unit)')
generator=once(generator,'topPhotoSourceRect:Rect?, onDismiss:()->Unit)', 'topPhotoSourceRect:Rect?, sourceKey:String?=null, onDismiss:()->Unit)')
generator=once(generator,"avatar.replace('onDismiss = { showAvatarPreview = false }','onDismiss = onDismiss')",
    "avatar.replace('onDismiss = { showAvatarPreview = false }','sourceKey = sourceKey,\\n            onDismiss = onDismiss')")
generator=once(generator,"top.replace('onDismiss = { showTopPhotoPreview = false }','onDismiss = onDismiss')+'\\n}\\n'",
    "top.replace('onDismiss = { showTopPhotoPreview = false }','sourceKey = sourceKey,\\n            onDismiss = onDismiss')+'\\n}\\n'+\\\n      '@Composable\\n'+banner+'\\n\\n@Composable\\n'+badge+'\\n'")
write(prepared/'tools/extract-space-image-preview-callers.py',generator)
spec=importlib.util.spec_from_file_location('space_stable_actual_banner_generator',prepared/'tools/extract-space-image-preview-callers.py')
module=importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
module.generate(REPO.parent/'BiliPai-v023',HERE/'generated')

names=['DesktopSpaceOverviewScreens.kt','DesktopCompleteSpaceScreen.kt','DesktopSpaceScreens.kt']
base={name:read(REPO/'desktop/src/main/kotlin/com/bilipai/desktop/ui'/name) for name in names}
for name,text in base.items(): write(HERE/'baseline'/name,text)

overview=base[names[0]]
overview=once(overview,'import androidx.compose.foundation.ScrollState', 'import androidx.compose.foundation.background\nimport androidx.compose.foundation.border\nimport androidx.compose.foundation.shape.CircleShape\nimport androidx.compose.foundation.ScrollState')
overview=once(overview,'import androidx.compose.ui.graphics.luminance',
    'import androidx.compose.ui.draw.alpha\nimport androidx.compose.ui.draw.clip\nimport androidx.compose.ui.geometry.Rect\nimport androidx.compose.ui.graphics.luminance\nimport androidx.compose.ui.platform.LocalWindowInfo')
overview=once(overview,'import androidx.compose.ui.window.Dialog\n','')
overview=once(overview,'import coil3.compose.AsyncImage',
    'import coil3.compose.AsyncImage\nimport coil3.compose.LocalPlatformContext\nimport coil3.request.ImageRequest\nimport coil3.request.crossfade\nimport com.android.purebilibili.core.util.FormatUtils\nimport com.android.purebilibili.core.plugin.skin.*\nimport com.android.purebilibili.feature.dynamic.components.*')
overview=once(overview,'modifier: Modifier = Modifier, action: @Composable () -> Unit = {}) {',
    'modifier: Modifier = Modifier,\n    onAvatarPreview: ((Rect?) -> Unit)? = null, onTopPhotoPreview: ((Rect?, String?) -> Unit)? = null,\n    isOwner: Boolean = false, action: @Composable () -> Unit = {}) {')
a=overview.index('    val topItems = user.topImages'); b=overview.index('    Column(modifier.fillMaxWidth()',a)
overview=overview[:a]+'''    val topItems = user.topImages
    val banner = normalizeSpaceTopPhotoUrl(if (dark && user.nightTopPhoto.isNotBlank()) user.nightTopPhoto else user.topPhoto)
    val context = LocalPlatformContext.current
    val window = LocalWindowInfo.current.containerSize
    val uiSkinState = LocalUiSkinState.current
    val activeProfileSkin = uiSkinState.activeSkin?.takeIf {
        uiSkinState.enabled && isOwner && UiSkinSurface.PROFILE in it.manifest.surfaces
    }
    val skinSpaceBackgroundPaths = activeProfileSkin?.manifest?.assets?.spaceBackgrounds.orEmpty().mapNotNull { background ->
        val preferLandscape = window.width > window.height
        activeProfileSkin?.assetFilePath(if (preferLandscape) background.landscape ?: background.portrait
            else background.portrait ?: background.landscape)
    }
    var currentBannerUrl by remember(user.mid) { mutableStateOf<String?>(null) }
    val topPhotoRect = rememberImagePreviewSourceRect()
    val topPhotoHidden = isImagePreviewSourceHidden(topPhotoRect.value, currentBannerUrl)
    val avatarRect = rememberImagePreviewSourceRect()
    val avatarHidden = isImagePreviewSourceHidden(avatarRect.value, user.face)
'''+overview[b:]
a=overview.index('        if (banner.isNotBlank()) AsyncImage'); b=overview.index('        Row(Modifier.padding(horizontal = 20.dp)',a)
overview=overview[:a]+'''        Box(Modifier.fillMaxWidth().height(150.dp).imagePreviewSourceBounds(topPhotoRect)
            .alpha(if (topPhotoHidden) 0f else 1f)
            .clickable(interactionSource = null, indication = null,
                enabled = onTopPhotoPreview != null && !topPhotoHidden && skinSpaceBackgroundPaths.isEmpty() &&
                    (shouldEnableSpaceTopPhotoPreview(banner) || topItems.isNotEmpty())) {
                onTopPhotoPreview?.invoke(topPhotoRect.value, currentBannerUrl)
            }) {
            DesktopOriginalSpaceHeaderBanner(topImages = topItems, fallbackTopPhotoUrl = banner,
                onCurrentBannerUrlChange = { currentBannerUrl = it }, skinBackgroundPaths = skinSpaceBackgroundPaths,
                isDarkTheme = dark, modifier = Modifier.fillMaxSize())
        }
'''+overview[b:]
overview=once(overview,'            AsyncImage(model = imageUrl(user.face), contentDescription = user.name, modifier = Modifier.size(72.dp).clickable { onUser(user.mid) })',
'''            Box(Modifier.size(80.dp).imagePreviewSourceBounds(avatarRect).alpha(if (avatarHidden) 0f else 1f)
                .clickable(interactionSource = null, indication = null, enabled = user.face.isNotBlank() && !avatarHidden) {
                    onAvatarPreview?.invoke(avatarRect.value) ?: onUser(user.mid)
                }) {
                AsyncImage(model = ImageRequest.Builder(context)
                    .data(FormatUtils.buildSizedImageUrl(user.face, width = 320, height = 320))
                    .memoryCacheKey(resolveImagePreviewPlaceholderCacheKey(user.face) ?: user.face)
                    .crossfade(false).build(), contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(CircleShape)
                        .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant))
            }''')
a=overview.index('    if (preview && previews.isNotEmpty()) Dialog'); b=overview.index('\n}\n',a)
overview=overview[:a]+overview[b:]
write(prepared/'src/main/kotlin/com/bilipai/desktop/ui'/names[0],overview)

complete=base[names[1]]
complete=once(complete,'import androidx.compose.ui.Modifier','import androidx.compose.ui.Modifier\nimport androidx.compose.ui.graphics.luminance')
complete=once(complete,'locateBvid: String? = null, onTopic: (Long) -> Unit = {}, onTopicKeyword: (String) -> Unit = {}) {',
    'locateBvid: String? = null, onTopic: (Long) -> Unit = {}, onTopicKeyword: (String) -> Unit = {},\n    onImagePreviewFeedback: (String) -> Unit = {}) {')
complete=once(complete,'onTopic = onTopic, onTopicKeyword = onTopicKeyword)',
    'onTopic = onTopic, onTopicKeyword = onTopicKeyword, onImagePreviewFeedback = onImagePreviewFeedback)')
complete=once(complete,'tab, false, state.leaf, onTopic, onTopicKeyword)',
    'tab, false, state.leaf, onTopic, onTopicKeyword, onImagePreviewFeedback)')
complete=once(complete,'            if (state.headerExpanded) DesktopSpaceHeader(overview,',
'''            if (state.headerExpanded) {
            val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
            val topPhotoUrl = normalizeSpaceTopPhotoUrl(if (dark && overview.user.nightTopPhoto.isNotBlank())
                overview.user.nightTopPhoto else overview.user.topPhoto)
            DesktopSpaceImagePreviews(repository, mid, overview.user.face, overview.user.topImages,
                topPhotoUrl, onImagePreviewFeedback) { avatarClick, topPhotoClick ->
            DesktopSpaceHeader(overview,''')
complete=once(complete,'modifier = Modifier.heightIn(max = 260.dp).verticalScroll(state.headerScroll), action = {',
    'modifier = Modifier.heightIn(max = 260.dp).verticalScroll(state.headerScroll),\n                onAvatarPreview = avatarClick, onTopPhotoPreview = topPhotoClick, isOwner = account?.mid == mid, action = {')
complete=once(complete,'                })\n            state.failure?.let', '                })\n            }\n            }\n            state.failure?.let')
write(prepared/'src/main/kotlin/com/bilipai/desktop/ui'/names[1],complete)

legacy=base[names[2]]
legacy=once(legacy,'import androidx.compose.foundation.ScrollState', 'import androidx.compose.foundation.background\nimport androidx.compose.foundation.border\nimport androidx.compose.foundation.shape.CircleShape\nimport androidx.compose.foundation.ScrollState')
legacy=once(legacy,'import androidx.compose.ui.Modifier',
    'import androidx.compose.ui.Modifier\nimport androidx.compose.ui.draw.alpha\nimport androidx.compose.ui.draw.clip\nimport androidx.compose.ui.layout.ContentScale')
legacy=once(legacy,'import coil3.compose.AsyncImage',
    'import coil3.compose.AsyncImage\nimport coil3.compose.LocalPlatformContext\nimport coil3.request.ImageRequest\nimport coil3.request.crossfade\nimport com.android.purebilibili.core.util.FormatUtils\nimport com.android.purebilibili.feature.dynamic.components.*')
legacy=once(legacy,'browseState: DesktopSpaceBrowseState? = null, onTopic: (Long) -> Unit = {}, onTopicKeyword: (String) -> Unit = {}) {',
    'browseState: DesktopSpaceBrowseState? = null, onTopic: (Long) -> Unit = {}, onTopicKeyword: (String) -> Unit = {},\n    onImagePreviewFeedback: (String) -> Unit = {}) {')
legacy=once(legacy,'        state.profile?.let { user ->\n            Box(Modifier.fillMaxWidth()) {',
'''        state.profile?.let { user ->
            DesktopSpaceImagePreviews(repository, mid, user.avatar, emptyList(), "", onImagePreviewFeedback) { avatarClick, _ ->
            val avatarRect = rememberImagePreviewSourceRect()
            val avatarHidden = isImagePreviewSourceHidden(avatarRect.value, user.avatar)
            val context = LocalPlatformContext.current
            Box(Modifier.fillMaxWidth()) {''')
legacy=once(legacy,'                    AsyncImage(model = imageUrl(user.avatar), contentDescription = user.name, modifier = Modifier.size(72.dp))',
'''                    Box(Modifier.size(80.dp).imagePreviewSourceBounds(avatarRect).alpha(if (avatarHidden) 0f else 1f)
                        .clickable(interactionSource = null, indication = null, enabled = user.avatar.isNotBlank() && !avatarHidden) {
                            avatarClick(avatarRect.value)
                        }) {
                        AsyncImage(model = ImageRequest.Builder(context)
                            .data(FormatUtils.buildSizedImageUrl(user.avatar, width = 320, height = 320))
                            .memoryCacheKey(resolveImagePreviewPlaceholderCacheKey(user.avatar) ?: user.avatar)
                            .crossfade(false).build(), contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().clip(CircleShape)
                                .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant))
                    }''')
legacy=once(legacy,'            }\n        }\n        state.profileError', '            }\n            }\n        }\n        state.profileError')
write(prepared/'src/main/kotlin/com/bilipai/desktop/ui'/names[2],legacy)

contract=dict(preparedOnly=True,originalTag='v0.2.3',originalCommit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',
 originalSource='app/src/main/java/com/android/purebilibili/feature/space/SpaceScreen.kt',
 originalSourceSha256Lf='2c7063c8c9b112b10f7b5394b34ddfc4362fcf2984d3eae3a3364469cc3557ca',
 baselineConsumers=[dict(path='desktop/src/main/kotlin/com/bilipai/desktop/ui/'+n,sha256Lf=hashlib.sha256(base[n].encode()).hexdigest(),
    actualBytesSha256=sha(REPO/'desktop/src/main/kotlin/com/bilipai/desktop/ui'/n)) for n in names],
 selected=['avatar corner and call','topImages/index/enabled and call','entire SpaceHeaderBanner','entire SpaceHeaderTitleBadge'],
 thinAdapters=['LocalContext→Coil LocalPlatformContext','private banner→internal DesktopOriginalSpaceHeaderBanner',
 'state input/dismiss parameters','optional original sourceKey bound to clicked immutable raw URL',
 'original skin landscape preference: desktop LocalWindowInfo same ratio comparison',
 'Main04 layout retained; avatar 80dp/Circle border source block'],
 solePreviewProducer='stable-image-preview-producer-parity',noMainWrites=True,noAccountRequests=True,noNative=True)
write(HERE/'preparation-contract.json',json.dumps(contract,ensure_ascii=False,indent=2))
print('Prepared original stable banner+caller and 3 actual consumer candidates; no Main edited.')
