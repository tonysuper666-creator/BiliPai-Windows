from pathlib import Path
import hashlib, json, difflib

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[2]
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def stage(path, transform):
    source=ROOT/path
    old=source.read_text(encoding='utf-8')
    new=transform(old)
    assert new != old, path
    target=HERE/'prepared'/path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(new,encoding='utf-8',newline='\n')
    patch=''.join(difflib.unified_diff(old.splitlines(True),new.splitlines(True),fromfile=path,tofile=path))
    (HERE/(source.name+'.patch')).write_text(patch,encoding='utf-8',newline='\n')
    return dict(path=path,baseSha256Bytes=sha(source),prepared=target.relative_to(HERE).as_posix(),sha256Bytes=sha(target))
def replace_once(s,a,b):
    assert s.count(a)==1,a
    return s.replace(a,b)
def archive(s):
    s=replace_once(s,'    fun restore(bytes: ByteArray): Int {','    @JvmOverloads\n    fun restore(bytes: ByteArray, onSuccessfulRestore: () -> Unit = {}): Int {')
    return replace_once(s,'        return replaced.size','        // Signal only after every replacement succeeded, before a suspend caller can lose its return to cancellation.\n        onSuccessfulRestore()\n        return replaced.size')
def coordinator(s):
    s=replace_once(s,'import kotlinx.coroutines.Dispatchers','import kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.NonCancellable\nimport kotlinx.coroutines.currentCoroutineContext\nimport kotlinx.coroutines.ensureActive')
    s=replace_once(s,'''    private val archive = DesktopBackupArchive(store.directory) {
        mutableState.update { it.copy(restartRequired = true) }
        runBlocking { beforeRestore() }
    }''','    private val archive = DesktopBackupArchive(store.directory)')
    s=replace_once(s,'''    suspend fun restoreLatest() = operation("设置已恢复，请退出并重新打开客户端") {
        val config = store.read().config; validateServer(config)
        service.restoreLatest(config).getOrThrow()
        mutableState.update { it.copy(restartRequired = true) }
        afterRestore()
    }''','''    suspend fun restoreLatest() = restoreOperation { restoreArchive, onCommitted ->
        val config = store.read().config; validateServer(config)
        WebDavBackupService(restoreArchive, onCommitted).restoreLatest(config).getOrThrow()
    }''')
    s=replace_once(s,'''    suspend fun importLocal(source: Path) = operation("设置已恢复，请退出并重新打开客户端") {
        require(Files.size(source) <= 32L * 1024 * 1024) { "备份压缩包过大" }
        archive.restore(Files.readAllBytes(source)); mutableState.update { it.copy(restartRequired = true) }
        afterRestore()
    }''','''    suspend fun importLocal(source: Path) = restoreOperation { restoreArchive, onCommitted ->
        require(Files.size(source) <= 32L * 1024 * 1024) { "备份压缩包过大" }
        restoreArchive.restore(Files.readAllBytes(source), onCommitted)
    }

    private suspend fun restoreOperation(
        block: suspend (DesktopBackupArchive, () -> Unit) -> Unit
    ): Result<Unit> = operation("设置已恢复，请退出并重新打开客户端") {
        var committed = false
        val operationContext = currentCoroutineContext()
        operationContext.ensureActive()
        val restoreArchive = DesktopBackupArchive(store.directory) {
            // A cancelled download/validation must not retire the app or replace settings.
            operationContext.ensureActive()
            mutableState.update { it.copy(restartRequired = true) }
            runBlocking { beforeRestore() }
        }
        try {
            block(restoreArchive) { committed = true }
        } finally {
            // beforeRestore may dispose the initiating UI scope. Once files committed, exit must still run.
            if (committed) withContext(NonCancellable) { afterRestore() }
        }
    }''')
    return s
def extractor(s):
    s=replace_once(s,'"class WebDavBackupService(private val archive: com.bilipai.desktop.backup.DesktopBackupArchive) {",',
      '"class WebDavBackupService @JvmOverloads constructor(private val archive: com.bilipai.desktop.backup.DesktopBackupArchive, private val onSuccessfulRestore: () -> Unit = {}) {",')
    return replace_once(s,'"    private fun restoreFromBackupArchive(bytes: ByteArray): Int = archive.restore(bytes)",',
      '"    private fun restoreFromBackupArchive(bytes: ByteArray): Int = archive.restore(bytes, onSuccessfulRestore)",')
rows=[stage('desktop/src/main/kotlin/com/bilipai/desktop/backup/DesktopBackupArchive.kt',archive),
      stage('desktop/src/main/kotlin/com/bilipai/desktop/backup/DesktopBackupCoordinator.kt',coordinator),
      stage('desktop/tools/extract-upstream-settings.py',extractor)]
(HERE/'owned-base-files.json').write_text(json.dumps(rows,indent=2)+'\n',encoding='utf-8')
