from pathlib import Path
import argparse,hashlib,importlib.util,json,subprocess,sys
sys.dont_write_bytecode=True
COMMIT='79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40'
BASE='app/src/main/java/com/android/purebilibili/'
TEST='app/src/test/java/com/android/purebilibili/'
def wide(p):return Path('\\\\?\\'+str(p.resolve())) if sys.platform=='win32' and not str(p).startswith('\\\\?\\') else p
def sha(b):return hashlib.sha256(b.encode() if isinstance(b,str) else b).hexdigest()
def write(p,b):p=wide(p);p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(b.encode() if isinstance(b,str) else b)
def module(n,p):
 s=importlib.util.spec_from_file_location(n,p);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
def generate(repo,output):
 sys.path.insert(0,str(repo/'desktop/tools'))
 from v025_source_paths import canonical_source
 parser=module('original_background_tokens',repo/'desktop/tools/sync-upstream.py')
 selector=module('original_background_selector',repo/'desktop/tools/extract-appearance-platform.py')
 controls=module('original_background_method_boundaries',repo/'desktop/tools/extract-upstream-video-player-full-controls.py')
 controls.parser=parser
 pins=[];proof=[];outputs=[]
 def original(path):
  raw=wide(canonical_source(repo,path)).read_bytes();b=raw.replace(b'\r\n',b'\n')
  fixed=subprocess.check_output(['git','show',COMMIT+':'+path],cwd=repo).replace(b'\r\n',b'\n');assert b==fixed,path
  pins.append(dict(path=path,rawSha256=sha(raw),sha256LF=sha(b),gitBlob=subprocess.check_output(['git','rev-parse',COMMIT+':'+path],cwd=repo,text=True).strip()))
  write(output/'original-lf'/path,b);return b.decode()
 def emit(root,rel,text):
  write(output/root/rel,text);outputs.append(dict(root=root,path=rel,sha256LF=sha(text),bytes=len(text.encode())))
 path=BASE+'feature/video/player/MiniPlayerManager.kt';s=original(path)
 names=['shouldContinueBackgroundAudioByPolicy','shouldKeepPlaybackForAudioNowPlayingBar']
 body=selector.declarations(parser,s,names)
 emit('generated-main','com/android/purebilibili/feature/video/player/DesktopOriginalWindowsBackgroundPolicy.kt',
  'package com.android.purebilibili.feature.video.player\nimport com.android.purebilibili.core.store.DesktopOriginalPlaybackSettingsPreferences as SettingsManager\n'+body+'\n')
 proof.append(dict(source=path,mode='COMPLETE_ORIGINAL_DECLARATIONS',names=names,before=body,after=body,sha256LF=sha(body),bodyByteExactLF=True))
 path=BASE+'feature/video/playback/session/PlaybackLifecycleCoordinator.kt';s=original(path)
 adapted=s.replace('import androidx.media3.common.Player','import com.bilipai.desktop.player.platform.DesktopMedia3PlayerStates as Player')
 assert adapted.replace('import com.bilipai.desktop.player.platform.DesktopMedia3PlayerStates as Player','import androidx.media3.common.Player')==s
 emit('generated-main','com/android/purebilibili/feature/video/playback/session/PlaybackLifecycleCoordinator.kt',adapted)
 proof.append(dict(source=path,mode='WHOLE_ORIGINAL_IMPORT_ALIAS_ONLY',before=s,after=adapted,fullInverse=True,sha256LF=sha(s)))
 path=BASE+'feature/video/state/PlayerLifecyclePlaybackPolicy.kt';s=original(path)
 names=['isPlaybackActiveForLifecycle','shouldResumeAfterLifecyclePause','shouldRestorePlayerVolumeOnResume']
 body=selector.declarations(parser,s,names)
 emit('generated-main','com/android/purebilibili/feature/video/state/DesktopOriginalWindowsLifecycleInputs.kt',
  'package com.android.purebilibili.feature.video.state\nimport com.bilipai.desktop.player.platform.DesktopMedia3PlayerStates as Player\n'+body+'\n')
 proof.append(dict(source=path,mode='COMPLETE_ORIGINAL_REQUIRED_DECLARATION_CLOSURE',names=names,before=body,after=body,sha256LF=sha(body),bodyByteExactLF=True))
 path=TEST+'feature/video/playback/session/PlaybackLifecycleCoordinatorTest.kt';s=original(path)
 adapted=s.replace('import androidx.media3.common.Player','import com.bilipai.desktop.player.platform.DesktopMedia3PlayerStates as Player')
 assert adapted.replace('import com.bilipai.desktop.player.platform.DesktopMedia3PlayerStates as Player','import androidx.media3.common.Player')==s
 emit('generated-test','com/android/purebilibili/feature/video/playback/session/PlaybackLifecycleCoordinatorTest.kt',adapted)
 proof.append(dict(source=path,mode='WHOLE_ORIGINAL_TEST_IMPORT_ALIAS_ONLY',before=s,after=adapted,fullInverse=True,originalTestCount=s.count('@Test'),assertionLines=[line for line in s.splitlines() if 'assertTrue(' in line or 'assertFalse(' in line]))
 path=TEST+'feature/video/player/BackgroundPlaybackPolicyTest.kt';s=original(path)
 names=['stopOnExitDisablesBackgroundAudioEvenInDefaultMode','defaultModeStillSupportsBackgroundAudioWhenOptionOff',
  'defaultModeStopsBackgroundAudioWhenLeavingByNavigation','audioBarKeepsDetailPlaybackAcrossNavigationExit',
  'inAppMiniModeCanFallbackToBackgroundAudioWhenAppLeavesForeground','systemPipCanFallbackToBackgroundAudioWhenNoPipTransitionWillHappen',
  'systemPipDoesNotEnableBackgroundAudioDuringPipTransition','backgroundPlaybackToggleDisablesBackgroundAudioEvenInDefaultMode']
 methods=[]
 for name in names:
  a,b=controls.function_range(s,name);prefix=s.rfind('    @Test',0,a);assert prefix>=0 and s[prefix:a].strip()=='@Test',(name,s[prefix:a])
  method=s[prefix:b];methods.append(method)
  proof.append(dict(source=path,mode='COMPLETE_ORIGINAL_TEST_METHOD',name=name,before=method,after=method,sha256LF=sha(method),originalAssertionLines=[x for x in method.splitlines() if 'assertTrue(' in x or 'assertFalse(' in x]))
 emit('generated-test','com/android/purebilibili/feature/video/player/DesktopOriginalBackgroundPlaybackPolicyTest.kt',
  'package com.android.purebilibili.feature.video.player\nimport com.android.purebilibili.core.store.DesktopOriginalPlaybackSettingsPreferences as SettingsManager\nimport kotlin.test.Test\nimport kotlin.test.assertTrue\nimport kotlin.test.assertFalse\n\nclass DesktopOriginalBackgroundPlaybackPolicyTest {\n'+'\n\n'.join(methods)+'\n}\n')
 write(output/'source-proof.json',json.dumps(dict(schemaVersion=1,canonicalCommit=COMMIT,pins=pins,proofs=proof,outputs=outputs,compileExecuted=False,nativeExecuted=False),ensure_ascii=False,indent=2)+'\n')
 print(json.dumps(dict(sourcePins=len(pins),mainOutputs=3,testOutputs=2,originalTestCount=8+next(p['originalTestCount'] for p in proof if 'originalTestCount' in p),sourceProofSha256=sha(wide(output/'source-proof.json').read_bytes()),compiled=False)))
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--repo',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args();generate(a.repo.resolve(),a.output.resolve())
