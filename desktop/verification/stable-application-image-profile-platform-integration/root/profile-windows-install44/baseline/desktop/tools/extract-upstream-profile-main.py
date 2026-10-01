"""Complete original stable Profile primary navigation UI / VM and wallpaper sheets.
Android account/settings/file/media operations become REQUIRED Windows ports, never fake Android.
Existing StoredAccountSession, raw APIs/DTO, Favorite protocol and skin repeat policy are reused.
"""
from pathlib import Path
import argparse,hashlib,json,importlib.util,re
SOURCE_PINS={'app/src/main/java/com/android/purebilibili/feature/profile/ProfileScreen.kt': '034c92c6622f540400972353e26ec1a61a022151cbb71e7c6ee45f4305617a0c', 'app/src/main/java/com/android/purebilibili/feature/profile/ProfileViewModel.kt': 'a2a08a77fefc78f9cbe41760034e273bb34f5555b65e979f7e12fad147c333a1', 'app/src/main/java/com/android/purebilibili/feature/profile/ProfileChromePolicy.kt': '0a26a4799c92fdeaf1f10b470ab85aca7f6dd12a269add128c55a836752348ef', 'app/src/main/java/com/android/purebilibili/feature/profile/ProfileLayoutPolicy.kt': '756136f81ec01df0575822ab2168851ffc43b34256eac13145adeb849d8319a1', 'app/src/main/java/com/android/purebilibili/feature/profile/ProfileSpacePolicy.kt': 'd7c2e8857b7006f7b11a97b27712f98a4b642342c22f75b190ccc6c93600de7e', 'app/src/main/java/com/android/purebilibili/feature/profile/ProfileFavoriteFolderShortcutPolicy.kt': '7e170e76dcea8912827b0de91e385be9dafca82587cf5659fd1487433a8a9bba', 'app/src/main/java/com/android/purebilibili/feature/profile/ProfileDashboardPolicy.kt': '88173befd9eac7db213b6e771e34ba9960a99a8b9e6f187fc01bc4348bcf7e3a', 'app/src/main/java/com/android/purebilibili/feature/profile/ProfileLoadingSkeleton.kt': 'cb193c0afc53bab41e26dbf97ceaf9ae63664cf365eeb81292e4bc963f468137', 'app/src/main/java/com/android/purebilibili/feature/profile/OfficialWallpaperSheet.kt': '0753f850d0feca7412619ad98cbf9a7b9243c51df67d99e6a7bac6032236c59c', 'app/src/main/java/com/android/purebilibili/feature/profile/OfficialWallpaperSelectionPolicy.kt': 'b0f8d1a53099f1d0e6694c941479cf645b36533ce7fee931bab5245a8b7654c8', 'app/src/main/java/com/android/purebilibili/feature/profile/WallpaperAdjustmentSheet.kt': '7aa9b8853899b0f4eb71e2e3414d173f8556fbc05ef4f4b56f7bffcfcadd2756', 'app/src/main/java/com/android/purebilibili/feature/profile/WallpaperImageImport.kt': '9d1d439a42738a9e1b92340e1b5c3e9c2abe5cf578cf3f605a43388f178cbc91', 'app/src/main/java/com/android/purebilibili/feature/profile/SplashWallpaperRandomPoolPolicy.kt': '693bf431e9f34e8ae87f6fa785d6259b58c75921785294f74c30b8df81c663d2', 'app/src/main/java/com/android/purebilibili/core/ui/wallpaper/WallpaperMedia.kt': '8f6f22e673a0b8d11455b2c4762ec72ce98ea5ff68eb270d4f021748c289324b', 'design-system/src/main/java/com/android/purebilibili/core/ui/wallpaper/ProfileWallpaperTransformPolicy.kt': 'd196cef4d2e96343ce7d42b2147c9ffd2793458e8d6758a65942fe44b64f16f3', 'app/src/main/java/com/android/purebilibili/data/repository/SplashRepository.kt': 'b962ce085c19bf4afef44770640e0b029a020358048a15bbfc4f89a0577b5ed8', 'app/src/main/java/com/android/purebilibili/core/store/SettingsManager.kt': '680005e1f25e8a365d30f0c78c988765e7d2140008c57d9bf31d859c5b835b1c', 'app/src/main/java/com/android/purebilibili/core/store/AccountSessionStore.kt': 'c7619b2386b42349693098c28657867cf8b73af3a66e1353d039bdc037a68011', 'app/src/main/java/com/android/purebilibili/core/network/ApiClient.kt': '42032bd904ecd3c2bb91f0d03134c7b409632b1aa08f844929e6db247bd852ad', 'app/src/main/java/com/android/purebilibili/feature/home/HomeScreen.kt': '54e78abcf5182de4339a0fe8dd6331de5ee07f363115024e91e6a69f9d23850a', 'app/src/main/java/com/android/purebilibili/navigation/AppNavigation.kt': '218267eba2d04714c57d0a67d319d6c11856c7fca4471cee294ed9e999aefa59', 'app/src/main/java/com/android/purebilibili/core/ui/TopReadabilityChrome.kt': 'aa046cdbabbed0e6cc983756b0b524e5bd9eff5caa03572ffe963de24e27ad1b', 'app/src/main/java/com/android/purebilibili/feature/video/ui/section/VideoActionSection.kt': '799f5d6fdd39adba83bd59aa52860b3d3378908b135aed4c55527e50686dc03a', 'app/src/main/java/com/android/purebilibili/feature/video/ui/components/CelebrationAnimations.kt': '0962e31c0e46ad0a870b4e5a4612ba1e7d31c4aa9a71cac2ea5480d6ca470372', 'app/src/main/java/com/android/purebilibili/feature/video/ui/feedback/TripleActionMotionSpec.kt': 'c1adf12dc3a718cd46b75c83bb7e767e1207e11a58f0abe7bb749157cfbfe7c8', 'app/src/main/java/com/android/purebilibili/feature/video/ui/feedback/VideoActionFeedbackPolicy.kt': '8be87ce3d877b5ad25793847ec0664d1196d8b5fe0abe700d2e29e0440a242c5', 'app/src/main/java/com/android/purebilibili/core/store/SplashWallpaperHistoryPolicy.kt': 'f18f960603e1d8b2fc2d12e1429209e3e98fb10c55817a603a35cc58b24f372d'}
BASE='app/src/main/java/com/android/purebilibili/'
DIRECT_NAMES=['ProfileChromePolicy','ProfileLayoutPolicy','ProfileSpacePolicy','ProfileFavoriteFolderShortcutPolicy','ProfileDashboardPolicy','OfficialWallpaperSelectionPolicy','SplashWallpaperRandomPoolPolicy']
def safe(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def write(p,s):
 p=safe(p);p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s,encoding='utf-8',newline='\n')
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
def load(p,n):
 sp=importlib.util.spec_from_file_location(n,p);m=importlib.util.module_from_spec(sp);sp.loader.exec_module(m);return m
