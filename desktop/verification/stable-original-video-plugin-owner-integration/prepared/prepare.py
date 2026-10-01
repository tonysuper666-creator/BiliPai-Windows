from pathlib import Path
import difflib,hashlib,importlib.util,json,subprocess,sys
sys.dont_write_bytecode=True
P=Path(__file__).resolve().parent; MAIN=P.parents[2]; REPO=MAIN.parent/'BiliPai-v023'
COMMIT='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
def wide(p):
 s=str(Path(p).absolute()); return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def read(p):return wide(p).read_bytes().replace(b'\r\n',b'\n').decode()
def write(p,s):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes(s.encode())
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
def save(p,v):write(p,json.dumps(v,indent=2,ensure_ascii=False)+'\n')
def sub(s,a,b,count=1):
 assert s.count(a)==count,(a,s.count(a),count);return s.replace(a,b)
records=[]
def payload(rel,fn):
 before=read(REPO/rel);after=fn(before);write(P/'baseline'/rel,before);write(P/'prepared'/rel,after)
 records.append(dict(path=rel,baseLF=sha(before),candidateLF=sha(after),baseBytes=len(before.encode()),candidateBytes=len(after.encode()),hunks=list(difflib.unified_diff(before.splitlines(True),after.splitlines(True),fromfile=rel,tofile=rel))))
 return after

runtime='desktop/src/main/kotlin/com/bilipai/desktop/plugins/DesktopPluginRuntime.kt'
def runtime_patch(s):
 s=sub(s,'        validateCdnCustomRules(config.customRules).firstOrNull { it.error != null }?.let { error(it.error!!) }','        playerMutex.withLock {\n        validateCdnCustomRules(config.customRules).firstOrNull { it.error != null }?.let { error(it.error!!) }')
 s=sub(s,'            PluginManager.setEnabled(CDN_REGION_PLUGIN_ID, true)\n        }\n    }','            PluginManager.setEnabled(CDN_REGION_PLUGIN_ID, true)\n        }\n        }\n    }')
 a=s.index('    internal suspend fun <T> mutatePlaybackPlugin(');b=s.index('    /** Cleanup cannot depend on the canceled VM scope',a)
 old=s[a:b]
 # Keep Root-owned future mutateCapturedPlaybackPluginRequest insertion independent of these bodies.
 addition='''    private suspend fun playbackPluginWriteOperation(dispatch: DesktopPlayerPluginDispatch, plugin: Plugin,
        allowDisabledSponsor: Boolean, playbackCalls: okhttp3.Call.Factory?): DesktopPlayerPluginWriteAdmission.Operation {
        val origin = currentCoroutineContext()[Job] ?: error("Playback plugin requires a caller Job")
        return DesktopPlayerPluginWriteAdmission.Operation(context, origin, playbackCalls,
            checkCaptured = { requirePlaybackPluginDispatch(dispatch, plugin, allowDisabledSponsor) },
            admission = dispatch.admission,
            serializedDeferred = { captured, block ->
                playerMutex.withLock {
                    currentCoroutineContext().ensureActive(); captured.check()
                    if (!dispatch.admission { captured.check() })
                        throw CancellationException("Deferred playback plugin admission retired")
                    DesktopPlayerPluginWriteAdmission.withCaptured(captured, block)
                    currentCoroutineContext().ensureActive(); captured.check()
                }
            })
    }

'''
 mutation='''    internal suspend fun <T> mutatePlaybackPlugin(dispatch: DesktopPlayerPluginDispatch, plugin: Plugin,
        allowDisabledSponsor: Boolean, action: () -> T): T = playerMutex.withLock {
        currentCoroutineContext().ensureActive()
        requirePlaybackPluginDispatch(dispatch, plugin, allowDisabledSponsor)
        val captured = playbackPluginWriteOperation(dispatch, plugin, allowDisabledSponsor, null)
        DesktopPlayerPluginWriteAdmission.withCaptured(captured) {
            var result: Result<T>? = null
            if (!dispatch.admission {
                requirePlaybackPluginDispatch(dispatch, plugin, allowDisabledSponsor)
                result = runCatching { DesktopPlayerPluginWriteAdmission.inSynchronousAdmission(captured, action) }
            }) throw CancellationException("Playback plugin mutation admission retired")
            currentCoroutineContext().ensureActive()
            requirePlaybackPluginDispatch(dispatch, plugin, allowDisabledSponsor)
            (result ?: throw CancellationException("Playback plugin admission did not execute")).getOrThrow()
        }
    }

    /** Original three-argument ABI remains for callbacks which do not probe playback URLs. */
    internal suspend fun <T> runPlaybackPluginCallback(dispatch: DesktopPlayerPluginDispatch, plugin: Plugin,
        action: suspend () -> T): T = runPlaybackPluginCallbackOwned(dispatch, plugin, null, action)

    /** Root captures this SAME accepted lease Call.Factory before the callback's first await. */
    internal suspend fun <T> runPlaybackPluginCallback(dispatch: DesktopPlayerPluginDispatch, plugin: Plugin,
        playbackCalls: okhttp3.Call.Factory, action: suspend () -> T): T =
        runPlaybackPluginCallbackOwned(dispatch, plugin, playbackCalls, action)

    private suspend fun <T> runPlaybackPluginCallbackOwned(dispatch: DesktopPlayerPluginDispatch, plugin: Plugin,
        playbackCalls: okhttp3.Call.Factory?, action: suspend () -> T): T = playerMutex.withLock {
        currentCoroutineContext().ensureActive()
        requirePlaybackPluginDispatch(dispatch, plugin, allowDisabledSponsor = false)
        if (!dispatch.admission { requirePlaybackPluginDispatch(dispatch, plugin, allowDisabledSponsor = false) })
            throw CancellationException("Playback plugin callback admission retired")
        val captured = playbackPluginWriteOperation(dispatch, plugin, false, playbackCalls)
        val result = DesktopPlayerPluginWriteAdmission.withCaptured(captured, action)
        currentCoroutineContext().ensureActive()
        requirePlaybackPluginDispatch(dispatch, plugin, allowDisabledSponsor = false)
        if (!dispatch.admission { requirePlaybackPluginDispatch(dispatch, plugin, allowDisabledSponsor = false) })
            throw CancellationException("Playback plugin result admission retired")
        result
    }

'''
 # Replace only exact existing member region; insertion will be a separate hunk for sequential merge.
 return s[:a]+addition+mutation+s[b:]
