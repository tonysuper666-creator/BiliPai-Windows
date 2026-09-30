"""Pin actual source ordering separately from fixture-executed product claims."""
from pathlib import Path
import hashlib,json,sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2]
def safe(p):
    value=str(Path(p).absolute());return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def read(p):return safe(p).read_text(encoding='utf-8')
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
new=HERE.parent/'dynamic-crash-main-product-snapshot/manifest.json';old=HERE.parent/'diagnostics-viewer-product-snapshot/manifest.json'
assert sha(new)=='c01330bf9345f8f5d1be2fa70bce6d7c1c980179448ee22f2ad0f467292a6345'
assert sha(old)=='5065fe8052e80c0c73f6342a4afc03fd62814fb0a144b6de46feeb5ddce1d3ae'
inputs={}
for path in [old,new]:
    manifest=json.loads(read(path))
    for entry in manifest['sourceFiles']+manifest['generatedProductFiles']:
        inputs[entry['path']]=dict(entry,identitySnapshotManifestSha256Bytes=sha(path))
for e in inputs.values():assert sha(REPO/e['path'])==e['sha256Bytes'],e['path']
main=read(REPO/'desktop/src/main/kotlin/com/bilipai/desktop/Main.kt')
shell=read(REPO/'desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt')
runtime=read(REPO/'desktop/src/main/kotlin/com/bilipai/desktop/plugins/DesktopPluginRuntime.kt')
lifecycle=read(REPO/'desktop/src/main/kotlin/com/bilipai/desktop/diagnostics/DesktopDiagnosticLifecycle.kt')
controller=read(REPO/'desktop/src/main/kotlin/com/bilipai/desktop/diagnostics/DesktopCrashPromptController.kt')
host=read(REPO/'desktop/src/main/kotlin/com/bilipai/desktop/diagnostics/DesktopCrashPromptHost.kt')
settings=read(REPO/'desktop/build/generated/diagnostics/sources/com/bilipai/desktop/diagnostics/DesktopDiagnosticSettings.kt')
checks=[]
def verify(label,value):assert value,label;checks.append(label)
def ordered(value,tokens):
    p=[value.index(t) for t in tokens];return p==sorted(p) and len(set(p))==len(p)
verify('Main same global store precedes actual diagnostics and Repository construction',ordered(main,[
    'val applicationPluginStore = remember','NetworkProxyStore.init','val diagnosticsResult = remember(applicationPluginStore)',
    'openDesktopDiagnostics(applicationPluginStore,','val repository = remember { DesktopRepository() }','val playerResult = remember']))
verify('Main failed Result retains null consumer and fixed safe visible error','val diagnostics = diagnosticsResult.getOrNull()' in main and
    'if (diagnosticsResult.isFailure)' in main and '诊断配置无法读取，请检查配置并重新启动。' in main)
verify('Main installs one identity-owned handler and restores previous identity',main.count('Thread.setDefaultUncaughtExceptionHandler(handler)')==1 and
    'Thread.getDefaultUncaughtExceptionHandler() === handler' in main and 'Thread.setDefaultUncaughtExceptionHandler(previous)' in main)
verify('Main retained callback covers failure before Ready with same lifecycle and global store',
    'AtomicReference<suspend () -> Unit>({ diagnosticLifecycle?.shutdownForRestore() })' in main and
    'applicationPluginStore = applicationPluginStore' in main and 'diagnosticLifecycle = diagnosticLifecycle' in main)
startup=shell[shell.index('internal fun DesktopApp('):shell.index('private fun DesktopReadyApp(')]
verify('Root startup uses actual DisposableEffect','DisposableEffect(startupGuard, diagnosticLifecycle)' in startup and 'SideEffect' not in startup)
verify('Root failure callback retires guard then same lifecycle then global backing',ordered(startup,[
    'registerShutdown?.invoke','closeDiscoveryStorage()','diagnosticLifecycle?.shutdownForRestore()','pluginStore.freezeWrites()']))
