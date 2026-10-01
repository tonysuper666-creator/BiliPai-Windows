from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023';LANE=MAIN/'desktop/.local/stable-application-image-loader-parity';OUT=HERE/'application-image-loader-install44';assert not OUT.exists()
sha=lambda b:hashlib.sha256(b).hexdigest()
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
raw=(LANE/'frozen-handoff.json').read_bytes();assert sha(raw)=='0740783057cdccbcc5b5d65ee09b74ec9c8e017a9e9fb6b940888d78dcaf293d';frozen=json.loads(raw);assert len(frozen['artifacts'])==46
for r in frozen['artifacts']:assert sha(wide(LANE/r['path']).read_bytes())==r['sha256Bytes'],r['path']
contract=json.loads((LANE/'install-contract.json').read_bytes());pending=[];receipt=[]
for r in contract['payloads']:
 data=wide(LANE/r['source']).read_bytes();assert sha(data)==r['sha256Bytes'];target=REPO/r['target'];assert not target.exists();pending.append((target,data));receipt.append(r)
reg=REPO/'desktop/upstream-sources.json';registry=json.loads(reg.read_bytes());assert len(registry['sources'])==927
for r in contract['newOriginalRows']:
 assert all(old['path']!=r['path']for old in registry['sources']);assert sha((REPO/r['path']).read_bytes().replace(b'\r\n',b'\n'))==r['sha256'];registry['sources'].append(r)
assert len(registry['sources'])==930;pending.append((reg,(json.dumps(registry,ensure_ascii=False,indent=2)+'\n').encode()))
gradle=REPO/'desktop/build.gradle.kts';text=gradle.read_text(encoding='utf-8');marker='val extractUpstreamHomeFullCard by tasks.registering(Exec::class) {';assert text.count(marker)==1
pins={r['target'].removeprefix('desktop/'):r['sha256Bytes']for r in contract['payloads']if r['target'].startswith(('desktop/third-party/','desktop/resources/'))}
pinRows=',\n'.join('            "'+k+'" to "'+v+'"'for k,v in pins.items())
snippet='''// Full original application Coil configuration and original background image budgets.
val extractOriginalApplicationImageLoader by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-application-image-loader.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-application-image-loader").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-application-image-loader.py", "tools/sync-upstream.py")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "original-application-image-loader" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-application-image-loader"))
}
val verifyCoilCacheControlSources by tasks.registering {
    val pins = mapOf(
'''+pinRows+'''
    )
    inputs.files(pins.keys)
    doLast {
        pins.forEach { (name, expected) ->
            val actual = java.security.MessageDigest.getInstance("SHA-256")
                .digest(File(projectDir, name).readBytes()).joinToString("") { "%02x".format(it) }
            check(actual == expected) { "Original Coil cache-control source/license changed: $name" }
        }
        logger.lifecycle("Verified three unchanged Coil3.5.0 cache-control sources and two Apache notices.")
    }
}
kotlin.sourceSets.named("main") {
    kotlin.srcDir(layout.buildDirectory.dir("generated/original-application-image-loader"))
    kotlin.srcDir("third-party/coil-cache-control/upstream/commonMain")
}
tasks.named("compileKotlin") { dependsOn(extractOriginalApplicationImageLoader, verifyCoilCacheControlSources) }
tasks.named("processResources") { dependsOn(verifyCoilCacheControlSources) }

'''
assert 'val extractOriginalApplicationImageLoader 'not in text;text=text.replace(marker,snippet+marker,1)
marker='    implementation("androidx.savedstate:savedstate-compose:1.4.0")';assert text.count(marker)==1
text=text.replace(marker,marker+'\n    // Existing runtime97 datetime module used by unchanged Coil CacheControl source.\n    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")',1);pending.append((gradle,text.encode()))
hunks=[]
def edit(relative,before,after):
 item=next((i for i,(p,b)in enumerate(pending)if p==REPO/relative),None)
 old=pending[item][1]if item is not None else(REPO/relative).read_bytes();text=old.decode().replace('\r\n','\n');assert text.count(before)==1,(relative,before)
 data=text.replace(before,after,1).encode()
 if item is None:pending.append((REPO/relative,data))
 else:pending[item]=(REPO/relative,data)
 hunks.append(dict(target=relative,before=before,after=after))
