from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import argparse,hashlib,importlib.util,json,re,subprocess
arguments=argparse.ArgumentParser();arguments.add_argument('--repo',required=True);arguments.add_argument('--output',required=True);args=arguments.parse_args()
REPO=Path(args.repo);LANE=Path(args.output);TAG='79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40'
LANE.mkdir(parents=True,exist_ok=True)
SOURCE_PINS={'app/src/main/java/com/android/purebilibili/app/PureApplication.kt': '70c522671e5e23941d8179caa905d263c52017e9ab2a06be29598cd7fceb6607', 'app/src/main/java/com/android/purebilibili/app/PureApplicationRuntimeConfig.kt': 'b332821a90253f2cfee7dbdd1845d4708dad8e584878ac53da4f4dccdc9f2582', 'app/src/main/java/com/android/purebilibili/core/lifecycle/BackgroundMemoryTrimPolicy.kt': 'd4a95f3372db9b4434f21cdeeed67f96eac0ab456379d280e040c527b8452d88'}
sha=lambda b:hashlib.sha256(b).hexdigest()
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
spec=importlib.util.spec_from_file_location('parser',REPO/'desktop/tools/sync-upstream.py');parser=importlib.util.module_from_spec(spec);spec.loader.exec_module(parser)
rows=[];deltas=[];sources={}
for name,path in [('application','app/src/main/java/com/android/purebilibili/app/PureApplication.kt'),('runtime','app/src/main/java/com/android/purebilibili/app/PureApplicationRuntimeConfig.kt'),('background','app/src/main/java/com/android/purebilibili/core/lifecycle/BackgroundMemoryTrimPolicy.kt')]:
 raw=(_desktop_canonical_source(REPO, path)).read_bytes().replace(b'\r\n',b'\n');blob=subprocess.check_output(['git','show',TAG+':'+path],cwd=REPO).replace(b'\r\n',b'\n');assert raw==blob
 assert sha(blob)==SOURCE_PINS[path];sources[name]=blob.decode()
 rows.append(dict(path=path,sha256=sha(blob),mode='policy-extract',features=['original-application-image-loader']))
def function_block(source,name):
 match=list(re.finditer(r'(?m)^\s*(?:override\s+)?fun\s+'+re.escape(name)+r'\(',source));assert len(match)==1
 begin=match[0].start();tokens=[t for t in parser.kotlin_tokens(source) if t[1]>=begin];start=next(i for i,t in enumerate(tokens)if t[0]=='{');depth=1;i=start
 while depth:i+=1;depth+=(tokens[i][0]=='{')-(tokens[i][0]=='}')
 return source[begin:tokens[i][2]].strip()
body=function_block(sources['application'],'newImageLoader')
def replace(before,after):
 global body
 assert body.count(before)==1,before;index=body.index(before);body=body.replace(before,after,1);deltas.append(dict(index=index,before=before,after=after))
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
inverse=body
for delta in reversed(deltas):
 index=delta['index'];after=delta['after'];assert inverse[index:index+len(after)]==after
 inverse=inverse[:index]+delta['before']+inverse[index+len(after):]
assert inverse==function_block(sources['application'],'newImageLoader')
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
p=wide(LANE/'com/android/purebilibili/app/DesktopOriginalApplicationImageLoader.kt');p.parent.mkdir(parents=True,exist_ok=True);p.write_text(imports+body+'\n\ninternal '+decl.replace('resolveImageMemoryCachePercent','resolveOriginalImageMemoryCachePercent')+'\n',encoding='utf-8',newline='\n')
background=sources['background'];end=background.index('/**');selected=background[background.index('internal const val BACKGROUND_IMAGE_TRIM_DELAY_MS'):end].rstrip()
p=wide(LANE/'com/android/purebilibili/core/lifecycle/DesktopOriginalBackgroundImageTrimPolicy.kt');p.parent.mkdir(parents=True,exist_ok=True);p.write_text('package com.android.purebilibili.core.lifecycle\n\n'+selected+'\n',encoding='utf-8',newline='\n')

(LANE/'producer-receipt.json').write_text(json.dumps(dict(stableCommit=TAG,originalRegistryRows=rows,exactMethodReplacements=deltas,newClients=0,fullAndroidApplicationPorted=False),ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print('Complete original application ImageLoader body and original cache/trim policies generated')