verify('Root error uses original AppSurface and diagnostics safe error','AppSurface(Modifier.fillMaxSize())' in startup and 'diagnosticStartupError?.let' in startup)
verify('Ready Runtime receives same retained lifecycle hook','beforeStoreFreeze = { diagnosticLifecycle?.shutdownForRestore() }' in shell)
verify('Root tracks exactly ordinary and audio native state observers on same lifecycle',shell.count('diagnosticLifecycle?.observePlayback(it.state, scope)')==2 and
    'DisposableEffect(diagnosticObservers)' in shell)
for label,begin,end in [('backup','val backup = remember','SideEffect {\n        registerShutdown?.invoke'),
                        ('normal','SideEffect {\n        registerShutdown?.invoke','var mediaActive')]:
    section=shell[shell.index(begin):shell.index(end,shell.index(begin)+len(begin))]
    verify(label+' stops playback/listen/casts before actor drain and privacy/runtime freeze',ordered(section,[
        'playback.close(); listen?.shutdownForRestore()','cast.quiesce()','diagnosticLifecycle?.shutdownForRestore()',
        'community.searchPreferences.freezeWritesForRestore()','pluginRuntime.shutdownForRestore()']))
retire=runtime[runtime.index('suspend fun shutdownForRestore():'):runtime.index('override fun close()')]
verify('Runtime single shutdown mutex hook precedes every own service/store freeze',ordered(retire,[
    'shutdownMutex.withLock','if (stopped)','closing.set(true)','beforeStoreFreeze()',
    'scope.coroutineContext[Job]?.cancelAndJoin()','store.freezeWrites()','stopped = true']))
verify('Runtime onDispose schedules same actual shutdown API','onDispose { pluginRuntime.close() }' in shell and
    'CoroutineScope(Dispatchers.Main).launch { shutdownForRestore() }' in runtime)
verify('Lifecycle rejects new observers and cancels jobs then retires prompt then joins jobs and drains actor',
    'check(!closing)' in lifecycle and ordered(lifecycle,['closing = true; observers.toList()',
    'jobs.forEach { it.cancel() }','crashPrompt.shutdownForRestore()','jobs.forEach { it.join() }','diagnostics.shutdownForRestore()','completed = true']))
verify('Enhanced consent remains same authoritative global settings namespace','store.snapshot("settings")' in settings and 'store.update("settings"' in settings and
    'store.preferences("settings")[KEY_ENHANCED_DIAGNOSTIC_LOGGING_ENABLED]' in settings)
verify('Lifecycle constructs exactly one controller while Main and Root do not duplicate it',lifecycle.count('DesktopCrashPromptController(diagnostics)')==1 and
    'DesktopCrashPromptController(' not in main and 'DesktopCrashPromptController(' not in shell)
promptMount=startup[startup.index('diagnosticLifecycle?.crashPrompt?.let'):]
verify('Root mounts original prompt from same lifecycle after storage boundary outside Ready gate',ordered(startup,[
    'DesktopDiscoveryStorageBoundary(','diagnosticLifecycle?.crashPrompt?.let']) and ordered(promptMount,[
    'DesktopAppearanceTheme(startupTheme)','DesktopCrashPromptHost(prompt)']) and
    'chooseDesktopDiagnosticExportFile(hostWindow)' in startup)
verify('Host reads same actor viewer and generation retirement guards late prompt results','controller.diagnostics' in host and
    'DesktopLocalDiagnosticViewer' in host and 'if(!owns(token))return@withLock' in controller and
    'operations.withLock{}' in controller and 'viewerRequested=false' in controller)
out=HERE/'source-seam-verification.json';assert not out.exists()
out.write_text(json.dumps(dict(passed=True,scope='source-contract-only-not-native-Main',checks=checks,
    sourceAndGeneratedIdentityCount=len(inputs),verifiedIdentities=list(inputs.values()),
    snapshotManifestSha256Bytes=sha(new),supplementaryUnchangedIdentitySnapshot=sha(old),nativeMainWindowExecuted=False),ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps(dict(passed=True,checks=len(checks),sourceAndGeneratedIdentityCount=len(inputs))))
