"""Construct the installable sole producer from reviewed task-only transformations.
No payload text is substituted for original source; production rereads pinned source.
"""
from pathlib import Path
import hashlib,importlib.util,json,re,subprocess,sys
sys.dont_write_bytecode=True
P=Path(__file__).resolve().parent;MAIN=P.parents[2];REPO=MAIN.parent/'BiliPai-v023';COMMIT='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589';BASE='app/src/main/java/com/android/purebilibili/'
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def read(p):return wide(p).read_bytes().replace(b'\r\n',b'\n').decode()
def write(p,t):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes(t.encode())
def sha(t):return hashlib.sha256(t.encode()if isinstance(t,str)else t).hexdigest()
helpers=json.loads(read(P/'section-helper-inventory.json'))['sources'];policy=json.loads(read(P/'section-policy-selection.json'));settings=json.loads(read(P/'section-settings-selection.json'));native=json.loads(read(P/'native-state-source-selection.json'))
paths={r['originalPath']for r in helpers}|{BASE+'feature/video/ui/section/'+n+'.kt'for n in ['VideoPlayerSection','VideoPlayerSectionContracts','VideoPlayerSectionPolicy']}|{settings['path'],BASE+'core/store/player/PlayerSettingsStore.kt',native['source']}
pins={}
for path in sorted(paths):
 b=subprocess.check_output(['git','show',COMMIT+':'+path],cwd=REPO).replace(b'\r\n',b'\n');pins[path]=dict(sha256LF=sha(b),gitBlob=subprocess.check_output(['git','rev-parse',COMMIT+':'+path],cwd=REPO,text=True).strip())
