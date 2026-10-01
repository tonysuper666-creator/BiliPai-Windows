from pathlib import Path
import ast,hashlib,importlib.util,json,re,sys,subprocess
sys.dont_write_bytecode=True
P=Path(__file__).resolve().parent;MAIN=P.parents[2];REPO=MAIN.parent/'BiliPai-v023';BASE='app/src/main/java/com/android/purebilibili/';COMMIT='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
def mod(n,p):
 s=importlib.util.spec_from_file_location(n,p);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
f=mod('install_fs',P/'prepare-fullscreen.py');libs=mod('install_libs',P/'prepare-libraries.py');full=mod('install_select',MAIN/'desktop/.local/stable-video-detail-full-ui-parity/prepare.py')
def read(p):return f.read(p).decode('utf-8')
def write(p,t):f.write(p,t)
def save(p,v):write(p,json.dumps(v,ensure_ascii=False,indent=2)+'\n')
def simple_edit(t,a,b,label,rows):
 count=t.count(a);assert count>0,(label,a);rows.append(dict(before=a,after=b,count=count,label=label));return t.replace(a,b)
def replay(t,edits):
 history=[]
 for e in edits:
  a=e['before'];b=e['after'];count=e.get('count',1);starts=[];pos=0
  for _ in range(count):
   at=t.index(a,pos);starts.append(at);pos=at+len(a)
  history.append((starts,a,b));t=t.replace(a,b,count)
 result=t
 for starts,a,b in reversed(history):
  for i,at in reversed(list(enumerate(starts))):
   at+=i*(len(b)-len(a));assert t[at:at+len(b)]==b
   t=t[:at]+a+t[at+len(b):]
 return result,t
