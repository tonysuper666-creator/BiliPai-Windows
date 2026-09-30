"""Read-only source contracts; these do not claim native Main Window execution."""
from pathlib import Path
import hashlib,json,sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent
REPO=HERE.parents[2]
def safe(p):
    value=str(Path(p).absolute());return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def read(p):return safe(p).read_text(encoding='utf-8')
snapshotPath=HERE.parent/'diagnostics-main-product-snapshot/manifest.json'
expected='5e8fc360372557bf004b55a1ded9fe4be1a3b6bae33090cb037d8fe600006fda'
assert sha(snapshotPath)==expected,'Root snapshot manifest changed'
snapshot=json.loads(read(snapshotPath))
verified=[]
for entry in snapshot['sourceFiles']+snapshot['generatedProductFiles']:
    assert sha(REPO/entry['path'])==entry['sha256Bytes'],('Pinned product source changed',entry['path'])
    verified.append(entry)
main=read(REPO/'desktop/src/main/kotlin/com/bilipai/desktop/Main.kt')
shell=read(REPO/'desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt')
runtime=read(REPO/'desktop/src/main/kotlin/com/bilipai/desktop/plugins/DesktopPluginRuntime.kt')
lifecycle=read(REPO/'desktop/src/main/kotlin/com/bilipai/desktop/diagnostics/DesktopDiagnosticLifecycle.kt')
settings=read(REPO/'desktop/build/generated/diagnostics/sources/com/bilipai/desktop/diagnostics/DesktopDiagnosticSettings.kt')
checks=[]
def verify(name,value):
    assert value,name
    checks.append(name)
def ordered(value,tokens):
    positions=[value.index(token) for token in tokens]
    return positions==sorted(positions) and len(set(positions))==len(positions)
verify('Main retains same global before proxy/diagnostics/Repository/native construction',ordered(main,[
    'val applicationPluginStore = remember','NetworkProxyStore.init','val diagnosticsResult = remember(applicationPluginStore)',
    'openDesktopDiagnostics(applicationPluginStore,','val repository = remember { DesktopRepository() }','val playerResult = remember']))
verify('Main preserves failed Result and fixed startup error rather than fallback disabled consumer',
    'val diagnostics = diagnosticsResult.getOrNull()' in main and 'if (diagnosticsResult.isFailure)' in main and
    '诊断配置无法读取，请检查配置并重新启动。' in main)
verify('Main installs/restores one identity-owned uncaught handler',main.count('Thread.setDefaultUncaughtExceptionHandler(handler)')==1 and
    'Thread.getDefaultUncaughtExceptionHandler() === handler' in main and 'Thread.setDefaultUncaughtExceptionHandler(previous)' in main)
verify('Main initial callback covers pre-Ready lifecycle and supplies the same Root store/consumer',
    'AtomicReference<suspend () -> Unit>({ diagnosticLifecycle?.shutdownForRestore() })' in main and
    'applicationPluginStore = applicationPluginStore' in main and 'diagnosticLifecycle = diagnosticLifecycle' in main)
startup=shell[shell.index('internal fun DesktopApp('):shell.index('private fun DesktopReadyApp(')]
verify('Root retains startup DisposableEffect rather than per-recomposition SideEffect',
    'DisposableEffect(startupGuard, diagnosticLifecycle)' in startup and 'SideEffect' not in startup)
verify('Root failed-constructor callback closes guard then diagnostics then global freeze',ordered(startup,[
    'registerShutdown?.invoke','closeDiscoveryStorage()','diagnosticLifecycle?.shutdownForRestore()','pluginStore.freezeWrites()']))
verify('Root actual error theme contains original AppSurface and safe diagnostics error',
    'AppSurface(Modifier.fillMaxSize())' in startup and 'diagnosticStartupError?.let' in startup)
verify('Ready Runtime receives same lifecycle beforeStoreFreeze hook',
    'beforeStoreFreeze = { diagnosticLifecycle?.shutdownForRestore() }' in shell)
verify('Root registers both native player/audio state on same lifecycle',
    'diagnosticLifecycle?.observePlayback(it.state, scope)' in shell and
    shell.count('diagnosticLifecycle?.observePlayback(it.state, scope)')==2 and
    'DisposableEffect(diagnosticObservers)' in shell)
for label,begin,end in [('backup','val backup = remember','SideEffect {\n        registerShutdown?.invoke'),
                        ('normal','SideEffect {\n        registerShutdown?.invoke','var mediaActive')]:
    section=shell[shell.index(begin):shell.index(end,shell.index(begin)+len(begin))]
    verify(f'{label} path stops owned playback/listen and casts before diagnostic drain/search freeze/Runtime freeze',ordered(section,[
        'playback.close(); listen?.shutdownForRestore()','cast.quiesce()','diagnosticLifecycle?.shutdownForRestore()',
        'community.searchPreferences.freezeWritesForRestore()','pluginRuntime.shutdownForRestore()']))
retire=runtime[runtime.index('suspend fun shutdownForRestore():'):runtime.index('override fun close()')]
verify('Runtime actual single mutex hook precedes every own service/store freeze',ordered(retire,[
    'shutdownMutex.withLock','if (stopped)','closing.set(true)','beforeStoreFreeze()',
    'scope.coroutineContext[Job]?.cancelAndJoin()','store.freezeWrites()','stopped = true']))
verify('Runtime onDispose close schedules the same actual shutdown API',
    'onDispose { pluginRuntime.close() }' in shell and 'CoroutineScope(Dispatchers.Main).launch { shutdownForRestore() }' in runtime)
verify('Lifecycle rejects late registration and cancel/joins observers before actor close',
    'check(!closing)' in lifecycle and ordered(lifecycle,[
        'closing = true; observers.toList()','jobs.forEach { it.cancel() }','jobs.forEach { it.join() }',
        'diagnostics.shutdownForRestore()','completed = true']))
verify('Actual actor consent reads/writes same authoritative settings key',
    'store.snapshot("settings")' in settings and 'store.update("settings"' in settings and
    'store.preferences("settings")[KEY_ENHANCED_DIAGNOSTIC_LOGGING_ENABLED]' in settings)
result={'passed':True,'scope':'pinned-source-contract-only','nativeMainWindowExecuted':False,
        'snapshotManifestSha256Bytes':expected,'sourceAndGeneratedIdentityCount':len(verified),
        'verifiedSourceIdentities':verified,'checks':checks}
destination=HERE/'source-seam-verification.json'
if destination.exists():raise ValueError('Frozen verification is never overwritten')
destination.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'passed':True,'checks':len(checks),'sourceIdentities':len(verified)}))
