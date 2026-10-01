from pathlib import Path
import hashlib,importlib.util,json,re,subprocess,zipfile
LANE=Path(__file__).resolve().parent;MAIN=LANE.parents[2];REPO=MAIN.parent/'BiliPai-v023';TAG='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
sha=lambda b:hashlib.sha256(b).hexdigest()
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
spec=importlib.util.spec_from_file_location('parser',REPO/'desktop/tools/sync-upstream.py');parser=importlib.util.module_from_spec(spec);spec.loader.exec_module(parser)
rows=[];deltas=[];sources={}
for name,path in [('application','app/src/main/java/com/android/purebilibili/app/PureApplication.kt'),('runtime','app/src/main/java/com/android/purebilibili/app/PureApplicationRuntimeConfig.kt'),('background','app/src/main/java/com/android/purebilibili/core/lifecycle/BackgroundMemoryTrimPolicy.kt')]:
 raw=(REPO/path).read_bytes().replace(b'\r\n',b'\n');blob=subprocess.check_output(['git','show',TAG+':'+path],cwd=REPO).replace(b'\r\n',b'\n');assert raw==blob
 p=wide(LANE/'original-stable'/path);p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(blob);sources[name]=blob.decode()
 rows.append(dict(path=path,sha256=sha(blob),mode='policy-extract',features=['original-application-image-loader']))
def function_block(source,name):
 match=list(re.finditer(r'(?m)^\s*(?:override\s+)?fun\s+'+re.escape(name)+r'\(',source));assert len(match)==1
 begin=match[0].start();tokens=[t for t in parser.kotlin_tokens(source) if t[1]>=begin];start=next(i for i,t in enumerate(tokens)if t[0]=='{');depth=1;i=start
 while depth:i+=1;depth+=(tokens[i][0]=='{')-(tokens[i][0]=='}')
 return source[begin:tokens[i][2]].strip()
body=function_block(sources['application'],'newImageLoader')
def replace(before,after):
 global body
 assert body.count(before)==1,before;body=body.replace(before,after,1);deltas.append(dict(before=before,after=after))
replace('override fun newImageLoader(context: android.content.Context): ImageLoader {','internal fun newImageLoader(context: coil3.PlatformContext, ownedCallFactory: okhttp3.Call.Factory, cacheDir: java.io.File): ImageLoader {')
replace('PureApplicationRuntimeConfig.resolveImageMemoryCachePercent()','resolveOriginalImageMemoryCachePercent()')
replace('ImageLoader.Builder(this)','ImageLoader.Builder(context)')
replace('callFactory = { NetworkModule.okHttpClient }','callFactory = { ownedCallFactory }')
replace('''if (Build.VERSION.SDK_INT >= 28) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }''','''// The existing Coil/Skia decoder provides Windows still images.
                // Animated wallpaper/preview callers use the existing owned Skia GIF actor;
                // neither Android decoder is a Windows service.''')
replace('''            //  允许适用图片使用 RGB_565，降低内存占用
            .allowRgb565(true)
''','''            // RGB_565 is an Android bitmap allocation hint; Skia owns Windows pixels.
''')
replace('''            .also { _imageLoader = it }  // 保存引用''','''            // The one Windows application owner retains this returned exact loader.''')
decl='fun resolveImageMemoryCachePercent(): Double = 0.10';assert sources['runtime'].count(decl)==1
imports='''package com.android.purebilibili.app
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.network.cachecontrol.CacheControlCacheStrategy
import coil3.disk.directory
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.addLastModifiedToFileCacheKey
import coil3.request.maxBitmapSize
import coil3.request.crossfade
@OptIn(coil3.annotation.ExperimentalCoilApi::class)
'''
p=wide(LANE/'prepared/generated/com/android/purebilibili/app/DesktopOriginalApplicationImageLoader.kt');p.parent.mkdir(parents=True,exist_ok=True);p.write_text(imports+body+'\n\ninternal '+decl.replace('resolveImageMemoryCachePercent','resolveOriginalImageMemoryCachePercent')+'\n',encoding='utf-8',newline='\n')
background=sources['background'];end=background.index('/**');selected=background[background.index('internal const val BACKGROUND_IMAGE_TRIM_DELAY_MS'):end].rstrip()
p=wide(LANE/'prepared/generated/com/android/purebilibili/core/lifecycle/DesktopOriginalBackgroundImageTrimPolicy.kt');p.parent.mkdir(parents=True,exist_ok=True);p.write_text('package com.android.purebilibili.core.lifecycle\n\n'+selected+'\n',encoding='utf-8',newline='\n')
jar=LANE/'coil-network-cache-control-jvm-3.5.0-sources.jar';assert sha(jar.read_bytes())=='bc0d4c05573187c53a32f0b31a05a73f5af734a576814d52b3e227e0b71ea9db'
external=[]
with zipfile.ZipFile(jar)as z:
 for name in z.namelist():
  if not name.endswith('.kt'):continue
  data=z.read(name);p=wide(LANE/'prepared/coil-upstream'/name);p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(data);external.append(dict(path=name,sha256Bytes=sha(data),modified=False))
assert len(external)==3
(LANE/'source-receipt.json').write_text(json.dumps(dict(stableCommit=TAG,originalRegistryRows=rows,originalMethod='PureApplication.newImageLoader full body',originalRuntimeMethod='resolveImageMemoryCachePercent full declaration, uniquely renamed',originalBackgroundDeclarations='all three original constants and two original pure functions',exactMethodReplacements=deltas,externalSourceArtifactUrl='https://repo.maven.apache.org/maven2/io/coil-kt/coil3/coil-network-cache-control-jvm/3.5.0/coil-network-cache-control-jvm-3.5.0-sources.jar',externalSourceArtifactSha256Bytes=sha(jar.read_bytes()),externalSources=external,newDependencyJars=0,sourceAndLicenseHeadersUnchanged=True,fullAndroidApplicationPorted=False),ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print('Prepared full original newImageLoader, 0.10 budget, five original trim declarations and three unchanged Coil cache-control sources')