def main():
 specs=[];rows=[]
 for path in sorted(f.wide(P/'prepared/generated').rglob('*.kt')):
  rel=path.relative_to(f.wide(P/'prepared/generated')).as_posix();local=rel.removeprefix('com/android/purebilibili/');output=read(path);extra={};kind='whole';edits=[]
  if path.name=='DesktopOriginalPortraitSettings.kt':
   origin=BASE+'core/store/SettingsManager.kt';raw=read(P/'original-stable'/origin);seeds=['getAutoPlay','getExternalPlaylistAutoContinue','getPrefetchVideo','setAudioQuality','getSubtitlePortraitVerticalOffsetFraction','setSubtitlePortraitVerticalOffsetFraction','getSubtitlePositionLocked','getPortraitLetterboxAmbientHazeSync'];chosen,names=libs.c.member_closure(libs.body(raw,'SettingsManager'),seeds)
   candidate=chosen.replace('com.android.purebilibili.core.util.Logger.','android.util.Log.');prefix=output[:output.index(candidate)];assert output==prefix+candidate+'\n}\n'
   kind='members';extra=dict(object='SettingsManager',seeds=seeds,selectedNames=names,prefix=prefix,suffix='\n}\n',selectionSHA=f.sha(chosen));rawForReplay=chosen
   edits=[dict(before='com.android.purebilibili.core.util.Logger.',after='android.util.Log.',count=chosen.count('com.android.purebilibili.core.util.Logger.'),label='Actual safe desktop logger')]
  elif path.name=='DesktopOriginalVideoCommentSheetSettings.kt':
   origin=BASE+'core/store/SettingsManager.kt';raw=read(P/'original-stable'/origin);seeds=['setCommentDefaultSortMode'];chosen,names=libs.c.member_closure(libs.body(raw,'SettingsManager'),seeds);prefix=output[:output.index(chosen)];assert output==prefix+chosen+'\n}\n';kind='members';extra=dict(object='SettingsManager',seeds=seeds,selectedNames=names,prefix=prefix,suffix='\n}\n',selectionSHA=f.sha(chosen));rawForReplay=chosen
  elif path.name=='DesktopOriginalPortraitVideoViewport.kt':
   origin=BASE+'feature/video/ui/pager/PortraitVideoPager.kt';raw=read(P/'original-stable'/origin);a=raw.index('internal data class PortraitVideoViewportSize(');_,b=full.function_range(raw,'resolvePortraitVideoViewportSize');chosen=raw[a:b];prefix=output[:output.index(chosen)];assert output==prefix+chosen+'\n';kind='declaration';extra=dict(name='PortraitVideoViewportSize + resolvePortraitVideoViewportSize',originalDeclaration=chosen,prefix=prefix,suffix='\n',selectionSHA=f.sha(chosen));rawForReplay=chosen
  elif path.name in ['DesktopOriginalPortraitAudioUrlCandidates.kt','DesktopOriginalVideoCommentListSkeleton.kt']:
   origin=BASE+('feature/video/viewmodel/VideoPlaybackViewModel.kt'if 'Audio' in path.name else'core/ui/skeleton/ContentLoadingSkeletons.kt');raw=read(P/'original-stable'/origin);name='buildPlaybackAudioUrlCandidates'if'Audio'in path.name else'CommentListSkeleton';a,b=full.function_range(raw,name);chosen=raw[a:b];prefix=output[:output.index(chosen)];assert output==prefix+chosen+'\n';kind='declaration';extra=dict(name=name,originalDeclaration=chosen,prefix=prefix,suffix='\n',selectionSHA=f.sha(chosen));rawForReplay=chosen
  else:
   origin=BASE+local;raw=read(P/'original-stable'/origin);rawForReplay=raw
   auditPath=P/'adaptations'/path.with_suffix('.kt.json').name
   if path.name=='FullscreenPlayerOverlay.kt':edits=json.loads(read(P/'fullscreen-adaptations.json'))['edits']
   elif f.wide(auditPath).exists()and 'edits'in json.loads(read(auditPath)):edits=json.loads(read(auditPath))['edits']
   elif output!=raw:
    current=raw
    aliases=[('import androidx.compose.ui.platform.LocalConfiguration','import com.bilipai.desktop.ui.DesktopHomeCardWindowMetrics as LocalConfiguration','Actual window metrics'),('import androidx.compose.ui.platform.LocalContext','import coil3.compose.LocalPlatformContext as LocalContext','Actual Coil JVM context')]
    if path.name=='SettingsPrefsCache.kt':aliases=[('import android.annotation.SuppressLint\n','','Android annotation import'),('@SuppressLint("ApplySharedPref")\n','','Android annotation'),('import android.content.Context','import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext as Context','Actual same global settings context'),('import android.content.SharedPreferences','import com.bilipai.desktop.ui.DesktopOriginalPlayerMirrorPreferences as SharedPreferences','Same global preferences mirror'),('import androidx.datastore.preferences.core.MutablePreferences','import com.bilipai.desktop.ui.DesktopOriginalPlayerPreferenceValues as MutablePreferences','Same global mutable preferences view'),('import androidx.datastore.preferences.core.edit\n','','Actual settings edit member')]
    if path.name=='PortraitCommentSheet.kt':aliases+=[('fun PortraitCommentSheet(','internal fun PortraitCommentSheet(','Actual required comment VM visibility')]
    if path.name=='PortraitDetailSheet.kt':aliases+=[('val blockedUpRepository = remember { com.android.purebilibili.data.repository.BlockedUpRepository(context) }','val platform = com.bilipai.desktop.ui.LocalDesktopOriginalPortraitPlatform.current\n                            val blockedUpRepository = platform.blockedUps','Existing global block authority'),('android.widget.Toast.makeText(context, result.message, android.widget.Toast.LENGTH_SHORT).show()','platform.showFeedback(result.message)','Same owned feedback')]
    for a,b,label in aliases:
     if a in current:current=simple_edit(current,a,b,label,edits)
    assert current==output,(local,f.sha(current),f.sha(output))
  value,inverse=replay(rawForReplay,edits);assert inverse==rawForReplay
  generated=extra.get('prefix','')+value+extra.get('suffix','')if kind!='whole'else value
  assert generated==output,(rel,f.sha(generated),f.sha(output));mode='direct'if kind=='whole'and not edits else'selected'
  spec=dict(output=rel,origin=origin,originalSHA=f.sha(raw),mode=mode,kind=kind,outputSHA=f.sha(output),edits=edits,**extra);specs.append(spec)
  rows.append(dict(path=origin,sha256LF=f.sha(raw),output=rel,mode=mode,fullOriginalFile=kind=='whole',originalPhysicalLines=len(raw.splitlines()),outputPhysicalLines=len(output.splitlines()),forwardAndReverseExact=True,declaredEdits=len(edits),selectedDeclarations=extra.get('selectedNames',[extra['name']]if'name'in extra else[])))
 # Source-backed fragment only; root appends to the existing sole Core producer.
 methods=json.loads(read(P/'portrait-protocol-original-audit.json'));protocol=methods['methods'];repoOrigin=BASE+'data/repository/VideoRepository.kt';repoOriginal=read(P/'original-stable'/repoOrigin)
 for r in protocol:assert r['originalBody']in repoOriginal
 tool='''"""Full original stable Fullscreen/Pager source-only Windows adaptation.
No player/client/cache/store construction. DIRECT inputs are sole Sync-owned in production.
Core class and parent CommentURL definitions are deliberately not produced here.
"""
from pathlib import Path
import argparse,hashlib,importlib.util,json,re
COMMIT='''+repr(COMMIT)+'''
SPECS='''+repr(specs)+'''
PROTOCOL='''+repr(protocol)+'''
PROTOCOL_ORIGIN='''+repr(repoOrigin)+'''
PROTOCOL_SHA='''+repr(f.sha(repoOriginal))+'''
def wide(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix)else prefix+s)
def digest(t):return hashlib.sha256(t.encode()if isinstance(t,str)else t).hexdigest()
def module(n,p):
 s=importlib.util.spec_from_file_location(n,p);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
def original(repo,rel,sha):
 t=wide(Path(repo)/rel).read_bytes().replace(b'\\r\\n',b'\\n');assert digest(t)==sha,(rel,digest(t),sha);return t.decode('utf-8')
def body(t,name,lex):
 m=lex.masked(t);a=m.index('object '+name);op=m.index('{',a);end=lex.balanced(m,op,'{','}');return t[op+1:end-1]
'''
 tree=ast.parse(read(MAIN/'desktop/.local/stable-video-player-full-controls-parity/prepare.py'));node=next(n for n in tree.body if isinstance(n,ast.FunctionDef)and n.name=='member_closure');tool+=ast.get_source_segment(read(MAIN/'desktop/.local/stable-video-player-full-controls-parity/prepare.py'),node)+'\n'
 tool+='''def protocol_members(repo):
 raw=original(repo,PROTOCOL_ORIGIN,PROTOCOL_SHA)
 for row in PROTOCOL:
  assert row['originalBody']in raw
  assert digest(row['candidateBody'])==row['candidateBodySHA256LF']
 return '\\n'.join(row['candidateBody']for row in PROTOCOL)
def generate(repo,output,standalone=False):
 global parser
 repo=Path(repo);output=Path(output);parser=module('original_fullscreen_tokens',repo/'desktop/tools/sync-upstream.py');lex=module('original_fullscreen_balanced',repo/'desktop/tools/extract-upstream-dynamic-reply-protocol.py');rows=[]
 for spec in SPECS:
  text=original(repo,spec['origin'],spec['originalSHA'])
  if spec['kind']=='members':text,names=member_closure(body(text,spec['object'],lex),spec['seeds']);assert names==spec['selectedNames'];assert digest(text)==spec['selectionSHA']
  elif spec['kind']=='declaration':assert spec['originalDeclaration']in text;text=spec['originalDeclaration']
  for edit in spec['edits']:
   count=edit.get('count',1);assert text.count(edit['before'])>=count,(spec['output'],edit.get('label'));text=text.replace(edit['before'],edit['after'],count)
  text=spec.get('prefix','')+text+spec.get('suffix','');assert digest(text)==spec['outputSHA'],spec['output']
  generated=standalone or spec['mode']!='direct';rows.append(dict(path=spec['output'],origin=spec['origin'],mode=spec['mode'],sha256LF=digest(text),generated=generated))
  if generated:
   p=wide(output/spec['output']);p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf-8',newline='\\n')
 p=wide(output/'source-inventory.json');p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(dict(commit=COMMIT,outputs=rows,protocolFragmentSHA=digest(protocol_members(repo))),indent=2)+'\\n',encoding='utf-8');return rows
if __name__=='__main__':
 a=argparse.ArgumentParser();a.add_argument('--repo',required=True);a.add_argument('--output',required=True);a.add_argument('--standalone',action='store_true');v=a.parse_args();generate(v.repo,v.output,v.standalone)
'''
 write(P/'install-packet/tools/extract-upstream-video-fullscreen-pager.py',tool)
 save(P/'source-identity-output-audit.json',dict(passed=True,sourceCommit=COMMIT,outputs=rows,rendererOriginalBodies='whole original renderer files; Android/native transports explicitly adapted; existing two pure thread policies referenced',forwardReverseExact=True,fullFiles=sum(r['fullOriginalFile']for r in rows),selectedFiles=sum(r['mode']=='selected'for r in rows),directFiles=sum(r['mode']=='direct'for r in rows),protocolOriginalSource=repoOrigin,protocolOriginalSHA=f.sha(repoOriginal),protocolMethods=[r['name']for r in protocol]))
 # Sole Core producer's one emit anchor only; preserve every existing raw/invocation family.
 path=REPO/'desktop/tools/extract-upstream-video-state-core.py';base=read(path);anchor="p.put(H/'prepared/protocol/com/android/purebilibili/data/repository/DesktopOriginalVideoLoadProtocol.kt',header+s+'\\n}\\n')";assert base.count(anchor)==1
 replacement="pager=mod('original_portrait_protocol_members',REPO/'desktop/tools/extract-upstream-video-fullscreen-pager.py')\nheader=header.replace('import kotlinx.coroutines.*','import kotlinx.coroutines.*\\nimport com.android.purebilibili.feature.video.ui.pager.PORTRAIT_PLAYBACK_TARGET_QUALITY\\nimport com.android.purebilibili.feature.video.ui.pager.shouldUsePortraitParallelPlaybackBootstrap')\ns+='\\n'+pager.protocol_members(REPO)\n"+anchor
 save(P/'install-packet/local-hunks/core-protocol-append.json',dict(path='desktop/tools/extract-upstream-video-state-core.py',baseSHA256LF=f.sha(base),candidateSHA256LF=f.sha(base.replace(anchor,replacement)),hunks=[dict(before=anchor,after=replacement)],noWholeFileInstall=True,sourceModesPreserved=True,proofOnlyCoreNotInstalled=True))
 for p in f.wide(P/'prepared/manual').rglob('*.kt'):write(P/'install-packet/manual'/p.relative_to(f.wide(P/'prepared/manual')),read(p))
 print(len(specs),'source outputs,',sum(s['mode']=='direct'for s in specs),'DIRECT, source producer prepared')
if __name__=='__main__':main()