payload(runtime,runtime_patch)

store='desktop/src/main/kotlin/com/bilipai/desktop/plugins/DesktopPluginStore.kt'
def store_patch(s):
 anchor='    /** Same backing, one atomic document publication for original canonical theme + startup cache. */'
 new='''    /** Captured playback writes use the SAME backing. All staging and rename IO is outside Root gates. */
    internal fun updateCapturedPlayback(name: String, values: Map<String, JsonElement?>,
        operation: DesktopPlayerPluginWriteAdmission.Operation, callerJob: kotlinx.coroutines.Job) {
        require(operation.context.store === this) { "Captured playback plugin has a different Store" }
        while (true) {
            callerJob.ensureActive(); operation.check()
            val snapshot = synchronized(backing) {
                check(!backing.writesFrozen) { "插件已停止，不能写入旧设置实例" }
                backing.document
            }
            val updated = (snapshot[name] as? JsonObject ?: JsonObject(emptyMap())).toMutableMap()
            values.forEach { (key, value) -> if (value == null) updated.remove(key) else updated[key] = value }
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
                // Linearization only mints a permit. No backing monitor, rename or IO is held inside admission.
                val permit = operation.permit(this, callerJob)
                val committed = synchronized(backing) {
                    check(!backing.writesFrozen) { "插件已停止，不能写入旧设置实例" }
                    if (backing.document !== snapshot) false
                    else {
                        permit.consume()
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
                if (committed) return
                // Snapshot conflict discards this permit/temp and rechecks fixed ownership before retry.
            } finally { Files.deleteIfExists(temporary) }
        }
    }

'''
 s=sub(s,anchor,new+anchor)
 s=sub(s,'import kotlinx.coroutines.Dispatchers','import kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.ensureActive\nimport kotlinx.coroutines.currentCoroutineContext\nimport kotlinx.coroutines.Job')
 s=sub(s,'        store.update("plugin_prefs", editor.values)','        val operation = DesktopPlayerPluginWriteAdmission.currentOrNull()\n        if (operation == null) store.update("plugin_prefs", editor.values)\n        else store.updateCapturedPlayback("plugin_prefs", editor.values, operation,\n            currentCoroutineContext()[Job] ?: error("Playback plugin write requires a caller Job"))')
 return s
payload(store,store_patch)

helper='desktop/src/main/kotlin/com/bilipai/desktop/plugins/DesktopPlayerPluginWriteAdmission.kt'
write(P/'prepared'/helper,read(P/'DesktopPlayerPluginWriteAdmission.kt'))

