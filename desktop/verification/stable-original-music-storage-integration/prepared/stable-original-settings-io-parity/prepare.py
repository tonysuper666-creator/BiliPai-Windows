from pathlib import Path
import hashlib, json, subprocess, sys
sys.stdout.reconfigure(encoding='utf-8'); sys.dont_write_bytecode = True
H = Path(__file__).resolve().parent; MAIN = H.parents[2]; REPO = MAIN.parent/'BiliPai-v023'
def sha(b): return hashlib.sha256(b).hexdigest()
def wide(p):
    s = str(Path(p).absolute()); prefix = chr(92)*2+'?'+chr(92)
    return Path(s if s.startswith(prefix) else prefix+s)
def read(p): return wide(p).read_bytes()
def write(p,b):
    wide(p).parent.mkdir(parents=True,exist_ok=True); wide(p).write_bytes(b)
def dump(p,v): write(p,(json.dumps(v,ensure_ascii=False,indent=2)+'\n').encode())
head = subprocess.check_output(['git','rev-parse','HEAD'],cwd=REPO,text=True).strip()
assert head.startswith('87462d91')
assert not subprocess.check_output(['git','status','--porcelain'],cwd=REPO,text=True).strip()
store = 'desktop/src/main/kotlin/com/bilipai/desktop/plugins/DesktopPluginStore.kt'
context = 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalPlayerSettingsContext.kt'
storeInsert = '''    /** Accepted global preference commit. Its consumption never reacquires Root gates. */
    internal class OriginalPreferenceWritePermit internal constructor(private val store: DesktopPluginStore) {
        private val consumed = java.util.concurrent.atomic.AtomicBoolean()
        internal fun consume(target: DesktopPluginStore) {
            require(store === target) { "Original preference permit belongs to a different Store" }
            check(consumed.compareAndSet(false, true)) { "Original preference write permit already consumed" }
        }
    }

    /** Original read/edit callbacks are pure and may be retried after a document CAS conflict.
     * The same global backing owns persistence. Snapshot reads, callback evaluation, staging,
     * fsync and replacement all run outside Root admission. Only permit minting enters Root.
     */
    internal fun <T> updateOriginalFromSnapshot(
        name: String,
        checkRequest: () -> Unit,
        acquirePermit: () -> OriginalPreferenceWritePermit,
        edit: (DesktopPreferenceSnapshot) -> Pair<T, Map<String, JsonElement?>>,
    ): T {
        while (true) {
            checkRequest()
            val snapshot = synchronized(backing) {
                check(!backing.writesFrozen) { "插件已停止，不能写入旧设置实例" }
                require(backing.document[name] == null || backing.document[name] is JsonObject) { "设置命名空间格式无效" }
                backing.document
            }
            val current = snapshot[name] as? JsonObject ?: JsonObject(emptyMap())
            val (result, edits) = edit(DesktopPreferenceSnapshot(current))
            checkRequest()
            val updated = current.toMutableMap()
            edits.forEach { (key, value) -> if (value == null) updated.remove(key) else updated[key] = value }
            if (name == "plugin_prefs") {
                updated["plugin_config_bilipai_feed_filter"]?.let { value ->
                    val encoded = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("推荐流过滤配置格式无效")
                    validateFeedFilter(json.decodeFromString<BiliPaiFeedFilterConfig>(encoded))
                }
            }
            val next = JsonObject(snapshot.toMutableMap().apply { put(name, JsonObject(updated)) })
            Files.createDirectories(backing.root)
            val temporary = Files.createTempFile(backing.root, "plugin-settings-", ".tmp")
            try {
                Files.writeString(temporary, json.encodeToString(next))
                FileChannel.open(temporary, StandardOpenOption.WRITE).use { it.force(true) }
                val permit = acquirePermit()
                val committed = synchronized(backing) {
                    check(!backing.writesFrozen) { "插件已停止，不能写入旧设置实例" }
                    if (backing.document !== snapshot) false
                    else {
                        permit.consume(this)
                        try { Files.move(temporary, backing.file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                        catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                            Files.move(temporary, backing.file, StandardCopyOption.REPLACE_EXISTING)
                        }
                        backing.document = next
                        backing.snapshots[name]?.value = DesktopPreferenceSnapshot(JsonObject(updated))
                        if (name == "plugin_prefs") {
                            backing.feedFilterConfig.value = decodeFeedFilter(next)
                            backing.feedFilterEnabled.value = updated["plugin_enabled_bilipai_feed_filter"]?.jsonPrimitive?.booleanOrNull ?: false
                        }
                        true
                    }
                }
                if (committed) return result
                // Conflict retires this unconsumed permit and owned temporary file; retry checks ownership afresh.
            } finally { Files.deleteIfExists(temporary) }
        }
    }

'''
contextPermit = '''    internal fun preferenceWritePermit(checkRequest: () -> Unit): com.bilipai.desktop.plugins.DesktopPluginStore.OriginalPreferenceWritePermit {
        lateinit var permit: com.bilipai.desktop.plugins.DesktopPluginStore.OriginalPreferenceWritePermit
        commit {
            checkRequest()
            permit = com.bilipai.desktop.plugins.DesktopPluginStore.OriginalPreferenceWritePermit(pluginContext.store)
        }
        return permit
    }
'''
oldEdit = '''        context.commit {
            caller.ensureActive()
            context.pluginContext.store.updateFromSnapshot("settings") { snapshot ->
                result = DesktopOriginalPlayerPreferenceValues(snapshot).apply(block)
                caller.ensureActive()
                result.changes
            }
        }
        result'''
