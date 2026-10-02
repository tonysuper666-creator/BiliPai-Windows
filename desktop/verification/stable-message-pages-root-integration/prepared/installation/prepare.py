"""Prepare exact Root integration hunks. Never modifies Candidate or the frozen packet."""
from pathlib import Path
import difflib, hashlib, json, os, subprocess, sys
from exact_patch import apply as apply_exact
sys.dont_write_bytecode = True
sys.stdout.reconfigure(encoding="utf8")
P=Path(__file__).resolve().parent; M=P.parents[2]; C=M.parent/'BiliPai-v023'
F=M/'desktop/.local/stable-original-message-pages-root-parity'
if len(sys.argv)>2:
 P=Path(sys.argv[2]).resolve()
 assert P.is_relative_to((M/'desktop/.local').resolve()) and P!=F.resolve(), 'Rebase outputs must stay in a new task-owned .local lane'
def wide(p):
 s=str(p);return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+os.path.abspath(s))
def raw(p):return wide(p).read_bytes()
def sha(p):return hashlib.sha256(raw(p)).hexdigest()
def lf(b):return b.replace(b'\r\n',b'\n')
def write(p,b):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes(b)
assert sha(F/'install-contract.json')=='32bf55205f093564d37444ead049f130f2cc4f3395a37b78fd2bf73d1b17ee9b'
assert sha(F/'frozen-handoff.json')=='27a644ddf45fea069b47b601d0728234118561110290aac4ddec5f3800a90ffd'
head=sys.argv[1] if len(sys.argv)>1 else subprocess.check_output(['git','-C',str(C),'rev-parse','HEAD'],text=True).strip()
subprocess.check_call(['git','-C',str(C),'cat-file','-e',head+'^{commit}'])
def candidate(target):return subprocess.check_output(['git','-C',str(C),'show',head+':'+target])
def candidate_sha(target):return hashlib.sha256(candidate(target)).hexdigest()
old=json.loads(raw(F/'install-contract.json')); frozen=json.loads(raw(F/'frozen-handoff.json'))
for item in frozen['files']:assert sha(F/item['path'])==item['sha256Bytes'],item['path']
targets=[]
def edit(target, replacements):
 before=candidate(target);s=lf(before).decode('utf8');original=s
 for a,b in replacements:
  assert s.count(a)==1,(target,a[:120],s.count(a));s=s.replace(a,b,1)
 after=s.encode('utf8');assert before!=after
 patch=''.join(difflib.unified_diff(original.splitlines(True),s.splitlines(True),fromfile=target,tofile=target)).encode('utf8')
 patchname=Path(target).name+'.patch'
 write(P/'baseline'/target,before);write(P/'prospective'/target,after);write(P/'patches'/patchname,patch)
 targets.append(dict(target=target,baseRawSha256=hashlib.sha256(before).hexdigest(),baseLfSha256=hashlib.sha256(lf(before)).hexdigest(),
  desiredRawSha256=hashlib.sha256(after).hexdigest(),patch='patches/'+patchname,patchSha256=hashlib.sha256(patch).hexdigest(),operation='exact-hunks-only'))