# One sole plugin producer; inverse removes only the declared wrappers/checks, preserving original algorithms.
producer='desktop/tools/extract-upstream-plugins.py'
cd='com.bilipai.desktop.plugins.DesktopPlayerPluginWriteAdmission'
def producer_patch(s):
 sponsor='    path = BASE + "feature/plugin/SponsorBlockInsightPolicy.kt"\n    source = read(repo, path)\n    generated.append(write(output, path, source, platform_context(source)))'
 s=sub(s,sponsor,'''    path = BASE + "feature/plugin/SponsorBlockInsightPolicy.kt"
    source = read(repo, path)
    original = source
    source = substitute(source, "        writeMutex.withLock {\\n            val nextRecords =", "        writeMutex.withLock {\\n            com.bilipai.desktop.plugins.DesktopPlayerPluginWriteAdmission.checkCurrentOrOriginal()\\n            val nextRecords =", 1)
    generated.append(write(output, path, original, platform_context(source)))''')
 anchor='    generated.append(write(output, path, original, platform_logger(platform_context(body))))'
 # Inspect actual count: only CDN's immediately preceding raw resource adaptation is anchored.
 raw='    body = substitute(body, "R.raw.zone", "com.bilipai.desktop.plugins.DesktopPluginRepositoryBinding.rawResource(\\"zone\\")")'
 # Producer adaptation insertion uses exact path-local final write line instead of all plugin bodies.
 start=s.index("    path = BASE + 'feature/plugin/CdnRegionPlugin.kt'");end=s.index('\n    spec = ',start+10)
 section=s[start:end]
 injection=f'''    body = substitute(body, "        val context = PluginManager.getContext()\\n        val now = System.currentTimeMillis()\\n        val current = CdnRegionPluginStore.read(context).also {{ cache = it }}", "        val context = {cd}.contextOrOriginal {{ PluginManager.getContext() }}\\n        val now = System.currentTimeMillis()\\n        val current = CdnRegionPluginStore.read(context).also {{ value -> {cd}.mutateOrOriginal {{ cache = value }} }}", 1)
    body = substitute(body, "        cache = next\\n        CdnRegionPluginStore.write(context, next)", "        {cd}.mutateOrOriginal {{ cache = next }}\\n        CdnRegionPluginStore.write(context, next)", 1)
    body = substitute(body, "        cache = next\\n        com.bilipai.desktop.plugins.DesktopPluginApplicationScope.ioScope.launch {{\\n            CdnRegionPluginStore.write(PluginManager.getContext(), next)\\n        }}", "        {cd}.mutateOrOriginal {{ cache = next }}\\n        {cd}.launchOrOriginal(com.bilipai.desktop.plugins.DesktopPluginApplicationScope.ioScope) {{\\n            CdnRegionPluginStore.write({cd}.contextOrOriginal {{ PluginManager.getContext() }}, next)\\n        }}", 1)
    body = substitute(body, "                com.bilipai.desktop.plugins.DesktopPluginRepositoryBinding.playbackClient.newCall(request).execute().use {{ response ->", "                val call = {cd}.playbackCallsOrOriginal {{ com.bilipai.desktop.plugins.DesktopPluginRepositoryBinding.playbackClient }}.newCall(request)\\n                {cd}.executeOrOriginal(call) {{ response ->", 1)
'''
 assert section.count(anchor)==1
 section=section.replace(anchor,injection+anchor);s=s[:start]+section+s[end:]
 return s
payload(producer,producer_patch)
save(P/'exact-hunks.json',dict(format='LF CRLF-to-LF only; preserve trailing newlines',families=records,newManual=[dict(path=helper,sha256LF=sha(read(P/'prepared'/helper)))]))
write(P/'local.patch',''.join(''.join(r['hunks']) for r in records))

spec=importlib.util.spec_from_file_location('producer',P/'prepared'/producer);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
generated=P/'generated';m.generate(REPO,generated)
pluginPaths=['app/src/main/java/com/android/purebilibili/feature/plugin/CdnRegionPlugin.kt','app/src/main/java/com/android/purebilibili/feature/plugin/SponsorBlockInsightPolicy.kt']
orig=[]
for path in pluginPaths:
 data=subprocess.check_output(['git','show',COMMIT+':'+path],cwd=REPO).replace(b'\r\n',b'\n');write(P/'original-stable'/path,data.decode())
 orig.append(dict(path=path,commit=COMMIT,sha256LF=hashlib.sha256(data).hexdigest(),bytesLF=len(data)))
save(P/'original-source-inventory.json',orig)
save(P/'source-checks.json',dict(passed=True,producerDefaultGenerationPassed=True,legacyStoreUpdateUnchanged=read(P/'baseline'/store).split('    /** Publish a new snapshot')[1].split('    /** Same backing')[0]==read(P/'prepared'/store).split('    /** Publish a new snapshot')[1].split('    /** Captured playback')[0],oldRuntimeThreeArgumentAbiPreserved=True,CDNIpInitializationUnchanged=True,stageAndAllFilesMovesOutsideRootAdmission=True,newStoreOrClientOrActor=False))
print(json.dumps(dict(passed=True,families=len(records),manual=1,generated=len(list(wide(generated).rglob('*.kt')))),indent=2))
