from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
OUT=REPO/'desktop/verification/stable-application-image-profile-platform-integration';assert not OUT.exists()
sha=lambda b:hashlib.sha256(b).hexdigest();rows=[];excluded=[]
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def put(name,data):
 p=wide(OUT/name);p.parent.mkdir(parents=True,exist_ok=True);assert not p.exists();p.write_bytes(data);rows.append(dict(path=name,sha256Bytes=sha(data),sizeBytes=len(data)))
def register(name,data):
 if Path(name).suffix in ('.jar','.class','.kotlin_module','.dll','.pyc','.obj','.lib','.exp','.pdb'):
  excluded.append(dict(path=name,sha256Bytes=sha(data),reason='Rebuildable compiler output'))
 else:put(name,data)
def tree(folder,prefix):
 for p in sorted(wide(folder).rglob('*'),key=str):
  if p.is_file():register(prefix+'/'+p.relative_to(wide(folder)).as_posix(),p.read_bytes())
for name,laneName,pin in [('image','stable-application-image-loader-parity','0740783057cdccbcc5b5d65ee09b74ec9c8e017a9e9fb6b940888d78dcaf293d'),('profileWindows','stable-profile-windows-platform-parity','15aa4e1197a7b804f8bdb9f6942972761c7864a8d552dbbdef72a33930f03db6')]:
 lane=MAIN/'desktop/.local'/laneName;raw=wide(lane/'frozen-handoff.json').read_bytes();assert sha(raw)==pin;put('prepared/'+name+'/frozen-handoff.json',raw)
 for r in json.loads(raw)['artifacts']:
  data=wide(lane/r['path']).read_bytes();assert sha(data)==r['sha256Bytes'],r['path'];register('prepared/'+name+'/'+r['path'],data)
proofLane=MAIN/'desktop/.local/stable-image-profile-actual44-proof'
proof=json.loads(wide(proofLane/'result.json').read_bytes());assert proof['passed'] and proof['productionOverrides']==0
manifest=json.loads(wide(proofLane/'frozen-proof.json').read_bytes());put('root/actual44/frozen-proof.json',wide(proofLane/'frozen-proof.json').read_bytes())
for r in manifest['artifacts']:
 data=wide(proofLane/r['path']).read_bytes();assert sha(data)==r['sha256Bytes'];register('root/actual44/'+r['path'],data)
for folder in ['application-image-loader-install44','profile-windows-install44']:tree(HERE/folder,'root/'+folder)
for name in ['install-application-image-loader44.py','install-profile-windows44.py','freeze-image-profile44.py','jvm-method-name-audit-44.json']:put('root/'+name,(HERE/name).read_bytes())
for name in ['manifest.json','ordered-runtime-cp.json']:put('root/snapshot44/'+name,(MAIN/'desktop/.local/stable-product-snapshot-44'/name).read_bytes())
for name in ['classes-44-attempt01.log','classes-44.log','export-44.log']:put('root/'+name,(REPO/'desktop/.local/stable-build-repair'/name).read_bytes())
report=dict(wholeClassesPhase=44,wholeClassesPassed=True,sourceIdentityCount=931,resourceCount=213,actualRuntimeEntries=97,actualKotlinClasses=11873,invalidJvmMethodNames=0,focusedActual44=proof,productionOverrides=0,actualRootImageSingletonConfigured=True,sameRepositoryClientUsed=True,originalImageMemoryPercent=0.10,originalDiskBytes=100*1024*1024,originalDecodeMaximum=[4608,4608],unchangedExternalCacheControlSources=3,newRuntimeArtifacts=0,fullOriginalWallpaperImportProduced=True,sameGlobalThemeAndCacheTransaction=True,sameGalleryAssetsAndLocations=True,backgroundTrimMounted=False,profileMainRouteMounted=False,actualRootDwmOrChooserAccepted=False,externalHttp=False,personalWallpaperRead=False,newWindowsExeDeployed=False,firstBuildFailure='The Gradle java extension shadowed the fully qualified java.security name. Reused the existing MessageDigest import; source compiler behavior unchanged.',pending=['Actual original Root MainHost/NavDisplay/Home/Profile mount','Final playback publication and transport source integration','Window/native/account/user playback acceptance and desktop portable EXE'])
put('integration-report.json',(json.dumps(report,indent=2)+'\n').encode())
raw=(json.dumps(dict(artifacts=rows,excludedRebuildableOutputs=excluded),indent=2)+'\n').encode();wide(OUT/'artifact-manifest.json').write_bytes(raw)
wide(OUT/'README.md').write_text('''Original application image loader and Profile Windows platform integration
========================================================================

Stable target remains v0.2.3, 3d5d19a2f994daccd0e2f8b5f522b6d82f43d589. The selected full application image-loader method, original memory setting and background budgets reuse original source. Three Coil3.5.0 cache-control sources retain their exact upstream bodies and Apache notices; no new runtime library was introduced. The actual Root singleton factory uses the sole Repository client with captured epoch/lifetime admission. Original 10% memory cache,100 MiB disk cache,4608x4608 decoding and cache-control behavior remain. The image service closes during actual shutdown/restore. Windows image decoding differences and the yet-unmounted background trim are explicitly retained in the prepared packet.

The full original127-line WallpaperImageImport is produced by one pinned extractor. Required Profile Windows effects use actual local Skia/file URI/bounded trusted ffprobe and the existing theme, Assets, Locations, clipboard and media services. The global canonical theme/startup cache writes share one atomic backing. Official download paths use unique names to avoid overwriting existing files; original preferences and legacy deletion remain.

Whole classes44 passed with931 source identities,213 resources and97 existing ordered runtime entries. The frozen product has11873 Kotlin classes and no invalid JVM method names. Focused tests compile fixture-only code against the immutable actual44 product, with zero production replacements and verified class origins/CP pins. Local media and private fixture state are synthetic. Actual HTTP, personal wallpaper, native chooser/DWM and mounted Profile navigation remain outside this acceptance. No new EXE is deployed by this source slice.

Raw prepared receipts, actual product proof, installation baselines and logs are retained. Rebuildable binaries and private generated fixture media/state are excluded. The initial Gradle script-name failure is retained; its repair uses the existing MessageDigest import. Full Root mount and final playback publication are the next integration work.
''',encoding='utf-8',newline='\n')
print(json.dumps(dict(rawArtifacts=len(rows),excludedBinaries=len(excluded),manifestSha256Bytes=sha(raw))))
