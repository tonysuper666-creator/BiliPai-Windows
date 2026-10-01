from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023';OUT=REPO/'desktop/verification/stable-playback-account-publication-integration';assert not OUT.exists()
rows=[];excluded=[];sha=lambda b:hashlib.sha256(b).hexdigest()
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def put(name,data):
 p=wide(OUT/name);p.parent.mkdir(parents=True,exist_ok=True);assert not p.exists();p.write_bytes(data);rows.append(dict(path=name,sha256Bytes=sha(data),sizeBytes=len(data)))
def register(name,data):
 if Path(name).suffix in ('.jar','.class','.kotlin_module','.dll','.pyc','.obj','.lib','.exp','.pdb'):
  excluded.append(dict(path=name,sha256Bytes=sha(data),reason='Rebuildable output'))
 else:put(name,data)
def tree(folder,prefix):
 for p in sorted(wide(folder).rglob('*'),key=str):
  if p.is_file():register(prefix+'/'+p.relative_to(wide(folder)).as_posix(),p.read_bytes())
proofLane=MAIN/'desktop/.local/stable-playback-publication-actual45-proof';proof=json.loads(wide(proofLane/'result.json').read_bytes());assert proof['passed'] and proof['productionOverrides']==0
unit=json.loads((HERE/'unit-tests-45/result.json').read_bytes());assert unit['passed'] and unit['uniqueAcceptedCases']==67
for name,laneName,pin,count in [('backend','stable-playback-account-protocol-parity','b990563ad4ec9cf4eca8ea495377fe0496073209684df8d8e922cbe8a08322a3',84),('publication','stable-playback-final-publication-parity','3c1f394d055a060a0f8e35a5e51f0f46da3d71f6f2ab6320080aeb306ab3bc5d',428)]:
 lane=MAIN/'desktop/.local'/laneName;raw=wide(lane/'frozen-handoff.json').read_bytes();assert sha(raw)==pin;put('prepared/'+name+'/frozen-handoff.json',raw);manifest=json.loads(raw);assert len(manifest['artifacts'])==count
 for r in manifest['artifacts']:
  data=wide(lane/r['path']).read_bytes();assert sha(data)==r['sha256Bytes'];register('prepared/'+name+'/'+r['path'],data)
raw=wide(proofLane/'frozen-proof.json').read_bytes();assert sha(raw)=='4cfd218c5493bf7b854b4468fc437c34b04c741bcee4bd56a40cad020023e236';put('root/actual45/frozen-proof.json',raw)
for r in json.loads(raw)['artifacts']:
 data=wide(proofLane/r['path']).read_bytes();assert sha(data)==r['sha256Bytes'];register('root/actual45/'+r['path'],data)
