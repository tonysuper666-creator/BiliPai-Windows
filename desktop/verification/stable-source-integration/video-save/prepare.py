from pathlib import Path
import difflib, hashlib, json, subprocess

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
BASE = 'bd094f91a637fbbc179a6d266a696101946c67f6'
ORIGINAL = 'fcf84853b287662e8a9129ea0d38576c36522a34'
assert subprocess.check_output(['git', '-c', 'core.longpaths=true', 'rev-parse', 'HEAD'], cwd=REPO, text=True).strip() == BASE
paths = [
 'desktop/src/main/kotlin/com/bilipai/desktop/platform/DesktopWindowsImageSaveDirectory.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopImageSaveLocations.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDynamicImageAssets.kt',
]
def sha(data): return hashlib.sha256(data).hexdigest()
def write(path, value):
 path.parent.mkdir(parents=True, exist_ok=True)
 path.write_text(value, encoding='utf-8', newline='\n')
def replace(value, old, new):
 assert value.count(old) == 1, old
 return value.replace(old, new)
rows = []; patches = []
for path in paths:
 old = (REPO/path).read_text(encoding='utf-8'); new = old
 if path.endswith('DesktopWindowsImageSaveDirectory.kt'):
  new = replace(new, 'Resolves the current user\'s redirected Pictures/BiliPai;', 'Resolves the current user\'s redirected Pictures/BiliPai or Videos/BiliPai;')
  new = replace(new, '''    internal fun resolveDefault(
        windows: Boolean,
        native: () -> DesktopWindowsKnownFolderNative,
    ): Result<Path> {
''', '''    fun resolveDefaultVideo(): Result<Path> = resolveDefaultVideo(
        windows = Platform.isWindows(),
        native = { JnaWindowsKnownFolderNative },
    )

    internal fun resolveDefault(
        windows: Boolean,
        native: () -> DesktopWindowsKnownFolderNative,
    ): Result<Path> = resolveKnownFolder(windows, native, video = false)

    internal fun resolveDefaultVideo(
        windows: Boolean,
        native: () -> DesktopWindowsKnownFolderNative,
    ): Result<Path> = resolveKnownFolder(windows, native, video = true)

    private fun resolveKnownFolder(
        windows: Boolean,
        native: () -> DesktopWindowsKnownFolderNative,
        video: Boolean,
    ): Result<Path> {
''')
  new = replace(new, '''                    // FOLDERID_Pictures, not FOLDERID_PicturesLibrary.
                    Memory(16).use { folderId ->
                        folderId.setInt(0, 0x33e28130)
                        folderId.setShort(4, 0x4e1e.toShort())
                        folderId.setShort(6, 0x4676.toShort())
                        folderId.write(8, byteArrayOf(
                            0x83.toByte(), 0x5a, 0x98.toByte(), 0x39,
                            0x5c, 0x3b, 0xc3.toByte(), 0xbb.toByte(),
                        ), 0, 8)
''', '''                    // Actual user folders, not the Pictures/Videos libraries.
                    Memory(16).use { folderId ->
                        if (video) {
                            // FOLDERID_Videos maps the original Movies/BiliPai album.
                            folderId.setInt(0, 0x18989b1d)
                            folderId.setShort(4, 0x99b5.toShort())
                            folderId.setShort(6, 0x455b.toShort())
                            folderId.write(8, byteArrayOf(
                                0x84.toByte(), 0x1c, 0xab.toByte(), 0x7c,
                                0x74, 0xe4.toByte(), 0xdd.toByte(), 0xfc.toByte(),
                            ), 0, 8)
                        } else {
                            // Preserve the existing FOLDERID_Pictures mapping.
                            folderId.setInt(0, 0x33e28130)
                            folderId.setShort(4, 0x4e1e.toShort())
                            folderId.setShort(6, 0x4676.toShort())
                            folderId.write(8, byteArrayOf(
                                0x83.toByte(), 0x5a, 0x98.toByte(), 0x39,
                                0x5c, 0x3b, 0xc3.toByte(), 0xbb.toByte(),
                            ), 0, 8)
                        }
''')
  new = replace(new, '''                        val pictures = try { Path.of(value) } catch (_: Exception) {
''', '''                        val base = try { Path.of(value) } catch (_: Exception) {
''')
  new = replace(new, '''                        if (!pictures.isAbsolute) throw DesktopKnownFolderFailure("relative-path")
                        Result.success(pictures.resolve("BiliPai"))
''', '''                        if (!base.isAbsolute) throw DesktopKnownFolderFailure("relative-path")
                        Result.success(base.resolve("BiliPai"))
''')
 elif path.endswith('DesktopImageSaveLocations.kt'):
  new = replace(new, '''    private val resolveDefaultDirectory: () -> Result<Path> = DesktopWindowsImageSaveDirectory::resolveDefault,
''', '''    private val resolveDefaultVideoDirectory: () -> Result<Path> = DesktopWindowsImageSaveDirectory::resolveDefaultVideo,
    private val resolveDefaultDirectory: () -> Result<Path> = DesktopWindowsImageSaveDirectory::resolveDefault,
''')
  new = replace(new, '''    ): Boolean = withContext(Dispatchers.IO) {
        require(Path.of(fileName).fileName.toString() == fileName && fileName.isNotBlank())
''', '''    ): Boolean = saveResolved(fileName, checkpoint, withOwnedCommit,
        useCustomImageDirectory = true, resolveDefaultDirectory, write)

    /** Standalone original live video always uses Movies/BiliPai, independently
     * of the image tree preference. Windows maps that album to Videos/BiliPai.
     */
    suspend fun saveDefaultVideo(
        fileName: String,
        checkpoint: suspend () -> Unit,
        withOwnedCommit: ((() -> Unit) -> Boolean),
        write: suspend (DesktopDynamicSaveTarget) -> Boolean,
    ): Boolean = saveResolved(fileName, checkpoint, withOwnedCommit,
        useCustomImageDirectory = false, resolveDefaultVideoDirectory, write)

    private suspend fun saveResolved(
        fileName: String,
        checkpoint: suspend () -> Unit,
        withOwnedCommit: ((() -> Unit) -> Boolean),
        useCustomImageDirectory: Boolean,
        resolveDirectory: () -> Result<Path>,
        write: suspend (DesktopDynamicSaveTarget) -> Boolean,
    ): Boolean = withContext(Dispatchers.IO) {
        require(Path.of(fileName).fileName.toString() == fileName && fileName.isNotBlank())
''')
  new = replace(new, 'val saved = preferences.getImageSaveTreeUriSync()', 'val saved = if (useCustomImageDirectory) preferences.getImageSaveTreeUriSync() else null')
  new = replace(new, 'attempt(resolveDefaultDirectory().getOrThrow(), true)', 'attempt(resolveDirectory().getOrThrow(), true)')
 elif path.endswith('DesktopDynamicImageAssets.kt'):
  new = replace(new, '''        val target = selectTarget("BiliPai_Live_${System.currentTimeMillis()}.mp4", "video/mp4") ?: return@withLock false
        checkpoint(); writeRaw(url, target, MAX_VIDEO_BYTES)
''', '''        val name = "BiliPai_Live_${System.currentTimeMillis()}.mp4"
        if (imageSaveLocations == null) {
            val target = selectTarget(name, "video/mp4") ?: return@withLock false
            checkpoint(); writeRaw(url, target, MAX_VIDEO_BYTES)
        } else imageSaveLocations.saveDefaultVideo(name, ::checkpoint, ::commitOwned) {
            writeRaw(url, it, MAX_VIDEO_BYTES)
        }
''')
 write(HERE/'original'/path, old); write(HERE/'prepared'/path, new)
 patches += list(difflib.unified_diff(old.splitlines(True), new.splitlines(True), fromfile='a/'+path, tofile='b/'+path))
 rows.append({'path':path, 'baseLfSha256':sha(old.encode()), 'candidateLfSha256':sha(new.encode()), 'candidate':str(HERE/'prepared'/path)})