newEdit = '''        fun checkRequest() {
            caller.ensureActive()
            context.requireCurrent()
            com.bilipai.desktop.plugins.DesktopSubscriptionWriteAdmission.checkCurrentRequestOrOriginal()
        }
        context.pluginContext.store.updateOriginalFromSnapshot("settings", ::checkRequest,
            { context.preferenceWritePermit(::checkRequest) }) { snapshot ->
            result = DesktopOriginalPlayerPreferenceValues(snapshot).apply(block)
            result to result.changes.toMap()
        }'''
oldApply = '''        fun apply() { context.commit { context.pluginContext.store.update(name, changes) } }'''
newApply = '''        fun apply() {
            val edits = changes.toMap()
            fun checkRequest() {
                context.requireCurrent()
                com.bilipai.desktop.plugins.DesktopSubscriptionWriteAdmission.checkCurrentRequestOrOriginal()
            }
            context.pluginContext.store.updateOriginalFromSnapshot(name, ::checkRequest,
                { context.preferenceWritePermit(::checkRequest) }) { Unit to edits }
        }'''
edits = {
    store: [('    /** Captured playback writes use the SAME backing. All staging and rename IO is outside Root gates. */', storeInsert+'    /** Captured playback writes use the SAME backing. All staging and rename IO is outside Root gates. */')],
    context: [('    internal fun commit(block: () -> Unit) {',contextPermit+'    internal fun commit(block: () -> Unit) {'),(oldEdit,newEdit),(oldApply,newApply)]
}
packets = []
for path, changes in edits.items():
    raw = read(REPO/path); base = raw.replace(b'\r\n',b'\n'); after = base; positions=[]; rows=[]
    for beforeText,afterText in changes:
        before = beforeText.encode(); desired=afterText.encode(); assert after.count(before)==1,path
        at=after.index(before); positions.append((at,before,desired)); after=after[:at]+desired+after[at+len(before):]
        rows.append(dict(before=beforeText,after=afterText))
    inverse=after
    for at,before,desired in reversed(positions):
        assert inverse[at:at+len(desired)]==desired; inverse=inverse[:at]+before+inverse[at+len(desired):]
    assert inverse==base
    write(H/'baseline'/path,raw); write(H/'prepared'/path,after)
    packets.append(dict(target=path,baseSHA256LF=sha(base),desiredSHA256LF=sha(after),edits=rows,indexedInverse=True))
dump(H/'exact-hunks.json',dict(baseCommit=head,families=packets,exactHunks=sum(len(v['edits']) for v in packets)))
dump(H/'scope.json',dict(actualBase=75,baseCommit=head,newStore=False,newActor=False,newDependencies=0,newOriginalIdentities=0,
    namespacesPreserved=True,diskAndBackingOutsideRootAdmission=True,acceptedGlobalCommitMayFinishAfterEntryRetires=True,
    suspendDataStoreCallerCancelledBeforePermitRejected=True,staleEntryBeforePermitRejected=True,
    CASConflictRechecksOwnership=True,freezeWinsFinalReplacement=True,
    synchronousMirrorApplyRemainsSynchronous=True,standaloneMirrorCallerCancellationClaimed=False,
    wholeRootAccepted=False,desktopExeReplaced=False))
print(json.dumps(dict(families=len(packets),hunks=sum(len(v['edits']) for v in packets),base=head)))
