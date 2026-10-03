"""Whole original picker UI, using selected EXISTING production Profile method adaptations.
The normal Home task depends on extractOriginalProfileMain; that output is SHA-pinned.
No second overview Profile VM or settings/account/network/media authority is emitted.
"""
from pathlib import Path
import difflib, hashlib, importlib.util, re, textwrap
from v025_source_paths import canonical_source

SCREEN='app/src/main/java/com/android/purebilibili/feature/profile/SplashWallpaperPickerSheet.kt'
VM='app/src/main/java/com/android/purebilibili/feature/profile/ProfileViewModel.kt'
SCREEN_SHA='5f12de49fb65f3aea34800fba8ae3512a549835c48b63281be85a766ec89eb1b'
VM_SHA='b8f658eef9e4ac99479479b51b14a9d38d17f6bc7a669694e0b6ee4f02d9e411'
GENERATED_VM='desktop/build/generated/profile-main/com/android/purebilibili/feature/profile/ProfileViewModel.kt'
GENERATED_VM_SHA='8c3385478613c9651f760dd4ac2a35583f2791abf3220ccde9ab27fabc26ef9d'
def wide(p):return Path('\\\\?\\'+str(p.absolute()))
def read(p):return wide(p).read_bytes().decode().replace('\r\n','\n')
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
def load(name,path):
 spec=importlib.util.spec_from_file_location(name,wide(path));m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m

