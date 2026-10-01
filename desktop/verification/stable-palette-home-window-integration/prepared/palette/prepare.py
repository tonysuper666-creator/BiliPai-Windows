from pathlib import Path
import hashlib,json,re,zipfile,subprocess,difflib
LANE=Path(__file__).resolve().parent;MAIN=LANE.parents[2];REPO=MAIN.parent/'BiliPai-v023';COMMIT='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
def read(p):return p.read_text(encoding='utf-8').replace('\r\n','\n')
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
def write(p,s):p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s,encoding='utf-8',newline='\n')
def js(p,v):write(p,json.dumps(v,ensure_ascii=False,indent=2))
path='app/src/main/java/com/android/purebilibili/feature/home/components/cards/WallpaperPaletteStore.kt'
original=subprocess.run(['git','show',COMMIT+':'+path],cwd=REPO,capture_output=True,check=True).stdout.decode().replace('\r\n','\n');assert original==read(REPO/path)
write(LANE/'original-stable'/path,original)
jar=MAIN/'desktop/.local/settings-home-full-card-parity/dependencies/palette-1.0.0-sources.jar'
with zipfile.ZipFile(jar) as z:
 targets=[n for n in z.namelist() if n.endswith('/Target.java')];palettes=[n for n in z.namelist() if n.endswith('/Palette.java')]
 assert len(targets)==len(palettes)==1
 target=z.read(targets[0]).decode().replace('\r\n','\n');palette=z.read(palettes[0]).decode().replace('\r\n','\n')
for name,s in [('Target.java',target),('Palette.java',palette)]:write(LANE/'official-palette-1.0.0'/name,s)
assert palette==read(REPO/'desktop/third-party/home-card-palette/upstream/androidx/palette/graphics/Palette.java')
adapt=re.sub(r'(?m)^import androidx.annotation.\w+;\n','',target)
adapt=re.sub(r'@(?:NonNull|Nullable)\s*','',adapt)
adapt=re.sub(r'@FloatRange\([^)]*\)\s*','',adapt)
adapt=adapt.replace('package androidx.palette.graphics;','package com.bilipai.desktop.palette;')
adapt=re.sub(r'\bTarget\b','DesktopPaletteTarget',adapt)
write(LANE/'prepared/manual-java/com/bilipai/desktop/palette/DesktopPaletteTarget.java',adapt)
def method(name):
 match=re.search(r'(?m)^    (?:private )?(?:void|Swatch|boolean|float) '+name+r'\(',palette);assert match,name
 begin=match.start();cur=palette.index('{',begin);depth=1;i=cur+1
 while depth:
  if palette[i]=='{':depth+=1
  elif palette[i]=='}':depth-=1
  i+=1
 return palette[begin:i]
