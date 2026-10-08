"""Full stable Video information/action/engagement and shared gesture closure.
Task-only producer; no shared source, registry or build mutation.
"""
from v025_source_paths import canonical_source as _desktop_canonical_source, canonical_relative as _desktop_canonical_relative
from pathlib import Path
import hashlib, importlib.util, json, re, subprocess, sys
sys.dont_write_bytecode=True
LANE=None
REPO=None
OUTPUT=None
STANDALONE=False
COMMIT='79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40'
BASE='app/src/main/java/com/android/purebilibili/'
def wide(p):
    s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def sha(t):return hashlib.sha256(t.encode() if isinstance(t,str) else t).hexdigest()
def write(p,t):
    wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes(t.encode() if isinstance(t,str) else t)
def save(p,v):write(p,json.dumps(v,ensure_ascii=False,indent=2)+'\n')
def module(name,path):
    spec=importlib.util.spec_from_file_location(name,path);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
SOURCE_PINS = {'app/src/main/java/com/android/purebilibili/feature/video/ui/gesture/GestureLevelOverlay.kt': {'sha256LF': '9269462327088da5a4103e451776a9bb70a1cd098bdb71b54ee73d9b43ce8edb', 'gitBlob': '06ca05617b78fe065421b206fbcd3a25d0ed8618'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/gesture/GestureLevelOverlayPolicy.kt': {'sha256LF': 'bfd4b78900bf0a1b04847ccccb4a18b6d0d445be258f00dcd3ff6f1ce1d71771', 'gitBlob': '5b4eaef8315ed17122ea8de8d4b872a74abdb46f'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/gesture/Md3GestureLevelHapticPolicy.kt': {'sha256LF': '24f95b99fbb5d8c8830acd31880ff651091c62195407a9ef85f9317f0f498a2a', 'gitBlob': 'dc40151ea62138e5797360e59c4271ee5701939d'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/components/AnimatedGesturePercentText.kt': {'sha256LF': '512a6a361feb78e06487a1ce2711f078f4aca291a6e1645e6efa2225923659f7', 'gitBlob': 'ddbd93e59a1ea42bef236f420c901d23fe581eb8'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/components/CircularGesturePercentText.kt': {'sha256LF': '964174cee6a0261a430094eb85af8008bf6d51555d11617da3053f4d3b5f5b78', 'gitBlob': 'f86b77fe3ef6250d0771b44f716b8e39c23089d1'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/components/CircularGesturePercentMotionPolicy.kt': {'sha256LF': 'adfd21ce5b00af901c5a5857eba9427a195de8d7c812cd80745969f5d2b1f99c', 'gitBlob': '7e5e8366ea689e0bbf020c431db962d7868985d1'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/section/VideoPlayerSectionPolicy.kt': {'sha256LF': '198913c1d091d5aed119b90fedfbb7e77183457584c49c737c4aae7f113f37c0', 'gitBlob': '1775313c3b78d802728b7ac9492a4ba8e27d07a0'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/section/VideoActionSection.kt': {'sha256LF': '799f5d6fdd39adba83bd59aa52860b3d3378908b135aed4c55527e50686dc03a', 'gitBlob': '6b7c035b4afd4523f0a54fbf5f47c3e4234c2aae'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/section/VideoInfoSection.kt': {'sha256LF': 'dff7b27b7f2bb8440faf8bdce9a9c1cdab91d0790b1106306fc6999d5bf9ca5d', 'gitBlob': 'ca0f4d62aff6111cd1945b8bb82245e1a0203202'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/section/VideoInfoDisplayPolicy.kt': {'sha256LF': '84f95453d715ece786e29ee291570b52e6a40ae99336b77b53f065d68c7b5128', 'gitBlob': 'dd6ff4f3c33bfc4306ee60dc3f2b22298c0f3aef'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/VideoFollowVisualPolicy.kt': {'sha256LF': '28a99feed6df363e283381e4270f34093b4de72589a5c231bfde3f38662f1785', 'gitBlob': 'e544093fce7111c8be8bb7bc54ad211f94004b00'}, 'design-system/src/main/java/com/android/purebilibili/core/ui/AppPlayerChromeProfile.kt': {'sha256LF': '678594826a55c05d161be7f52bd3c4bbdab1af46346458c64bc7027d060f0042', 'gitBlob': '1e82f0ce4b054335593787f19a510e7da960e879'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/section/OwnerDecoratedAvatar.kt': {'sha256LF': 'f24f729482b9deac38a4fa17c8e60a15b8be49d08b6d641cdacdd87902b28e32', 'gitBlob': 'dfe434f8d2727796fc8bc31c70340c109da5b969'}, 'app/src/main/java/com/android/purebilibili/core/ui/common/Modifiers.kt': {'sha256LF': 'ffb0a934822dc39744a56ec031875e813f8e2a055ed857f5dfc1db4e1e5a5fa2', 'gitBlob': '24e6b81e179fb0f778b2212e8f9fa7554ab5c57e'}, 'app/src/main/java/com/android/purebilibili/data/repository/VideoRepository.kt': {'sha256LF': 'acf05cba9a89666533378eef35a21609484a02ccd874a460c259c3f3363d1e64', 'gitBlob': 'cbebfced03ada9944da60520a77f227bc05a0e29'}, 'app/src/main/java/com/android/purebilibili/core/store/SettingsManager.kt': {'sha256LF': '5799bb8802992594ae9494b48d6357ee00ecc7be03d97ed0dcb5fede7774328c', 'gitBlob': '5d24281dc2152c1e2113ab3476418f61261cd15a'}, 'app/src/main/java/com/android/purebilibili/feature/video/viewmodel/VideoSubjectSnapshot.kt': {'sha256LF': 'e266056182d40e924535401011071fc61fcfc59738e9263076717f49765c47fa', 'gitBlob': '3a287fd3974b1fbf1dbc6b2907f97b828250b882'}, 'app/src/main/java/com/android/purebilibili/feature/video/viewmodel/VideoDomainBinding.kt': {'sha256LF': '73fcfd5af57e257658164b7adbd2b103c412686a91efa5b8db99d1f67a84f491', 'gitBlob': 'eb987f1b06e0a2339931c570ec2fd3754ae3fc14'}, 'app/src/main/java/com/android/purebilibili/feature/video/viewmodel/VideoEngagementViewModel.kt': {'sha256LF': '62c0eae9b9447cfd615a5972ab8f58be906ca2a0cee184ccd57cf8b997ebbb1f', 'gitBlob': '32c9a2eaad4e1c329a496cb7f4b486f55673a299'}, 'app/src/main/java/com/android/purebilibili/feature/video/usecase/VideoInteractionUseCase.kt': {'sha256LF': '63c0826814274dc8df583105b3dc796498da6ab41d1bd2c50491b52ef68c74d6', 'gitBlob': 'fde1e2268a673569315dc32368b629893d2c57b2'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/feedback/TripleActionVisualStatePolicy.kt': {'sha256LF': '15d803c80018a16b4ca0bb37bf7b89bab65bb18e102bcd2c84fca991833fe822', 'gitBlob': 'ab7dc02a955cc11439eeb774289e45410611aba3'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/feedback/VideoActionFeedbackMessagePolicy.kt': {'sha256LF': 'ac32f597609d7fa820d04d4fe6b3fc6071fe94a431c22ca4ae9d20e90d1605e4', 'gitBlob': '0873e5808de0201dea49acdd3156fbefc1318150'}, 'app/src/main/java/com/android/purebilibili/data/repository/ActionRepository.kt': {'sha256LF': 'd6a59bbf30b9c2058f352e299399713f9af13fd44262eeae6473e1447769087f', 'gitBlob': '377e9680841ef3c1f2edcc2aca3605008099606a'}, 'app/src/main/java/com/android/purebilibili/core/util/AnalyticsHelper.kt': {'sha256LF': 'b8a96390d286964518caea6849afaeba075f5d3e8ac1cdb23b73af17feac6ccc', 'gitBlob': 'b25cb99570d4e5ac71e02209ce24353e30acc0ff'}}
SOURCES={};OUTPUTS=[];ADAPTATIONS=[]
def read(path):
    if path not in SOURCES:
        selected=_desktop_canonical_source(REPO,path)
        assert not selected.is_symlink() and selected.resolve().is_relative_to(REPO.resolve()),path
        raw=wide(selected).read_bytes().replace(b'\r\n',b'\n')
        pin=SOURCE_PINS[path]
        assert sha(raw)==pin['sha256LF'],path+' source differs from pinned v0.2.3'
        blob=subprocess.check_output(['git','-c','core.longpaths=true','rev-parse',COMMIT+':'+_desktop_canonical_relative(REPO,path)],cwd=REPO,text=True).strip()
        current=subprocess.check_output(['git','-c','core.longpaths=true','hash-object','--path='+_desktop_canonical_relative(REPO,path),str(_desktop_canonical_source(REPO,path))],cwd=REPO,text=True).strip()
        assert blob==current==pin['gitBlob'],path+' Git identity differs'
        SOURCES[path]=dict(text=raw.decode('utf-8'),**pin)
    return SOURCES[path]['text']
def adapt(t,a,b,label):
    assert t.count(a)==1,(label,t.count(a));ADAPTATIONS.append(dict(label=label,before=a,after=b));return t.replace(a,b,1)
def confirmed_favorite_receipt_delta(path,t):
    if path != 'com/android/purebilibili/feature/video/viewmodel/VideoEngagementViewModel.kt':return t
    before='    internal fun emitMessage(message: String) {\n'
    after='    /** Confirmed existing Root Favorite receipt; exact same domain subject only. */\n    internal fun confirmDesktopFavoriteCount(subject: VideoSubjectSnapshot, count: Int): Boolean {\n        require(count >= 0)\n        if (_uiState.value.subject != subject) return false\n        var applied = false\n        environment.commit {\n            if (_uiState.value.subject == subject) {\n                locallyModifiedFields = locallyModifiedFields + VideoEngagementField.FAVORITE\n                _uiState.value = _uiState.value.copy(favoriteCount = count)\n                applied = true\n            }\n        }\n        return applied\n    }\n\n    internal fun emitMessage(message: String) {\n'
    assert t.count(before)==1,'actual Favorite receipt domain anchor'
    return t.replace(before,after,1)

def emit(path,t,origin,mode):
    from v032_catalog import apply_and_record as apply_v032_catalog
    t=apply_v032_catalog(path,t,OUTPUT)
    t=confirmed_favorite_receipt_delta(path,t)
    from v029_video_feedback import apply_video_feedback, celebration_source, OUTPUT_ANIMATIONS, COMMIT as FEEDBACK_COMMIT
    t,feedbackProof=apply_video_feedback(path,t,parser)
    from v029_video_feedback_origin import apply_feedback_origin
    t,feedbackOriginProof=apply_feedback_origin(path,t)
    from v029_video_feedback_host import apply_feedback_lifetime
    t,feedbackLifetimeProof=apply_feedback_lifetime(path,t)
    if feedbackLifetimeProof is not None:
        save(OUTPUT/'v029-video-feedback-lifetime-proof.json',feedbackLifetimeProof)
    if feedbackOriginProof is not None:
        save(OUTPUT/'v029-video-feedback-origin-proof.json',feedbackOriginProof)
    if feedbackProof is not None:
        save(OUTPUT/'v029-video-feedback-state-proof.json',feedbackProof)
        animation,animationProof=celebration_source()
        write(OUTPUT/OUTPUT_ANIMATIONS,animation)
        save(OUTPUT/'v029-video-feedback-celebration-proof.json',animationProof)
        OUTPUTS.append(dict(path=OUTPUT_ANIMATIONS,origin=animationProof['origin'],mode='fixed-v029-complete-feedback-animations-platform-symbol-adapt',fixedCommit=FEEDBACK_COMMIT,sha256LF=sha(animation),physicalLines=len(animation.splitlines()),generated=True))
        from v029_video_feedback_host import feedback_motion_source, OUTPUT_MOTION
        motion,motionProof=feedback_motion_source()
        write(OUTPUT/OUTPUT_MOTION,motion)
        save(OUTPUT/'v029-video-feedback-motion-proof.json',motionProof)
        OUTPUTS.append(dict(path=OUTPUT_MOTION,origin=motionProof['origin'],mode='fixed-v029-complete-decoration-blocks-owned-window',fixedCommit=FEEDBACK_COMMIT,sha256LF=sha(motion),physicalLines=len(motion.splitlines()),generated=True))
    from v031_repost_coin import apply_and_record
    t,repostProof=apply_and_record(path,t,OUTPUT)
    if repostProof is not None:mode="fixed-v031-repost-coin-owned-delta"
    if STANDALONE or mode!='direct':write(OUTPUT/path,t)
    OUTPUTS.append(dict(path=path,origin=origin,mode=mode,sha256LF=sha(t),physicalLines=len(t.splitlines()),generated=STANDALONE or mode!='direct'))
def function_range(t,name):
    matches=list(re.finditer(r'(?m)^[ \t]*(?:(?:internal|private|suspend|inline)\s+)*fun\s+(?:[\w.]+\.)?'+re.escape(name)+r'\s*\(',t));assert len(matches)==1,(name,len(matches))
    m=matches[0];tokens=parser.kotlin_tokens(t);i=next(i for i,(_,a,_)in enumerate(tokens) if a>=m.start())
    while tokens[i][0]!='(':i+=1
    depth=1
    while depth:i+=1;depth+=(tokens[i][0]=='(')-(tokens[i][0]==')')
    while tokens[i][0]!='{':i+=1
    depth=1
    while depth:i+=1;depth+=(tokens[i][0]=='{')-(tokens[i][0]=='}')
    return m.start(),tokens[i][2]
def func(t,name,annotations=True):
    a,b=function_range(t,name);s=t[a:b]
    if annotations:
        previous=t[:a].rstrip('\n');lines=[]
        while previous.split('\n')[-1].startswith('@'):
            lines.insert(0,previous.split('\n')[-1]);previous=previous[:previous.rfind('\n')]
        return '\n'.join(lines)+'\n'+s
    return s
def selected(t,names):return selector.declarations(parser,t,names)
def remove_function(t,name):
    i,end=function_range(t,name)
    start=t.rfind('@Composable',0,i)
    if start>=0 and not t[start+len('@Composable'):i].strip():return t[:start]+t[end:]
    return t[:i]+t[end:]

def main():
    # Complete shared gesture renderers and policies, not a reduced offline alias.
    paths=['feature/video/ui/gesture/GestureLevelOverlay.kt','feature/video/ui/gesture/GestureLevelOverlayPolicy.kt','feature/video/ui/gesture/Md3GestureLevelHapticPolicy.kt','feature/video/ui/components/AnimatedGesturePercentText.kt','feature/video/ui/components/CircularGesturePercentText.kt','feature/video/ui/components/CircularGesturePercentMotionPolicy.kt']
    for rel in paths:
        p=BASE+rel;t=read(p)
        t=t.replace('import android.os.SystemClock\n','import com.bilipai.desktop.ui.DesktopOriginalVideoGestureClock as SystemClock\n')
        if 'import android.os.Build\n' in t:
            t=t.replace('import android.os.Build\n','import com.bilipai.desktop.ui.desktopDetailRenderEffectsSupported\n')
            t=adapt(t,'Build.VERSION.SDK_INT >= Build.VERSION_CODES.S','desktopDetailRenderEffectsSupported()','Circular numeral actual desktop RenderEffect capability')
        emit('com/android/purebilibili/'+rel,t,p,'full-source-platform-adapt' if t!=read(p) else 'direct')
    p=BASE+'feature/video/ui/section/VideoPlayerSectionPolicy.kt';t=read(p)
    emit('com/android/purebilibili/feature/video/ui/section/DesktopOriginalVideoGestureMotion.kt','package com.android.purebilibili.feature.video.ui.section\nimport com.android.purebilibili.feature.video.ui.components.GesturePercentMotionDefaults\n'+selected(t,['VideoGestureMode','VideoGestureMotionSpec','resolveVideoGestureMotionSpec'])+'\n',p,'selected-original-motion')
    # Full action renderer: the already installed public TripleProgressIcon is referenced.
    p=BASE+'feature/video/ui/section/VideoActionSection.kt';t=read(p)
    t=remove_function(t,'TripleProgressIcon')
    emit('com/android/purebilibili/feature/video/ui/section/DesktopOriginalVideoActionSection.kt',t,p,'full-renderer-reference-existing-TripleProgressIcon')
    # Information: all original ordinary info functions. Existing BGM/team/honor renderers are sole owners.
    p=BASE+'feature/video/ui/section/VideoInfoSection.kt';original=read(p)
    header=original[:original.index('private val useMiuixSpring')]
    for line in ['import com.android.purebilibili.data.repository.VideoRepository\n','import com.android.purebilibili.data.repository.ViewGrpcRepository\n','import com.android.purebilibili.feature.video.screen.buildVideoNavigationOptions\n','import com.android.purebilibili.core.store.SettingsManager\n','import androidx.compose.ui.platform.LocalContext\n']:
        header=header.replace(line,'')
    header+='import coil3.compose.LocalPlatformContext\nimport com.bilipai.desktop.ui.LocalDesktopOriginalVideoInfoBindings\nimport com.android.purebilibili.core.store.DesktopOriginalVideoInfoSettings as SettingsManager\n'
    pure=original[original.index('private val useMiuixSpring'):original.index('private const val BGM_DISCOVERY_LOAD_DELAY_MS')]
    bodies=[]
    for name in ['VideoTitleSection','VideoDetailSponsorLabelChip','VideoTitleWithDesc','UpInfoSection','DescriptionSection']:
        body=func(original,name)
        body=body.replace('(String, android.os.Bundle?) -> Unit','(String, Long) -> Unit')
        body=body.replace('com.android.purebilibili.core.store.SettingsManager','SettingsManager')
        body=body.replace('val context = LocalContext.current','val context = LocalDesktopOriginalVideoInfoBindings.current.context')
        body=body.replace('getPlayerControlVisibilitySettings(LocalContext.current)','getPlayerControlVisibilitySettings(LocalDesktopOriginalVideoInfoBindings.current.context)')
        body=body.replace('ImageRequest.Builder(LocalContext.current)','ImageRequest.Builder(LocalPlatformContext.current)')
        body=body.replace('VideoRepository.getCreatorCardStats(info.owner.mid)','LocalDesktopOriginalVideoInfoBindings.current.getCreatorCardStats(info.owner.mid)')
        # CompositionLocals must be captured during composition, not inside produceState coroutine.
        if name=='UpInfoSection':
            body=adapt(body,'    val playerControlVisibility by','    val VideoRepository = LocalDesktopOriginalVideoInfoBindings.current\n    val playerControlVisibility by','Capture actual info request binding')
            body=body.replace('LocalDesktopOriginalVideoInfoBindings.current.getCreatorCardStats(info.owner.mid)','VideoRepository.getCreatorCardStats(info.owner.mid)')
        body=body.replace('InlineBgmSection(','DesktopOriginalInlineBgmSection(')
        if name=='VideoTitleWithDesc':
            body=body.replace('SettingsManager\n        .getVideoArgueMsgShown(context)','com.android.purebilibili.core.store.DesktopOriginalVideoMetadataSettings\n        .getVideoArgueMsgShown(context)')
        bodies.append(body)
    emit('com/android/purebilibili/feature/video/ui/section/DesktopOriginalVideoInfoSection.kt',header+pure+'\n\n'.join(bodies)+'\n',p,'full-ordinary-info-renderers-and-rich-description')
    p=BASE+'feature/video/ui/section/VideoInfoDisplayPolicy.kt';t=read(p)
    for name in ['resolveVideoHonorChipText','resolveVideoHonorJumpUrl','shouldShowCreatorTeamSection','resolveVideoDetailBadges','resolveCompactPublishTimeRowText']:
        t=remove_function(t,name)
    emit('com/android/purebilibili/feature/video/ui/section/DesktopOriginalVideoInfoDisplayPolicy.kt',t,p,'remaining-original-display-policy-reference-sole-shared')
    # Actual47 already owns this full original policy; reference it rather than emit duplicate FQNs.
    p=BASE+'feature/video/ui/VideoFollowVisualPolicy.kt';read(p)
    p='design-system/src/main/java/com/android/purebilibili/core/ui/AppPlayerChromeProfile.kt';emit('com/android/purebilibili/core/ui/AppPlayerChromeProfile.kt',read(p),p,'direct')
    p=BASE+'feature/video/ui/section/VideoInfoSection.kt';t=read(p)
    emit('com/android/purebilibili/feature/video/ui/section/DesktopOriginalVideoBgmTag.kt','package com.android.purebilibili.feature.video.ui.section\nimport com.android.purebilibili.data.model.response.BgmInfo\n'+selected(t,['resolveBgmTagInfo'])+'\n',p,'original-tag-helper-no-existing-emitter')
    p=BASE+'feature/video/ui/section/OwnerDecoratedAvatar.kt';t=read(p)
    t=t.replace('import androidx.compose.ui.platform.LocalContext','import coil3.compose.LocalPlatformContext').replace('import com.android.purebilibili.data.repository.VideoRepository','import com.bilipai.desktop.ui.LocalDesktopOriginalVideoInfoBindings')
    t=adapt(t,'    var card by remember(ownerMid)','    val VideoRepository = LocalDesktopOriginalVideoInfoBindings.current\n    var card by remember(ownerMid)','Capture current original avatar request owner')
    t=t.replace('LocalContext.current','LocalPlatformContext.current')
    emit('com/android/purebilibili/feature/video/ui/section/OwnerDecoratedAvatar.kt',t,p,'full-original-avatar-platform-adapt')
    p=BASE+'core/ui/common/Modifiers.kt';t=read(p)
    copy_body='''package com.android.purebilibili.core.ui.common
import androidx.compose.foundation.clickable
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.bilipai.desktop.ui.LocalDesktopOriginalVideoInfoBindings
'''+t[t.index('fun Modifier.copyOnLongPress('):t.index(': Modifier = this',t.index('fun Modifier.copyOnLongPress('))+len(': Modifier = this')]+'\n\n'+func(t,'copyOnClick',False)+'\n'
    handler=func(t,'rememberClipboardCopyHandler',annotations=True)
    handler=handler.replace('val context = LocalContext.current','val context = LocalDesktopOriginalVideoInfoBindings.current')
    handler=handler.replace('copyPlainTextToClipboard(context, text, label ?: "BiliPai")','context.copyText(text, label ?: "BiliPai")')
    a='''                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                    Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
                } else if (label != null) {
                    Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
                }'''
    handler=adapt(handler,a,'                context.showFeedback(toastMsg)','Windows feedback replaces Android clipboard toast capability branch')
    # Reply's existing clipboard helper owns the original same-package public name.
    # Only this local helper/callsite name changes; the original copy semantics stay intact.
    copy_body=copy_body.replace('rememberClipboardCopyHandler()','rememberVideoInfoClipboardCopyHandler()')
    handler=handler.replace('rememberClipboardCopyHandler','rememberVideoInfoClipboardCopyHandler')
    emit('com/android/purebilibili/core/ui/common/DesktopOriginalVideoCopyModifiers.kt',copy_body+handler+'\n',p,'complete-original-copy-modifier-and-platform-feedback')
    p=BASE+'data/repository/VideoRepository.kt';t=read(p)
    emit('com/android/purebilibili/data/repository/DesktopOriginalCreatorCardStats.kt','package com.android.purebilibili.data.repository\n'+selected(t,['CreatorCardStats'])+'\n',p,'original-model')
    # Same existing global settings adapter: original keys, getters, defaults and mirror cache.
    p=BASE+'core/store/SettingsManager.kt';t=read(p);manager=t[t.index('object SettingsManager {')+len('object SettingsManager {'):t.rfind('}')]
    names=['KEY_VIDEO_INFO_DEFAULT_EXPANDED','KEY_VIDEO_TAG_SIZE_PRESET','KEY_SHOW_PLAYER_CAST_BUTTON','KEY_SHOW_VIDEO_FOLLOW_BUTTON','KEY_COMPACT_PLAYER_CHROME','KEY_TRIPLE_JUMP_ENABLED','KEY_EASTER_EGG_ENABLED','getVideoInfoDefaultExpanded','setVideoInfoDefaultExpanded','getVideoTagSizePreset','setVideoTagSizePreset','getPlayerControlVisibilitySettings','setShowPlayerCastButton','setShowVideoFollowButton','setCompactPlayerChrome','getTripleJumpEnabled','getEasterEggEnabled','setEasterEggEnabled','isEasterEggEnabledSync']
    settings='''package com.android.purebilibili.core.store
import com.bilipai.desktop.plugins.DesktopPluginContext as Context
import com.bilipai.desktop.plugins.booleanPreferencesKey
import com.bilipai.desktop.settings.dynamicIntPreferencesKey as intPreferencesKey
import com.bilipai.desktop.settings.dynamicSettingsDataStore as settingsDataStore
import com.android.purebilibili.core.ui.components.AppTagChipSize
import kotlinx.coroutines.flow.*
'''+selected(t,['PlayerControlVisibilitySettings'])+'\ninternal object DesktopOriginalVideoInfoSettings {\n'+selected(manager,names)+'\n}\n'
    emit('com/android/purebilibili/core/store/DesktopOriginalVideoInfoSettings.kt',settings,p,'original-key-read-write-bridge-same-global-store')
    engagement()
    from v031_repost_coin import direct_outputs
    for output,text,origin in direct_outputs():
        emit(output,text,origin,"fixed-v031-complete-original-source")
    read(BASE+'core/util/AnalyticsHelper.kt') # Original event/default/privacy contract for the local diagnostics platform port.
    save(OUTPUT/'source-bindings.json',dict(pinnedCommit=COMMIT,sourceIdentities=[dict(path=p,**{k:v for k,v in r.items() if k!='text'}) for p,r in SOURCES.items()],outputs=OUTPUTS,adaptations=ADAPTATIONS,scope='Complete original ordinary information/actions and shared gesture units. Full ordinary player overlays and page assembly are next, not yet claimed.',existingReferenceOwners=['metadata honor/team','BGM discovery full subtree','profile public TripleProgressIcon','Home resolveCompactPublishTimeRowText'],actualProductAcceptance=False))
    print(json.dumps(dict(sources=len(SOURCES),outputs=len(OUTPUTS)),ensure_ascii=False))
def engagement():
    p=BASE+'feature/video/viewmodel/VideoSubjectSnapshot.kt';t=read(p)
    emit('com/android/purebilibili/feature/video/viewmodel/VideoSubjectSnapshot.kt','package com.android.purebilibili.feature.video.viewmodel\nimport androidx.compose.runtime.Immutable\n'+selected(t,['VideoSubjectSnapshot'])+'\n',p,'complete-original-subject-schema')
    p=BASE+'feature/video/viewmodel/VideoDomainBinding.kt';emit('com/android/purebilibili/feature/video/viewmodel/VideoDomainBinding.kt',read(p),p,'direct')
    p=BASE+'feature/video/viewmodel/VideoEngagementViewModel.kt';t=read(p)
    header=t[:t.index('data class VideoEngagementSeed')]
    header=header.replace('import android.content.Context','import com.bilipai.desktop.plugins.DesktopPluginContext as Context')
    for s in ['import androidx.lifecycle.ViewModel\n','import androidx.lifecycle.viewModelScope\n','import com.android.purebilibili.core.store.TokenManager\n','import com.android.purebilibili.core.network.NetworkModule\n']:
        header=header.replace(s,'')
    header=header.replace('import com.android.purebilibili.core.store.SettingsManager','import com.android.purebilibili.core.store.DesktopOriginalVideoInfoSettings as SettingsManager')
    header+='import com.bilipai.desktop.ui.DesktopOriginalVideoEngagementEnvironment\nimport com.bilipai.desktop.ui.DesktopOriginalVideoEngagementPresentation\nimport kotlin.coroutines.EmptyCoroutineContext\nimport kotlinx.coroutines.*\n'
    models=t[t.index('data class VideoEngagementSeed'):t.index('private val DefaultVideoCoinBalanceLoader')]
    actions=t[t.index('interface VideoEngagementActions'):t.index('class VideoEngagementViewModel(')]
    actions=actions.replace('private val useCase: VideoInteractionUseCase = VideoInteractionUseCase()','private val useCase: VideoInteractionUseCase')
    actions=actions.replace('private class DefaultVideoEngagementActions','internal class DefaultVideoEngagementActions',1)
    body=t[t.index('class VideoEngagementViewModel('):t.index('internal fun VideoPlaybackUiState.Success.toEngagementSeed')]
    before='''class VideoEngagementViewModel(
    private val actions: VideoEngagementActions = DefaultVideoEngagementActions(),
    private val coinBalanceLoader: VideoCoinBalanceLoader = DefaultVideoCoinBalanceLoader
) : ViewModel() {'''
    after='''internal class VideoEngagementViewModel(private val environment: DesktopOriginalVideoEngagementEnvironment) {
    private val actions get() = environment.actions
    private val coinBalanceLoader get() = environment.coinBalanceLoader
    private val viewModelScope get() = environment.scope
    private fun updateOwned(transform: (VideoEngagementUiState) -> VideoEngagementUiState) =
        environment.commit { _uiState.update(transform) }
    private suspend fun sendOwned(event: VideoEngagementEvent) {
        currentCoroutineContext().ensureActive(); environment.assertOwned()
        _events.send(event)
    }
'''
    body=adapt(body,before,after,'Full EngagementVM uses required existing entry scope/actions/current owner')
    body=body.replace('private var appContext: Context? = null','private var appContext: Context? = environment.context')
    body=adapt(body,'private val _events = Channel<VideoEngagementEvent>(Channel.BUFFERED)','private val _events = Channel<Pair<Long?, VideoEngagementEvent>>(Channel.BUFFERED)','Original events retain transient subject generation in the existing channel')
    body=adapt(body,'val events = _events.receiveAsFlow()','val events = _events.receiveAsFlow().mapNotNull { (generation, event) ->\n        if (environment.isOwned() && _uiState.value.subject?.generation == generation) event else null\n    }','Reject queued old-subject events without changing original public event schema')
    body=body.replace('_uiState.update {','updateOwned {')
    body=body.replace('_events.send(', 'sendOwned(')
    # Undo the substitution in the one transport helper's actual channel send.
    body=body.replace('        sendOwned(event)\n    }','        _events.send(_uiState.value.subject?.generation to event)\n    }',1)
    body=body.replace('viewModelScope.launch {','viewModelScope.launch {\n            currentCoroutineContext().ensureActive(); environment.assertOwned();\n')
    body=body.replace('.onSuccess { result ->','.onSuccess { result ->\n                    currentCoroutineContext().ensureActive(); environment.assertOwned()')
    for n in ['following','liked','disliked','favorited','inWatchLater']:
        body=body.replace('.onSuccess { '+n+' ->','.onSuccess { '+n+' ->\n                    currentCoroutineContext().ensureActive(); environment.assertOwned()')
    body=body.replace('.onSuccess {\n','.onSuccess {\n                    currentCoroutineContext().ensureActive(); environment.assertOwned()\n')
    body=body.replace('.onFailure { emitMessage(it.message', '.onFailure { if (it is CancellationException) throw it; environment.assertOwned(); if (_uiState.value.subject?.generation == state.subject?.generation) emitMessage(it.message')
    body=body.replace('onResult?.invoke(liked)','environment.commit { if (_uiState.value.subject?.generation == state.subject?.generation) onResult?.invoke(liked) }').replace('onResult?.invoke(result)','environment.commit { if (_uiState.value.subject?.generation == state.subject?.generation) onResult?.invoke(result) }')
    # Original bind replaces the full seed atomically, and only under the same account/entry gate.
    a='        _uiState.value = VideoEngagementUiState(';b='        environment.commit { _uiState.value = VideoEngagementUiState('
    body=adapt(body,a,b,'Atomic initial engagement seed publication')
    body=adapt(body,'            followingMids = seed.followingMids\n        )','            followingMids = seed.followingMids\n        ) }','Close seed publication gate')
    # Coin balance is an async subject read, so do not write a replacement video's open dialog.
    body=adapt(body,'    fun openCoinDialog() {','    fun openCoinDialog() {\n        val capturedSubject = _uiState.value.subject','Capture coin balance subject generation')
    body=adapt(body,'            val balance = coinBalanceLoader.load()','            val balance = coinBalanceLoader.load()\n            currentCoroutineContext().ensureActive(); environment.assertOwned()\n            if (_uiState.value.subject?.generation != capturedSubject?.generation) return@launch','Reject coin balance after old subject retires')
    body=adapt(body,'    internal fun emitMessage(message: String) {','    internal fun emitMessage(message: String) {\n        val capturedGeneration = _uiState.value.subject?.generation','Capture delayed original feedback subject')
    body=adapt(body,'environment.assertOwned();\n sendOwned(VideoEngagementEvent.Message(message))','environment.assertOwned();\n            if (_uiState.value.subject?.generation != capturedGeneration) return@launch\n            sendOwned(VideoEngagementEvent.Message(message))','Reject replacement subject during original feedback launch')
    body=engagement_presentation_delta(body)
    header+='import kotlinx.coroutines.flow.mapNotNull\n'
    emit('com/android/purebilibili/feature/video/viewmodel/VideoEngagementViewModel.kt',header+models+actions+body,p,'complete-original-engagement-state-actions-algorithm-owned-platform')
    factory='''internal fun originalVideoEngagementActions(useCase: VideoInteractionUseCase): VideoEngagementActions = DefaultVideoEngagementActions(useCase)
'''
    emit('com/android/purebilibili/feature/video/viewmodel/DesktopOriginalVideoEngagementActionsFactory.kt','package com.android.purebilibili.feature.video.viewmodel\nimport com.android.purebilibili.feature.video.usecase.VideoInteractionUseCase\n'+factory,p,'thin-existing-original-usecase-delegate')
    # Original default loader becomes a required owned API-backed loader, preserving sentinel/timeout policy.
    loader=t[t.index('private val DefaultVideoCoinBalanceLoader'):t.index('interface VideoEngagementActions')]
    loader=loader.replace('private val DefaultVideoCoinBalanceLoader = VideoCoinBalanceLoader {','internal fun originalVideoCoinBalanceLoader(api: com.android.purebilibili.core.network.BilibiliApi, hasSession: () -> Boolean, assertOwned: () -> Unit): VideoCoinBalanceLoader = VideoCoinBalanceLoader {\n    currentCoroutineContext().ensureActive(); assertOwned()')
    loader=loader.replace('TokenManager.sessDataCache.isNullOrEmpty()','!hasSession()').replace('NetworkModule.api.getNavInfo()','api.getNavInfo().also { currentCoroutineContext().ensureActive(); assertOwned() }')
    loader=loader.replace('} catch (_: Exception) {','} catch (_: Exception) {\n            assertOwned()')
    emit('com/android/purebilibili/feature/video/viewmodel/DesktopOriginalVideoCoinBalanceLoader.kt','package com.android.purebilibili.feature.video.viewmodel\nimport kotlinx.coroutines.*\n'+loader,p,'original-coin-read-timeout-policy-existing-owned-api')
    # Preserve the complete use case and its real required analytics/transport callbacks.
    p=BASE+'feature/video/usecase/VideoInteractionUseCase.kt';t=read(p)
    t=t.replace('import com.android.purebilibili.core.util.AnalyticsHelper','import com.bilipai.desktop.ui.DesktopOriginalVideoInteractionAnalytics')
    t=t.replace('import com.android.purebilibili.core.util.Logger','import com.bilipai.desktop.ui.DesktopOriginalVideoInteractionLog as Logger')
    t=t.replace('import com.android.purebilibili.data.repository.ActionRepository','import com.android.purebilibili.data.repository.DesktopOriginalVideoEngagementProtocol')
    t=adapt(t,'class VideoInteractionUseCase {','internal class VideoInteractionUseCase(\n    private val ActionRepository: DesktopOriginalVideoEngagementProtocol,\n    private val AnalyticsHelper: DesktopOriginalVideoInteractionAnalytics,\n) {','Original complete interaction usecase on existing owned transport and required analytics')
    emit('com/android/purebilibili/feature/video/usecase/VideoInteractionUseCase.kt',t,p,'complete-original-usecase-required-owned-dependencies')
    p=BASE+'feature/video/ui/feedback/TripleActionVisualStatePolicy.kt';emit('com/android/purebilibili/feature/video/ui/feedback/TripleActionVisualStatePolicy.kt',read(p),p,'direct')
    p=BASE+'feature/video/ui/feedback/VideoActionFeedbackMessagePolicy.kt';emit('com/android/purebilibili/feature/video/ui/feedback/VideoActionFeedbackMessagePolicy.kt',read(p),p,'direct')
    protocol()

def engagement_presentation_delta(body):
    """Keep the complete original actions; carry one caller's accepted lease.
    Each adaptation is counted and inversely checked against the prior owned
    full-body platform projection. Canonical v025 pins are unchanged.
    """
    before=body;first=len(ADAPTATIONS)
    body=adapt(body,'private val _events = Channel<Pair<Long?, VideoEngagementEvent>>(Channel.BUFFERED)',
        'private val _events = Channel<Triple<Long?, DesktopOriginalVideoEngagementPresentation?, VideoEngagementEvent>>(Channel.BUFFERED)',
        'Queued original events also retain the captured presentation permission')
    body=adapt(body,'_events.send(_uiState.value.subject?.generation to event)',
        '_events.send(Triple(_uiState.value.subject?.generation, DesktopOriginalVideoEngagementPresentation.capture(), event))',
        'Stamp the existing event channel without changing its public event schema')
    body=adapt(body,'''val events = _events.receiveAsFlow().mapNotNull { (generation, event) ->
        if (environment.isOwned() && _uiState.value.subject?.generation == generation) event else null
    }''','''val events = _events.receiveAsFlow().mapNotNull { (generation, presentation, event) ->
        var eligible = false
        val check = { eligible = environment.isOwned() && _uiState.value.subject?.generation == generation }
        val admitted = if (presentation == null) { check(); true } else presentation.admit(check)
        if (admitted && eligible) event else null
    }''','Reject old accepted-source events even when the subject generation is unchanged')
    for name,old,new in [
        ('toggleFollow','fun toggleFollow(mid: Long? = null, currentlyFollowing: Boolean? = null)',
            'fun toggleFollow(mid: Long? = null, currentlyFollowing: Boolean? = null, presentation: DesktopOriginalVideoEngagementPresentation? = null)'),
        ('doTripleAction','        onResult: ((TripleActionResult) -> Unit)? = null',
            '        onResult: ((TripleActionResult) -> Unit)? = null,\n        presentation: DesktopOriginalVideoEngagementPresentation? = null'),
    ]:
        a,b=function_range(body,name);method=body[a:b]
        assert method.count(old)==method.count('viewModelScope.launch {')==1,name
        changed=method.replace(old,new,1).replace('viewModelScope.launch {',
            'viewModelScope.launch(presentation?.context ?: EmptyCoroutineContext) {',1)
        # Preserve both the original visual state and its modified-field receipt
        # in one bounded commit. Nothing in these ranges suspends or performs IO.
        start=changed.index('                    locallyModifiedFields =') if name=='toggleFollow' else changed.index('                    val visual =')
        end=changed.index('                    emitMessage(if (following)') if name=='toggleFollow' else changed.index('                    if (result.favoriteSuccess) {\n                        sendOwned(')
        bounded=changed[start:end]
        changed=changed[:start]+'                    environment.commit {\n'+''.join('    '+line if line.strip() else line for line in bounded.splitlines(keepends=True))+'                    }\n'+changed[end:]
        body=adapt(body,method,changed,'Original '+name+' complete body receives and launches the captured presentation permission')
    body=adapt(body,'        val capturedGeneration = _uiState.value.subject?.generation\n        viewModelScope.launch {',
        '        val capturedGeneration = _uiState.value.subject?.generation\n        val presentation = DesktopOriginalVideoEngagementPresentation.capture()\n        viewModelScope.launch(presentation?.context ?: EmptyCoroutineContext) {',
        'Original nested feedback launch inherits the same captured permission')
    edits=ADAPTATIONS[first:]
    inverse=body
    for edit in reversed(edits):
        assert inverse.count(edit['after'])==1,edit['label']
        inverse=inverse.replace(edit['after'],edit['before'],1)
    assert inverse==before,'Complete engagement presentation adaptation inverse differs'
    save(OUTPUT/'engagement-presentation-proof.json',dict(
        upstreamCommit=COMMIT,originalSourceSha256LF=SOURCE_PINS[BASE+'feature/video/viewmodel/VideoEngagementViewModel.kt']['sha256LF'],
        previousOwnedBodySha256LF=sha(before),adaptedOwnedBodySha256LF=sha(body),
        fullOwnedBodyInverse=True,adaptations=edits,scope='Same retained original VM/actions; command-card presentation permission only'))
    return body

def protocol():
    p=BASE+'data/repository/ActionRepository.kt';t=read(p);methods=[]
    names=['followUser','favoriteVideo','getDefaultFolderId','likeVideo','dislikeVideo','coinVideo','tripleAction','toggleWatchLater','checkLikeStatus','checkFavoriteStatus','checkFollowStatus','checkCoinStatus']
    for name in names:
        body=func(t,name,False)
        body=body.replace('TokenManager.csrfCache','readCsrf()').replace('TokenManager.midCache','readMid()').replace('TokenManager.sessDataCache','readSessData()').replace('TokenManager.accessTokenCache','readAccessToken()')
        body=body.replace('com.android.purebilibili.core.util.Logger','Logger').replace('android.util.Log.e','Logger.e')
        body=body.replace('_followStateChanges.tryEmit(FollowStateChange(mid = mid, isFollowing = follow))','confirmFollow(FollowStateChange(mid = mid, isFollowing = follow))')
        if name=='followUser':
            body=adapt(body,'confirmFollow(FollowStateChange(mid = mid, isFollowing = follow))',
                'DesktopOriginalVideoEngagementPresentation.commitCurrent { assertOwned(); confirmFollow(FollowStateChange(mid = mid, isFollowing = follow)) }',
                'Source admission precedes the existing success-only account follow event')
        body=body.replace('withContext(Dispatchers.IO) {','withContext(Dispatchers.IO) {\n            currentCoroutineContext().ensureActive(); assertOwned()')
        # Guard response publication and any original success bus before updating UI consumers.
        body=body.replace('                if (response.code == 0) {','                currentCoroutineContext().ensureActive(); assertOwned()\n                if (response.code == 0) {')
        body=body.replace('                when (response.code) {','                currentCoroutineContext().ensureActive(); assertOwned()\n                when (response.code) {')
        body=body.replace('                when {','                currentCoroutineContext().ensureActive(); assertOwned()\n                when {')
        if name=='getDefaultFolderId':
            body=adapt(body,'            val response = api.getFavFolders(mid)','            currentCoroutineContext().ensureActive(); assertOwned()\n            val response = api.getFavFolders(mid)\n            currentCoroutineContext().ensureActive(); assertOwned()','Original default-folder read guarded before response consumption')
        body=body.replace('} catch (e: Exception) {','} catch (cancelled: CancellationException) {\n                throw cancelled\n            } catch (e: Exception) {\n                assertOwned()')
        methods.append(body)
    triple=selected(t[t.index('object ActionRepository {')+len('object ActionRepository {'):t.rfind('}')],['TripleResult'])
    header='''package com.android.purebilibili.data.repository
import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.data.model.response.FavFolder
import com.android.purebilibili.core.refresh.WatchLaterRefreshBus
import com.bilipai.desktop.ui.DesktopOriginalVideoInteractionLog as Logger
import com.bilipai.desktop.ui.DesktopOriginalVideoEngagementPresentation
import kotlinx.coroutines.*

/** Original action bodies on the already-owned Root API; no network/session/store is built. */
internal class DesktopOriginalVideoEngagementProtocol(
    private val api: BilibiliApi,
    private val readCsrf: () -> String?,
    private val readMid: () -> Long?,
    private val readSessData: () -> String?,
    private val readAccessToken: () -> String?,
    private val ownerCheckpoint: () -> Unit,
    private val confirmFollow: (FollowStateChange) -> Unit,
    private val folderProtocol: DesktopOriginalFavoriteFolderProtocol,
) {
    private fun assertOwned() {
        DesktopOriginalVideoEngagementPresentation.assertCurrent()
        ownerCheckpoint()
    }
    suspend fun getFavoriteFolders(aid:Long?=null):Result<List<FavFolder>> = folderProtocol.getFavoriteFolders(aid)
    suspend fun updateFavoriteFolders(aid:Long,addFolderIds:Set<Long>,removeFolderIds:Set<Long>):Result<Boolean> = folderProtocol.updateFavoriteFolders(aid,addFolderIds,removeFolderIds)
'''
    emit('com/android/purebilibili/data/repository/DesktopOriginalVideoEngagementProtocol.kt',header+triple+'\n\n'+'\n\n'.join(methods)+'\n}\n',p,'original-raw-protocol-original-fields-errors-sequential-triple')
    # The original creator card cache has exactly one owner, within this current Ops/detail entry.
    p=BASE+'data/repository/VideoRepository.kt';t=read(p);body=func(t,'getCreatorCardStats',False)
    body=body.replace('withContext(Dispatchers.IO) {','withContext(Dispatchers.IO) {\n        currentCoroutineContext().ensureActive(); assertOwned()')
    body=body.replace('            if (response.code == 0 && data != null) {','            currentCoroutineContext().ensureActive(); assertOwned()\n            if (response.code == 0 && data != null) {')
    body=body.replace('} catch (e: Exception) {','} catch (cancelled: CancellationException) {\n            throw cancelled\n        } catch (e: Exception) {\n            assertOwned()')
    emit('com/android/purebilibili/data/repository/DesktopOriginalVideoCreatorCard.kt','''package com.android.purebilibili.data.repository
import com.android.purebilibili.core.network.BilibiliApi
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
internal class DesktopOriginalVideoCreatorCard(private val api:BilibiliApi,private val assertOwned:()->Unit) {
    private val creatorCardStatsCache = ConcurrentHashMap<Long, CreatorCardStats>()
'''+body+'\n}\n',p,'original-creator-read-cache-single-current-entry-authority')
    fragment='''// Desktop original complete video engagement/info binding
    private val videoCreatorCard by lazy { com.android.purebilibili.data.repository.DesktopOriginalVideoCreatorCard(api, ::assertOwned) }
    suspend fun getVideoCreatorCardStats(mid:Long):Result<com.android.purebilibili.data.model.response.CreatorCardStats> = result {
        read { videoCreatorCard.getCreatorCardStats(mid).getOrThrow() }
    }
    internal fun originalVideoCoinBalanceLoader():com.android.purebilibili.feature.video.viewmodel.VideoCoinBalanceLoader =
        com.android.purebilibili.feature.video.viewmodel.originalVideoCoinBalanceLoader(api,
            { assertOwned(); !repository.authCookies()["SESSDATA"].isNullOrEmpty() }, ::assertOwned)
    internal fun originalVideoEngagementActions(analytics:com.bilipai.desktop.ui.DesktopOriginalVideoInteractionAnalytics):com.android.purebilibili.feature.video.viewmodel.VideoEngagementActions {
        val owner = repository.dynamicCacheSessionGuard.dynamicCacheOwner()
        val protocol = com.android.purebilibili.data.repository.DesktopOriginalVideoEngagementProtocol(api,
            { assertOwned(); repository.requireCsrf() },
            { assertOwned(); repository.account.value?.mid },
            { assertOwned(); repository.authCookies()["SESSDATA"] },
            { assertOwned(); repository.accessTokenCredentials().first }, ::assertOwned,
            { change -> assertOwned(); repository.followStateEvents.confirm(checkNotNull(owner),change) },
            com.android.purebilibili.data.repository.DesktopOriginalFavoriteFolderProtocol(api,
                { assertOwned(); repository.account.value?.mid }, { assertOwned(); repository.requireCsrf() }, ::assertOwned))
        val original = com.android.purebilibili.feature.video.viewmodel.originalVideoEngagementActions(
            com.android.purebilibili.feature.video.usecase.VideoInteractionUseCase(protocol,analytics))
        return object : com.android.purebilibili.feature.video.viewmodel.VideoEngagementActions {
            override suspend fun toggleFollow(mid:Long,currentlyFollowing:Boolean)=result { mutate { original.toggleFollow(mid,currentlyFollowing).getOrThrow() } }
            override suspend fun toggleLike(aid:Long,currentlyLiked:Boolean,bvid:String)=result { mutate { original.toggleLike(aid,currentlyLiked,bvid).getOrThrow() } }
            override suspend fun toggleDislike(aid:Long,currentlyDisliked:Boolean,bvid:String)=result { mutate { original.toggleDislike(aid,currentlyDisliked,bvid).getOrThrow() } }
            override suspend fun toggleFavorite(aid:Long,currentlyFavorited:Boolean,bvid:String)=result { mutate { original.toggleFavorite(aid,currentlyFavorited,bvid).getOrThrow() } }
            override suspend fun toggleWatchLater(aid:Long,currentlyInWatchLater:Boolean,bvid:String)=result { mutate { original.toggleWatchLater(aid,currentlyInWatchLater,bvid).getOrThrow() } }
            override suspend fun doCoin(aid:Long,count:Int,alsoLike:Boolean,bvid:String)=result { mutate { original.doCoin(aid,count,alsoLike,bvid).getOrThrow() } }
            override suspend fun doTripleAction(aid:Long,coinCount:Int)=result { mutate { original.doTripleAction(aid,coinCount).getOrThrow() } }
        }
    }
'''
    brandBefore = fragment
    brandFirst = len(ADAPTATIONS)
    fragment = adapt(fragment,
        '{ change -> assertOwned(); repository.followStateEvents.confirm(checkNotNull(owner),change) },',
        '{ change -> assertOwned(); repository.followStateEvents.confirm(checkNotNull(owner),change)\n                com.bilipai.desktop.ui.DesktopOriginalVideoEngagementPresentation.confirmBrandFollow(change.isFollowing) },',
        'Confirmed original protocol callback submits only its originating Windows brand receipt')
    brandEdits = ADAPTATIONS[brandFirst:]
    brandInverse = fragment
    for edit in reversed(brandEdits):
        assert brandInverse.count(edit['after']) == 1, edit['label']
        brandInverse = brandInverse.replace(edit['after'], edit['before'], 1)
    assert brandInverse == brandBefore
    save(OUTPUT/'brand-success-operations-proof.json', dict(schemaVersion=1,
        originalProtocolCommit=COMMIT, fixedBrandCommit='a4b77f894d0a2dd26c0b9fc144b8adb88ac05480',
        beforeSha256LF=sha(brandBefore), afterSha256LF=sha(fragment), fullInverseExact=True, edits=brandEdits))
    write(OUTPUT/'video-operations-members.fragment',fragment)
def generate(repo,output,standalone=False):
    global REPO,OUTPUT,STANDALONE,parser,media,selector,SOURCES,OUTPUTS,ADAPTATIONS
    REPO=Path(repo).resolve();OUTPUT=Path(output).resolve();STANDALONE=standalone
    SOURCES={};OUTPUTS=[];ADAPTATIONS=[]
    manifest=json.loads(wide(REPO/'desktop/upstream-sources.json').read_text(encoding='utf-8'))
    assert manifest['upstreamCommit']==COMMIT
    registry={r['path']:r['sha256']for r in manifest['sources']}
    for path,pin in SOURCE_PINS.items():
        if path in registry:assert registry[path]==pin['sha256LF'],path+' existing registry identity differs'
    parser=module('video_full_tokens',REPO/'desktop/tools/sync-upstream.py')
    media=module('video_full_functions',REPO/'desktop/tools/extract-upstream-media.py')
    selector=module('video_full_decls',REPO/'desktop/tools/extract-appearance-platform.py')
    main()
if __name__=='__main__':generate(Path(sys.argv[1]),Path(sys.argv[2]),'--standalone' in sys.argv[3:])