def generate(repo,pins):
 original=read(canonical_source(repo,SCREEN));originalVm=read(canonical_source(repo,VM))
 assert sha(original)==SCREEN_SHA and sha(originalVm)==VM_SHA,'Original wallpaper picker/source drift'
 production=read(repo/GENERATED_VM)
 assert sha(production)==GENERATED_VM_SHA,'Existing whole original Profile producer output drift; regenerate through the normal Profile task'
 for path,text in [(SCREEN,original),(VM,originalVm)]:
  pins[path]={'sha256Raw':sha(text),'sha256LF':sha(text),'physicalRawSha256':hashlib.sha256(wide(canonical_source(repo,path)).read_bytes()).hexdigest()}
 media=load('home_picker_media',repo/'desktop/tools/extract-upstream-media.py');parser=media.parser_for(repo)
 rows=[];adapt=original
 def replace(before,after,label):
  nonlocal adapt
  count=adapt.count(before);assert count>0,(label,before)
  rows.append({'before':before,'after':after,'count':count,'label':label});adapt=adapt.replace(before,after)
 for line in original.splitlines(True):
  if line.startswith('import android.') or line.startswith('import androidx.activity.') or line.startswith('import androidx.lifecycle.viewmodel.compose.') or line.startswith('import com.android.purebilibili.core.store.SettingsManager') or line.startswith('import com.android.purebilibili.core.util.PickGalleryVisualMedia') or line.startswith('import com.android.purebilibili.core.util.Logger') or line.startswith('import androidx.compose.ui.platform.LocalContext'):
   replace(line,'','Android-only import platform leaf')
 replace('fun SplashWallpaperPickerSheet(','internal fun DesktopOriginalSplashWallpaperPickerContent(','Required owned wrapper retains original whole content function')
 replace('    viewModel: ProfileViewModel = viewModel(),','    viewModel: DesktopOriginalHomeWallpaperViewModel,\n    bindings: com.bilipai.desktop.ui.DesktopOriginalHomeWallpaperPickerBindings,','Selected original wallpaper methods and modal file leases')
 replace('    val context = LocalContext.current','    val environment = com.bilipai.desktop.ui.LocalDesktopProfileEnvironment.current\n    val context = coil3.compose.LocalPlatformContext.current','Actual existing Coil and owned Root environment')
 replace('    val importScope = rememberCoroutineScope()','    val importScope = bindings.scope','Actual modal child Job, cancelled on dismissal')
 replace('onDispose { previewFile?.delete() }','onDispose { previewFile?.let(bindings::releasePreview) }','Exclusive imported preview lease cleanup, including after retirement')
 before='''    val customWallpaperPickerLauncher = rememberLauncherForActivityResult(
        contract = PickGalleryVisualMedia()
    ) { uri ->'''
 replace(before,'    val customWallpaperPickerLauncher = com.bilipai.desktop.ui.rememberDesktopProfileMediaPicker { uri ->','Existing actual native FileDialog owner/result actor')
 before='''                    val file = if (target == WallpaperPickerTarget.HOME) importWallpaperMedia(
                        context, uri, File(context.cacheDir, "wallpaper_imports"),
                    ) else importWallpaperImage(
                        context = context,
                        source = uri,
                        destinationDirectory = File(context.cacheDir, "wallpaper_imports"),
                    )'''
 replace(before,'                    val file = bindings.importPreview(uri, imageOnly = target != WallpaperPickerTarget.HOME)','Existing original import algorithm with actual private Root directory/lease')
 replace('''                    importedWallpaper = file
                    selectedUrl = Uri.fromFile(file).toString()
                    saveToGallery = false
                    showSplashAdjustmentSheet = target == WallpaperPickerTarget.SPLASH''','''                    environment.publishCallback {
                        importedWallpaper = file
                        selectedUrl = file.toPath().toUri().toString()
                        saveToGallery = false
                        showSplashAdjustmentSheet = target == WallpaperPickerTarget.SPLASH
                    }''','Late imported result publishes only to exact actual modal owner')
 replace('Logger.w("SplashWallpaperPicker", "Unable to import selected wallpaper", error)','environment.platform.reportWallpaperPreviewFailure(error)','Existing diagnostics actor; no raw account/file disclosure')
 toastPattern=r'Toast\.makeText\(context, ("[^"]+"), Toast\.LENGTH_(?:LONG|SHORT)\)\.show\(\)'
 for text in sorted(set(m.group() for m in re.finditer(toastPattern,adapt))):
  m=re.fullmatch(toastPattern,text)
  replace(text, 'bindings.notice('+m.group(1)+')','Existing original notice through current page owner; survives successful modal close')
 replace('                    isImportingWallpaper = false','                    if (bindings.context.isCurrentForOriginalWrite()) environment.publishCallback { isImportingWallpaper = false }','Retired modal completion cannot mutate successor UI')
 replace('''            customWallpaperPickerLauncher.launch(
                PickVisualMediaRequest(if (target == WallpaperPickerTarget.HOME) ActivityResultContracts.PickVisualMedia.ImageAndVideo else ActivityResultContracts.PickVisualMedia.ImageOnly)
            )''','            customWallpaperPickerLauncher.launch()','Existing Windows native media picker preserves HOME image/video selection policy')
 replace('SettingsManager.setSplashRandomPoolUris(context, randomPool)','viewModel.setSplashRandomPoolUris(randomPool)','Original random-pool side effect, same canonical/mirror final publisher')
 replace('ImageRequest.Builder(LocalContext.current)','ImageRequest.Builder(coil3.compose.LocalPlatformContext.current)','Actual Coil platform context')
 inverse=adapt
 for row in reversed(rows):
  assert inverse.count(row['after'])==row['count'] if row['after'] else True
  # Empty import removals use exact line reconstruction from difflib, below.
 opcodes=list(difflib.SequenceMatcher(a=original.splitlines(True),b=adapt.splitlines(True),autojunk=False).get_opcodes())
 rebuilt=adapt.splitlines(True)
 inverseRows=[]
 for tag,a,b,c,d in reversed(opcodes):
  if tag!='equal':
   inverseRows.append({'originalRange':[a,b],'preparedRange':[c,d],'before':''.join(original.splitlines(True)[a:b]),'after':''.join(adapt.splitlines(True)[c:d])})
   rebuilt[c:d]=original.splitlines(True)[a:b]
 assert ''.join(rebuilt)==original,'Whole picker source inverse failed'

 methods=['loadOfficialWallpapers','setAsSplashWallpaper','setCustomSplashWallpaper','setAsHomeWallpaper','setCustomHomeWallpaper','saveImageToGallery']
 adaptedMethods={n:media.function(production,n,parser) for n in methods}
 originalMethods={n:media.function(originalVm,n,parser) for n in methods}
 fields=[]
 for line in production.splitlines():
  if re.match(r'\s*(?:private )?val (?:_officialWallpapers(?:Loading|Error)?|officialWallpapers(?:Loading|Error)?|_splashSaveState|splashSaveState)\b',line):fields.append(line)
 assert len(fields)==8,len(fields)
 getter=re.search(r'(?m)^\s*fun getSplashAlignment\([^\n]+',production).group().strip()
 vmBody='''package com.android.purebilibili.feature.profile
import com.bilipai.desktop.ui.*
import com.android.purebilibili.core.store.DesktopOriginalHomeSettingsManager
import kotlinx.coroutines.*
import java.io.File
/** Only original wallpaper transient state/actions, copied from the existing normal whole Profile producer.
 * No overview/account refresh init, independent client/store/player or replacement algorithm. */
internal class DesktopOriginalHomeWallpaperViewModel(
    private val environment: DesktopProfileEnvironment,
    private val context: DesktopOriginalPlayerSettingsContext,
) {
    private val preferences get() = environment.preferences
    private val platform get() = environment.platform
    private fun <T> ownedFlow(initial: T) = DesktopOwnedProfileState(initial, environment)
'''+ '\n'.join(fields)+'\n\n    '+getter+'\n\n'+textwrap.indent('\n\n'.join(adaptedMethods.values()),'    ')+'''
    suspend fun setSplashRandomPoolUris(pool: List<String>) {
        DesktopOriginalPlaybackPreferenceOperation.run(context, context::isCurrentForOriginalWrite) {
            DesktopOriginalHomeSettingsManager.setSplashRandomPoolUris(context, pool)
        }
    }
}
'''
 wrapper='''package com.android.purebilibili.feature.profile
import androidx.compose.runtime.*
import com.bilipai.desktop.ui.*
import java.awt.EventQueue
/** Actual modal child owner. This binding is currently mounted only for the original HOME target. */
@Composable
internal fun SplashWallpaperPickerSheet(target: WallpaperPickerTarget, onDismiss: () -> Unit) {
    require(target == WallpaperPickerTarget.HOME) { "Current Root mounts the HOME wallpaper target" }
    val bindings = rememberDesktopOriginalHomeWallpaperPickerBindings()
    val parent = LocalDesktopOriginalPlayerSettingsContext.current
    val dismissNow by rememberUpdatedState(onDismiss)
    val dismiss: () -> Unit = {
        // Original completion can be called under bounded source publication. Cancel/filesystem
        // cleanup and modal teardown run after that gate returns on the actual EDT.
        EventQueue.invokeLater {
            bindings.close()
            if (parent.isCurrentForOriginalWrite()) dismissNow()
        }
    }
    DisposableEffect(bindings) { onDispose { bindings.dispose() } }
    CompositionLocalProvider(LocalDesktopProfileEnvironment provides bindings.environment) {
        DesktopOriginalSplashWallpaperPickerContent(bindings.viewModel, bindings, target, dismiss)
    }
}
'''
 proof={'wholeOriginalPickerFileInverseExact':True,'originalLfSha256':sha(original),'adaptedLfSha256':sha(adapt),'lineHunksForInverse':list(reversed(inverseRows)),
  'requiredExistingProfileProducerOutput':GENERATED_VM,'existingProfileProducerOutputSha256LF':GENERATED_VM_SHA,
  'selectedExistingWholeProfileAdaptedMethods':{n:sha(s) for n,s in adaptedMethods.items()},
  'selectedFixedOriginalProfileMethods':{n:sha(s) for n,s in originalMethods.items()},
  'originalToExistingProductionMethodAdaptations':{n:list(difflib.unified_diff(originalMethods[n].splitlines(),adaptedMethods[n].splitlines(),fromfile='fixed79',tofile='existing-normal-profile-production',lineterm='')) for n in methods},
  'newOverviewProfileViewModel':False,'splashTargetMounted':False,'actualModalUiVerified':False,'actualFileCleanupVerified':False}
 return {
  'com/android/purebilibili/feature/profile/DesktopOriginalSplashWallpaperPickerContent.kt':adapt,
  'com/android/purebilibili/feature/profile/DesktopOriginalHomeWallpaperViewModel.kt':vmBody,
  'com/android/purebilibili/feature/profile/DesktopOriginalHomeWallpaperPickerHost.kt':wrapper,
 },proof