write(HERE/'candidate.patch', ''.join(patches))
original_path = 'app/src/main/java/com/android/purebilibili/feature/dynamic/components/ImagePreviewDialog.kt'
raw = subprocess.check_output(['git','-c','core.longpaths=true','show',f'{ORIGINAL}:{original_path}'],cwd=REPO).decode('utf-8')
write(HERE/'original'/'ImagePreviewDialog.kt', raw)
lines=raw.splitlines()
write(HERE/'original'/'ImagePreviewDialog-2564-2625.txt','\n'.join(f'{i+1}: {lines[i]}' for i in range(2563,2625))+'\n')
stable_commit = subprocess.check_output(['git','-c','core.longpaths=true','rev-parse','refs/tags/v0.2.3'],cwd=REPO,text=True).strip()
stable_raw = subprocess.check_output(['git','-c','core.longpaths=true','show',f'{stable_commit}:{original_path}'],cwd=REPO).decode('utf-8')
start_alpha=raw.index('suspend fun saveLivePhotoVideoToGallery(')
start_stable=stable_raw.index('suspend fun saveLivePhotoVideoToGallery(')
alpha_block=raw[start_alpha:raw.index('\n@Composable',start_alpha)]
stable_block=stable_raw[start_stable:stable_raw.index('\n@Composable',start_stable)]
write(HERE/'original'/'v0.2.3-saveLivePhotoVideoToGallery.kt',stable_block)
write(HERE/'stable-scope-comparison.json',json.dumps({'alpha9Commit':ORIGINAL,'stableCommit':stable_commit,
 'alpha9StartLine':raw[:start_alpha].count('\n')+1,'stableStartLine':stable_raw[:start_stable].count('\n')+1,
 'alpha9HelperLfSha256':sha(alpha_block.encode()),'stableHelperLfSha256':sha(stable_block.encode()),
 'standaloneHelperByteEqual':alpha_block==stable_block,'scope':'Only standalone saveLivePhotoVideoToGallery; not all stable functions',
 'helperReadsImageTreePreference': 'getImageSaveTreeUri' in stable_block,
 'relativePath':'Movies/BiliPai','mimeType':'video/mp4','displayName':'BiliPai_Live_<timestamp>.mp4'},indent=2)+'\n')
