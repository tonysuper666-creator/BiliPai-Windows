from pathlib import Path
import importlib.util,re,json,hashlib
HERE=Path(__file__).parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
p=HERE/'prepare.py';s=p.read_text(encoding='utf-8-sig')
pos=s.index("write(HERE/'producer-inventory.json'")
insert='''# Windows binds original frame/scroll metric state changes to the Root's real metric sink.
path='app/src/main/java/com/android/purebilibili/core/ui/performance/JankTracking.kt';s=read(REPO/path)
text='package com.android.purebilibili.core.ui.performance\\nimport androidx.compose.runtime.*\\nimport androidx.compose.foundation.gestures.ScrollableState\\nimport kotlinx.coroutines.CoroutineScope\\nimport com.bilipai.desktop.ui.*\\n\\n'+ '\\n\\n'.join(d for n,d in declarations(s) if n in ['rememberMetricsStateHolder','TrackJank','TrackScrollJank','TrackJankStateValue','TrackJankStateFlag'])
start=text.index('@Composable');end=text.index('@Composable',start+1)
text=text[:start]+'@Composable\\nfun rememberMetricsStateHolder(): DesktopHomeMetricHolder = LocalDesktopHomeMetricHolder.current\\n\\n'+text[end:]
text=text.replace('Holder','DesktopHomeMetricHolder').replace('DesktopHomeMetricDesktopHomeMetricHolder','DesktopHomeMetricHolder')
write(HERE/'prepared/generated/com/android/purebilibili/core/ui/performance/DesktopHomeJankTracking.kt',text)
report.append(dict(path=path,sha256LF=hashlib.sha256(s.encode()).hexdigest(),mode='policy-extract',selectedDeclarations=['rememberMetricsStateHolder','TrackJank','TrackScrollJank','TrackJankStateValue','TrackJankStateFlag'],reason='only Android hierarchy Holder lookup becomes required current Windows window metric port; original state changes/scroll collectors retained'))
path='app/src/main/java/com/android/purebilibili/core/util/ModifierExt.kt';s=read(REPO/path)
text='package com.android.purebilibili.core.util\\nimport androidx.compose.animation.core.*\\nimport androidx.compose.foundation.*\\nimport androidx.compose.foundation.interaction.*\\nimport androidx.compose.runtime.*\\nimport androidx.compose.ui.Modifier\\nimport androidx.compose.ui.composed\\nimport androidx.compose.ui.graphics.graphicsLayer\\n'+ '\\n\\n'.join(d for n,d in declarations(s) if n=='iOSCardTapEffect')
write(HERE/'prepared/generated/com/android/purebilibili/core/util/DesktopHomeCardTapEffect.kt',text)
report.append(dict(path=path,sha256LF=hashlib.sha256(s.encode()).hexdigest(),mode='policy-extract',selectedDeclarations=['iOSCardTapEffect'],reason='missing exact original modifier; existing HapticType/feedback implementation sole reused'))
'''
s=s[:pos]+insert+s[pos:];p.write_text(s,encoding='utf-8',newline='\n')