main='desktop/src/main/kotlin/com/bilipai/desktop/Main.kt';shell='desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt'
edit(main,'        val closing = remember { java.util.concurrent.atomic.AtomicBoolean() }','''        val closing = remember { java.util.concurrent.atomic.AtomicBoolean() }
        // Configure the original singleton before any Window renderer asks Coil for an image.
        val applicationImages = remember(repository, closing) {
            com.bilipai.desktop.ui.DesktopApplicationImageLoader(repository,
                DesktopLibrary.directoryForAccount(null).resolve("cache"), rootAlive = { !closing.get() })
        }
        DisposableEffect(applicationImages) {
            onDispose {
                applicationImages.stopAccepting()
                applicationScope.launch(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { applicationImages.close() }
            }
        }''')
edit(main,'                    shutdown.get().invoke()','''                    shutdown.get().invoke()
                    withContext(Dispatchers.IO) { applicationImages.close() }''')
edit(main,'            DesktopApp(repository, playerResult.getOrNull(), playerResult.exceptionOrNull()?.message, initialVideo,','''            androidx.compose.runtime.CompositionLocalProvider(
                com.bilipai.desktop.ui.LocalDesktopApplicationImageLoader provides applicationImages,
            ) {
            DesktopApp(repository, playerResult.getOrNull(), playerResult.exceptionOrNull()?.message, initialVideo,''')
edit(main,'                danmakuPresentation = danmakuPresentation)\n            restartFailure?.let','''                danmakuPresentation = danmakuPresentation)
            }
            restartFailure?.let''')
edit(shell,'    val pluginStore = remember(applicationPluginStore) {','''    val applicationImages = LocalDesktopApplicationImageLoader.current
    val pluginStore = remember(applicationPluginStore) {''')
edit(shell,'    val diagnostics = diagnosticLifecycle?.diagnostics','''    val applicationImages = LocalDesktopApplicationImageLoader.current
    val diagnostics = diagnosticLifecycle?.diagnostics''')
edit(shell,'                diagnosticLifecycle?.shutdownForRestore()\n                withContext(Dispatchers.IO) { pluginStore.freezeWrites() }','''                withContext(Dispatchers.IO) { applicationImages.close() }
                diagnosticLifecycle?.shutdownForRestore()
                withContext(Dispatchers.IO) { pluginStore.freezeWrites() }''')
edit(shell,'                homeRootRef.getAndSet(null)?.closeAndJoin()\n                favoritesQueueRef.getAndSet(null)?.close()','''                homeRootRef.getAndSet(null)?.closeAndJoin()
                withContext(Dispatchers.IO) { applicationImages.close() }
                favoritesQueueRef.getAndSet(null)?.close()''')
edit(shell,'            homeRootRef.getAndSet(null)?.closeAndJoin()\n            closeDiscoveryStorage()','''            homeRootRef.getAndSet(null)?.closeAndJoin()
            withContext(Dispatchers.IO) { applicationImages.close() }
            closeDiscoveryStorage()''')
edit(shell,'                homeRootRef.getAndSet(null)?.closeAndJoin()\n                closeDiscoveryStorage()','''                homeRootRef.getAndSet(null)?.closeAndJoin()
                withContext(Dispatchers.IO) { applicationImages.close() }
                closeDiscoveryStorage()''')
OUT.mkdir()
for target,data in pending:
 if target.exists():
  baseline=wide(OUT/'baseline'/target.relative_to(REPO));baseline.parent.mkdir(parents=True,exist_ok=True);baseline.write_bytes(target.read_bytes())
 target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(data)
(OUT/'installed.json').write_text(json.dumps(dict(payloads=receipt,sourceCountBefore=927,sourceCountAfter=930,sourceManifestSha256Bytes=sha(raw),exactRootHunks=hunks,newRuntimeArtifacts=0,actualSingletonConfiguredInMain=True,actualBackgroundTrimNotMounted=True,newExeDeployed=False),indent=2)+'\n',encoding='utf-8')
print(json.dumps(dict(applied=True,payloads=8,sourceCount=930,rootHunks=len(hunks),backgroundTrimMounted=False)))
