"""Original v030 live source sheet and bounded reload constant, sole media output."""
import json
from pathlib import Path
import re
import v030_live_stream as live

ROOT = Path(__file__).resolve().parent.parent / 'upstream-slices/v030-live-recovery'
BASE = 'app/src/main/java/com/android/purebilibili/feature/live/'
PINS = {
    BASE+'LivePlayerViewModel.kt': ('605d846a97f2faaf5af468d5757d180caf1ba098', 'e86a019aa43d9d5aca595ff6073bb0cf50d00436142bb5a0bda50655b2507e74', 63055),
    BASE+'LivePlayerScreen.kt': ('e3adc45d4cc252a01fce28d8703ddfa49fa94cf1', '7445e2c4ade4a53784acb5d425cba42e5391066eb39ae6a0fe479d4256a912b6', 133571),
    BASE+'components/LiveStreamSourceSheet.kt': ('d946f012c92dc45fd5cc7f76d9eae10f0e558f0f', '2ff81ee9da7cc3660df803d0a1f3fbcc38711952b791f531ce47e06960012fcf', 6138),
}

def sources():
    import hashlib
    manifest = json.loads(live.safe(ROOT/'manifest.json').read_bytes())
    if manifest.get('schemaVersion')!=1 or manifest.get('fixedUpstreamCommit')!=live.COMMIT or manifest.get('hashNormalization')!='raw':
        raise ValueError('Unknown fixed live recovery manifest')
    rows=manifest.get('files',[])
    if len(rows)!=3 or {x['path'] for x in rows}!=set(PINS):raise ValueError('Incomplete live recovery input')
    result={}
    for row in rows:
        path=row['path'];blob,digest,size=PINS[path]
        expected=dict(path=path,gitBlob=blob,sha256Bytes=digest,bytes=size,url='https://github.com/jay3-yy/BiliPai/blob/'+live.COMMIT+'/'+path)
        if row!=expected:raise ValueError('Live recovery input identity changed')
        raw=live.safe(ROOT/path).read_bytes()
        if len(raw)!=size or live.sha(raw)!=digest or hashlib.sha1(b'blob '+str(size).encode()+b'\0'+raw).hexdigest()!=blob:
            raise ValueError('Live recovery raw bytes changed')
        result[path]=raw.decode('utf8').replace('\r\n','\n')
    return result

def emit_recovery(repo, output):
    source=sources();sheet_path=BASE+'components/LiveStreamSourceSheet.kt';sheet=source[sheet_path]
    edits=[]
    adapted=live.counted(sheet,'import com.android.purebilibili.core.ui.AppModalBottomSheet\n',
        'import com.bilipai.desktop.ui.DesktopWindowsLiveSourceSheetBody as AppModalBottomSheet\n',
        'owned Windows dialog supplies the one native presentation; complete original sheet body unchanged',edits)
    target=live.safe(output/'com/android/purebilibili/feature/live/components/LiveStreamSourceSheet.kt')
    target.parent.mkdir(parents=True,exist_ok=True);target.write_text(adapted,encoding='utf8',newline='\n')
    vm_path=BASE+'LivePlayerViewModel.kt';vm=source[vm_path]
    found=re.findall(r'(?m)^\s*private const val MAX_PLAYBACK_RELOAD_ATTEMPTS = [0-9]+',vm)
    if len(found)!=1:raise ValueError('Original live retry budget changed')
    constant=found[0].strip();body='package com.android.purebilibili.feature.live\n\n'+constant.replace('private const','internal const',1)+'\n'
    budget=live.safe(output/'com/android/purebilibili/feature/live/DesktopLiveReloadBudget.kt')
    budget.parent.mkdir(parents=True,exist_ok=True);budget.write_text(body,encoding='utf8',newline='\n')
    proof=dict(upstreamCommit=live.COMMIT,rawPins=PINS,
        sheetCompleteInverse=live.whole_proof(sheet,adapted),countedSheetAdaptations=edits,
        budgetCompleteInverse=live.whole_proof(vm,body),budgetOriginal=constant,
        originalTryNextUrl=live.selected_function(repo,vm,'tryNextUrl'),
        originalManualSwitch=live.selected_function(repo,vm,'switchPlaybackCandidate'),
        strategyConsumer='DesktopLivePageMemory -> original advanceLivePlayback; MAX_PLAYBACK_RELOAD_ATTEMPTS',
        errorConsumer='typed PlayerFailure -> original resolveLivePlaybackErrorRecovery; never PlayerState.ended')
    live.safe(output/'v030-live-recovery-source-proof.json').write_text(json.dumps(proof,ensure_ascii=True,indent=2)+'\n',encoding='utf8',newline='\n')
    return [target,budget]
