"""One explicit Windows footer draw mapping; never edits Main or old evidence."""
from pathlib import Path
import difflib,hashlib,json,subprocess,sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2]
SNAP=REPO/'desktop/.local/dynamic-detail-container-main-integration/main-product-snapshot-02'
def raw(p):return p.read_bytes()
def sha(p):return hashlib.sha256(raw(p)).hexdigest()
def lf(p):return raw(p).replace(b'\r\n',b'\n')
def write(p,v):p.parent.mkdir(parents=True,exist_ok=True);assert not p.exists();p.write_text(v,encoding='utf-8',newline='\n')
def save(p,v):write(p,json.dumps(v,ensure_ascii=False,indent=2)+'\n')
manifest='f164846bb1ebe5545c727824bfa6fe853a5fca92bfd1bcfddf59abb53c943d17'
assert sha(SNAP/'manifest.json')==manifest
m=json.loads(raw(SNAP/'manifest.json'));pins={r['path']:r['sha256Lf'] for r in m['sourceFiles']}
generated={r['path']:r['sha256Bytes'] for r in m['generatedProductFiles']}
canvas='desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopCommentImageCanvas.kt'
producer='desktop/tools/extract-upstream-dynamic-reply.py'
saver='app/src/main/java/com/android/purebilibili/feature/video/ui/components/ReplyCommentImageSaver.kt'
renderer='desktop/build/generated/dynamic-reply/com/android/purebilibili/feature/video/ui/components/DesktopOriginalReplyImageRenderer.kt'
for rel in (canvas,producer):assert hashlib.sha256(lf(REPO/rel)).hexdigest()==pins[rel],rel
assert sha(REPO/renderer)==generated[renderer]
original=subprocess.check_output(['git','show','v0.2.3-alpha.9:'+saver],cwd=REPO).replace(b'\r\n',b'\n')
assert original==lf(REPO/saver)
for rel in (canvas,producer,saver,renderer):write(HERE/'original'/Path(rel).name,lf(REPO/rel).decode('utf-8'))
source=lf(REPO/canvas).decode('utf-8')
needle='    fun drawRect(left: Float, top: Float, right: Float, bottom: Float, value: DesktopCommentPaint) {'
assert source.count(needle)==1
method='''    /** Only the printed footer URL is clipped to the existing qrLeft boundary.
     * QR/spec URL, original font, coordinates, geometry and other text stay intact.
     * Graphics state is isolated; no global drawText clipping policy is changed. */
    fun drawFooterTextBeforeQr(text: String, x: Float, y: Float, value: DesktopCommentPaint, qrLeft: Float) {
        paint(value)
        val clipped = graphics.create() as java.awt.Graphics2D
        try {
            clipped.clip(Rectangle2D.Float(x, 0f, (qrLeft - x).coerceAtLeast(0f), height.toFloat()))
            clipped.drawString(text, x, y)
        } finally { clipped.dispose() }
    }
'''
candidate=source.replace(needle,method+needle,1)
prepared=HERE/'prepared'/canvas;write(prepared,candidate)
write(HERE/'canvas-vs-main.diff',''.join(difflib.unified_diff(source.splitlines(True),candidate.splitlines(True),fromfile='actual-Main-Canvas',tofile='prepared-Windows-footer-clip')))
tool=lf(REPO/producer).decode('utf-8')
anchor="    body = body.replace('    return bitmap\\n}', '    canvas.close()\\n    return bitmap\\n}', 1)\n"
assert tool.count(anchor)==1
oldcall='canvas.drawText(spec.qrUrl, footerLeft, footerBaseline + 84f, tinyPaint)'
newcall='canvas.drawFooterTextBeforeQr(spec.qrUrl, footerLeft, footerBaseline + 84f, tinyPaint, qrLeft)'
adaptation="    # Windows AWT footer URL glyphs must not paint over the original QR bitmap.\n    # Keep the complete original spec/QR payload; only this printed URL is clipped.\n    body = replace_once(body, "+repr(oldcall)+",\n                        "+repr(newcall)+")\n"
newtool=tool.replace(anchor,anchor+adaptation,1)
preparedtool=HERE/'prepared'/producer;write(preparedtool,newtool)
write(HERE/'producer-vs-main.diff',''.join(difflib.unified_diff(tool.splitlines(True),newtool.splitlines(True),fromfile='actual-Main-producer',tofile='prepared-Windows-footer-clip')))
for label,p in (('baseline',REPO/producer),('candidate',preparedtool)):
    out=HERE/'generated'/label
    result=subprocess.run([sys.executable,str(p),'--repo',str(REPO),'--output',str(out)],capture_output=True,text=True,encoding='utf-8',errors='replace')
    write(HERE/f'producer-{label}.log',result.stdout+result.stderr);result.check_returncode()
base=HERE/'generated/baseline';cand=HERE/'generated/candidate'
basefiles=sorted(p for p in base.rglob('*') if p.is_file())
changes=[]
for p in basefiles:
    rel=p.relative_to(base);q=cand/rel;assert q.is_file()
    if raw(p)!=raw(q):changes.append(str(rel).replace('\\','/'))
assert len(changes)==1 and changes[0].endswith('/DesktopOriginalReplyImageRenderer.kt'),changes
renderbase=base/changes[0];rendercand=cand/changes[0]
assert lf(renderbase)==lf(REPO/renderer)
assert raw(rendercand)==raw(renderbase).replace(oldcall.encode(),newcall.encode(),1)
write(HERE/'renderer-vs-main.diff',''.join(difflib.unified_diff(raw(renderbase).decode().splitlines(True),raw(rendercand).decode().splitlines(True),fromfile='actual-Main-generated-renderer',tofile='prepared-Windows-one-call-mapping')))
save(HERE/'source-audit.json',{
    'actualMainManifestSha256Bytes':manifest,'sourceTag':'v0.2.3-alpha.9',
    'originalSaverLfSha256':hashlib.sha256(original).hexdigest(),'GitTagOriginalSaverByteEqual':True,
    'actualMainCanvasLfSha256':pins[canvas],'candidateCanvasSha256Bytes':sha(prepared),
    'actualMainProducerLfSha256':pins[producer],'candidateProducerSha256Bytes':sha(preparedtool),
    'actualMainRendererSha256Bytes':generated[renderer],'candidateRendererSha256Bytes':sha(rendercand),
    'freshOriginalProducerAllOutputs':len(basefiles),'onlyChangedGeneratedOutput':changes,
    'onlyRendererCallMapping':{'old':oldcall,'new':newcall},'rendererAllOtherBytesIdentical':True,
    'noOriginalSpecOrUrlPayloadChanges':True,'allDrawTextUnchangedExceptExplicitPrintedFooterURLCall':True,
    'writerBodyAndSignatureUnchanged':True,'AndroidTypographyUsesDefaultPaintAndDefaultBold':True,
    'WindowsTypographyUsesDialogAWTFont':True,'AndroidActualRenderExecuted':False,
    'baselineWindowsQrOverlapObservedInSeparateFrozenPNG77':True,
    'printedFooterUrlCanBeVisuallyClipped':True,'fullQrPayloadAndOriginalGeometryPreserved':True,
    'writesMainOrGradle':False,
})
print('PASS exact one-call renderer mapping; original Git bytes; fresh producer diff only renderer')
