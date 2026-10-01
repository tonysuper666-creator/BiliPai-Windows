from pathlib import Path
import hashlib, importlib.util, json, re, subprocess, sys, zipfile
sys.dont_write_bytecode=True
P=Path(__file__).resolve().parent; MAIN=P.parents[2]; REPO=MAIN.parent/'BiliPai-v023'
COMMIT='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def write(p,b):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes(b.encode()if isinstance(b,str)else b)
def sha(b):return hashlib.sha256(b.encode()if isinstance(b,str)else b).hexdigest()
s=importlib.util.spec_from_file_location('section_policy_selector',MAIN/'desktop/.local/stable-video-player-full-controls-parity/prepare.py');c=importlib.util.module_from_spec(s);s.loader.exec_module(c)
path='app/src/main/java/com/android/purebilibili/feature/video/ui/section/VideoPlayerSectionPolicy.kt';t=subprocess.check_output(['git','show',COMMIT+':'+path],cwd=REPO).replace(b'\r\n',b'\n').decode();write(P/'original-stable'/path,t)
jar=MAIN/'desktop/.local/stable-product-snapshot-50/main-kotlin.jar';package='com/android/purebilibili/feature/video/ui/section/'
with zipfile.ZipFile(wide(jar))as z: actual=set(z.namelist());wrappers=[n[:-6].replace('/','.')for n in actual if n.startswith(package)and n.endswith('Kt.class')and'$'not in n]
javap=MAIN.parent/'toolchain/jdk/jdk-21.0.12.1+1/bin/javap.exe'
raw=subprocess.check_output([str(javap),'-p','-classpath',str(jar),*sorted(wrappers)],encoding='utf-8');write(P/'actual50-section-methods.txt',raw)
methods=set(re.findall(r'public static[^\n]*\s(\w+)\(',raw));fields=set(re.findall(r'public static[^\n]*\s(\w+);',raw))
tokens=c.parser.kotlin_tokens(t);depth=parens=brackets=0;starts=[]
for i,(word,a,b)in enumerate(tokens):
 if depth==parens==brackets==0 and word in ['fun','val','var','class','object','interface']:
  name=tokens[i+1][0];line=t.rfind('\n',0,a)+1;starts.append((name,line,word))
 depth+=(word=='{')-(word=='}');parens+=(word=='(')-(word==')');brackets+=(word=='[')-(word==']')
chosen=[];skip=[];chunks=[]
for i,(name,a,kind)in enumerate(starts):
 chunk=t[a:starts[i+1][1]if i+1<len(starts)else len(t)]
 existing=(kind in ['class','interface','object']and package+name+'.class'in actual)or(kind=='fun'and name in methods)or(kind in['val','var']and(name in fields or 'get'+name[0].upper()+name[1:]in methods))
 android=any(word in ['PlayerView','SurfaceView','TextureView'] for word,_,_ in c.parser.kotlin_tokens(chunk))
 if existing or android:skip.append(dict(name=name,reason='same-original-declaration-already-actual50'if existing else'Android-view-transport-requires-owned-Windows-port',originalLF=sha(chunk)));continue
 chosen.append(name);chunks.append(chunk)
header=t[:t.index('\ninternal const val INITIAL_PLAYER_CONTROLS_VISIBLE')]
header=re.sub(r'(?m)^@file:androidx.annotation.OptIn.*\n','',header)
header=re.sub(r'(?m)^import (?:android\.|androidx.media3.ui.PlayerView).*$\n','',header)
header=header.replace('import androidx.media3.common.Player','import com.bilipai.desktop.ui.DesktopOriginalMpvOverlayControl as Player').replace('import androidx.media3.common.PlaybackParameters','import com.bilipai.desktop.ui.DesktopOriginalPlaybackRate as PlaybackParameters')
write(P/'section-generated/com/android/purebilibili/feature/video/ui/section/DesktopOriginalVideoPlayerSectionPolicy.kt',header+'\n'+'\n'.join(chunks))
write(P/'section-policy-selection.json',json.dumps(dict(commit=COMMIT,path=path,sourceSha256LF=sha(t),selectedDeclarations=chosen,excludedDeclarations=skip,actual50MainJarSha256Bytes=sha(wide(jar).read_bytes()),policyBodies='Selected original whole declarations; only imports alias the existing owned MPV/platform. Existing same-package declarations referenced.'),indent=2)+'\n')
print(json.dumps(dict(selected=len(chosen),excluded=len(skip))))
