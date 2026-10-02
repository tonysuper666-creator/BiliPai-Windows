from pathlib import Path
import hashlib,json

MAIN=Path('C:/Users/TONYS/Documents/Codex/2026-09-29/https-github-com-jay3-yy-bilipai/work/BiliPai')
SOURCE=MAIN/'desktop/.local/stable-video-root-retained-cleanup-delta'
PREVIOUS=MAIN/'desktop/.local/stable-video-root-lifecycle-review'
OUT=Path(__file__).parent
CANDIDATE=MAIN.parent/'BiliPai-v023'
def wide(p):
    s=str(Path(p).absolute()); prefix=chr(92)*2+'?'+chr(92)
    return Path(s if s.startswith(prefix) else prefix+s)
def read(p): return wide(p).read_bytes()
def sha(b):return hashlib.sha256(b).hexdigest()
def lf(b):return b.replace(b'\r\n',b'\n')
def put(p,b):
    dest=wide(OUT/p);dest.parent.mkdir(parents=True,exist_ok=True)
    if dest.exists():raise RuntimeError('Review is immutable: '+str(p))
    dest.write_bytes(b)
def dump(v):return (json.dumps(v,ensure_ascii=False,indent=2)+'\n').encode()

hunks= json.loads(read(SOURCE/'exact-hunks.json'))
assert len(hunks)==4
inputs=[];replays=[]
def capture(label,path):
    b=read(path);put('inputs/'+label,b)
    inputs.append({'path':'inputs/'+label,'sourcePath':path.as_posix(),'sha256Bytes':sha(b),'size':len(b)})
    return b
capture('exact-hunks.json',SOURCE/'exact-hunks.json')
for h in hunks:
    name=Path(h['target']).name
    after=capture(name,SOURCE/'prepared/after'/h['target'])
    baseline=read(PREVIOUS/'inputs'/name)
    beforeText=lf(baseline).decode('utf-8-sig'); afterText=lf(after).decode('utf-8-sig')
    assert sha(lf(baseline))==h['baselineWholeSHA256LF']
    assert sha(lf(after))==h['afterWholeSHA256LF']
    assert beforeText.count(h['before'])==h['count']==1
    assert beforeText.replace(h['before'],h['after'],1)==afterText
    assert afterText.replace(h['after'],h['before'],1)==beforeText
    replays.append({'target':h['target'],'baselineWholeSHA256LF':sha(lf(baseline)),
        'afterWholeSHA256LF':sha(lf(after)),'exactForward':True,'exactInverse':True,'onlyDeclaredHunk':True})

for label,relative in {'author-compile02-result.json':'runs/02/result.json',
    'author-compile02-log.txt':'runs/02/compile.log',
    'author-compile02-pins-before.json':'runs/02/pins-before.json',
    'author-compile02-pins-after.json':'runs/02/pins-after.json'}.items():
    capture(label,SOURCE/relative)
for name in ['DesktopHomeRootFactory.kt','DesktopHomeRetainedEntry.kt']:
    capture(name,CANDIDATE/'desktop/src/main/kotlin/com/bilipai/desktop/ui'/name)
capture('previous-frozen-review.json',PREVIOUS/'frozen-review.json')

receipt={'kind':'independent source-only cleanup postfix review','blockingCount':0,
 'appliesWhen':'Four after files and exact-hunks match these pins at final Parent installation. Prior failed review remains immutable.',
 'resolvesPriorBlocker':{'previousManifestSha256':sha(read(PREVIOUS/'frozen-review.json')),
    'id':'retirement-reference-cleared-before-child-drain-success'},
 'sourceReplay':replays,
 'observations':[
  {'boundary':'failed downstream drain','result':'corrected','locations':'ShellOwner 98-99; ShellMount 34-36; Assembler 384-391',
   'detail':'If lyric false/throw or Window close throws, afterDrain does not return; neither factory.afterDrain nor windows CAS executes. Slot still owns exact old Assembly/Factory. Factory.gate remains available for retry; platform AtomicReference is retained until success.'},
  {'boundary':'successful drain identity','result':'corrected','detail':'Window reference clears only captured previousWindows via compareAndSet after successful cleanup. Factory Built clears only after that successful callback. Slot then clears its own exact refs. No added actor, producer, store, native player, or queue.'},
  {'boundary':'same-epoch stop / temporary route cover','result':'source-consistent','locations':'Assembler 388-389; HomeRootFactory 79; HomeRetainedEntry 53',
   'detail':'Dismiss condition uses real root resource lifetime, account epoch, or Home retained gate/VM retirement. Ordinary video gate retirement alone and current route cover are absent from condition; live Home owner and same epoch keep the two original presentation flows.'},
  {'boundary':'account/window/restore retirement','result':'source-consistent','locations':'Shell 247-259/320-330/555-578/1417; Assembler 388-389',
   'detail':'Existing drain sets ordinaryVideoResourcesRetired before owner cleanup; actual restore/shutdown hooks await this drain before closing Home/Store/media. Required ShellResources.rootAlive now includes that flag. Epoch change or retired retained Home owner independently clears original Session after child and Window cleanup succeeds, before old entry reference releases.'}],
 'authorEvidence':{'compile02Passed':True,'inputs':4,'actual79RuntimeEntries':101,
    'explicitProspectiveFamilies':True,'RootMounted':False,'Native':False,'Window':False,'HTTP':False},
 'scopeLimit':'Source review of four local hunks only. No new compiler/runtime/window/input/native/HTTP test. Actual Main UI, cleanup failure runtime, and hardware controls not accepted by this receipt.',
 'inputs':inputs,'reviewerExecution':{'compile':False,'runtime':False,'Window':False,'HTTP':False,'CandidateMutation':False}}
put('review-receipt.json',dump(receipt))
artifacts=[]
for p in sorted(OUT.rglob('*')):
    if p.is_file():
        b=read(p);artifacts.append({'path':p.relative_to(OUT).as_posix(),'sha256Bytes':sha(b),'size':len(b)})
put('frozen-review.json',dump({'schema':'source-only-review-v1','blockingCount':0,
    'rawCount':len(artifacts),'artifacts':artifacts,'immutable':True}))
print(json.dumps({'manifestSha256':sha(read(OUT/'frozen-review.json')),
    'receiptSha256':sha(read(OUT/'review-receipt.json')),'rawCount':len(artifacts),
    'blockingCount':0,'replays':replays},ensure_ascii=False,indent=2))