mount='desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopReadyOriginalRootMount.kt'
edit(mount,[
 ('    val personalLists = AtomicReference<DesktopPersonalListsRoot?>()\n',
  '    val personalLists = AtomicReference<DesktopPersonalListsRoot?>()\n    val messagePages = AtomicReference<DesktopOriginalMessagePagesRoot?>()\n'),
 ('        route.getAndSet(null)?.close()\n        personalLists.getAndSet(null)?.closeAndJoin()\n',
  '        route.getAndSet(null)?.close()\n        messagePages.getAndSet(null)?.closeAndJoin()\n        personalLists.getAndSet(null)?.closeAndJoin()\n'),
 ('    leaf: @Composable (BiliPaiNavKey, DesktopOriginalRootRouteCommands, Boolean, Boolean, DesktopPersonalListsRoot, DesktopHomeSettingsPort) -> Unit,\n',
  '    leaf: @Composable (BiliPaiNavKey, DesktopOriginalRootRouteCommands, Boolean, Boolean, DesktopPersonalListsRoot, DesktopHomeSettingsPort, DesktopOriginalMessagePagesRoot) -> Unit,\n'),
 ('        handle.route.getAndSet(null)?.close(); handle.retainer.retire()\n',
  '        handle.route.getAndSet(null)?.close(); handle.messagePages.get()?.close(); handle.retainer.retire()\n'),
 ('            handle.route.getAndSet(null)?.close()\n            physicalStack.clear(); physicalStack.add(BiliPaiNavKey.MainHost)\n',
  '            handle.route.getAndSet(null)?.close()\n            handle.messagePages.getAndSet(null)?.closeAndJoin()\n            physicalStack.clear(); physicalStack.add(BiliPaiNavKey.MainHost)\n'),
 ('        DisposableEffect(routes) { onDispose { handle.route.compareAndSet(routes, null); routes.close() } }\n',
  '        DisposableEffect(routes) { onDispose { handle.route.compareAndSet(routes, null); routes.close() } }\n'
  '        val messagePages = rememberDesktopOriginalMessagePagesRoot(services.repository, services.community,\n'
  '            routes, desktopDetailRenderEffectsSupported())\n'
  '        SideEffect { if (handle.isActive() && messagePages.isOwned()) handle.messagePages.set(messagePages) else messagePages.close() }\n'),
 ('{ key, commands, active, hosted -> leaf(key, commands, active, hosted, personalLists, root.environment.settings) }',
  '{ key, commands, active, hosted -> leaf(key, commands, active, hosted, personalLists, root.environment.settings, messagePages) }'),
])
shell='desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt'
fallbacks=[line for line in lf(candidate(shell)).decode('utf8').splitlines(True)
 if 'section in listOf(DesktopSection.DYNAMIC,' in line and 'DesktopSection.MESSAGES' in line]
assert len(fallbacks)==1
edit(shell,[
 ('{ entryKey,commands,active,pagerHosted,personalLists,originalHomePreferences ->',
  '{ entryKey,commands,active,pagerHosted,personalLists,originalHomePreferences,messagePages ->'),
 ('                            entryKey is BiliPaiNavKey.CommentDetail ->\n',
  '                            entryKey == BiliPaiNavKey.Inbox || entryKey == BiliPaiNavKey.ReplyMe ||\n'
  '                                entryKey == BiliPaiNavKey.AtMe || entryKey == BiliPaiNavKey.LikeMe ||\n'
  '                                entryKey == BiliPaiNavKey.SystemNotice || entryKey is BiliPaiNavKey.Chat ->\n'
  '                                DesktopDetailWindow { DesktopOriginalMessagePageRootHost(entryKey, messagePages, messageRoutes, active) }\n'
  '                            entryKey is BiliPaiNavKey.CommentDetail ->\n'),
 (fallbacks[0],fallbacks[0].replace(' DesktopSection.MESSAGES,','')),
 ('                                    DesktopSection.MESSAGES -> CommunitySection.MESSAGES\n',''),
])
# No RootStack or Main mutation: its real NavDisplay/saveable entry dispatches these keys
# through leafContent, and existing Shell restore/shutdown awaits THIS shared handle.
stack='desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalRootStack.kt'
stacktext=lf(candidate(stack)).decode('utf8')
assert 'else -> leafContent(key, routes, active, pagerHosted)' in stacktext
shelltext=lf(candidate(shell)).decode('utf8')
assert shelltext.count('homeRootRef.getAndSet(null)?.closeAndJoin()')==3
gradle='desktop/build.gradle.kts'
build=lf(candidate(gradle)).decode('utf8')
assert 'extractOriginalMessagePages' not in build
producer='''
// Full original message seven pages. DIRECT4 are copied once by prepareUpstreamSources;
// production intentionally omits --standalone. Existing unread/share producers stay unique.
val extractOriginalMessagePages by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractUpstreamDynamicFullCard, extractOriginalHomeProtocols)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-message-pages.py",
        "--repo", repositoryRoot.absolutePath,
        "--out", layout.buildDirectory.dir("generated/original-message-pages").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-message-pages.py", "tools/extract-appearance-platform.py",
        "tools/extract-upstream-plugins.py", "tools/extract-upstream-media.py", "tools/sync-upstream.py", sourceManifest)
    inputs.files(sources.filter { "stable-original-message-pages-root-parity" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-message-pages"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-message-pages")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalMessagePages) }
'''
# Match the actual existing shared task names, not guessed aliases.
assert 'val extractUpstreamDynamicFullCard by ' in build
assert 'val extractOriginalHomeProtocols by ' in build
edit(gradle,[
 ('    implementation("org.jetbrains.compose.material3:material3:1.12.0-alpha03")\n',
  '    implementation("org.jetbrains.compose.material3:material3:1.12.0-alpha03")\n'
  '    // Actual JetBrains JVM counterparts of original Android adaptive 1.3.0.\n'
  '    implementation("org.jetbrains.compose.material3.adaptive:adaptive:1.3.0-rc01")\n'
  '    implementation("org.jetbrains.compose.material3.adaptive:adaptive-layout:1.3.0-rc01")\n'),
 (''.join(build.splitlines(True)[-5:]),''.join(build.splitlines(True)[-5:])+producer),
])
for row in old['hunkTargets']:
 target=row['target'];before=candidate(target);patch=raw(F/row['patch']);after=apply_exact(before,patch)
 write(P/'baseline'/target,before);write(P/'prospective'/target,after)
 name=Path(target).name+'.patch';write(P/'patches'/name,patch)
 targets.append(dict(target=target,baseRawSha256=hashlib.sha256(before).hexdigest(),baseLfSha256=hashlib.sha256(lf(before)).hexdigest(),
  desiredRawSha256=hashlib.sha256(after).hexdigest(),patch='patches/'+name,patchSha256=hashlib.sha256(patch).hexdigest(),
  frozenPatchSha256=row['patchSha256Bytes'],operation='exact-hunks-only',rebaseRule='Original exact hunk bytes; current unrelated source bytes retained.'))
