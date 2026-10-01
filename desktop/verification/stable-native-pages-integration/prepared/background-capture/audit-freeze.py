from pathlib import Path
import hashlib,importlib.util,json,sys,zipfile
from PIL import Image
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('tool',HERE/'run-fixture.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
def main():
 target=HERE/'frozen-handoff.json';assert not c.safe(target).exists()
 checks=[]
 def check(name,value):assert value,name;checks.append(dict(name=name,pass_=True))
 accepted=json.loads(c.safe(HERE/'proof-02/accepted-result.json').read_text(encoding='utf-8'))
 raw=json.loads(c.safe(HERE/'proof-02/scratch/result.json').read_text(encoding='utf-8'))
 check('accepted one focused cell',accepted['status']=='PASS' and accepted['caseCount']==1 and accepted['assertions']==25)
 check('zero product class overlap/overrides',accepted['actualProductOverrides']==0 and accepted['classOverlap']==[])
 check('same fixture source bytes',c.sha(HERE/'CaptureFixture.kt')==accepted['fixtureSourceSha256Bytes'])
 with zipfile.ZipFile(c.safe(c.SNAP/'main-kotlin.jar')) as jar:
  for row in raw['actualCodeSources']:
   check('actual identity '+row['class'],hashlib.sha256(jar.read(row['class'].replace('.','/')+'.class')).hexdigest()==row['sha256ClassBytes'])
 pngs=[]
 for filename,background in [('white-normal-with-foreground.png',(255,255,255)),('black-normal-with-foreground.png',(0,0,0))]:
  p=HERE/'proof-02/scratch'/filename
  with Image.open(c.safe(p)) as image:
   image.load();rgb=image.convert('RGB');check('PNG dimensions '+filename,image.size==(360,160))
   pixels=list(rgb.crop((0,90,360,160)).getdata());check('normal background pixels '+filename,all(x==background for x in pixels))
   foreground=list(rgb.crop((16,16,344,90)).getdata());count=sum(x!=background for x in foreground);check('actual original liquid row pixels independent of sentinel '+filename,count>100)
   sentinel=list(rgb.crop((0,0,8,8)).getdata());check('normal draw red sentinel '+filename,all(x==(255,0,0) for x in sentinel))
   pngs.append(dict(path=str(p),sha256Bytes=c.sha(p),normalRowNonBackgroundPixels=count,backgroundRgb=background))
 c.save(HERE/'independent-pillow-result.json',dict(status='PASS',imageCount=2,images=pngs,checks=checks[-8:],scope='Read actual normal draw PNGs; background layer purity comes from actual sampleBitmap assertions, not screenshot inference.'))
 snapshot=json.loads(c.safe(c.SNAP/'manifest.json').read_text(encoding='utf-8'));refs=[]
 stable=c.MAIN.parent/'BiliPai-v023'
 for path in ['desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt','desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopLiquidReadabilityPlatform.kt','desktop/tools/extract-upstream-shared-liquid-tabs.py']:
  source=stable/path;expected=next(x['sha256Bytes'] for x in snapshot['inputs'] if x['path']==path);actual=c.sha(source)
  refs.append(dict(path=str(source),currentSha256Bytes=actual,snapshot14InputSha256Bytes=expected,currentBytesMatchSnapshot14Input=actual==expected,role='read-only source reference; actual product identity is immutable Main14 jar'))
 # Keep only exact reviewed pattern fragments; no product sources are generated or installed.
 shell=c.safe(stable/'desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt').read_text(encoding='utf-8')
 start=shell.index('        Surface(Modifier.fillMaxSize().onGloballyPositioned { liquidBackgroundBounds = it.boundsInWindow() }')
 end=shell.index('            }.onKeyEvent',start)
 pattern=shell[start:end]
 c.write(HERE/'reference-root-record-pattern.txt',pattern+'\n')
 check('Root capture order retained in fixture',all(x in c.safe(HERE/'CaptureFixture.kt').read_text(encoding='utf-8') for x in ['source.recordBackground {','layer.record { this@drawWithContent.drawContent() }','drawContent()']))
 c.save(HERE/'source-and-identity-audit.json',dict(status='PASS',checkCount=len(checks),checks=checks,references=refs,exactRootPatternSha256LF=hashlib.sha256(pattern.encode()).hexdigest(),sixActualClasses=raw['actualCodeSources'],independentPillowSha256Bytes=c.sha(HERE/'independent-pillow-result.json'),actualMain14ManifestSha256Bytes=c.sha(c.SNAP/'manifest.json'),actualCp92Sha256Bytes=c.sha(c.SNAP/'ordered-runtime-cp.json')))
 rows=[]
 for p in sorted(c.safe(HERE).rglob('*')):
  if p.is_file():rows.append(dict(path=p.relative_to(c.safe(HERE)).as_posix(),sha256Bytes=c.sha(p),bytes=p.stat().st_size))
 c.save(target,dict(frozen=True,artifactCount=len(rows),artifacts=rows,scope='Evidence-only actual Main14 liquid background capture seam; no install payload',installPayload=[],actualMain14ManifestSha256Bytes=c.sha(c.SNAP/'manifest.json'),actualCp92Sha256Bytes=c.sha(c.SNAP/'ordered-runtime-cp.json'),proof=dict(cells=1,assertions=25,actualBackgroundSamples=2,actualCodeSources=6,productOverrides=0,independentPillowImages=2,actualRootMounted=False,noHWND=True,noHTTP=True,noMainEdits=True,noSharedGradle=True),history='proof-01 retained: capture/normal/finally passed but string readability preference decoded original STABLE; proof-02 uses original ADAPTIVE integer and independently asserts it. No product source changed.'))
 print(json.dumps(dict(manifest=str(target),sha256Bytes=c.sha(target),artifacts=len(rows),auditSha256Bytes=c.sha(HERE/'source-and-identity-audit.json'),pillowSha256Bytes=c.sha(HERE/'independent-pillow-result.json'))))
if __name__=='__main__':main()
