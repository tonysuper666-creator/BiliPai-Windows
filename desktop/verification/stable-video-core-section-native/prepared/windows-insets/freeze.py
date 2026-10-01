"""Freeze source observations only. No product fix, fixture run, or physical acceptance."""
from pathlib import Path
import hashlib,json
LANE=Path(__file__).resolve().parent
def safe(p):
    s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92)
    return Path(s if s.startswith(prefix) else prefix+s)
def row(p):
    b=safe(p).read_bytes();return {'path':str(p),'sha256Bytes':hashlib.sha256(b).hexdigest(),'size':len(b)}
def js(name,v):safe(LANE/name).write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
inputs=json.loads(safe(LANE/'source-inputs.json').read_text(encoding='utf-8'))
native=json.loads(safe(LANE/'native/source-provenance.json').read_text(encoding='utf-8'))
assert native['fullCommit']=='1c417fbfc2f70ab03a565b0af0a5a3c6f5e15ad6'
anchors=[
 {'path':'jdk/java.desktop/java/awt/Container.java','line':413,'finding':'Container returns a clone of peer cached Insets, not an immediate OS client query.'},
 {'path':'jdk/java.desktop/sun/awt/windows/WPanelPeer.java','line':62,'finding':'getInsets directly returns existing insets_ cache.'},
 {'path':'jdk/java.desktop/sun/awt/windows/WFramePeer.java','line':143,'finding':'A decorated Frame reshape uses native reshapeFrame.'},
 {'path':'native/awt_Window.cpp','line':2574,'finding':'_ReshapeFrame dispatches WM_AWT_RESHAPE_COMPONENT to existing peer HWND.'},
 {'path':'native/awt_Component.cpp','line':1896,'finding':'Existing native message handler invokes virtual Reshape; no HWND replacement.'},
 {'path':'native/awt_Frame.cpp','line':654,'finding':'Normal noniconic/nonzoomed Frame follows AwtWindow.Reshape.'},
 {'path':'native/awt_Window.cpp','line':1220,'finding':'AwtWindow.Reshape performs current monitor DPI conversion and calls ReshapeNoScale; a manager-rejected size can invoke WmSize manually.'},
 {'path':'native/awt_Component.cpp','line':976,'finding':'ReshapeNoScale calls SetWindowPos or DeferWindowPos with SWP_NOACTIVATE|SWP_NOZORDER; dimensions are supplied, no SWP_NOSIZE.'},
 {'path':'native/awt_Frame.cpp','line':980,'finding':'Noniconic normal WM_SIZE delegates to AwtWindow.WmSize.'},
 {'path':'native/awt_Window.cpp','line':1933,'finding':'WmSize calls UpdateInsets(NULL) before writing target dimensions and WindowResized.'},
 {'path':'native/awt_Window.cpp','line':1397,'finding':'UpdateInsets computes native outside/client rectangles, scales down, and stores values in the existing Java peer Insets object.'},
 {'path':'native/awt_Window.cpp','line':1474,'finding':'Direct insets_ID object write refreshes existing WPanelPeer.inset_ used by Container.'},
 {'path':'native/awt_Window.cpp','line':1969,'finding':'Nonclient recalc also updates Insets when a valid rectangle exists.'},
 {'path':'native/awt_Window.h','line':356,'finding':'Setting FSEM flag does not itself refresh Insets; only flag and security-warning update.'},
 {'path':'jdk/java.desktop/sun/awt/Win32GraphicsDevice.java','line':412,'finding':'Superclass window bounds path precedes exclusive entry; Java follow-up updates GC, not explicit cached Insets.'},
 {'path':'jdk/java.desktop/sun/java2d/d3d/D3DGraphicsDevice.java','line':181,'finding':'D3D overrides native entry; actual53 does not separately record selected graphics device subtype, so no D3D-only runtime cause claimed.'}
]
for a in anchors:
    p=LANE/a['path'];assert safe(p).exists();a['source']=row(p)
js('findings.json',{'scope':'Post-closed actual53 readonly source analysis','runtimeSourceCommit':native['fullCommit'],
    'concreteAnchors':anchors,'publicAdapterRecommendation':
    'Extend sole existing placement actor with a per-revision fullscreen-entry ticket. After actual window.placement==Fullscreen on EDT and same captured graphics device/monitor/transform still holds, consume ticket once and apply current full Rectangle -> +1 on x/y/width/height -> current Rectangle -> validate. Existing Floating exit ticket remains independent. Never restore stale captured geometry or loop on the events caused by the repair.',
    'sourceFeasibility':'The public bounds path has a concrete existing HWND -> WM_SIZE -> UpdateInsets -> cached Java peer Insets refresh path. A real dimension change is necessary; validate alone cannot guarantee cache refresh.',
    'runtimeAcceptance':False,'guaranteedWindowsResizeAcceptance':False,'exactCppRootCauseObservedWithDebugger':False,
    'requiredNextAcceptance':'Root actual54 unchanged full renderer and fresh Sky visual observation; require initial and settled re-entered fullscreen full anchor/client alignment, normal exits preserved, current media source unchanged.',
    'knownActual53VisualFailure':'Settled re-entry: native Main client3840x2160, AWT anchor2546x1404 at7,30; Root fresh Sky saw inset border. The normal-Inset cache explanation is supported by sources and observed geometry, not a direct runtime peer-field read.',
    'notProposed':['dispose/recreate HWND','private peer access','add-opens','new GraphicsDevice fullscreen setter','native input injection','multiply AWT screen coordinates by guessed DPI']})
raw=[]
for ep in sorted(safe(LANE).rglob('*')):
    if ep.is_file() and ep.name!='frozen-handoff.json' and '__pycache__' not in ep.parts:
        raw.append(row(LANE/ep.relative_to(safe(LANE))))
js('frozen-handoff.json',{'status':'FROZEN_READONLY_SOURCE_FEASIBILITY_NOT_RUNTIME_ACCEPTANCE',
    'schema':'path,sha256Bytes,size','rawArtifacts':raw,'actualSnapshot':53,'runtimeEntries':101,
    'runtimeSourceCommit':native['fullCommit'],'sourceInputs':row(LANE/'source-inputs.json'),
    'findings':row(LANE/'findings.json'),'candidateMutations':False,'Gradle':False,
    'nativeWindowExecuted':False,'productionFixInstalled':False,'historicalReceiptsModified':False})
print(json.dumps({'receipt':row(LANE/'frozen-handoff.json'),'rawArtifacts':len(raw)},indent=2))
