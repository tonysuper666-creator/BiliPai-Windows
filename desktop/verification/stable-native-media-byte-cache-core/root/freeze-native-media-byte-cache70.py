from pathlib import Path
import hashlib,json,subprocess
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
LANE=MAIN/'desktop/.local/stable-native-media-byte-cache-parity';OUT=REPO/'desktop/verification/stable-native-media-byte-cache-core'
assert not OUT.exists()
assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=REPO,text=True).strip()=='9fbfe50973de4dc2b04984ef6ef6870bee617f2b'
def wide(p):
    s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix)else prefix+s)
def read(p):return wide(p).read_bytes()
def sha(b):return hashlib.sha256(b).hexdigest()
rows=[];excluded=[]
def put(name,b):
    p=wide(OUT/name);p.parent.mkdir(parents=True,exist_ok=True);assert not p.exists(),name
    p.write_bytes(b);rows.append(dict(path=name,sha256Bytes=sha(b),sizeBytes=len(b)))
raw=read(LANE/'frozen-handoff.json');assert sha(raw)=='62a15351de76f512ecb6ccae86e206f8e2fd5eeb8f0582124deff30d2af488e6'
packet=json.loads(raw);assert len(packet['artifacts'])==240
for item in packet['artifacts']:
    b=read(LANE/item['path']);assert sha(b)==item['sha256Bytes'] and len(b)==item['size']
    name='prepared/'+Path(item['path']).as_posix()
    if Path(name).suffix.lower()in('.class','.jar','.pyc','.mp4','.m4a','.png','.dll'):
        excluded.append(dict(path=name,sha256Bytes=sha(b),reason='Rebuildable binary or rendered output'))
    else:put(name,b)
put('prepared/frozen-handoff.json',raw)
install=HERE/'native-media-byte-cache-install70';installed=json.loads(read(install/'installed.json'))
assert installed['payloadCount']==5 and installed['exactHunks']==21 and installed['exactHunkFiles']==9
snap=MAIN/'desktop/.local/stable-product-snapshot-70'
assert sha(read(snap/'manifest.json'))=='f1e410523c6991a1f588ffc4e1d571f1251c81f6ab2b2d25ed9c947f9be03621'
assert sha(read(snap/'ordered-runtime-cp.json'))=='3e2c79ad73fd69d07025ab12d617d8aa9932cef580ef1046e3550458b8482aba'
meta=json.loads(read(snap/'manifest.json'));assert meta['sourceRegistryCount']==1169 and meta['runtimeEntries']==101 and meta['resourceCount']==213
inputs={r['path']:r['sha256Bytes']for r in meta['inputs']}
for item in installed['targets']:
    assert sha(read(REPO/item['path']))==item['sha256Bytes']==inputs[item['path']]
for item in installed['changedFiles']:
    assert sha(read(REPO/item['path']))==item['afterSha256Bytes']==inputs[item['path']]
for p in sorted(wide(install).rglob('*'),key=str):
    if p.is_file():put('root/install/'+p.relative_to(wide(install)).as_posix(),p.read_bytes())
