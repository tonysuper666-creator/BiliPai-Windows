"""Exact MainActivity crash-prompt policy/dialog; only Android side effects become ports."""
from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import argparse,hashlib,importlib.util,json,sys
sys.dont_write_bytecode=True
SOURCE='app/src/main/java/com/android/purebilibili/MainActivity.kt'
PIN='fb836e7cd9a798e05230b6b92d813d99464ea170d863a1cf091a8ebbbef41ada'
def load(p,name):
    s=importlib.util.spec_from_file_location(name,p);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
def generate(repo,out):
    text=(_desktop_canonical_source(repo, SOURCE)).read_text(encoding='utf-8').replace('\r\n','\n')
    assert hashlib.sha256(text.encode()).hexdigest()==PIN,'Fixed original MainActivity changed'
    host=load(repo/'desktop/tools/extract-upstream-plugins.py','crashhost');media=host.media_extractor(repo);parser=media.parser_for(repo)
    start=text.index('internal enum class CrashLogPromptAction {')
    policy=text[start:text.index('internal fun shouldUseRealtimeSplashBlur',start)].rstrip()
    condition=text.index('                    if (\n                        shouldShowPendingCrashLogPrompt(',text.index('var pendingCrashSnapshotPath'))
    start=text.index('AppAlertDialog(',condition)
    tokens=[t for t in parser.kotlin_tokens(text) if t[1]>=start]
    opening=next(i for i,t in enumerate(tokens) if t[0]=='(');depth=1;i=opening
    while depth:
        i+=1;depth+=(tokens[i][0]=='(')-(tokens[i][0]==')')
    dialog=text[start:tokens[i][2]]
    for action,old in [('DISMISS','''hasHandledCrashPrompt = true
                                if (shouldClearPendingCrashLogAfterAction(CrashLogPromptAction.DISMISS)) {
                                    Logger.clearPendingCrashSnapshot(context)
                                    pendingCrashSnapshotPath = null
                                }'''),('SHARE','''hasHandledCrashPrompt = true
                                    Logger.sharePendingCrashSnapshot(context)
                                    if (shouldClearPendingCrashLogAfterAction(CrashLogPromptAction.SHARE)) {
                                        Logger.clearPendingCrashSnapshot(context)
                                        pendingCrashSnapshotPath = null
                                    }'''),('DISMISS','''hasHandledCrashPrompt = true
                                    if (shouldClearPendingCrashLogAfterAction(CrashLogPromptAction.DISMISS)) {
                                        Logger.clearPendingCrashSnapshot(context)
                                        pendingCrashSnapshotPath = null
                                    }''')]:
        assert dialog.count(old)==1,'Original crash action seam changed'
        dialog=dialog.replace(old,'onAction(CrashLogPromptAction.'+action+')')
    header='// GENERATED from '+SOURCE+'; do not edit.\n// LF-normalized SHA-256: '+PIN+'\n'
    base=out/'com/android/purebilibili';base.mkdir(parents=True,exist_ok=True)
    (base/'DesktopCrashLogPromptPolicy.kt').write_text(header+'package com.android.purebilibili\n'+policy+'\n',encoding='utf-8',newline='\n')
    ui='''package com.android.purebilibili
import androidx.compose.runtime.Composable
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.core.ui.AppAlertDialog
import com.android.purebilibili.core.ui.AppDialogAction
@Composable
internal fun DesktopPendingCrashLogPrompt(hasPendingCrashSnapshot:Boolean,hasPromptBeenHandled:Boolean,onAction:(CrashLogPromptAction)->Unit) {
    if(shouldShowPendingCrashLogPrompt(hasPendingCrashSnapshot,hasPromptBeenHandled)) {
'''+dialog+'\n    }\n}\n'
    (base/'DesktopPendingCrashLogPrompt.kt').write_text(header+ui,encoding='utf-8',newline='\n')
    reference=out.parent/'reference-only';reference.mkdir(exist_ok=True)
    (reference/'OriginalCrashLogPrompt.kt').write_text(text[condition:tokens[i][2]]+'\n                    }\n',encoding='utf-8',newline='\n')
def inventory():return [dict(path=SOURCE,mode='policy-extract',features=['settings-crash-prompt-parity'],sha256=PIN)]
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--repo',type=Path,required=True);p.add_argument('--output',type=Path);p.add_argument('--inventory',action='store_true');a=p.parse_args()
    if a.inventory:print(json.dumps(inventory(),indent=2))
    else:generate(a.repo,a.output)
