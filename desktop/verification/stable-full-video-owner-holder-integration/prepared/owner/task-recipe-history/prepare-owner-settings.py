from pathlib import Path
import subprocess,hashlib,json,re,importlib.util
H=Path(__file__).resolve().parent;R=H.parents[2].parent/'BiliPai-v023'
commit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589';path='app/src/main/java/com/android/purebilibili/core/store/SettingsManager.kt'
raw=subprocess.check_output(['git','-C',str(R),'show',commit+':'+path]).decode().replace('\r\n','\n')
def mod(name,path):
 sp=importlib.util.spec_from_file_location(name,path);m=importlib.util.module_from_spec(sp);sp.loader.exec_module(m);return m
sel=mod('ownerSelect',R/'desktop/tools/extract-upstream-video-detail-full-units.py');sel.parser=mod('ownerTokens',R/'desktop/tools/sync-upstream.py')
names=['getPlaybackCdnPreference','getVideoCodec','setVideoCodec','getVideoCodecSync','getVideoSecondCodec','setVideoSecondCodec','getVideoSecondCodecSync','getAudioQuality','getDataSaverModeSync','getVideoNoteEnabledSync','getTripleJumpEnabled','getResumePlaybackPromptEnabledSync','hasResumePlaybackPromptShown','markResumePlaybackPromptShown','isEasterEggEnabledSync']
names+=['getAutoPlaySync','getExternalPlaylistAutoContinueSync','getAutoHighestQualitySync','getShowOnlineCountSync']
parts=[sel.func(raw,n,False) for n in names]
used={key for key in re.findall(r'\b[A-Z][A-Z0-9_]{3,}\b','\n'.join(parts)) if re.search(r'(?m)^\s*private (?:const )?val '+key+r'\s*=',raw)}
defs=[]
for key in sorted(used):
 m=re.search(r'(?m)^\s*private (?:const )?val '+key+r'\s*=.*$',raw)
 if not m: m=re.search(r'(?m)^\s*private const val '+key+r'\s*=.*$',raw)
 assert m,key
 defs.append(m.group().strip())
match=re.search(r'(?m)^\s*enum class DataSaverMode\b',raw);a=match.start();start=raw.index('{',match.end());level=0;b=start
for b in range(start,len(raw)):
 if raw[b]=='{':level+=1
 if raw[b]=='}':
  level-=1
  if level==0:break
parts.append(raw[a:b+1].strip())
body='\n'.join(defs)+'\n\n'+'\n\n'.join(parts)
body=body.replace('Context','com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext').replace('com.android.purebilibili.core.util.Logger','android.util.Log')
header='''package com.android.purebilibili.core.store
import com.bilipai.desktop.ui.playerBooleanPreferencesKey as booleanPreferencesKey
import com.bilipai.desktop.ui.playerFloatPreferencesKey as floatPreferencesKey
import com.bilipai.desktop.ui.playerIntPreferencesKey as intPreferencesKey
import com.bilipai.desktop.ui.playerStringPreferencesKey as stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
internal object DesktopOriginalVideoOwnerSettings {
'''
out=H/'prepared/prerequisites/com/android/purebilibili/core/store/DesktopOriginalVideoOwnerSettings.kt';out.parent.mkdir(parents=True,exist_ok=True);out.write_text(header+body+'\n}\n',encoding='utf-8',newline='\n')
(H/'owner-settings-identities.json').write_text(json.dumps(dict(originalPath=path,originalSha256LF=hashlib.sha256(raw.encode()).hexdigest(),selectedOriginalFunctions=names,excludedSoleExisting=['DesktopOriginalPlayerSectionSettings.getClickToPlaySync','DesktopOriginalPortraitSettings.setAudioQuality'],outputSha256Bytes=hashlib.sha256(out.read_bytes()).hexdigest(),requiredMirrorStringSetSupport=True,preparedOnly=True),indent=2)+'\n',encoding='utf-8')
print('Selected full original settings methods',len(names))
# The existing control-settings Flow and setter own this exact original memory
# cache. Select its Sync reader into that same object, never a second cache.
control=R/'desktop/build/generated/original-video-player-full-controls/com/android/purebilibili/core/store/DesktopOriginalVideoControlSettings.kt'
control_base=control.read_text(encoding='utf-8').replace('\r\n','\n')
sync_reader=sel.func(raw,'getPlaybackCompletionBehaviorSync',False)
assert 'playbackCompletionBehaviorMemoryCache' in sync_reader
assert control_base.count('private var playbackCompletionBehaviorMemoryCache')==1
assert 'fun getPlaybackCompletionBehaviorSync(' not in control_base
desired=control_base[:control_base.rfind('}')]+sync_reader+'\n'+control_base[control_base.rfind('}'):]
legacy=H/'prepared/legacy/com/android/purebilibili/core/store/DesktopOriginalVideoControlSettings.kt'
legacy.parent.mkdir(parents=True,exist_ok=True);legacy.write_text(desired,encoding='utf-8',newline='\n')
tool=R/'desktop/tools/extract-upstream-video-player-full-controls.py'
tool_base=tool.read_text(encoding='utf-8').replace('\r\n','\n')
needle="'getPlaybackCompletionBehavior','setPlaybackCompletionBehavior'"
assert tool_base.count(needle)==1
tool_desired=tool_base.replace(needle,"'getPlaybackCompletionBehavior','getPlaybackCompletionBehaviorSync','setPlaybackCompletionBehavior'")
patch_out=H/'prepared/producer-deltas/extract-upstream-video-player-full-controls.py'
patch_out.parent.mkdir(parents=True,exist_ok=True);patch_out.write_text(tool_desired,encoding='utf-8',newline='\n')
(H/'completion-settings-delta.json').write_text(json.dumps(dict(preparedOnly=True,soleExistingObject='DesktopOriginalVideoControlSettings',source=path,originalReader=sync_reader,originalReaderSha256LF=hashlib.sha256(sync_reader.encode()).hexdigest(),baseGeneratedSha256LF=hashlib.sha256(control_base.encode()).hexdigest(),desiredGeneratedSha256LF=hashlib.sha256(desired.encode()).hexdigest(),baseProducerSha256LF=hashlib.sha256(tool_base.encode()).hexdigest(),desiredProducerSha256LF=hashlib.sha256(tool_desired.encode()).hexdigest(),secondMemoryCache=False,installRecipe='Single exact names-list addition in existing sole full-controls producer; generated legacy file is compile-only, never an install payload.'),ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
