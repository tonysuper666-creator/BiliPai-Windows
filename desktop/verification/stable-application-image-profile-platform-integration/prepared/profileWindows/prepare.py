from pathlib import Path
import hashlib,json,re,subprocess,difflib
LANE=Path(__file__).resolve().parent;MAIN=LANE.parents[2];REPO=MAIN.parent/'BiliPai-v023';COMMIT='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
def read(p):return p.read_text(encoding='utf-8').replace('\r\n','\n')
def digest(s):return hashlib.sha256(s.encode()).hexdigest()
def write(p,s):p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s,encoding='utf-8',newline='\n')
def js(p,v):write(p,json.dumps(v,ensure_ascii=False,indent=2))
path='app/src/main/java/com/android/purebilibili/feature/profile/WallpaperImageImport.kt'
original=subprocess.run(['git','show',COMMIT+':'+path],cwd=REPO,capture_output=True,check=True).stdout.decode().replace('\r\n','\n');assert original==read(REPO/path)
write(LANE/'original-stable'/path,original)
source=original
source=re.sub(r'(?m)^import android\.[^\n]+\n','',source)
source=source.replace('import java.io.File','import com.bilipai.desktop.ui.DesktopProfileWallpaperImportContext\nimport java.io.File')
source=source.replace('context: Context','context: DesktopProfileWallpaperImportContext').replace('source: Uri','source: String')
source=source.replace('val file = copyWallpaperImage(','val copied = copyWallpaperImage(\n                context = context,')
source=source.replace('            imported = file\n            ensureActive()','            val file = context.publishImport(copied)\n            imported = file\n            context.ensureCurrent()\n            ensureActive()',1)
source=source.replace('context.contentResolver.openInputStream(source)','context.openSource(source)')
before='''                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(candidate.absolutePath, options)
                    options.outWidth > 0 && options.outHeight > 0'''
assert before in source;source=source.replace(before,'                    context.validateImage(candidate)')
source=source.replace('internal fun copyWallpaperImage(\n','internal fun copyWallpaperImage(\n    context: DesktopProfileWallpaperImportContext,\n')
source=source.replace('!destinationDirectory.mkdirs()','!context.prepareDirectory(destinationDirectory)')
source=source.replace('File.createTempFile("wallpaper_", ".img", destinationDirectory)','context.createImportFile(destinationDirectory, ".img")')
source=source.replace('destination.outputStream()','context.openImportOutput(destination)')
source=source.replace('context.contentResolver.getType(source)','context.mimeType(source)')
before='''            val extension = android.webkit.MimeTypeMap.getSingleton()
                .getExtensionFromMimeType(context.mimeType(source).orEmpty())'''
after='''            val extension = when(context.mimeType(source)) {
                "video/mp4" -> "mp4"; "video/x-m4v" -> "m4v"; "video/webm" -> "webm"
                "video/x-matroska" -> "mkv"; "video/quicktime" -> "mov"; "video/3gpp" -> "3gp"; else -> null
            }'''
assert before in source;source=source.replace(before,after).replace('source.lastPathSegment','context.lastPathSegment(source)')
source=source.replace('File.createTempFile("wallpaper_", ".$extension", destinationDirectory)','context.createImportFile(destinationDirectory, ".$extension")')
source=source.replace('file.outputStream()','context.openImportOutput(file)')
start=source.index('            val metadata = android.media.MediaMetadataRetriever()');end=source.index('            ensureActive()',start)
source=source[:start]+'''            val width = context.videoWidth(file)
            if (width <= 0) throw IOException("视频损坏或格式不受支持")
            val published = context.publishImport(file)
            imported = published
            context.ensureCurrent()
'''+source[end:]
source=source.replace('            ensureActive()\n            file\n        }\n    } catch (error: Throwable) {\n        imported?.delete()', '            ensureActive()\n            published\n        }\n    } catch (error: Throwable) {\n        imported?.delete()',1)
source=source.replace('imported?.delete()','imported?.let(context::deleteImport)').replace('destination.delete()','context.deleteImport(destination)')
write(LANE/'prepared/generated/com/android/purebilibili/feature/profile/WallpaperImageImport.kt',source)
changes=[];a=original.splitlines(True);b=source.splitlines(True)
for tag,i1,i2,j1,j2 in difflib.SequenceMatcher(None,a,b,autojunk=False).get_opcodes():
 if tag!='equal':changes.append({'originalRange':[i1,i2],'preparedRange':[j1,j2],'before':''.join(a[i1:i2]),'after':''.join(b[j1:j2])})
