from pathlib import Path
import hashlib,importlib.util,json,re,sys
sys.dont_write_bytecode=True
P=Path(__file__).resolve().parent;MAIN=P.parents[2];REPO=MAIN.parent/'BiliPai-v023';BASE='app/src/main/java/com/android/purebilibili/'
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def put(p,t):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes(t.encode())
spec=importlib.util.spec_from_file_location('selector',MAIN/'desktop/.local/stable-video-player-full-controls-parity/prepare.py');selector=importlib.util.module_from_spec(spec);spec.loader.exec_module(selector)
ms=importlib.util.spec_from_file_location('mask',REPO/'desktop/tools/extract-upstream-dynamic-reply-protocol.py');mask=importlib.util.module_from_spec(ms);ms.loader.exec_module(mask)
raw=wide(P/'original-stable'/BASE/'core/store/SettingsManager.kt').read_text(encoding='utf-8');a=raw.index('object SettingsManager');m=mask.masked(raw);op=m.index('{',a);end=mask.balanced(m,op,'{','}');body=raw[op+1:end-1]
names=['getLongPressSpeedHintScale','getLongPressSpeedHintAlpha','getPortraitOnlyVerticalRecommendations','getQualitySwitchFailureDialogEnabled','getQualitySwitchFailureDialogOnceEnabled','getQualitySwitchFailureDialogShown','setQualitySwitchFailureDialogOnceEnabled','markQualitySwitchFailureDialogShown']
chosen,decls=selector.member_closure(body,names)
prefix='''package com.android.purebilibili.core.store
import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext as Context
import com.bilipai.desktop.ui.playerBooleanPreferencesKey as booleanPreferencesKey
import com.bilipai.desktop.ui.playerFloatPreferencesKey as floatPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
internal object DesktopOriginalVideoHolderSettings {
'''
defaults='\n'.join(re.search(r'^internal const val '+n+r' = .*$',raw,re.M)[0]for n in ['DEFAULT_QUALITY_SWITCH_FAILURE_DIALOG_ENABLED','DEFAULT_QUALITY_SWITCH_FAILURE_DIALOG_ONCE_ENABLED'])+'\n'
out=prefix+chosen+'\n}\n'+defaults;put(P/'prepared/generated/com/android/purebilibili/core/store/DesktopOriginalVideoHolderSettings.kt',out)
put(P/'holder-settings-source-audit.json',json.dumps(dict(passed=True,path=BASE+'core/store/SettingsManager.kt',sourceCommit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',selectedMembers=decls,originalSelectedBodySHA256LF=hashlib.sha256(chosen.encode()).hexdigest(),outputSHA256LF=hashlib.sha256(out.encode()).hexdigest(),completeSelectedOriginalBodies=True,secondStore=False),indent=2)+'\n')
print('Original Holder settings',decls)