methods='\n\n'.join(method(n) for n in ['generate','generateScoredTarget','getMaxScoredSwatchForTarget','shouldBeScoredForTarget','generateScore','findDominantSwatch'])
methods=re.sub(r'\bTarget\b','DesktopPaletteTarget',methods)
methods=methods.replace('mUsedColors.append(maxScoreSwatch.getRgb(), true);','mUsedColors.add(maxScoreSwatch.getRgb());').replace('mUsedColors.get(swatch.getRgb())','mUsedColors.contains(swatch.getRgb())')
scoring=palette[:palette.index('package androidx.palette.graphics;')]+'''/* Original Palette1.0.0 target scoring, not another quantizer. */
package com.bilipai.desktop.palette;
import com.bilipai.desktop.palette.DesktopPalette.Swatch;
import java.util.*;
public final class DesktopWallpaperPaletteScoring {
 private final List<Swatch> mSwatches;
 private final List<DesktopPaletteTarget> mTargets;
 private final Map<DesktopPaletteTarget,Swatch> mSelectedSwatches=new HashMap<>();
 private final Set<Integer> mUsedColors=new HashSet<>();
 private final Swatch mDominantSwatch;
 public DesktopWallpaperPaletteScoring(List<Swatch> swatches) {
  mSwatches=swatches;
  mTargets=Arrays.asList(DesktopPaletteTarget.LIGHT_VIBRANT,DesktopPaletteTarget.VIBRANT,DesktopPaletteTarget.DARK_VIBRANT,DesktopPaletteTarget.LIGHT_MUTED,DesktopPaletteTarget.MUTED,DesktopPaletteTarget.DARK_MUTED);
  mDominantSwatch=findDominantSwatch();generate();
 }
 public Swatch getVibrantSwatch(){return mSelectedSwatches.get(DesktopPaletteTarget.VIBRANT);}
 public Swatch getLightVibrantSwatch(){return mSelectedSwatches.get(DesktopPaletteTarget.LIGHT_VIBRANT);}
 public Swatch getDarkVibrantSwatch(){return mSelectedSwatches.get(DesktopPaletteTarget.DARK_VIBRANT);}
 public Swatch getMutedSwatch(){return mSelectedSwatches.get(DesktopPaletteTarget.MUTED);}
 public Swatch getDominantSwatch(){return mDominantSwatch;}
'''+methods+'\n}\n'
write(LANE/'prepared/manual-java/com/bilipai/desktop/palette/DesktopWallpaperPaletteScoring.java',scoring)
store=original
store=re.sub(r'(?m)^import (?:android\.[^\n]+|androidx.palette.graphics.Palette|coil3\.[^\n]+|java.io.File)\n','',store)
store=store.replace('object WallpaperPaletteStore {','internal class WallpaperPaletteStore : AutoCloseable {')
store=store.replace('LruCache<String, WallpaperPalette>(MAX_CACHE_SIZE)','com.bilipai.desktop.ui.DesktopWallpaperPaletteLru<WallpaperPalette>(MAX_CACHE_SIZE)')
store=store.replace('context: Context','context: com.bilipai.desktop.ui.DesktopWallpaperPaletteContext')
start=store.index('    fun loadWallpaperPalette(');end=store.index('    private fun createDefaultThemePalette()',start)
store=store[:start]+read(LANE/'store-methods.template.kt')+'\n'+store[end:]
store=store.replace('    fun clearCache() {\n','    fun clearCache() {\n        synchronized(requestGate) { generation++;activeJob?.cancel();activeJob=null }\n')
store=store.replace('\n    fun clear() {','\n    override fun close() { synchronized(requestGate) { closed=true };clear() }\n\n    fun clear() {')
store=store.replace('    private const val MAX_CACHE_SIZE = 8','    private val MAX_CACHE_SIZE = 8\n    private val requestGate=Any()\n    private var generation=0L\n    private var activeJob:kotlinx.coroutines.Job?=null\n    private var closed=false')
store=store.replace('import kotlinx.coroutines.launch','import kotlinx.coroutines.launch\nimport kotlinx.coroutines.CoroutineStart\nimport kotlinx.coroutines.CancellationException\nimport kotlinx.coroutines.currentCoroutineContext\nimport kotlinx.coroutines.ensureActive')
write(LANE/'prepared/generated/com/android/purebilibili/feature/home/components/cards/WallpaperPaletteStore.kt',store)
js(LANE/'source-pins.json',{'upstreamPath':path,'upstreamCommit':COMMIT,'originalSha256LF':sha(original),'officialSourcesJar':str(jar),'officialSourcesJarSha256Bytes':hashlib.sha256(jar.read_bytes()).hexdigest(),'officialTargetSha256LF':sha(target),'officialPaletteSha256LF':sha(palette)})
for name,before,after in [('WallpaperPaletteStore',original,store),('DesktopPaletteTarget',target,adapt)]:
 changes=[];a=before.splitlines(True);b=after.splitlines(True)
 for tag,i1,i2,j1,j2 in difflib.SequenceMatcher(None,a,b,autojunk=False).get_opcodes():
  if tag!='equal':changes.append({'tag':tag,'originalRange':[i1,i2],'preparedRange':[j1,j2],'before':''.join(a[i1:i2]),'after':''.join(b[j1:j2])})
 reverse=b[:]
 for h in reversed(changes):reverse[h['preparedRange'][0]:h['preparedRange'][1]]=a[h['originalRange'][0]:h['originalRange'][1]]
 assert ''.join(reverse)==before
 js(LANE/'reverse-adapters'/(name+'.json'),{'originalSha256LF':sha(before),'preparedSha256LF':sha(after),'changes':changes})
js(LANE/'selection-methods.json',{'officialSelectedMethods':['generate','generateScoredTarget','getMaxScoredSwatchForTarget','shouldBeScoredForTarget','generateScore','findDominantSwatch'],'onlyCollectionAndTargetNameSeams':True,'secondQuantizer':False,'sourceOriginalSha256LF':sha(palette)})
print('prepared original Store + full Target + original scoring')
