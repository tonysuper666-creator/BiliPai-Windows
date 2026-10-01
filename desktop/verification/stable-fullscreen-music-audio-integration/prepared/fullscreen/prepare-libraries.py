from pathlib import Path
import hashlib,importlib.util,json,subprocess,sys,re
sys.dont_write_bytecode=True
P=Path(__file__).resolve().parent;MAIN=P.parents[2];REPO=MAIN.parent/'BiliPai-v023';BASE='app/src/main/java/com/android/purebilibili/';COMMIT='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
def module(name,p):
 s=importlib.util.spec_from_file_location(name,p);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
f=module('pager_library_edits',P/'prepare-fullscreen.py');c=module('pager_library_tokens',MAIN/'desktop/.local/stable-video-player-full-controls-parity/prepare.py')
def src(rel):
 b=subprocess.check_output(['git','show',COMMIT+':'+BASE+rel],cwd=REPO).replace(b'\r\n',b'\n');f.write(P/'original-stable'/BASE/rel,b);return b.decode()
def body(t,name):
 m=f.parser.masked(t);a=m.index('object '+name);op=m.index('{',a);end=f.parser.balanced(m,op,'{','}');return t[op+1:end-1]
def extract(t,names):return c.member_closure(t,names)[0]
def main():
 original=src('core/store/SettingsManager.kt');chosen,decls=c.member_closure(body(original,'SettingsManager'),['getAutoPlay','getExternalPlaylistAutoContinue','getPrefetchVideo','setAudioQuality','getSubtitlePortraitVerticalOffsetFraction','setSubtitlePortraitVerticalOffsetFraction','getSubtitlePositionLocked','getPortraitLetterboxAmbientHazeSync'])
 chosen=chosen.replace('com.android.purebilibili.core.util.Logger.','android.util.Log.')
 prefix='''package com.android.purebilibili.core.store
import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext as Context
import com.bilipai.desktop.ui.playerBooleanPreferencesKey as booleanPreferencesKey
import com.bilipai.desktop.ui.playerIntPreferencesKey as intPreferencesKey
import com.bilipai.desktop.ui.playerFloatPreferencesKey as floatPreferencesKey
import com.android.purebilibili.feature.video.subtitle.normalizeSubtitleVerticalOffsetFraction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
internal object DesktopOriginalPortraitSettings {
'''
 f.write(P/'prepared/generated/com/android/purebilibili/core/store/DesktopOriginalPortraitSettings.kt',prefix+chosen+'\n}\n')
 original=src('core/store/SettingsPrefsCache.kt');t=original
 t=t.replace('import android.annotation.SuppressLint\n','').replace('@SuppressLint("ApplySharedPref")\n','')
 t=t.replace('import android.content.Context','import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext as Context').replace('import android.content.SharedPreferences','import com.bilipai.desktop.ui.DesktopOriginalPlayerMirrorPreferences as SharedPreferences').replace('import androidx.datastore.preferences.core.MutablePreferences','import com.bilipai.desktop.ui.DesktopOriginalPlayerPreferenceValues as MutablePreferences').replace('import androidx.datastore.preferences.core.edit\n','')
 f.write(P/'prepared/generated/com/android/purebilibili/core/store/SettingsPrefsCache.kt',t)
 f.write(P/'settings-library-audit.json',json.dumps(dict(settingsManagerSHA256LF=f.sha(src('core/store/SettingsManager.kt')),selectedMembers=decls,settingsCacheOriginalSHA256LF=f.sha(original),settingsCacheCandidateSHA256LF=f.sha(t),cacheWriterWholeFile=True,platformAliasesOnly=True),indent=2)+'\n')
 # Full original two request bodies are appended to the EXISTING sole protocol class.
 original=src('data/repository/VideoRepository.kt');chosen,names=c.member_closure(body(original,'VideoRepository'),['getPortraitPlaybackDetails','preloadPortraitPlayUrl']) if False else ('',[])
 # Dependencies deliberately reference installed Core methods rather than reselecting its entire class.
 raw=body(original,'VideoRepository');tokens=c.parser.kotlin_tokens(raw);depth=pa=br=0;starts=[]
 for word,a,b in tokens:
  if depth==pa==br==0 and word in ['fun','val','var','class','object','interface']:
   tail=raw[b:];name=re.match(r'\s*(\w+)',tail).group(1);line=raw.rfind('\n',0,a)+1;starts.append((name,line))
  depth+=(word=='{')-(word=='}');pa+=(word=='(')-(word==')');br+=(word=='[')-(word==']')
 parts={n:raw[a:starts[i+1][1]if i+1<len(starts)else len(raw)]for i,(n,a)in enumerate(starts)}
 chunks=[];rows=[]
 for name in ['getPortraitPlaybackDetails','preloadPortraitPlayUrl']:
  originalBody=parts[name];t=originalBody.replace('PlayUrlCache.get(','environment.cache.get(').replace('com.android.purebilibili.core.util.Logger.','Logger.')
  t=t.replace('withContext(Dispatchers.IO) {\n        try {','withContext(Dispatchers.IO) {\n        environment.assertOwned()\n        try {',1)if name=='getPortraitPlaybackDetails'else t
  if name=='getPortraitPlaybackDetails':
   t=t.replace('\n    }\n\n','\n    }.also { environment.assertOwned() }\n\n',1)
  else:
   t=t.replace('return withContext(Dispatchers.IO) {','environment.assertOwned()\n        return withContext(Dispatchers.IO) {',1)
   close=t.rfind('\n        }\n');assert close>=0;t=t[:close]+t[close:].replace('\n        }\n','\n        }.also { environment.assertOwned() }\n',1)
   t=t.replace('targetQuality = targetQuality\n            )','targetQuality = targetQuality,\n                audioLang = null\n            )')
  chunks.append(t);rows.append(dict(name=name,originalBodySHA256LF=f.sha(originalBody),candidateBodySHA256LF=f.sha(t),originalBody=originalBody,candidateBody=t))
 fragment='\n'.join(chunks)
 f.write(P/'prepared/fragments/portrait-protocol-members.kt.fragment',fragment)
 f.write(P/'portrait-protocol-original-audit.json',json.dumps(dict(sourceSHA256LF=f.sha(original),methods=rows,existingHomeGetHomeVideosReference=True),ensure_ascii=False,indent=2)+'\n')
 path=MAIN/'desktop/.local/stable-video-state-holder-parity/producer-production-02/com/android/purebilibili/data/repository/DesktopOriginalVideoLoadProtocol.kt';base=f.read(path).decode()
 # Generated inputs are not manifest.inputs. Use preserved Core192 canonical source;
 # the actual55 product JAR remains the compiler dependency, with an explicit Core-only override.
 row=dict(path=str(path),sha256Bytes=f.sha(base),kind='frozen-Core192-production-source',notActual55ManifestInput=True)
 candidate=base[:base.rfind('}')]+fragment+base[base.rfind('}'):]
 candidate=candidate.replace('import kotlinx.coroutines.*','import kotlinx.coroutines.*\nimport com.android.purebilibili.feature.video.ui.pager.PORTRAIT_PLAYBACK_TARGET_QUALITY\nimport com.android.purebilibili.feature.video.ui.pager.shouldUsePortraitParallelPlaybackBootstrap')
 f.write(P/'proof-only/com/android/purebilibili/data/repository/DesktopOriginalVideoLoadProtocol.kt',candidate)
 f.write(P/'core-append-contract.json',json.dumps(dict(actual55Source=row,baseSHA256LF=f.sha(base),candidateSHA256LF=f.sha(candidate),fragmentPath='prepared/fragments/portrait-protocol-members.kt.fragment',fragmentSHA256LF=f.sha(fragment),overrideOnlyForProspectiveCompile=True,productionOwner='parent sole extract-upstream-video-state-core.py',getHomeVideos='REFERENCE existing DesktopOriginalHomeVideoProtocol, same retained instance'),indent=2)+'\n')
 print('Required same-store settings + exact Core two-member append prepared')
if __name__=='__main__':main()