for row in targets:
 assert hashlib.sha256(apply_exact(candidate(row['target']),raw(P/row['patch']))).hexdigest()==row['desiredRawSha256']
registryTarget='desktop/upstream-sources.json'; registryBytes=candidate(registryTarget)
registry=json.loads(registryBytes); entries=registry['sources']; existing={r['path']:r for r in entries}
changes=[]
for row in json.loads(raw(F/'source-inventory.json')):
 source=subprocess.check_output(['git','-C',str(C),'show',old['upstreamCommit']+':'+row['path']]).replace(b'\r\n',b'\n')
 assert hashlib.sha256(source).hexdigest()==row['sha256'],row['path']
 if row['path'] in existing:
  current=existing[row['path']];assert current['sha256']==row['sha256'] and current.get('mode','direct')==row['mode'],row['path']
  current['features']=list(dict.fromkeys(current.get('features',[])+row['features']));op='union-features'
 else:entries.append(row);op='append'
 changes.append(dict(path=row['path'],operation=op,sha256Lf=row['sha256'],mode=row['mode']))
assert sum(r['operation']=='append'for r in changes)==13
assert sum(r['operation']=='union-features'for r in changes)==4
afterRegistry=(json.dumps(registry,ensure_ascii=False,indent=2)+'\n').encode('utf8')
write(P/'prospective'/registryTarget,afterRegistry)
write(P/'baseline'/registryTarget,registryBytes)
registryDelta=dict(target=registryTarget,operation='verified-union',baseRawSha256=hashlib.sha256(registryBytes).hexdigest(),
 beforeCount=len(entries)-13,afterCount=len(entries),changes=changes,desiredRawSha256=hashlib.sha256(afterRegistry).hexdigest())
write(P/'registry-delta.json',json.dumps(registryDelta,ensure_ascii=False,indent=2).encode('utf8'))
contract=dict(schemaVersion=1,candidateBase=head,baselineReadMode='git-show-pinned-commit (no dirty working-tree inputs)',frozenPacket=str(F),frozenContractSha256=sha(F/'install-contract.json'),
 frozenHandoffSha256=sha(F/'frozen-handoff.json'),exactHunkTargets=targets,
 originalPacketCopyTargets=old['copyTargets'],originalPacketDataHunksProvenance=old['hunkTargets'],registry=registryDelta,
 unchangedConsumers=[dict(path=stack,sha256Bytes=candidate_sha(stack),reason='Actual retained NavDisplay dispatches all six message key types through leafContent; no competing dispatcher.'),
  dict(path='desktop/src/main/kotlin/com/bilipai/desktop/Main.kt',sha256Bytes=candidate_sha('desktop/src/main/kotlin/com/bilipai/desktop/Main.kt'),reason='Existing registerShutdown calls shared handle through Shell; do not add another application shutdown coordinator.')],
 dependencies=old['dependencies'],productionWrites=0,sharedGradleRuns=0,rootAccepted=False)
write(P/'root-install-contract.json',json.dumps(contract,ensure_ascii=False,indent=2).encode('utf8'))
print(json.dumps(dict(base=head,hunkTargets=len(targets),registryBefore=registryDelta['beforeCount'],registryAfter=registryDelta['afterCount'],rootContractSha256=sha(P/'root-install-contract.json')),indent=2))