recovered=b[:]
for row in reversed(changes):recovered[row['preparedRange'][0]:row['preparedRange'][1]]=row['before'].splitlines(True)
assert ''.join(recovered)==original
js(LANE/'original-import-reverse.json',{'path':path,'commit':COMMIT,'originalSha256LF':digest(original),'preparedSha256LF':digest(source),'changes':changes})

allhunks=[];seenTargets=set()
def patch(target,before,after,reason):
 basepath=LANE/'shared-bases'/target;candidatepath=LANE/'prepared/shared-candidates'/target
 if target not in seenTargets:
  if not basepath.exists():write(basepath,read(REPO/target))
  assert read(basepath)==read(REPO/target),'Shared baseline changed: '+target
  write(candidatepath,read(basepath));seenTargets.add(target)
 text=read(candidatepath);assert text.count(before)==1,(target,before[:80]);text=text.replace(before,after);write(candidatepath,text)
 allhunks.append({'target':target,'before':before,'after':after,'beforeSha256LF':digest(before),'afterSha256LF':digest(after),'reason':reason})
store='desktop/src/main/kotlin/com/bilipai/desktop/plugins/DesktopPluginStore.kt'
fragment='''    /** Same backing, one atomic document publication for original canonical theme + startup cache. */
    internal fun updateThemeFromSnapshot(checkPublication: () -> Unit, edit: (JsonObject, JsonObject) -> Map<String, Map<String, JsonElement?>>) = synchronized(backing) {
        check(!backing.writesFrozen) { "插件已停止，不能写入旧设置实例" }
        requireObjectNamespace("settings"); requireObjectNamespace("theme_cache")
        val updates = edit(preferences("settings"), preferences("theme_cache"))
        require(updates.keys.all { it in setOf("settings", "theme_cache") })
        val document = backing.document.toMutableMap()
        for ((name, edits) in updates) {
            val values = preferences(name).toMutableMap()
            edits.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
            document[name] = JsonObject(values)
        }
        val next = JsonObject(document)
        Files.createDirectories(backing.root)
        val temporary = Files.createTempFile(backing.root, "plugin-settings-", ".tmp")
        try {
            Files.writeString(temporary, json.encodeToString(next))
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { it.force(true) }
            DesktopSubscriptionWriteAdmission.checkCurrentRequestOrOriginal()
            checkPublication()
            try { Files.move(temporary, backing.file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary, backing.file, StandardCopyOption.REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temporary) }
        backing.document = next
        for (name in updates.keys) backing.snapshots[name]?.value = DesktopPreferenceSnapshot(preferences(name))
    }

'''
patch(store,'    /** All facades for this old file generation become read-only before restoration. */',fragment+'    /** All facades for this old file generation become read-only before restoration. */','one existing backing atomic original theme/cache transaction')
theme='desktop/src/main/kotlin/com/bilipai/desktop/appearance/DesktopThemePrefs.kt'
start=read(REPO/theme).index('    suspend fun setThemeMode(');end=read(REPO/theme).index('    suspend fun setAppLanguage(',start)
before=read(REPO/theme)[start:end]
after='''    suspend fun setThemeMode(mode: AppThemeMode) = withContext(Dispatchers.IO) {
        val caller = kotlinx.coroutines.currentCoroutineContext()
        caller.ensureActive()
        writeOriginalThemeMode(mode) { caller.ensureActive() }
    }

    suspend fun setThemeModeOwned(mode: AppThemeMode, owns: () -> Boolean,
        withOwnedAdmission: ((() -> Unit) -> Boolean)) = withContext(Dispatchers.IO) {
        val caller = kotlinx.coroutines.currentCoroutineContext()
        caller.ensureActive()
        if (!owns()) throw kotlinx.coroutines.CancellationException("Profile theme entry retired")
        if (!withOwnedAdmission { caller.ensureActive();writeOriginalThemeMode(mode) { caller.ensureActive();if(!owns())throw kotlinx.coroutines.CancellationException("Profile theme entry retired") } })
            throw kotlinx.coroutines.CancellationException("Profile theme entry retired")
    }

    private fun writeOriginalThemeMode(mode: AppThemeMode, checkPublication: () -> Unit) {
        store.updateThemeFromSnapshot(checkPublication) { current, _ ->
            val darkStyle = resolveDarkThemeStylePreference(current.int("dark_theme_style_v1"), current.int("theme_mode_v2"))
            val canonical = linkedMapOf<String, JsonElement?>("theme_mode_v2" to JsonPrimitive(mode.value))
            if (current.int("dark_theme_style_v1") == null) canonical["dark_theme_style_v1"] = JsonPrimitive(darkStyle.value)
            mapOf("settings" to canonical, "theme_cache" to mapOf("theme_mode" to JsonPrimitive(mode.value), "dark_theme_style" to JsonPrimitive(darkStyle.value)))
        }
    }

    suspend fun setDarkThemeStyle(style: DarkThemeStyle) = withContext(Dispatchers.IO) {
        val caller = kotlinx.coroutines.currentCoroutineContext()
        caller.ensureActive()
        store.updateThemeFromSnapshot({caller.ensureActive()}) { _, _ -> mapOf("settings" to mapOf("dark_theme_style_v1" to JsonPrimitive(style.value)),
            "theme_cache" to mapOf("dark_theme_style" to JsonPrimitive(style.value))) }
    }
'''
patch(theme,before,after,'complete original setThemeMode/setDarkThemeStyle migration, startup cache and existing global live flow')
patch(theme,'import kotlinx.coroutines.withContext','import kotlinx.coroutines.withContext\nimport kotlinx.coroutines.ensureActive','real caller cancellation')

