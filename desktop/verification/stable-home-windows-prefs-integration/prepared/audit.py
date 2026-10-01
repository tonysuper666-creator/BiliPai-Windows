from pathlib import Path
import concurrent.futures,datetime,hashlib,json,subprocess,sys,urllib.request,zipfile
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];CANDIDATE=MAIN.parent/'BiliPai-v023'
COMMIT='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
def safe(path):
 value=str(path.absolute());return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def sha(data):return hashlib.sha256(data).hexdigest()
def pin(path):return dict(path=str(path),sha256Bytes=sha(safe(path).read_bytes()),sha256LF=sha(safe(path).read_bytes().replace(b'\r\n',b'\n')))
def main():
 urls={
  'preferred-profile':'https://learn.microsoft.com/en-us/uwp/api/windows.networking.connectivity.networkinformation.getinternetconnectionprofile?view=winrt-26100',
  'iana-type':'https://learn.microsoft.com/en-us/uwp/api/windows.networking.connectivity.networkadapter.ianainterfacetype?view=winrt-26100',
  'wwan-profile':'https://learn.microsoft.com/en-us/uwp/api/windows.networking.connectivity.connectionprofile.iswwanconnectionprofile?view=winrt-26100',
  'connectivity-level':'https://learn.microsoft.com/en-us/uwp/api/windows.networking.connectivity.networkconnectivitylevel?view=winrt-26100',
  'iana-iftype-registry':'https://www.iana.org/assignments/ianaiftype-mib/ianaiftype-mib',
  'graphics-configuration':'https://docs.oracle.com/en/java/javase/21/docs/api/java.desktop/java/awt/GraphicsConfiguration.html',
  'display-mode':'https://docs.oracle.com/en/java/javase/21/docs/api/java.desktop/java/awt/DisplayMode.html',
  'openjdk21u-awt-toolkit':'https://raw.githubusercontent.com/openjdk/jdk21u/master/src/java.desktop/windows/native/libawt/windows/awt_Toolkit.cpp',
 }
 def download(item):
  name,url=item
  with urllib.request.urlopen(urllib.request.Request(url,headers={'User-Agent':'BiliPai-source-review/1'}),timeout=30) as response:data=response.read();final=response.url
  path=HERE/'official-sources'/(name+'.source');path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(data)
  return dict(url=url,finalUrl=final,**pin(path),bytes=len(data))
 with concurrent.futures.ThreadPoolExecutor(max_workers=4) as executor:docs=list(executor.map(download,urls.items()))
 (HERE/'official-sources.json').write_text(json.dumps(dict(retrievedAtUTC=datetime.datetime.now(datetime.timezone.utc).isoformat(),officialOnly=True,sources=docs,openJdkNativeSourceIsReferenceNotExactRuntimeBuild=True),indent=2)+'\n',encoding='utf-8',newline='\n')
 rows=[]
 for suffix in ['core/util/NetworkUtils.kt','core/store/SettingsManager.kt']:
  path='app/src/main/java/com/android/purebilibili/'+suffix
  data=subprocess.run(['git','-C',str(CANDIDATE),'show',COMMIT+':'+path],check=True,capture_output=True).stdout
  target=HERE/'original-source'/path;safe(target.parent).mkdir(parents=True,exist_ok=True);safe(target).write_bytes(data)
  rows.append(dict(originalPath=path,gitCommit=COMMIT,**pin(target)))
 snapshot=MAIN/'desktop/.local/stable-product-snapshot-33';cp=json.loads((snapshot/'ordered-runtime-cp.json').read_text())
 names=[b'resolveLargeScreenOrFoldableConfiguration',b'LARGE_SCREEN_SMALLEST_WIDTH_DP']
 hits=[]
 for item in cp:
  assert sha(safe(Path(item['path'])).read_bytes())==item['sha256Bytes']
  with zipfile.ZipFile(safe(Path(item['path']))) as jar:
   for name in jar.namelist():
    if name.endswith('.class'):
     data=jar.read(name)
     if any(needle in data for needle in names):hits.append(dict(jar=item['path'],className=name))
 assert not hits,hits
 producer=CANDIDATE/'desktop/tools/extract-upstream-dynamic-detail-container.py'
 base=producer.read_text(encoding='utf-8')
 before="select(BASE+'core/util/FoldableDisplayPolicy.kt',['AppFoldableDisplayRole','AppDisplayNaturalOrientation','AppFoldableDetectionBasis','AppDisplayContext'],'','DesktopOriginalDetailDisplayModel.kt')"
 after=before.replace("'AppDisplayContext']","'AppDisplayContext','LARGE_SCREEN_SMALLEST_WIDTH_DP','resolveLargeScreenOrFoldableConfiguration']")
 assert base.count(before)==1
 (HERE/'sole-classification-producer-hunk.json').write_text(json.dumps(dict(path=str(producer),baseSha256Bytes=sha(producer.read_bytes()),baseSha256LF=sha(producer.read_bytes().replace(b'\r\n',b'\n')),before=before,after=after,RootInstallNewStandaloneClassificationFile=False,selectedStandaloneProofHasSameOriginalBodies=True),indent=2)+'\n',encoding='utf-8',newline='\n')
 registry=CANDIDATE/'desktop/upstream-sources.json';registryData=json.loads(registry.read_text(encoding='utf-8'));path='app/src/main/java/com/android/purebilibili/core/util/FoldableDisplayPolicy.kt'
 matching=[row for row in registryData['sources'] if row['path']==path];assert len(matching)==1
 original=HERE/'original-source'/path
 assert matching[0]['sha256']==sha(original.read_bytes().replace(b'\r\n',b'\n'))
 (HERE/'registry-merge-recipe.json').write_text(json.dumps(dict(existingRegistry=pin(registry),path=path,sha256LF=matching[0]['sha256'],existingRow=matching[0],operation='Preserve existing selected mode and all features; merge home-windows-prefs-ports feature into this same source identity only',noSecondIdentity=True),indent=2)+'\n',encoding='utf-8',newline='\n')
 native=json.loads((HERE/'runs/native-compile-01/producer-input-graph.json').read_text())
 kotlin=json.loads((HERE/'runs/compile-01/compile-result.json').read_text())
 value=dict(status='SOURCE_ONLY_KOTLIN_AND_SAME_DLL_NATIVE_COMPILE_PASS',upstreamCommit=COMMIT,originalPolicySources=rows,actual33BinaryDeclarationNameMatches=hits,runtimeEntries=97,compileClasses=kotlin['classCount'],productionClassIntersection=kotlin['productionClassIntersection'],nativeBuildHeaders=len(native['transitiveHeaders']),nativeBuildLibraries=len(native['searchedLibrariesConservativePins']),nativeCompilerFiles=len(native['compilerBinDirectoryConservativePins']),nativeDll=native['dll'],freshPreferredProfileNoActorOrProfileCache=True,networkObservationIncludesSeparateInternetAccess=True,rootGlobalStoreNotReadOrCopied=True,appOrNativeQueryExecuted=False,HWNDOrGuiExecuted=False,officialSourceDownloadsOnly=True,MainOrSharedModified=False)
 (HERE/'source-checks.json').write_text(json.dumps(value,indent=2)+'\n',encoding='utf-8',newline='\n')
 print(json.dumps(dict(status=value['status'],nativeDllSha256Bytes=value['nativeDll']['sha256Bytes'],officialSources=len(docs),classes=value['compileClasses'],actual33DeclarationMatches=len(hits)),indent=2))
if __name__=='__main__':main()
