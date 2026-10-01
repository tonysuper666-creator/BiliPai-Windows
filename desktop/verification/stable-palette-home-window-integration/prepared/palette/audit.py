from pathlib import Path
import json,hashlib,subprocess,re,zipfile,os,importlib.util
LANE=Path(__file__).resolve().parent;MAIN=LANE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
def safe(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def lfsha(p):return hashlib.sha256(read(p).encode()).hexdigest()
def write(p,s):safe(p).parent.mkdir(parents=True,exist_ok=True);safe(p).write_text(s,encoding='utf-8',newline='\n')
def js(p,v):write(p,json.dumps(v,ensure_ascii=False,indent=2))
checks=[]
def expect(ok,label):assert ok,label;checks.append(label)
pins=json.loads(read(LANE/'source-pins.json'));path=pins['upstreamPath'];commit=pins['upstreamCommit']
original=read(LANE/'original-stable'/path);prepared=read(LANE/'prepared/generated/com/android/purebilibili/feature/home/components/cards/WallpaperPaletteStore.kt')
expect(hashlib.sha256(original.encode()).hexdigest()==pins['originalSha256LF'],'original full Store pin')
expect(subprocess.run(['git','show',commit+':'+path],cwd=REPO,capture_output=True,check=True).stdout.decode().replace('\r\n','\n')==original,'actual pinned Git full Store')
for name,before,after in [('WallpaperPaletteStore',original,prepared),('DesktopPaletteTarget',read(LANE/'official-palette-1.0.0/Target.java'),read(LANE/'prepared/manual-java/com/bilipai/desktop/palette/DesktopPaletteTarget.java'))]:
 receipt=json.loads(read(LANE/'reverse-adapters'/(name+'.json')));lines=after.splitlines(True)
 expect(hashlib.sha256(after.encode()).hexdigest()==receipt['preparedSha256LF'],name+' exact adapted hash')
 for row in reversed(receipt['changes']):
  a,b=row['preparedRange'];expect(''.join(lines[a:b])==row['after'],name+' exact adapter '+str(row['preparedRange']))
  lines[a:b]=row['before'].splitlines(True)
 expect(''.join(lines)==before,name+' full original recovered')
palette=read(LANE/'official-palette-1.0.0/Palette.java');scoring=read(LANE/'prepared/manual-java/com/bilipai/desktop/palette/DesktopWallpaperPaletteScoring.java')
def method(name):
 match=re.search(r'(?m)^    (?:private )?(?:void|Swatch|boolean|float) '+name+r'\(',palette);assert match,name
 start=match.start();cursor=palette.index('{',start);depth=1;end=cursor+1
 while depth:
  if palette[end]=='{':depth+=1
  elif palette[end]=='}':depth-=1
  end+=1
 return palette[start:end]
for name in json.loads(read(LANE/'selection-methods.json'))['officialSelectedMethods']:
 selected=re.sub(r'\bTarget\b','DesktopPaletteTarget',method(name)).replace('mUsedColors.append(maxScoreSwatch.getRgb(), true);','mUsedColors.add(maxScoreSwatch.getRgb());').replace('mUsedColors.get(swatch.getRgb())','mUsedColors.contains(swatch.getRgb())')
 expect(selected in scoring,'original exact scoring '+name)
expect(palette[:palette.index('package androidx.palette.graphics;')] in scoring,'selected scoring full Apache header')
expect('DesktopPaletteTarget.LIGHT_VIBRANT,DesktopPaletteTarget.VIBRANT,DesktopPaletteTarget.DARK_VIBRANT,DesktopPaletteTarget.LIGHT_MUTED,DesktopPaletteTarget.MUTED,DesktopPaletteTarget.DARK_MUTED' in scoring,'original six target order')
expect('val sliceCount = 5' in prepared and 'val sliceHeight = (bitmap.height / sliceCount).coerceAtLeast(1)' in prepared,'original five slice algorithm')
expect(original[original.index('    private fun createDefaultThemePalette()'):original.index('    fun clearCache()')]==prepared[prepared.index('    private fun createDefaultThemePalette()'):prepared.index('    fun clearCache()')],'original defaults full body')
platform=read(LANE/'prepared/manual/com/bilipai/desktop/ui/DesktopWallpaperPalettePlatform.kt')
expect('windowsMajor==null || windowsMajor>=10' in platform,'documented modern COLOR_DESKTOP unsupported')
expect('api.SystemParametersInfoW(0x0073,buffer.size,buffer,0)' in platform,'actual system wallpaper SPI effect')
expect('GetSysColorBrush' in platform and 'api.GetSysColor(1)' in platform,'actual supported color effect')
expect('DesktopPalette.quantize(pixels,8)' in platform,'sole installed quantizer and max8')
expect('val scale=scaledWidth/width.toDouble()' in platform and 'floor(top*scale)' in platform and 'ceil(bottom*scale)' in platform,'original width-ratio floor/ceil region')
expect(not re.search(r'\b(?:bitmap|rawBitmap)\.(?:close|recycle)\(',platform),'borrowed Coil Bitmap not closed')
expect('ImageLoader.Builder' not in platform and 'OkHttpClient' not in platform,'no second loader/HTTP')
expect('private val maximum:Int' in platform and 'size>maximum' in platform and 'MAX_CACHE_SIZE = 8' in prepared,'original bounded access-order8 cache')
expect('context.commitIfOwned {' in prepared and 'generation==version' in prepared and 'catch(cancelled:CancellationException) {throw cancelled}' in prepared,'owned publication generation/CE guard')

# Isolated replay of production CLI. Its Git reads use the actual repository object database.
# Only the copied source/merged manifest live here; shared registry/index remain untouched.
replay=LANE/'replay-repository';write(replay/path,original)
manifest=json.loads(read(REPO/'desktop/upstream-sources.json'));records=[r for r in manifest['sources'] if r['path']!=path]
records.append({k:v for k,v in json.loads(read(LANE/'registry-merge-recipe.json'))['records'][0].items() if k in ['path','sha256','mode','features']});manifest['sources']=records
js(replay/'desktop/upstream-sources.json',manifest)
env=os.environ.copy();env['GIT_DIR']=subprocess.run(['git','rev-parse','--absolute-git-dir'],cwd=REPO,capture_output=True,text=True,check=True).stdout.strip();env['GIT_WORK_TREE']=str(replay)
python=Path('C:/Users/TONYS/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe')
r=subprocess.run([str(python),str(LANE/'prepared/tools/extract-upstream-wallpaper-palette.py'),'--source-repo',str(replay),'--output-dir',str(LANE/'replay-production')],capture_output=True,text=True,encoding='utf-8',errors='replace',env=env,timeout=30)
write(LANE/'replay-production.log',r.stdout+r.stderr);expect(r.returncode==0,'production CLI replay pinned Git blob '+r.stderr)
expect(read(LANE/'replay-production/com/android/purebilibili/feature/home/components/cards/WallpaperPaletteStore.kt')==prepared,'production replay emits exact compiled Store')
js(LANE/'source-audit.json',{'checks':len(checks),'checksPassed':checks,'fullOriginalFilesRecovered':2,'officialMethodsExact':6,'productionReplayExact':True,'sharedSourcesModified':False})

# Same-package public static signatures supplement exact class-name collision checks.
defs={'__file__':str(LANE/'audit.py')};exec(read(REPO/'desktop/.local/stable-home-live-list-parity/audit_abi.py').split('aud=json.loads')[0],defs);parse=defs['parse']
cp=json.loads(read(LANE/'input-audit.json'))['verifiedDependencyPins'];actualclasses=set();actualmethods={}
for row in cp:
 expect(sha(row['path'])==row['sha256Bytes'],'actual97 dependency pin '+str(len(actualclasses)))
 with zipfile.ZipFile(safe(row['path'])) as z:
  for name in z.namelist():
   if name.endswith('.class'):
    actualclasses.add(name)
    if name.startswith(('com/android/purebilibili/','com/bilipai/desktop/')) and name.endswith('Kt.class'):
     for access,methodName,descriptor in parse(z.read(name)):
      if access&9==9:actualmethods.setdefault((name.rsplit('/',1)[0],methodName,descriptor),[]).append(name)
compiled=json.loads(read(LANE/'compile-06/compile-result.json'));jar=compiled['jar'];expect(sha(jar)==compiled['jarSha256Bytes'],'candidate jar exact')
methods=0;classes=[];invalid=[];overlap=[]
with zipfile.ZipFile(safe(jar)) as z:
 for name in z.namelist():
  if not name.endswith('.class'):continue
  assert name not in actualclasses,name;classes.append(name);members=parse(z.read(name));methods+=len(members)
  for access,methodName,descriptor in members:
   if any(x in methodName for x in '.;/[') or ('<' in methodName and methodName not in ['<init>','<clinit>']):invalid.append([name,methodName])
   if name.endswith('Kt.class') and access&9==9 and (name.rsplit('/',1)[0],methodName,descriptor) in actualmethods:overlap.append([name,methodName,descriptor])
assert not invalid and not overlap,(invalid,overlap)
loader='''import java.util.*;public class PaletteClassLoadProof {public static void main(String[] args)throws Exception{int n=0;for(String s:args){Class.forName(s,false,PaletteClassLoadProof.class.getClassLoader());n++;}System.out.println("PALETTE_CLASSES_LOADED "+n);}}'''
write(LANE/'classload-proof/PaletteClassLoadProof.java',loader)
spec=importlib.util.spec_from_file_location('auditorcompiler',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
runtime=[jar]+[r['path'] for r in cp];out=LANE/'classload-proof'
r=subprocess.run([str(c.JAVA.with_name('javac.exe')),'-encoding','UTF-8','-cp',';'.join(runtime),str(out/'PaletteClassLoadProof.java')],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=30);write(out/'compile.log',r.stdout+r.stderr);assert r.returncode==0,r.stderr
r=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-cp',';'.join([str(out)]+runtime),'PaletteClassLoadProof']+[n[:-6].replace('/','.') for n in classes],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=30);write(out/'run.log',r.stdout+r.stderr);assert r.returncode==0 and 'PALETTE_CLASSES_LOADED '+str(len(classes)) in r.stdout,r.stdout+r.stderr
result={'actualSnapshot':41,'strictRuntimeEntries':97,'candidateClasses':len(classes),'candidateMethods':methods,'classFqnOverlap':[],'samePackagePublicStaticMethodOverlap':overlap,'invalidJvmMethodNames':invalid,'loadedWithoutInitialization':len(classes),'overrides':0}
js(LANE/'abi-audit.json',result);print(json.dumps({'sourceChecks':len(json.loads(read(LANE/'source-audit.json'))['checksPassed']),'abi':result},indent=2))