controlNames=sorted(set(re.findall(r'\bfun\s+(\w+)\s*\(',read(MAIN/'desktop/.local/stable-video-player-full-controls-parity/generated/com/android/purebilibili/core/store/DesktopOriginalVideoControlSettings.kt'))))
def function_part(text,name,nextName):return text[text.index('def '+name+'('):text.index('def '+nextName+'(')]
section=read(P/'prepare-section.py');render=section[section.index('def main():'):section.index("if __name__=='__main__'")]
render=render.replace('def main():','def render_section():')
render=render[:render.index(" write(P/'section-source-adaptations.json'")]
render=render.replace("match in re.findall(r'fun\\s+(\\w+)',read(MAIN/'desktop/.local/stable-video-player-full-controls-parity/generated/com/android/purebilibili/core/store/DesktopOriginalVideoControlSettings.kt').decode())",'match in EXISTING_CONTROL_NAMES')
settingsText=read(P/'prepare-section-settings.py');settingsBody=settingsText[settingsText.index("t=subprocess.check_output"):settingsText.index("contextPath=")]
settingsBody=settingsBody.replace("t=subprocess.check_output(['git','show',COMMIT+':'+SOURCE],cwd=REPO).replace(b'\\r\\n',b'\\n').decode()", "t=source('core/store/SettingsManager.kt')")
settingsBody=settingsBody.replace("sectionSource=read(P/'original-stable/app/src/main/java/com/android/purebilibili/feature/video/ui/section/VideoPlayerSection.kt').decode()", "sectionSource=source('feature/video/ui/section/VideoPlayerSection.kt')")
a=settingsBody.index('controlSource=');b=settingsBody.index('sectionNames=',a)
settingsBody=settingsBody[:a]+'existingControlNames=set(EXISTING_CONTROL_NAMES)\n'+settingsBody[b:]
settingsBody=settingsBody.replace("keyText=subprocess.check_output(['git','show',COMMIT+':'+keySource],cwd=REPO).replace(b'\\r\\n',b'\\n').decode()", "keyText=source('core/store/player/PlayerSettingsStore.kt')")
settingsBody=settingsBody.replace("write(P/'generated/com/android/purebilibili/core/store/DesktopOriginalPlayerSectionSettings.kt',", "emit('core/store/DesktopOriginalPlayerSectionSettings.kt',")
settingsBody=settingsBody.replace("write(P/'generated/com/android/purebilibili/core/store/DesktopOriginalPlayerInteractionSettings.kt',", "emit('core/store/DesktopOriginalPlayerInteractionSettings.kt',")
settingsBody='def render_settings():\n exec('+repr(settingsBody)+',globals())\n'
helperText=read(P/'prepare-section-helpers.py');helperBody=helperText[helperText.index('for rel in ['):helperText.index("write(P/'section-helper-inventory.json'")]
a=helperBody.index(' path=BASE+rel;');b=helperBody.index(' adaptations=[]',a)
helperBody=helperBody[:a]+' text=source(rel)\n'+helperBody[b:]
helperBody=helperBody.replace(" write(P/'section-generated/com/android/purebilibili'/rel,text)"," emit(rel,text)")
helperBody=helperBody[:helperBody.index(' rows.append(')]
helperBody='def render_helpers():\n'+''.join(' '+line+'\n'for line in helperBody.splitlines())
controlTool=read(MAIN/'desktop/.local/stable-video-player-full-controls-parity/prepared/desktop/tools/extract-upstream-video-player-full-controls.py');closure=function_part(controlTool,'member_closure','main')
header='''from pathlib import Path
import hashlib,importlib.util,json,re,subprocess,sys
from types import SimpleNamespace
sys.dont_write_bytecode=True
REPO=OUTPUT=None;STANDALONE=False
COMMIT='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589';BASE='app/src/main/java/com/android/purebilibili/'
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def write(p,t):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes(t.encode()if isinstance(t,str)else t)
def sha(t):return hashlib.sha256(t.encode()if isinstance(t,str)else t).hexdigest()
def module(name,p):
 s=importlib.util.spec_from_file_location(name,p);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
SOURCES={};OUTPUTS=[];EDITS=[]
def source(rel):
 path=BASE+rel
 if path not in SOURCES:
  p=REPO/path;assert not p.is_symlink() and p.resolve().is_relative_to(REPO.resolve())
  b=wide(p).read_bytes().replace(b'\\r\\n',b'\\n');assert sha(b)==SOURCE_PINS[path]['sha256LF'],path
  blob=subprocess.check_output(['git','-c','core.longpaths=true','hash-object','--path='+path,path],cwd=REPO,text=True).strip();assert blob==SOURCE_PINS[path]['gitBlob'],path
  SOURCES[path]=dict(text=b.decode(),**SOURCE_PINS[path])
 return SOURCES[path]['text']
def emit(rel,t):
 direct=BASE+rel in DIRECT
 OUTPUTS.append(dict(path='com/android/purebilibili/'+rel,sha256LF=sha(t),mode='direct-complete-original'if direct else'selected-platform',generated=STANDALONE or not direct))
 if STANDALONE or not direct:write(OUTPUT/'com/android/purebilibili'/rel,t)
def exact(t,before,after,label):
 assert t.count(before)==1,(label,t.count(before));EDITS.append(dict(label=label,before=before,after=after));return t.replace(before,after,1)
def between(t,start,end,after,label):
 a=t.index(start);b=t.index(end,a);return exact(t,t[a:b],after,label)
def balanced_call(t,start,after,label):
 a=t.index(start);masked=protocol.masked(t);op=masked.index('(',a);b=protocol.balanced(masked,op,'(',')');return exact(t,t[a:b],after,label)
'''
direct=[r['originalPath']for r in helpers if not r['adaptations']]
header=header[:header.index('def wide(')]+function_part(section,'wide','read')+header[header.index('def write('):]
header+='SOURCE_PINS='+repr(pins)+'\nDIRECT='+repr(direct)+'\nEXISTING_CONTROL_NAMES='+repr(controlNames)+'\nPOLICY_NAMES='+repr(policy['selectedDeclarations'])+'\n'
policyFn='''def render_policy():
 t=source('feature/video/ui/section/VideoPlayerSectionPolicy.kt')
 tokens=parser.kotlin_tokens(t);depth=parens=brackets=0;starts=[]
 for i,(word,a,b)in enumerate(tokens):
  if depth==parens==brackets==0 and word in ['fun','val','var','class','object','interface']:
   starts.append((tokens[i+1][0],t.rfind('\\n',0,a)+1))
  depth+=(word=='{')-(word=='}');parens+=(word=='(')-(word==')');brackets+=(word=='[')-(word==']')
 chunks=[t[a:starts[i+1][1]if i+1<len(starts)else len(t)]for i,(name,a)in enumerate(starts)if name in POLICY_NAMES]
 assert len(chunks)==len(POLICY_NAMES)
 header=t[:t.index('\\ninternal const val INITIAL_PLAYER_CONTROLS_VISIBLE')]
 header=re.sub(r'(?m)^@file:androidx.annotation.OptIn.*\\n','',header)
 header=re.sub(r'(?m)^import (?:android\\.|androidx.media3.ui.PlayerView).*$\\n','',header)
 header=header.replace('import androidx.media3.common.Player','import com.bilipai.desktop.ui.DesktopOriginalMpvOverlayControl as Player').replace('import androidx.media3.common.PlaybackParameters','import com.bilipai.desktop.ui.DesktopOriginalPlaybackRate as PlaybackParameters')
 emit('feature/video/ui/section/DesktopOriginalVideoPlayerSectionPolicy.kt',header+'\\n'+'\\n'.join(chunks))
def render_dimension():
 t=source('feature/video/state/VideoPlayerState.kt');a=t.index('internal fun resolveApiDimensionIsVertical(');masked=protocol.masked(t);b=protocol.balanced(masked,masked.index('{',a),'{','}')
 emit('feature/video/state/DesktopOriginalApiDimensionPolicy.kt','package com.android.purebilibili.feature.video.state\\n'+t[a:b]+'\\n')
def generate(repo,output,standalone=False):
 global REPO,OUTPUT,STANDALONE,parser,selector,protocol,controls,c,SOURCES,OUTPUTS,EDITS
 REPO=Path(repo).resolve();OUTPUT=Path(output).resolve();STANDALONE=standalone;SOURCES={};OUTPUTS=[];EDITS=[]
 manifest=json.loads(wide(REPO/'desktop/upstream-sources.json').read_text(encoding='utf-8'));assert manifest['upstreamCommit']==COMMIT
 registered={r['path']:r['sha256']for r in manifest['sources']}
 for path,pin in SOURCE_PINS.items():
  if path in registered:assert registered[path]==pin['sha256LF'],path+' registered original identity changed'
 parser=module('section_tokens',REPO/'desktop/tools/sync-upstream.py');selector=module('section_declarations',REPO/'desktop/tools/extract-appearance-platform.py');protocol=module('section_brackets',REPO/'desktop/tools/extract-upstream-dynamic-reply-protocol.py')
 controls=SimpleNamespace(parser=parser);c=SimpleNamespace(parser=parser,selector=selector,member_closure=member_closure)
 render_section();render_settings();render_helpers();render_policy();render_dimension()
 return OUTPUTS
if __name__=='__main__':generate(Path(sys.argv[1]),Path(sys.argv[2]),'--standalone'in sys.argv[3:])
'''
tool=header+closure+render+'\n'+settingsBody+'\n'+helperBody+'\n'+policyFn
target=P/'prepared/desktop/tools/extract-upstream-video-player-section-full.py';write(target,tool)
write(P/'sole-producer-config.json',json.dumps(dict(producerPath=str(target),sha256LF=sha(tool),sourcePins=pins,directSources=direct,selectedPolicyDeclarations=policy['selectedDeclarations'],existingControlMethods=controlNames,scope='One installable producer rereads original pinned sources. No compiled bodies or task absolute paths.'),indent=2)+'\n')
print(json.dumps(dict(producerSHA=sha(tool),identities=len(pins),directSources=len(direct))))
