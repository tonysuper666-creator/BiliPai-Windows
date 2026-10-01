from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
OUT=REPO/'desktop/verification/stable-profile-live-aggregate-integration'
assert not OUT.exists()
sha=lambda b:hashlib.sha256(b).hexdigest()
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
rows=[];excluded=[]
def put(name,b):
 p=wide(OUT/name);p.parent.mkdir(parents=True,exist_ok=True);assert not p.exists();p.write_bytes(b)
 rows.append(dict(path=name,sha256Bytes=sha(b),sizeBytes=len(b)))
installs={name:json.loads((HERE/folder/'installed.json').read_text(encoding='utf-8'))for name,folder in [('profile','profile-main-install'),('aggregateUri','aggregate-profile-uri-install'),('live','live-navigation-install')]}
lanes=[('profile',REPO,'stable-profile-main-parity',installs['profile']['preparedManifestSHA256']),
 ('profileUri',REPO,'stable-profile-file-uri-native-delta',installs['aggregateUri']['preparedManifests']['profileUri']),
 ('aggregate',MAIN,'stable-home-four-page-aggregate-parity',installs['aggregateUri']['preparedManifests']['aggregate']),
 ('live',MAIN,'stable-home-live-navigation-parity',installs['live']['preparedManifestSHA256'])]
for name,owner,laneName,pin in lanes:
 lane=owner/'desktop/.local'/laneName;raw=wide(lane/'frozen-handoff.json').read_bytes();assert sha(raw)==pin
 put('prepared/'+name+'/frozen-handoff.json',raw);d=json.loads(raw)
 for r in d.get('artifacts',d.get('rawArtifacts',[])):
  relative=r.get('relative',r['path']);source=Path(r['path'])if Path(r['path']).is_absolute()else lane/r['path']
  b=wide(source).read_bytes();assert sha(b)==r['sha256Bytes'],source
  if source.suffix in ('.jar','.class','.kotlin_module','.dll','.pyc'):
   excluded.append(dict(lane=name,path=relative,sha256Bytes=sha(b),reason='Rebuildable preparation/compiler output'))
  else:put('prepared/'+name+'/'+relative,b)
for folder in ['profile-main-install','aggregate-profile-uri-install','live-navigation-install','profile-media-extension-install','profile-live-aggregate-actual41','profile-media-actual41']:
 for p in sorted(wide(HERE/folder).rglob('*'),key=str):
  if not p.is_file():continue
  name='root/'+folder+'/'+p.relative_to(wide(HERE/folder)).as_posix();b=p.read_bytes()
  if p.suffix in ('.jar','.class','.kotlin_module','.dll','.pyc'):
   excluded.append(dict(path=name,sha256Bytes=sha(b),reason='Rebuildable fixture output'))
  else:put(name,b)
for name in ['install-profile-main.py','install-aggregate-profile-uri.py','install-live-navigation.py','prove-profile-live-aggregate41.py','prove-profile-media41.py','freeze-profile-live-aggregate41.py','jvm-method-name-audit-41.json']:
 put('root/'+name,(HERE/name).read_bytes())
snap=MAIN/'desktop/.local/stable-product-snapshot-41'
for name in ['manifest.json','ordered-runtime-cp.json']:put('root/snapshot41/'+name,(snap/name).read_bytes())
put('root/classes-41.log',(REPO/'desktop/.local/stable-build-repair/classes-41.log').read_bytes())
for name in ['DesktopHomeOwnedMedia.kt','DesktopHomeWallpaperImages.kt','DesktopOriginalProfileMedia.kt']:
 put('root/installed-media/'+name,(REPO/'desktop/src/main/kotlin/com/bilipai/desktop/ui'/name).read_bytes())
