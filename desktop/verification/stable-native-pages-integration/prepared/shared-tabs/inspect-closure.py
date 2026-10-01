from pathlib import Path
import hashlib,importlib.util,json,re,subprocess,sys,zipfile
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent
MAIN=next(p for p in HERE.parents if (p/'.git').exists());REPO=MAIN.parent/'BiliPai-v023';SNAP=MAIN/'desktop/.local/stable-product-snapshot-11'
COMMIT='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589';BASE='app/src/main/java/com/android/purebilibili/'
PATHS=[BASE+'core/ui/components/'+n+'.kt' for n in ['AppLiquidAwareTabRow','TabSelectionScroll','LiquidDockViewport']]
PATHS += [BASE+'feature/home/components/'+n+'.kt' for n in ['BottomBarLiquidSegmentedControl','BottomBarFloatingSegmentedControl','FloatingBottomBar','FloatingBottomBarGeometry','BottomBarMatchedLiquidChrome','FloatingDockChrome','LiquidGlassTuning','LiquidGlassShader','LiquidGlassSelectionContentPolicy','LiquidGlassAdaptiveReadability']]
PATHS += [BASE+'feature/home/components/miuix/'+n+'.kt' for n in ['InteractiveHighlight','InteractiveHighlightPalette','InteractiveHighlightMotionSpec','DragGestureInspector','DampedDragAnimation']]
PATHS += [BASE+'feature/home/components/liquid/'+n+'.kt' for n in ['Lens','Vibrancy','InnerShadow','CombinedBackdrop']]
def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def write(p,s):safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(s,encoding='utf-8',newline='\n')
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def main():
 with zipfile.ZipFile(SNAP/'main-kotlin.jar') as z:names=z.namelist()
 actual=sorted(n[:-6].replace('/','.') for n in names if n.endswith('.class') and '$' not in n and any(n.startswith(x) for x in ['com/android/purebilibili/feature/home/components/','com/android/purebilibili/core/store/','com/android/purebilibili/core/ui/components/','com/android/purebilibili/core/ui/animation/']))
 rows=[]
 for p in PATHS:
  text=read(REPO/p);original=subprocess.check_output(['git','show',COMMIT+':'+p],cwd=REPO).decode().replace('\r\n','\n');assert text==original
  write(HERE/'original-source'/p,text)
  rows.append(dict(path=p,sha256LF=hashlib.sha256(text.encode()).hexdigest(),lineCount=len(text.splitlines()),platformRefs=[dict(line=i+1,text=line.strip()) for i,line in enumerate(text.splitlines()) if any(x in line for x in ['import android.','LocalContext','LocalConfiguration','SettingsManager','HomeSettings','Build.VERSION','SystemClock','ShaderBrush','RuntimeShader'])]))
 spec=importlib.util.spec_from_file_location('existing_compiler',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
 javap=c.JAVA.parent/'javap.exe'
 r=subprocess.run([str(javap),'-p','-s','-classpath',str(SNAP/'main-kotlin.jar')]+actual,capture_output=True,text=True,encoding='utf-8',timeout=90)
 assert r.returncode==0;write(HERE/'actual-stable11-symbols.javap.txt',r.stdout)
 result=dict(sourceCount=len(rows),originalCommit=COMMIT,sources=rows,actualStable11Classes=actual,actualMainKotlinSha256Bytes=hashlib.sha256((SNAP/'main-kotlin.jar').read_bytes()).hexdigest())
 write(HERE/'initial-closure-inventory.json',json.dumps(result,ensure_ascii=False,indent=2)+'\n')
 print(json.dumps(dict(sourceCount=len(rows),originalLines=sum(x['lineCount'] for x in rows),actualClasses=len(actual))));print('\n'.join(actual))
if __name__=='__main__':main()
