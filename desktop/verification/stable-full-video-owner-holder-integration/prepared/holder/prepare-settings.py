from pathlib import Path
import hashlib,importlib.util,json,subprocess,sys
sys.dont_write_bytecode=True
P=Path(__file__).resolve().parent;MAIN=P.parents[2];REPO=MAIN.parent/'BiliPai-v023';COMMIT='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589';BASE='app/src/main/java/com/android/purebilibili/'
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def put(p,t):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes(t.encode())
def sha(t):return hashlib.sha256(t.encode()).hexdigest()
spec=importlib.util.spec_from_file_location('selector',MAIN/'desktop/.local/stable-video-player-full-controls-parity/prepare.py');selector=importlib.util.module_from_spec(spec);spec.loader.exec_module(selector)
raw=subprocess.check_output(['git','-C',str(REPO),'show',COMMIT+':'+BASE+'core/store/SettingsManager.kt']).replace(b'\r\n',b'\n').decode();put(P/'original-stable'/BASE/'core/store/SettingsManager.kt',raw)
ms=importlib.util.spec_from_file_location('mask',REPO/'desktop/tools/extract-upstream-dynamic-reply-protocol.py');mask=importlib.util.module_from_spec(ms);ms.loader.exec_module(mask)
a=raw.index('object SettingsManager');m=mask.masked(raw);op=m.index('{',a);end=mask.balanced(m,op,'{','}');body=raw[op+1:end-1]
names=['getDanmakuSendColor','setDanmakuSendColor','getDanmakuSendMode','setDanmakuSendMode','getDanmakuSendFontSize','setDanmakuSendFontSize']
chosen,decls=selector.member_closure(body,names)
prefix='''package com.android.purebilibili.core.store
import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext as Context
import com.bilipai.desktop.ui.playerIntPreferencesKey as intPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
internal object DesktopOriginalVideoHolderSendSettings {
'''
out=prefix+chosen+'\n}\n';put(P/'prepared/generated/com/android/purebilibili/core/store/DesktopOriginalVideoHolderSendSettings.kt',out)
put(P/'send-settings-source-audit.json',json.dumps(dict(passed=True,sourceCommit=COMMIT,path=BASE+'core/store/SettingsManager.kt',originalSHA256LF=sha(raw),selectedMembers=decls,originalSelectedBodySHA256LF=sha(chosen),outputSHA256LF=sha(out),completeSelectedOriginalBodies=True,platformAliasesOnly=True,secondStore=False),indent=2)+'\n')
print('Original send settings closure',decls)
