"""Full original v0.2.3 WallpaperPaletteStore; required owned Windows context.
The schema and original quantizer are supplied by their existing sole producers.
No external dependency, settings namespace or image/network owner is created.
"""
from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import argparse,hashlib,json,re,subprocess
COMMIT='79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40'
PATH='app/src/main/java/com/android/purebilibili/feature/home/components/cards/WallpaperPaletteStore.kt'
ORIGINAL_SHA='521e632d1b8f3655e862abbeb3624bfaabf144fd8274f5315a2f9d646adb2219'
PREPARED_SHA='be96484050dfea039e5e0cb687712fa00c5a3e5940e0fc707a400749f7f3cf36'

def digest(text):return hashlib.sha256(text.encode()).hexdigest()
def write(path,text):
 path.parent.mkdir(parents=True,exist_ok=True);path.write_text(text,encoding='utf-8',newline='\n')
def adapt(original):
 store=original
 store=re.sub(r'(?m)^import (?:android\.[^\n]+|androidx.palette.graphics.Palette|coil3\.[^\n]+|java.io.File)\n','',store)
 store=store.replace('object WallpaperPaletteStore {','internal class WallpaperPaletteStore : AutoCloseable {')
 store=store.replace('LruCache<String, WallpaperPalette>(MAX_CACHE_SIZE)','com.bilipai.desktop.ui.DesktopWallpaperPaletteLru<WallpaperPalette>(MAX_CACHE_SIZE)')
 store=store.replace('context: Context','context: com.bilipai.desktop.ui.DesktopWallpaperPaletteContext')
 start=store.index('    fun loadWallpaperPalette(');end=store.index('    private fun createDefaultThemePalette()',start)
 store=store[:start]+'    fun loadWallpaperPalette(context:com.bilipai.desktop.ui.DesktopWallpaperPaletteContext,uri:String,scope:CoroutineScope) {\n        if(!context.isOwned() || scope.coroutineContext[kotlinx.coroutines.Job]?.isActive==false)return\n        val version=synchronized(requestGate) {\n            if(closed)return\n            generation++;activeJob?.cancel();activeJob=null;generation\n        }\n        fun publish(palette:WallpaperPalette,cache:Boolean) {\n            context.commitIfOwned {\n                synchronized(requestGate) {\n                    if(!closed && generation==version && scope.coroutineContext[kotlinx.coroutines.Job]?.isActive!=false) {\n                        if(cache)synchronized(paletteCache) {paletteCache.put(uri,palette)}\n                        _currentPalette.value=palette\n                    }\n                }\n            }\n        }\n        val cached=getCachedPalette(uri)\n        if(cached!=null) {publish(cached,false);return}\n        val job=scope.launch(Dispatchers.Default,start=CoroutineStart.LAZY) {\n            val palette=if(uri.isBlank())extractSystemWallpaperPalette(context) ?: createDefaultThemePalette()\n                else extractWallpaperPaletteFromUri(context,uri) ?: extractSystemWallpaperPalette(context) ?: createDefaultThemePalette()\n            currentCoroutineContext().ensureActive()\n            publish(palette,uri.isNotBlank())\n        }\n        val stillOwned=context.isOwned()\n        synchronized(requestGate) {\n            if(closed || generation!=version || !stillOwned)job.cancel()\n            else {activeJob=job;job.start()}\n        }\n    }\n\n    private suspend fun extractSystemWallpaperPalette(context:com.bilipai.desktop.ui.DesktopWallpaperPaletteContext):WallpaperPalette? {\n        return try {\n            val system=context.systemWallpaper()\n            val image=system.fileUri?.let {extractWallpaperPaletteFromUri(context,it)}\n            image ?: system.desktopArgb?.let {argb ->Color(argb).let {primary ->WallpaperPalette(primary,primary,primary,listOf(primary,primary,primary))}}\n        } catch(cancelled:CancellationException) {throw cancelled}\n        catch(failure:Exception) {currentCoroutineContext().ensureActive();if(!context.isOwned())throw CancellationException("Wallpaper owner retired");null}\n    }\n\n    private suspend fun extractWallpaperPaletteFromUri(context:com.bilipai.desktop.ui.DesktopWallpaperPaletteContext,uri:String):WallpaperPalette? {\n        return try {\n            val bitmap=context.readOwnedPixels(uri) ?: return null\n            val stops = mutableListOf<Color>()\n            val sliceCount = 5\n            val sliceHeight = (bitmap.height / sliceCount).coerceAtLeast(1)\n            for (i in 0 until sliceCount) {\n                currentCoroutineContext().ensureActive()\n                if(!context.isOwned())throw CancellationException("Wallpaper owner retired")\n                val sliceTop = (i * sliceHeight).coerceIn(0, bitmap.height - 1)\n                val sliceBottom = ((i + 1) * sliceHeight).coerceIn(sliceTop + 1, bitmap.height)\n                val palette = bitmap.paletteForOriginalRegion(sliceTop,sliceBottom)\n                val colorInt = palette.vibrantSwatch?.rgb\n                    ?: palette.lightVibrantSwatch?.rgb\n                    ?: palette.darkVibrantSwatch?.rgb\n                    ?: palette.mutedSwatch?.rgb\n                    ?: palette.dominantSwatch?.rgb\n                    ?: bitmap.getPixel(bitmap.width / 2, (sliceTop + sliceBottom) / 2)\n                stops.add(Color(colorInt))\n            }\n            WallpaperPalette(\n                topColor = stops.first(),\n                bottomColor = stops.last(),\n                dominantColor = stops[stops.size / 2],\n                stops = stops\n            )\n        } catch(cancelled:CancellationException) {throw cancelled}\n        catch(failure:Exception) {currentCoroutineContext().ensureActive();if(!context.isOwned())throw CancellationException("Wallpaper owner retired");null}\n    }\n'+'\n'+store[end:]
 store=store.replace('    fun clearCache() {\n','    fun clearCache() {\n        synchronized(requestGate) { generation++;activeJob?.cancel();activeJob=null }\n')
 store=store.replace('\n    fun clear() {','\n    override fun close() { synchronized(requestGate) { closed=true };clear() }\n\n    fun clear() {')
 store=store.replace('    private const val MAX_CACHE_SIZE = 8','    private val MAX_CACHE_SIZE = 8\n    private val requestGate=Any()\n    private var generation=0L\n    private var activeJob:kotlinx.coroutines.Job?=null\n    private var closed=false')
 store=store.replace('import kotlinx.coroutines.launch','import kotlinx.coroutines.launch\nimport kotlinx.coroutines.CoroutineStart\nimport kotlinx.coroutines.CancellationException\nimport kotlinx.coroutines.currentCoroutineContext\nimport kotlinx.coroutines.ensureActive')
 return store