assets='desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDynamicImageAssets.kt'
gallery='''    /** Original Profile gallery writes its supplied bytes with JPEG display name. Reuse this sole save actor. */
    suspend fun saveProfileGalleryBytes(bytes: ByteArray, fileName: String, profileOwns: () -> Boolean,
        profileCommit: ((() -> Unit) -> Boolean)): Boolean = saveMutex.withLock {
        suspend fun profileCheckpoint() {checkpoint();if(!profileOwns())throw CancellationException("Profile gallery entry retired")}
        profileCheckpoint();require(bytes.size.toLong() in 1..MAX_IMAGE_BYTES)
        val profileCaller=currentCoroutineContext()
        fun commitProfileOwned(block: () -> Unit): Boolean = commitOwned {
            if(!profileCommit {profileCaller.ensureActive();if(!profileOwns())throw CancellationException("Profile gallery entry retired");block()})
                throw CancellationException("Profile gallery entry retired")
        }
        require(fileName == Path.of(fileName).fileName.toString() && fileName.isNotBlank())
        suspend fun write(target: DesktopDynamicSaveTarget): Boolean = withContext(Dispatchers.IO) {
            profileCheckpoint();val parent=validateTarget(target)
            val stage=Files.createTempFile(parent,".bilipai-profile-gallery-",".tmp")
            try {
                Files.newOutputStream(stage).use { output ->
                    var offset=0
                    while(offset<bytes.size) {profileCheckpoint();val count=minOf(64*1024,bytes.size-offset);output.write(bytes,offset,count);offset+=count}
                }
                if(!commitProfileOwned {validateTarget(target);Files.move(stage,target.path.toAbsolutePath().normalize())})
                    throw CancellationException("Profile gallery owner retired")
                true
            } finally {Files.deleteIfExists(stage)}
        }
        if(imageSaveLocations==null)write(selectTarget(fileName,"image/jpeg") ?: return@withLock false)
        else imageSaveLocations.save(fileName,::profileCheckpoint,::commitProfileOwned,::write)
    }

'''
patch(assets,'    suspend fun saveLivePhotoVideo(rawUrl: String): Boolean = saveMutex.withLock {',gallery+'    suspend fun saveLivePhotoVideo(rawUrl: String): Boolean = saveMutex.withLock {','sole Assets/Locations actor consumes original Profile raw gallery bytes')
profileProducer='desktop/tools/extract-upstream-profile-main.py'
anchor=" s=s.replace('saveImageToGallery(context, bytes,','saveImageToGallery(bytes,')\n emit(p,s)"
patch(profileProducer,anchor," s=s.replace('saveImageToGallery(context, bytes,','saveImageToGallery(bytes,')\n s=s.replace('File(imagesDir, \"profile_bg.jpg\")','File(imagesDir, \"profile_bg_${java.util.UUID.randomUUID()}.jpg\")')\n emit(p,s)",'explicit Windows no-clobber physical path; original savedUri/prefs/delete legacy behavior preserved')
js(LANE/'shared-local-hunks.json',{'rows':allhunks,'wholeCandidateInstall':False,'targets':[{'path':str(p.relative_to(LANE/'shared-bases')).replace('\\','/'),'baseSha256LF':digest(read(p)),'candidateSha256LF':digest(read(LANE/'prepared/shared-candidates'/p.relative_to(LANE/'shared-bases')))} for p in (LANE/'shared-bases').rglob('*') if p.is_file()]})
js(LANE/'pins.json',{'commit':COMMIT,'originalImportPath':path,'originalImportSha256LF':digest(original),'originalSettingsManagerSha256LF':digest(read(REPO/'app/src/main/java/com/android/purebilibili/core/store/SettingsManager.kt'))})
print('Prepared full original import + four shared target deltas')