tree(HERE/'playback-publication-install45','root/install45');tree(HERE/'unit-tests-45','root/unit-tests45')
for name in ['install-playback-publication45.py','repair-native-test-contract45.py','collect-unit-tests45.py','freeze-playback-publication45.py','jvm-method-name-audit-45.json']:put('root/'+name,(HERE/name).read_bytes())
for name in ['manifest.json','ordered-runtime-cp.json']:put('root/snapshot45/'+name,(MAIN/'desktop/.local/stable-product-snapshot-45'/name).read_bytes())
for name in ['classes-45-attempt01.log','classes-45-attempt02.log','classes-45.log','export-45.log']:put('root/'+name,(REPO/'desktop/.local/stable-build-repair'/name).read_bytes())
report=dict(wholeClassesPhase=45,wholeClassesPassed=True,wholeTestSourcesCompiled=True,sourceIdentityCount=931,resourceCount=213,actualRuntimeEntries=97,actualKotlinClasses=11893,invalidJvmMethodNames=0,focusedActual45=proof,actualFocusedAssertions=86,actualFocusedGroups=8,productionOverrides=0,targetedJUnit=unit,uniqueJUnitCasesPassed=67,selectedPlaybackStoreRevisionIndependent=True,originalSelectedCookieJarBodyPreserved=True,finalControllerListenDownloadAndCastPublicationInstalled=True,sameMpvCoreAndDownloadQueueAndClient=True,downloadOriginalIdentityModeNowPlatformRewrite=True,newOriginalIdentities=0,newRuntimeArtifacts=0,oldSyntheticCallersExplicitlyAdmitted=True,existingCastVerifierPreserved=True,profilePlaybackAccountUiMounted=False,physicalNativeDecodeOrLanReceiverAccepted=False,newWindowsExeDeployed=False,failuresRetained=['Cast provenance omitted two changed existing Kotlin records; corrected only their hashes/lengths','Three historical native test fixtures lacked five render-interface methods; added explicit unexpected-call failures without changing assertions','Older settings fixture replaced only api, so one guest fake-BVID request reached actual HTTP; bind the same existing memory-only client. The failed class alone was rerun with exactly the same25 case names'],pending=['Concrete Profile account platform methods and original selection UI','Actual original MainHost/Home/NavDisplay/typed leaf Window mount','Native/account/receiver interaction and final desktop EXE'])
put('integration-report.json',(json.dumps(report,indent=2)+'\n').encode());manifest=(json.dumps(dict(artifacts=rows,excludedRebuildableOutputs=excluded),indent=2)+'\n').encode();wide(OUT/'artifact-manifest.json').write_bytes(manifest)
wide(OUT/'README.md').write_text('''Same-store playback account and final consumer publication
========================================================

Stable target remains v0.2.3/3d5d19a2f994daccd0e2f8b5f522b6d82f43d589. The same encrypted SessionStore preserves original independent playback MID selection and revision behavior. The original selected CookieJar body delegates through the sole Repository client. Primary Home epoch remains independent. Nonsecret receipts survive cache, native conversion, plugin/CDN/audio mapping and final Controller/Listen/PGC/download/cast publication. Store admission publishes bounded memory/command work; download persistence, socket/ACK and native drain remain outside Store locks. Cancellation and stale commands cannot switch to another account or recover a foreign source. Selecting a different playback account does not automatically stop already-running media.

The original downloader full body remains unchanged except its OkHttpClient-to-Call.Factory constructor field type, produced once. Its existing registry identity changes mode rather than adding a second copy. The unique Channel fork adds its bounded writer with explicit cancellation/close admission. Existing Google Cast verifier and original archives/dependencies stay enabled. Required synthetic fixture contracts are explicit; null receipts do not give the real Root default selected-source permission.

Whole classes45 and compileTestKotlin pass with931 source identities,213 resources and97 existing runtime entries. Actual product contains11893 Kotlin classes and no invalid JVM method names. Fixture-only immutable-product tests have zero production replacements:8 groups/86 unchanged assertions verify real product class origins and CP pins. Six targeted JUnit classes finish67 unique cases, replacing the one failed25-case class with a passing rerun with the same case names. Current product classes/resources still match frozen45 byte for byte after test-only repairs.

All failed attempts are retained. Cast verification correctly rejected two omitted changed Kotlin provenance records. Three older native fakes lacked five interface methods; explicit unexpected-render errors complete only their contracts. A settings fixture still replaced only the API object; its new owner-tagged factory used the normal client and one guest fake-BVID request reached real HTTP. The fixture now binds its already-existing memory-only client as well; business assertions and product sources are unchanged. No personal session was used. Focused86-assertion tests themselves remain memory-only with no HTTP/socket/HWND/native DLL, and do not establish physical decoding or receiver behavior.

Complete Profile account effects/selection UI, original Root/window mounting, native/user-account/receiver acceptance and a newly deployed portable EXE remain pending. Raw source/evidence is preserved; rebuildable binaries and private fixture state are excluded.
''',encoding='utf-8',newline='\n');print(json.dumps(dict(rawArtifacts=len(rows),excludedBinaries=len(excluded),manifestSha256Bytes=sha(manifest))))