def main():
 parser=argparse.ArgumentParser();parser.add_argument('--source-repo',required=True);parser.add_argument('--output-dir',required=True);args=parser.parse_args()
 repo=Path(args.source_repo).resolve();out=Path(args.output_dir).resolve()
 manifest=json.loads((repo/'desktop/upstream-sources.json').read_text(encoding='utf-8'))
 assert manifest['upstreamCommit']==COMMIT,'WallpaperPalette stable source identity changed'
 original=(_desktop_canonical_source(repo, PATH)).read_text(encoding='utf-8').replace('\r\n','\n')
 assert digest(original)==ORIGINAL_SHA,'WallpaperPalette source content changed'
 blob=subprocess.run(['git','show',COMMIT+':'+PATH],cwd=repo,capture_output=True,check=True).stdout.decode().replace('\r\n','\n')
 assert blob==original,'WallpaperPalette source differs from pinned Git blob'
 rows=[r for r in manifest['sources'] if r['path']==PATH]
 assert len(rows)==1 and rows[0]['sha256']==ORIGINAL_SHA and rows[0]['mode']=='policy-extract','WallpaperPalette sole registry row missing/different'
 store=adapt(original);assert digest(store)==PREPARED_SHA,'WallpaperPalette Windows adaptation changed'
 write(out/'com/android/purebilibili/feature/home/components/cards/WallpaperPaletteStore.kt',store)
 proof={'pinnedCommit':COMMIT,'originalPath':PATH,'originalSha256LF':ORIGINAL_SHA,'preparedSha256LF':PREPARED_SHA,'wholeOriginalObjectRetained':True,'windowsOwnedInstance':True,'schemaAndQuantizerProduced':False,'generatedSources':1}
 write(out/'wallpaper-palette-selection-proof.json',json.dumps(proof,indent=2))
 print('Generated full original WallpaperPaletteStore with owned Windows services')
if __name__=='__main__':main()