def generate(repo,out,standalone=False):
 repo=Path(repo);out=Path(out);source={p:read(repo/p) for p in SOURCE_PINS}
 for p,s in source.items():assert sha(s)==SOURCE_PINS[p],p
 parser=load(repo/'desktop/tools/sync-upstream.py','profile_parser');media=load(repo/'desktop/tools/extract-upstream-media.py','profile_selector');records=[]
 def emit(path,body,target=None):
  target=target or 'com/android/purebilibili/'+path.removeprefix(BASE)
  write(out/target,body);records.append(dict(source=path,output=target,originalSha256LF=sha(source[path]),preparedSha256LF=sha(body)))
 def replace_fun(s,name,body):
  matches=list(re.finditer(r'(?m)^[ \t]*(?:(?:internal|private|suspend)\s+)*fun\s+'+re.escape(name)+r'\s*\(',s));assert len(matches)==1,name
  match=matches[0];tokens=parser.kotlin_tokens(s);i=next(i for i,t in enumerate(tokens) if t[1]>=match.start())
  while tokens[i][0]!='(':i+=1
  depth=1
  while depth:i+=1;depth+=(tokens[i][0]=='(')-(tokens[i][0]==')')
  while tokens[i][0]!='{':i+=1
  depth=1
  while depth:i+=1;depth+=(tokens[i][0]=='{')-(tokens[i][0]=='}')
  return s[:match.start()]+body+s[tokens[i][2]:]
 for n in DIRECT_NAMES:
  p=BASE+'feature/profile/'+n+'.kt'
  if standalone:emit(p,source[p])
 p='design-system/src/main/java/com/android/purebilibili/core/ui/wallpaper/ProfileWallpaperTransformPolicy.kt'
 if standalone:emit(p,source[p],'com/android/purebilibili/core/ui/wallpaper/ProfileWallpaperTransformPolicy.kt')
 p=BASE+'feature/video/ui/feedback/TripleActionMotionSpec.kt'
 if standalone:emit(p,source[p])
 p=BASE+'feature/video/ui/feedback/VideoActionFeedbackPolicy.kt'
 if standalone:emit(p,source[p])
 p=BASE+'core/store/SplashWallpaperHistoryPolicy.kt'
 if standalone:emit(p,source[p])
 p=BASE+'core/ui/TopReadabilityChrome.kt'
 if standalone:emit(p,source[p])
 p=BASE+'feature/video/ui/section/VideoActionSection.kt';body=media.function(source[p],'TripleProgressIcon',parser)
 imports="""package com.android.purebilibili.feature.video.ui.section
import androidx.compose.runtime.Composable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.components.AppIcon
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.feature.video.ui.feedback.resolveVideoActionTint
import com.android.purebilibili.feature.video.ui.feedback.resolveVideoActionCountTint
"""
 emit(p,imports+'\n@Composable\n'+body+'\n','com/android/purebilibili/feature/video/ui/section/DesktopOriginalProfileTripleProgressIcon.kt')
 p=BASE+'feature/video/ui/components/CelebrationAnimations.kt'
 imports='\n'.join(l for l in source[p].splitlines() if l.startswith('import ') and 'core.plugin.skin' not in l)
 names=['TripleSuccessAnimation','TripleActionIcon','phaseProgress','iconActivationProgress','lerp']
 body='package com.android.purebilibili.feature.video.ui.components\n'+imports+'\n\n'
 for name in names:body+=('@Composable\n' if name in names[:2] else '')+media.function(source[p],name,parser)+'\n\n'
 emit(p,body,'com/android/purebilibili/feature/video/ui/components/DesktopOriginalProfileTripleSuccessAnimation.kt')
 def ui(s):
  s='\n'.join(l for l in s.splitlines() if not l.startswith('import android.') and not l.startswith('import androidx.activity.') and not any(x in l for x in ['import androidx.core.view.WindowInsetsControllerCompat','import androidx.media3.','import androidx.compose.ui.viewinterop.AndroidView','import androidx.lifecycle.viewmodel.compose.viewModel','import com.android.purebilibili.core.util.PickGalleryVisualMedia','import coil3.request.allowHardware','import com.android.purebilibili.core.store.SettingsManager','import androidx.compose.ui.platform.LocalView']))+'\n'
  s=s.replace('import androidx.compose.ui.platform.LocalContext','import coil3.compose.LocalPlatformContext').replace('import androidx.compose.ui.platform.LocalConfiguration','import com.bilipai.desktop.ui.desktopProfileWindowConfiguration')
  s=s.replace('import coil3.request.placeholder\n','')
  s=s.replace('package com.android.purebilibili.feature.profile','package com.android.purebilibili.feature.profile\nimport com.bilipai.desktop.ui.LocalDesktopProfileEnvironment\nimport com.bilipai.desktop.ui.rememberDesktopProfileMediaPicker')
  s=s.replace('LocalContext.current','LocalPlatformContext.current').replace('LocalConfiguration.current','desktopProfileWindowConfiguration()')
  s=s.replace('    val context = LocalPlatformContext.current','    val platform = LocalDesktopProfileEnvironment.current\n    val context = LocalPlatformContext.current')
  s=s.replace('    val view = LocalView.current\n','').replace('viewModel: ProfileViewModel = viewModel()','viewModel: ProfileViewModel')
  s=s.replace('.placeholder(android.R.color.darker_gray)','')
  s=s.replace('.allowHardware(false)','')
  s=s.replace('import coil3.imageLoader\n','').replace('import com.android.purebilibili.core.ui.blur.shouldAllowRenderEffectBackedHazeEffect\n','')
  s=s.replace('import com.android.purebilibili.core.ui.motion.rememberSystemReduceMotion','import com.bilipai.desktop.ui.rememberDesktopDynamicReduceMotion as rememberSystemReduceMotion')
  s=s.replace('Logger.w("WallpaperAdjustment", "Wallpaper preview failed", it.result.throwable)','platform.platform.reportWallpaperPreviewFailure(it.result.throwable)')
  s=s.replace('com.android.purebilibili.R.mipmap.ic_launcher_3d','LocalDesktopProfileEnvironment.current.platform.applicationIconModel')
  s=s.replace('shouldAllowRenderEffectBackedHazeEffect(Build.VERSION.SDK_INT)','platform.supportsRenderEffectBackedHaze')
  s=re.sub(r'Toast\.makeText\(\s*context\s*,([\s\S]*?),\s*Toast\.LENGTH_(?:SHORT|LONG),?\s*\)\.show\(\)',r'platform.feedback(\1)',s)
  s=s.replace('SettingsManager.getPrivacyModeEnabled(context)','platform.preferences.getPrivacyModeEnabled()').replace('SettingsManager.isPrivacyModeEnabledSync(context)','platform.preferences.isPrivacyModeEnabledSync()').replace('SettingsManager.setPrivacyModeEnabled(context,','platform.preferences.setPrivacyModeEnabled(').replace('SettingsManager.setThemeMode(\n                context,','platform.preferences.setThemeMode(').replace('SettingsManager.getShowProfileEditButton(context)','platform.preferences.getShowProfileEditButton()')
  s=s.replace('com.android.purebilibili.core.util.AnalyticsHelper.logScreenView("ProfileScreen")','platform.analytics.logScreenView("ProfileScreen")')
  # Required file picker carries the actual Root Window; original local state/onSelect remain.
  pattern=r'rememberLauncherForActivityResult\(\s*contract = PickGalleryVisualMedia\(\)\s*\)'
  s=re.sub(pattern,'rememberDesktopProfileMediaPicker()',s).replace('uri: Uri?','uri: String?').replace('mutableStateOf<Uri?>','mutableStateOf<String?>')
  s=re.sub(r'photoPickerLauncher\.launch\(\s*PickVisualMediaRequest\(ActivityResultContracts\.PickVisualMedia\.ImageAndVideo\)\s*\)','photoPickerLauncher.launch()',s)
  return s
 for n in ['ProfileLoadingSkeleton','OfficialWallpaperSheet','WallpaperAdjustmentSheet']:
  p=BASE+'feature/profile/'+n+'.kt';body=ui(source[p])
  if n=='OfficialWallpaperSheet':body=body.replace('fun OfficialWallpaperSheet(','internal fun OfficialWallpaperSheet(')
  emit(p,body)
 p=BASE+'feature/profile/ProfileScreen.kt';s=ui(source[p])
 s=s.replace('fun ProfileScreen(','internal fun ProfileScreen(')
 s=s.replace('MobileProfileContent(\n                    captureBackground','MobileProfileContent(\n                    viewModel = viewModel,\n                    captureBackground')
 # Sole packages producer already emits the original repeat policy.
 raw=media.function(source[p],'resolveProfileSkinVideoRepeatMode',parser);s=s.replace(raw,'')
 start=s.index('    //  设置沉浸式状态栏和导航栏');end=s.index('\n    LaunchedEffect(viewModel, isCurrentPage',start)
 s=s[:start]+"""    // Same Windows Root chrome lease restores the prior window state on disposal.
    DisposableEffect(shouldControlSystemBars, lightStatusBars) {
        val lease = platform.acquireSystemBars(shouldControlSystemBars, lightStatusBars)
        onDispose { lease.close() }
    }
"""+s[end:]
 s=s.replace('@android.annotation.SuppressLint("UnsafeOptInUsageError")\n','')
 s=replace_fun(s,'ProfileSkinVideoBackground',"""private fun ProfileSkinVideoBackground(
    videoPath: String,
    playMode: String?,
    playbackEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    LocalDesktopProfileEnvironment.current.media.skinVideo(videoPath, playMode, playbackEnabled, modifier)
}""")
 start=s.index('                            val clipboard = context.getSystemService');end=s.index('\n                        }',start)
 s=s[:start]+"""                            platform.copyText("动态链接", dynamicUrl)
                            platform.feedback("已复制链接")"""+s[end:]
 emit(p,s)
 p=BASE+'core/ui/wallpaper/WallpaperMedia.kt';s=source[p]
 # Home's sole DesktopHomeWallpaperType already owns this exact original pure declaration.
 start=s.index('internal fun isVideoWallpaper(');end=s.index('/** Shared image/GIF/video renderer.',start);s=s[:start]+s[end:]
 s='\n'.join(l for l in s.splitlines() if not l.startswith('import android.') and not any(x in l for x in ['import androidx.media3.','import androidx.compose.ui.viewinterop.AndroidView','import androidx.compose.ui.platform.LocalContext','import coil3.asDrawable','import com.android.purebilibili.R']))+'\n'
 s=s.replace('package com.android.purebilibili.core.ui.wallpaper','package com.android.purebilibili.core.ui.wallpaper\nimport com.bilipai.desktop.ui.LocalDesktopProfileEnvironment')
 s=s.replace('val context = LocalContext.current','val environment = LocalDesktopProfileEnvironment.current')
 start=s.index('    val resolvedVideo by produceState');s=s[:start]+"""    // Original file-extension/MIME policy, using the actual Windows URI resolver.
    val resolvedVideo by produceState(video, uri, video) {
        value = video || environment.media.isVideoUri(uri)
    }
    // Required physical carrier owns the same existing Root media lease/image actor.
    environment.media.wallpaper(uri, imageModel, alignment, playing, resolvedVideo, modifier)
}
"""
 emit(p,s)
 p=BASE+'feature/profile/ProfileViewModel.kt';s=source[p]
 s='\n'.join(l for l in s.splitlines() if not l.startswith('import android.') and not l.startswith('import androidx.lifecycle.') and not any(x in l for x in ['import com.android.purebilibili.core.network.NetworkModule','import com.android.purebilibili.core.store.AccountSessionStore','import com.android.purebilibili.core.store.TokenManager','import com.android.purebilibili.core.store.SettingsManager','import com.android.purebilibili.data.repository.FavoriteRepository','import com.android.purebilibili.data.repository.BangumiRepository']))+'\n'
 s=s.replace('package com.android.purebilibili.feature.profile','package com.android.purebilibili.feature.profile\nimport com.bilipai.desktop.ui.DesktopProfileEnvironment\nimport com.bilipai.desktop.ui.DesktopOwnedProfileState\nimport kotlinx.coroutines.CoroutineScope')
 s=s.replace('class ProfileViewModel(application: Application) : AndroidViewModel(application) {',"""internal class ProfileViewModel(private val environment: DesktopProfileEnvironment) {
    private val viewModelScope get() = environment.scope
    private val platform get() = environment.platform
    private val preferences get() = environment.preferences
    private val accountsPort get() = environment.accounts
    private fun <T> ownedFlow(initial: T) = DesktopOwnedProfileState(initial, environment)
""")
 s=s.replace('MutableStateFlow','ownedFlow').replace('import kotlinx.coroutines.flow.ownedFlow\n','')
 s=s.replace('TokenManager.midCache','accountsPort.currentMid()').replace('TokenManager.csrfCache','environment.csrf()').replace('TokenManager.sessDataCache.isNullOrEmpty()','!accountsPort.hasSession()')
 s=s.replace('NetworkModule.api.','environment.api.').replace('NetworkModule.spaceApi.','environment.spaceApi.').replace('NetworkModule.dynamicApi.','environment.dynamicApi.').replace('NetworkModule.searchApi','environment.searchApi')
 s=s.replace('import com.android.purebilibili.core.network.getSpaceAggregate','import com.bilipai.desktop.ui.getDesktopProfileSpaceAggregate')
 s=s.replace('environment.spaceApi.getSpaceAggregate(mid)','environment.getDesktopProfileSpaceAggregate(mid)')
 s=s.replace('FavoriteRepository.','environment.favorite.').replace('BangumiRepository.','environment.bangumi.')
 s=s.replace('com.android.purebilibili.data.repository.SplashRepository.getOfficialWallpapers()','environment.splash.getOfficialWallpapers()')
 s=s.replace('        val context = getApplication<Application>()\n','')
 s=s.replace('                val context = getApplication<Application>()\n','')
 s=s.replace('AccountSessionStore.getAccounts(context)','accountsPort.getAccounts()').replace('AccountSessionStore.getActiveAccountMid(context)','accountsPort.getActiveAccountMid()').replace('AccountSessionStore.getPlaybackAccountMid(context)','accountsPort.getPlaybackAccountMid()').replace('AccountSessionStore.setPlaybackAccountMid(context, mid)','accountsPort.setPlaybackAccountMid(mid)')
 s=s.replace('TokenManager.saveMid(getApplication(), data.mid)','accountsPort.saveMid(data.mid)').replace('TokenManager.saveVipStatus(data.vip.status == 1)','accountsPort.saveVipStatus(data.vip.status == 1)').replace('AccountSessionStore.upsertCurrentAccount(getApplication(), data)','accountsPort.upsertCurrentAccount(data)').replace('AccountSessionStore.upsertCurrentAccount(getApplication())','accountsPort.upsertCurrentAccount(null)').replace('TokenManager.clear(getApplication())','accountsPort.clearCurrentSession()').replace('AccountSessionStore.clearActiveAccount(getApplication())','accountsPort.clearActiveAccount()').replace('AccountSessionStore.activateAccount(getApplication(), mid)','accountsPort.activateAccount(mid)').replace('AccountSessionStore.removeAccount(getApplication(), mid)','accountsPort.removeAccount(mid)')
 s=s.replace('SettingsManager.','preferences.').replace('(getApplication(),','(').replace('(getApplication())','()').replace('(application)','()')
 s=s.replace('(context,','(').replace('context = context,','')
 s=s.replace('preferences.resetProfileBgTransform(context)','preferences.resetProfileBgTransform()')
 s=s.replace('context.filesDir','platform.stateDirectory.toFile()')
 s=s.replace('uri: Uri','uri: String').replace('Uri.parse(customBgUri).path.orEmpty()','java.nio.file.Paths.get(java.net.URI.create(customBgUri)).toString()').replace('Uri.parse(uri)','uri').replace('Uri.fromFile(wallpaper).toString()','wallpaper.toPath().toUri().toString()').replace('Uri.fromFile(destFile).toString()','destFile.toPath().toUri().toString()')
 s=s.replace('importWallpaperMedia(context, uri,','platform.importWallpaperMedia(uri,').replace('importWallpaperImage(context, uri,','platform.importWallpaperImage(uri,')
 # After context-argument removal, these exact calls still preserve the original directory names.
 s=s.replace('importWallpaperMedia( uri,','platform.importWallpaperMedia(uri,').replace('importWallpaperImage( uri,','platform.importWallpaperImage(uri,')
 s=s.replace('NetworkModule.okHttpClient.newCall(request).execute()','platform.openOwnedDownload(request)')
 # Response closure is required on success, non-2xx, stream failure and cancellation.
 while 'val response = platform.openOwnedDownload(request)' in s:
  start=s.index('val response = platform.openOwnedDownload(request)');begin=s.index('if (response.isSuccessful)',start);tokens=parser.kotlin_tokens(s)
  i=next(i for i,t in enumerate(tokens) if t[1]>=begin)
  while tokens[i][0]!='{':i+=1
  depth=1
  while depth:i+=1;depth+=(tokens[i][0]=='{')-(tokens[i][0]=='}')
  assert tokens[i+1][0]=='else'
  i+=2;assert tokens[i][0]=='{';depth=1
  while depth:i+=1;depth+=(tokens[i][0]=='{')-(tokens[i][0]=='}')
  end=tokens[i][2];s=s[:end]+'\n                }'+s[end:]
  s=s[:start]+s[start:].replace('val response = platform.openOwnedDownload(request)','platform.openOwnedDownload(request).use { response ->',1)
 s=s.replace('response.body.byteStream().copyTo(output)','platform.copyOwned(response.body.byteStream(), output)').replace('response.body.bytes()','platform.readOwnedBytes(response.body)')
 s=s.replace('FileOutputStream(destFile).use { output ->\n                        platform.copyOwned(response.body.byteStream(), output)\n                    }','platform.writeOwnedFile(destFile, response.body)')
 s=s.replace('FileOutputStream(destFile).use { output ->\n                        output.write(bytes)\n                    }','platform.writeOwnedFile(destFile, bytes)')
 s=s.replace('File(platform.stateDirectory.toFile(), "images/profile_bg.jpg").delete()','platform.deleteOwnedFile(File(platform.stateDirectory.toFile(), "images/profile_bg.jpg"))')
 s=s.replace('android.widget.Toast.makeText(getApplication(), error.message ?: "壁纸导入失败", android.widget.Toast.LENGTH_LONG).show()','platform.feedback(error.message ?: "壁纸导入失败")')
 s=s.replace('android.widget.Toast.makeText( error.message ?: "壁纸导入失败", android.widget.Toast.LENGTH_LONG).show()','platform.feedback(error.message ?: "壁纸导入失败")')
 s=s.replace('com.android.purebilibili.core.util.AnalyticsHelper.','environment.analytics.')
 s=s.replace('e.printStackTrace()','// Root diagnostic actor controls raw exception disclosure.')
 s=s.replace('} catch (e: Exception) {','} catch (cancelled: CancellationException) { throw cancelled\n            } catch (e: Exception) {')
 s=s.replace('} catch (cancelled: CancellationException) {\n                throw cancelled\n            } catch (cancelled: CancellationException) { throw cancelled','} catch (cancelled: CancellationException) { throw cancelled')
 s=s.replace('viewModelScope.launch','environment.launchOwned')
 s=s.replace('return@launch','return@launchOwned')
 s=s.replace('onComplete()','environment.publishCallback { onComplete() }').replace('onSuccess()','environment.publishCallback { onSuccess() }')
 s=s.replace('onFailure(message)','environment.publishCallback { onFailure(message) }')
 s=re.sub(r'(?m)^([ \t]*)(onFailure|onResult)(\([^\n]*\))[ \t]*$',r'\1environment.publishCallback { \2\3 }',s)
 s=s.replace('} catch (e: CancellationException) {\n            throw e\n        } catch (cancelled: CancellationException) { throw cancelled','} catch (e: CancellationException) { throw e')
 s=s.replace('            val latest = _uiState.value as? ProfileUiState.Success ?: return@launchOwned\n            val response = result.getOrNull()',"""            (result.exceptionOrNull() as? CancellationException)?.let { throw it }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val latest = _uiState.value as? ProfileUiState.Success ?: return@launchOwned
            val response = result.getOrNull()""")
 s=s.replace('import kotlinx.coroutines.CancellationException','import kotlinx.coroutines.CancellationException\nimport kotlinx.coroutines.ensureActive')
 s=s.replace('platform.deleteOwnedFile(File(platform.stateDirectory.toFile(), "images/profile_bg.jpg"))\n            }','platform.deleteOwnedFile(File(platform.stateDirectory.toFile(), "images/profile_bg.jpg"))\n            }.onFailure { if (it is CancellationException) throw it }')
 s=s.replace('import java.io.FileOutputStream\n','')
 # Android MediaStore writes become the one Root gallery publication actor.
 s=replace_fun(s,'saveImageToGallery',"""private suspend fun saveImageToGallery(bytes: ByteArray, fileName: String) {
        platform.saveImageToGallery(bytes, fileName)
    }""")
 s=s.replace('saveImageToGallery(context, bytes,','saveImageToGallery(bytes,')
 emit(p,s)
 # The original extension's request semantics and sole existing buildSpaceAggregateParams stay intact.
 p=BASE+'core/network/ApiClient.kt';start=source[p].index('suspend fun SpaceApi.getSpaceAggregate(');end=source[p].index('internal fun buildSpaceAggregateParams(',start);body=source[p][start:end].strip()
 body=body.replace('suspend fun SpaceApi.getSpaceAggregate','internal suspend fun DesktopProfileEnvironment.getDesktopProfileSpaceAggregate').replace('return getSpaceAggregate(','val credentials = accounts.accessTokenCredentials()\n    return spaceApi.getSpaceAggregate(').replace('TokenManager.accessTokenCache','credentials.first').replace('TokenManager.accessTokenPlatformCache','credentials.second')
 emit(p,'package com.bilipai.desktop.ui\nimport com.android.purebilibili.core.network.buildSpaceAggregateParams\n\n'+body+'\n','com/bilipai/desktop/ui/DesktopOriginalProfileSpaceAggregate.kt')
 p=BASE+'data/repository/SplashRepository.kt';s=source[p].replace('import com.android.purebilibili.core.network.NetworkModule\n','').replace('object SplashRepository {','internal class DesktopOriginalProfileSplashProtocol(private val api: com.android.purebilibili.core.network.SplashApi) {').replace('    private val api = NetworkModule.splashApi\n','')
 s=s.replace('} catch (e: Exception) {','} catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled\n            } catch (e: Exception) {').replace('e.printStackTrace()','// Root diagnostics controls exception disclosure.')
 emit(p,s,'com/android/purebilibili/data/repository/DesktopOriginalProfileSplashProtocol.kt')
 write(out/'profile-selection-proof.json',json.dumps(records,ensure_ascii=False,indent=2))
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--repo',required=True);p.add_argument('--output',required=True);p.add_argument('--standalone',action='store_true');a=p.parse_args();generate(a.repo,a.output,a.standalone)
