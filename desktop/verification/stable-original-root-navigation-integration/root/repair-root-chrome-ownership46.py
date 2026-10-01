from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2].parent/'BiliPai-v023';OUT=HERE/'original-root-navigation-install46/chrome-sole-ownership-repair';assert not OUT.exists()
p=REPO/'desktop/tools/extract-upstream-root-home-navigation.py';raw=p.read_bytes();text=raw.decode().replace('\r\n','\n')
before="""    selected('app/src/main/java/com/android/purebilibili/core/ui/transition/VideoCardTransitionBackgroundPolicy.kt',
        ['videoCardTransitionChromeReveal'],
        'com/android/purebilibili/core/ui/transition/DesktopOriginalRootChromeReveal.kt',
        'package com.android.purebilibili.core.ui.transition\\nimport androidx.compose.ui.Modifier\\nimport androidx.compose.ui.graphics.graphicsLayer\\nimport androidx.compose.ui.layout.layout\\nimport kotlin.math.roundToInt\\n\\n')"""
after="""    # Reuse the already emitted full original policy in home-full-card; one top-function owner.
    stale_chrome = out / 'com/android/purebilibili/core/ui/transition/DesktopOriginalRootChromeReveal.kt'
    if stale_chrome.exists():
        stale_chrome.unlink()"""
assert text.count(before)==1;result=text.replace(before,after,1);OUT.mkdir();(OUT/p.name).write_bytes(raw)
(OUT/'repair.json').write_text(json.dumps(dict(path='desktop/tools/extract-upstream-root-home-navigation.py',before=before,after=after,baseSha256LF=hashlib.sha256(text.encode()).hexdigest(),candidateSha256LF=hashlib.sha256(result.encode()).hexdigest(),reason='Whole source compiler caught duplicate internal top function already owned by full original Home card policy; Root directly consumes that existing same-module function',sameOriginalFunctionBodyPreserved=True,sourceRegistryIdentityUnchanged=True,staleOutputCleanupOneExactFile=True),indent=2)+'\n',encoding='utf-8')
p.write_text(result,encoding='utf-8',newline='\n');print('Original chrome reveal has one canonical producer; Root consumes existing full policy')