expected=read(LANE/'generated/com/android/purebilibili/core/player/DesktopOriginalPlaybackMediaCachePolicy.kt')
actual=read(REPO/'desktop/build/generated/original-media-byte-cache-policy/com/android/purebilibili/core/player/DesktopOriginalPlaybackMediaCachePolicy.kt')
assert actual.replace(b'\r\n',b'\n')==expected.replace(b'\r\n',b'\n')
put('root/production-policy/DesktopOriginalPlaybackMediaCachePolicy.kt',actual)
for r in json.loads(read(snap/'ordered-runtime-cp.json')):assert sha(read(r['path']))==r['sha256Bytes']
for name in ('manifest.json','ordered-runtime-cp.json'):put('root/snapshot70/'+name,read(snap/name))
for name in ('classes-70.log','classpath-70.log'):put('root/'+name,read(REPO/'desktop/.local/stable-build-repair'/name))
audit=json.loads(read(HERE/'jvm-method-name-audit-70.json'));assert audit['issueCount']==0 and audit['classCount']==15089
for name in ('install-native-media-byte-cache70.py','freeze-native-media-byte-cache70.py','jvm-method-name-audit-70.json'):put('root/'+name,read(HERE/name))
report=dict(phase=70,sourceIdentityCount=1169,resourceCount=213,runtimeEntries=101,
    wholeClassesPassed=True,wholeTestSourcesCompiled=True,kotlinClassCount=15089,illegalJvmMethodNames=0,
    payloadCount=5,exactHunks=21,existingFamilyCount=9,sharedFamiliesReplaced=False,
    newIdentityCount=0,existingOriginalCacheIdentityPromotedToPolicyExtract=True,newDependencyCount=0,
    originalPureCacheHelpersMatchFrozenSource=True,newHttpClientsOrAccountStoresOrPlayers=0,
    nativeOnlyCarrierFieldAndMpvLifecycleInstalled=True,remoteSourceFieldsRetained=True,
    actualRepositoryFinalTypedHeaderSeamInstalled=True,actualEffectivePlaybackNamespaceGetterInstalled=True,
    nativeOwnerRequiresActualAcceptedSourceLifetimeAdmission=True,
    previousProspectiveActual66Assertions=42,previousProspectiveProductionOverrides=0,
    actual70InstalledCarrierNativeAccepted=False,actual70NativeProofInProgress=True,
    wholeRootOwnerMounted=False,invocationPortraitCdnConsumersPending=True,
    actualDirectFallbackOnCacheFailurePending=True,realAccountOrExternalHttpOrMainStartup=False,
    desktopExeReplaced=False,sourceInventoryIsNotFunctionalOrReusePercentage=True)
put('integration-report.json',(json.dumps(report,indent=2)+'\n').encode())
manifest=(json.dumps(dict(artifacts=rows,excludedRebuildableOutputs=excluded),indent=2)+'\n').encode()
wide(OUT/'artifact-manifest.json').write_bytes(manifest)
wide(OUT/'README.md').write_text('''Native media byte-cache production core
=======================================

Five whitelisted source payloads and21 exact single-anchor hunks across nine existing families add the Root-owned128MiB interval cache, same-Repository admission, original cache-key/eligibility/size policies, native-only immutable transport, final typed origin headers and effective playback-account cache namespace. The existing original PlaybackMediaCache identity is promoted from reference-only to policy extraction, preserving its hash/features. No original identity, dependency, HTTP client, account store, player or Cast singleton is added. The three original pure helper bodies match the frozen original selection. The remote playback source fields remain authoritative; MPV only projects the native carrier into its input. Initial source reads share the real native load ACK guard. Retained adoption rebinds the same carrier to the new exact publication and accepted source lifetime, and old owner close cannot retire an adopted publication.

Actual70 whole classes and test sources compile. Static classfile audit finds zero illegal JVM method names among15089 Kotlin classes. All1169 sources,213 resources and101 runtime artifacts remain pinned. The immutable prepared packet preserves240 raw source/proof artifacts and its historical failures. Its42 assertions against actual66 used prospective cache classes without product overrides and explicitly projected native URIs; those proofs are not relabeled installed actual70 acceptance.

A new independent actual70 native fixture is being prepared to exercise the installed carrier field, same Repository final headers, effective namespace and NativeOwner lifetime/adoption without production overrides. That acceptance is pending in this source/core commit. The real Main Root owner still needs the same Bound in Invocation, original CDN and Portrait consumers, complete original MPD preparation and direct upstream fallback on cache failure. No full page mount, real account, external HTTP, Main startup, packaging or desktop EXE replacement is claimed here. Inventory is not functional completion or source reuse.
''',encoding='utf8',newline='\n')
print(json.dumps(dict(rawArtifacts=len(rows),excludedBinaries=len(excluded),manifestSha256Bytes=sha(manifest))))