proof=json.loads((HERE/'profile-live-aggregate-actual41/result.json').read_text(encoding='utf-8'))
media=json.loads((HERE/'profile-media-actual41/accepted.json').read_text(encoding='utf-8'))
assert proof['passed']and media['passed']and proof['productionOverrides']==media['productionOverrides']==0
report=dict(wholeClassesPhase=41,wholeClassesPassed=True,sourceIdentityCount=895,resourceCount=213,actualRuntimeEntries=97,actualKotlinClasses=11488,invalidJvmMethodNames=0,
 completeOriginalProfileUiAndViewModelCompiled=True,originalLiveFourSubroutesCompiled=True,homeFourPageAggregateCompiled=True,
 profileInstalledClassLoadCount=180,profileClassInitialization=False,liveAssertions=25,liveGroups=3,aggregateLifecycleGroups=4,
 actualProductClassOrigins=26,protocolLifecycleClassOrigins=14,nativeMediaClassOrigins=12,productionOverrides=0,
 profileMediaNativeAssertions=27,nativeVideoEofRepeatVerified=True,skinStartedLifecycleAndGifCropPixelsVerified=True,
 nativeMediaScope='Fixed local libmpv sources and offscreen Compose; no HTTP or physical Window',
 fullRootNavigationOrPhysicalWindowAccepted=False,fullHomeRootMounted=False,profileMounted=False,liveSubroutesMounted=False,
 actualRuntimeAggregateFactoryConstructed=False,playbackAccountAuthorizationIntegrated=False,newWindowsExeDeployed=False,
 pending=['Full original navigation host and actual Root backstack/geometry/transition window acceptance','Retained original Home/aggregate factory and simultaneous old planner retirement',
 'Profile theme, palette, file import/export and same-Store playback authorization Root ports','Full Share/Consent platform boundaries','Final EXE deployment and user playback/interaction acceptance'])
put('integration-report.json',(json.dumps(report,indent=2)+'\n').encode());put('root/compiled-output-exclusions.json',(json.dumps(excluded,indent=2)+'\n').encode())
manifest=(json.dumps(dict(schema='raw-artifact-manifest-v1',artifacts=rows),indent=2)+'\n').encode();wide(OUT/'artifact-manifest.json').write_bytes(manifest)
wide(OUT/'README.md').write_text('''# Original Profile, Live subnavigation and Home aggregate — installed source closure

Windows classes41 compiles the full original Profile screen/ViewModel/sheets and selected action UI, four complete original Live subroutes, and the retained four-page Home aggregate. The original-source inventory is895 identities and213 resources. Reference-only helpers were not installed; existing producers retain sole ownership. Three precise follow-up URI hunks preserve Windows drive/file existence checks and canonical file:/// URIs. Prepared Profile137, URI7, Aggregate22 and Live100 receipts remain immutable below; rebuildable binaries are enumerated separately.

The actual immutable41/97 graph accepts180 installed Profile classes without initialization, original Live fixture3 groups/25 assertions and retained page4 lifecycle groups. Fourteen observed protocol/lifetime class origins point to the actual product JAR, with zero production class replacements. Protocol replies and navigation exposure are synthetic. The temporary global stores do not contain a user's account; no HTTP, actual aggregate Runtime construction, gallery service or physical Window is accepted by those fixtures. Historical actual39/prepared wording remains preserved inside original fixture bodies; this outer receipt identifies the installed actual41 graph.

The Profile media port reuses the existing private Home media lifetime/lease and actual libmpv texture. Video wallpaper retains the original centered ZOOM behavior; alignment applies to image/GIF branches. Skin video uses the original sole repeat-mode policy and STARTED lifecycle, while ordinary wallpaper keeps its RESUMED default. The existing offscreen/native19 checks plus8 new assertions pass27 checks with12 actual product class origins and zero overrides: real EOF versus repeat timeline wrap, skin lifecycle pixels/background hold, and three actual GIF crop pixel observations. Sources are fixed local generated files, with pinned libmpv/ffmpeg; there is no native network source or real account read. This is not a physical Root-window or monitor screenshot acceptance.

The full Root navigation, retained Home factory/old planner retirement, playback authorization, palette/theme/file boundaries, Share/Consent and final EXE remain pending. The desktop test installation is unchanged by this source slice. Source identity count is not a feature-completion percentage.
''',encoding='utf-8',newline='\n')
print(json.dumps(dict(rawArtifacts=len(rows),excludedBinaries=len(excluded),manifestSha256Bytes=sha(manifest))))
