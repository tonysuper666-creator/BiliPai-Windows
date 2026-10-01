from pathlib import Path
import hashlib, json, subprocess, sys
sys.stdout.reconfigure(encoding='utf-8');sys.dont_write_bytecode=True
H=Path(__file__).resolve().parent;MAIN=H.parents[2];REPO=MAIN.parent/'BiliPai-v023'
def wide(p):
    s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def read(p):return wide(p).read_bytes()
def sha(b):return hashlib.sha256(b).hexdigest()
def write(p,b):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes(b)
target='desktop/tools/extract-upstream-music-player-full.py'
base=read(REPO/target).replace(b'\r\n',b'\n')
before="CURRENT='data/repository/ExternalPlaylistRepository.kt';t=read(CURRENT)\ninner=t[t.index('object ExternalPlaylistRepository {')"
addition='''# The existing model producers remain sole owners. Emit the complete original
# history/local-playlist objects separately, replacing only their object name
# and consumed Android Context/preferences imports with the same Root Store view.
for originalName, desktopName in [('PlayHistoryStore', 'DesktopOriginalAudioHistoryStore'),
                                  ('LocalPlaylistStore', 'DesktopOriginalLocalPlaylistStore')]:
 CURRENT='core/store/'+originalName+'.kt';t=read(CURRENT)
 selected=selector.declarations(parser,t,[originalName])
 originalObject=selected
 declaration='object '+originalName+' {'; adapted='object '+desktopName+' {'
 assert selected.count(declaration)==1
 selected=selected.replace(declaration,adapted,1)
 assert selected.replace(adapted,declaration,1)==originalObject
 header='package com.android.purebilibili.core.store\\n'
 header+='import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext as Context\\n'
 header+='import com.bilipai.desktop.ui.playerStringPreferencesKey as stringPreferencesKey\\n'
 header+='import kotlinx.coroutines.flow.Flow\\nimport kotlinx.coroutines.flow.map\\n'
 header+='import kotlinx.serialization.encodeToString\\nimport kotlinx.serialization.json.Json\\n'
 emit('core/store/'+desktopName+'.kt',header+selected+'\\n',CURRENT,
      {'declarations':[originalName], 'fullObjectBody':True, 'originalObjectSHA256LF':sha(originalObject),
       'onlyObjectIdentifierRenamed':{'before':originalName,'after':desktopName}, 'exactWholeObjectInverse':True})
'''
assert base.count(before.encode())==1
after=base.replace(before.encode(),addition.encode()+before.encode(),1)
assert after.replace(addition.encode()+before.encode(),before.encode(),1)==base
write(H/'baseline'/target,read(REPO/target));write(H/'prepared'/target,after)
write(H/'producer-hunk.json',(json.dumps(dict(target=target,baseSHA256LF=sha(base),desiredSHA256LF=sha(after),
    edits=[dict(before=before,after=addition+before)],indexedInverse=True),indent=2)+'\n').encode())
output=H/'generated';assert not output.exists()
command=[sys.executable,str(H/'prepared'/target),'--repo',str(REPO),'--output',str(output),'--standalone']
result=subprocess.run(command,capture_output=True,timeout=60);write(H/'generation.log',result.stdout+result.stderr)
if result.returncode:print((result.stdout+result.stderr).decode());raise SystemExit(result.returncode)
print(result.stdout.decode().strip())
