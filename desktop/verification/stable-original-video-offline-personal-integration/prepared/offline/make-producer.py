from pathlib import Path
import json,hashlib
LANE=Path(__file__).resolve().parent
inventory=json.loads((LANE/'source-inventory.json').read_text(encoding='utf-8'))
ui_delta=json.loads((LANE/'ui-platform-delta.json').read_text(encoding='utf-8'))
prefix=(LANE/'prepared/generated/com/android/purebilibili/core/store/player/DesktopOriginalLongPressSpeedSettings.kt').read_text(encoding='utf-8').split('object DesktopOriginalLongPressSpeedSettings {')[0]
pins={r['path']:r['lfSHA256'] for r in inventory}
text='''"""Full fixed-tag offline UI. Only actual Windows controls/window/surface/metadata seams are adapted."""
from pathlib import Path
import argparse,hashlib,json,subprocess,importlib.util,re
COMMIT='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
BASE='app/src/main/java/com/android/purebilibili/'
FEATURE='stable-offline-player-original'
'''
text+='SOURCE_PINS='+repr(pins)+'\nUI_DELTA='+repr(ui_delta)+'\nLONG_PRESS_PREFIX='+repr(prefix)+'\n'
text+='''
def safe(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def read(p):return safe(p).read_text(encoding='utf-8').replace('\\r\\n','\\n')
def write(p,s):
 p=safe(p);p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s,encoding='utf-8',newline='\\n')
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
def load(p,n):
 spec=importlib.util.spec_from_file_location(n,p);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
def policyfunction(r,n):
 begin=r.index('internal fun '+n+'(')
 next=re.search(r'\\ninternal (?:fun|class|enum)',r[begin+1:])
 return r[begin:begin+1+next.start()].rstrip() if next else r[begin:].rstrip()
def generate(repo,output,standalone=False):
 repo=Path(repo).resolve();output=Path(output).resolve();manifest=json.loads(read(repo/'desktop/upstream-sources.json'))
 assert manifest['upstreamCommit']==COMMIT,'Fixed target changed'
 originals={};records=[]
 for path,pin in SOURCE_PINS.items():
  s=read(repo/path);assert sha(s)==pin,path
  blob=subprocess.check_output(['git','show',COMMIT+':'+path],cwd=repo).decode('utf-8').replace('\\r\\n','\\n')
  assert s==blob,'Working original differs from fixed Git blob: '+path
  if not standalone:
   rows=[r for r in manifest['sources'] if r['path']==path]
   assert len(rows)==1 and rows[0]['sha256']==pin and FEATURE in rows[0]['features'],'Missing sole registered source feature: '+path
  originals[path]=s;write(output/'original-retained'/(path+'.txt'),s)
 path=BASE+'feature/download/OfflineVideoPlayerScreen.kt';s=originals[path]
 for change in UI_DELTA:
  i=change['index'];before=change['before'];assert s[i:i+len(before)]==before,change['label']
  s=s[:i]+change['after']+s[i+len(before):]
 reverse=s
 for change in UI_DELTA[::-1]:
  i=change['index'];after=change['after'];assert reverse[i:i+len(after)]==after,change['label']
  reverse=reverse[:i]+change['before']+reverse[i+len(after):]
 assert reverse==originals[path],'Full original UI inverse failed'
 assert 'ExoPlayer' not in s and 'import android.' not in s and 'MiniPlayerManager' not in s
 destination='com/android/purebilibili/feature/download/OfflineVideoPlayerScreen.kt';write(output/destination,s)
 records.append({'path':path,'sha256LF':SOURCE_PINS[path],'output':destination,'outputSha256LF':sha(s),'fullFourDeclarationsRetained':True,'exactInversePass':True})
 path=BASE+'feature/download/OfflineVideoPlaybackPolicy.kt';r=originals[path]
 names=['resolveOfflineVideoStartFullscreen','shouldResumePlaybackAfterOfflineSeek','shouldShowOfflineDanmakuControl','shouldShowOfflineDanmakuLayer','resolveOfflineSeekProgressFromTouch','resolveOfflineSeekPositionFromTouch']
 selected='\\n\\n'.join(policyfunction(r,n) for n in names)
 adapted=selected.replace('Player.STATE_ENDED','DesktopOfflinePlaybackState.ENDED')
 assert adapted.replace('DesktopOfflinePlaybackState.ENDED','Player.STATE_ENDED')==selected
 body='package com.android.purebilibili.feature.download\\nimport com.bilipai.desktop.ui.DesktopOfflinePlaybackState\\n\\n'+adapted+'\\n'
 destination='com/android/purebilibili/feature/download/DesktopOfflineInteractionPolicy.kt';write(output/destination,body)
 records.append({'path':path,'sha256LF':SOURCE_PINS[path],'output':destination,'outputSha256LF':sha(body),'selectedMethods':names,'selectedOriginalSha256LF':sha(selected),'selectedAdaptedSha256LF':sha(adapted),'exactInversePass':True,'persistedPositionHelperReusedExistingMediaProducer':True,'AndroidPhoneOrientationHelpersRetainedReviewOnly':True})
 path=BASE+'feature/download/OfflinePlaybackSessionPolicy.kt';r=originals[path]
 destination='com/android/purebilibili/feature/download/OfflinePlaybackSessionPolicy.kt'
 if standalone:write(output/destination,r)
 direct={'path':path,'mode':'direct','sha256LF':SOURCE_PINS[path],'standaloneEmitted':standalone,'productionSyncSoleCopy':True}
 media=load(repo/'desktop/tools/extract-upstream-media.py','offline_media_parser');parser=media.parser_for(repo)
 path=BASE+'core/store/player/PlayerSettingsStore.kt';r=originals[path]
 getter=media.function(r,'getLongPressSpeed',parser)
 adapted=getter.replace('context: Context','context: DesktopPluginContext').replace('context.settingsDataStore.data','context.store.snapshot("settings")')
 assert adapted.replace('context: DesktopPluginContext','context: Context').replace('context.store.snapshot("settings")','context.settingsDataStore.data')==getter
 body=LONG_PRESS_PREFIX+'object DesktopOriginalLongPressSpeedSettings {\\n'+adapted+'\\n}\\n'
 destination='com/android/purebilibili/core/store/player/DesktopOriginalLongPressSpeedSettings.kt';write(output/destination,body)
 records.append({'path':path,'sha256LF':SOURCE_PINS[path],'output':destination,'outputSha256LF':sha(body),'selectedMethods':['getLongPressSpeed'],'originalGetterSha256LF':sha(getter),'adaptedGetterSha256LF':sha(adapted),'exactInversePass':True,'canonicalKey':'long_press_speed','existingDefaultAndNormalizePolicyReused':True})
 write(output/'source-receipt.json',json.dumps({'fixedCommit':COMMIT,'sources':records,'soleDirectReference':direct,'standalone':standalone,'exactUiDelta':UI_DELTA,'completeOriginalUiLines':len(originals[BASE+'feature/download/OfflineVideoPlayerScreen.kt'].splitlines()),'originalFourDeclarations':['GestureMode','OfflineVideoPlayerScreen','ProgressInfo','OfflineProgressBar'],'newPlayerOrStoreOrHTTP':False},ensure_ascii=False,indent=2))
 return records
if __name__=='__main__':
 cli=argparse.ArgumentParser(description=__doc__);cli.add_argument('--repo',type=Path,required=True);cli.add_argument('--output',type=Path,required=True);cli.add_argument('--standalone',action='store_true');args=cli.parse_args()
 print('Generated',len(generate(args.repo,args.output,args.standalone)),'full original offline selected sources')
'''
p=LANE/'prepared/desktop/tools/extract-upstream-offline-player.py';p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf-8',newline='\n')
registry=[]
for r in inventory:
 mode='direct' if 'OfflinePlaybackSessionPolicy' in r['path'] else 'policy-extract' if 'PlaybackPolicy' in r['path'] else 'extracted'
 registry.append({'path':r['path'],'mode':mode,'sha256':r['lfSHA256'],'features':['stable-offline-player-original','downloads','source-parity'],'operation':'mergeExistingFeatures' if 'PlayerSettingsStore' in r['path'] or 'OfflineVideoPlaybackPolicy' in r['path'] else 'addOneSourceIdentity'})
(LANE/'registry-delta.json').write_text(json.dumps(registry,indent=2),encoding='utf-8')
print(json.dumps({'producer':str(p),'sha256Bytes':hashlib.sha256(p.read_bytes()).hexdigest(),'registryNew':2,'registryMerge':2}))