sdk=Path('C:/Program Files (x86)/Windows Kits/10/Include/10.0.26100.0/um/KnownFolders.h')
sdk_raw=sdk.read_bytes(); sdk_lines=sdk_raw.decode('utf-8-sig').splitlines()
selected=[{'line':i+1,'text':line} for i,line in enumerate(sdk_lines) if 'FOLDERID_Videos,' in line or '18989B1D' in line]
assert len(selected)==2
write(HERE/'windows-sdk-videos-evidence.json',json.dumps({'path':str(sdk),'sha256Bytes':sha(sdk_raw),'lines':selected},indent=2)+'\n')
write(HERE/'source-inventory.json',json.dumps({'baseCommit':BASE,'originalCommit':ORIGINAL,'candidates':rows,
 'originalImagePreviewSha256Bytes':sha(raw.encode()),'mainModified':False,'newDependencies':False,
 'behaviors':['default standalone video never reads image tree preference','same Root app lifetime gate',
 'existing SessionStore owner before Assets lock before app lifetime before Files',
 'existing anonymous 64KiB streamed download / 200MiB cap / callback-drain cleanup',
 'original MP4 display name plus only collision suffix','null-location explicit chooser seam retained']},indent=2)+'\n')
print(json.dumps({'preparedFiles':len(rows),'mainModified':False,'sdkVideosLines':selected,'candidates':rows},indent=2))
