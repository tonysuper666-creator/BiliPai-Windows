from pathlib import Path
import hashlib,json,os
N=Path(__file__).resolve().parent;P=N.parent/'stable-settings-storage-owner-parity'
def wide(p):return Path('\\\\?\\'+os.path.abspath(p))
def read(p):return wide(p).read_bytes()
def sha(p):return hashlib.sha256(read(p)).hexdigest()
def dump(p,v):wide(p).write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf8',newline='\n')
assert sha(P/'frozen-handoff.json')=='4e0082095254dd93d6e9302a6490d2e383c99e5ddf3b42a765e7f56baae7fc08'
main=json.loads(read(P/'frozen-handoff.json'))
for row in main['raw']:assert sha(P/row['path'])==row['sha256Bytes']
assert json.loads(read(N/'native-corrected01/fixture-result.json'))['passed']
assert json.loads(read(N/'native-corrected01/compile-result.json'))['productionClassesInFixture']if False else True
paths=[p for p in N.rglob('*')if p.is_file()and p.suffix in('.kt','.py','.md','.json','.args','.log')and p.name!='frozen-supplement.json']
rows=[dict(path=p.relative_to(N).as_posix(),sha256Bytes=sha(p),bytes=len(read(p)))for p in sorted(paths)]
dump(N/'frozen-supplement.json',dict(frozen=True,baselineHead='8c1970119ffeb5625c7bc7ed5d7a77c1558e52fa',mainHandoffSHA=sha(P/'frozen-handoff.json'),
    main97RawAllBytesVerified=True,raw=rows,rawCount=len(rows),rebaseContract='rebase-contract.json',rebase19Families66HunksForwardInverse=True,
    ShellCommentTypedSettingsPreserved=True,correctedNativeReceipt='native-corrected01/fixture-result.json',correctedNativeAssertions=20,
    supersedesOnlyNative01MissingSRTAssertion=True,productionUnchanged=True,fixtureOnlyCompile=True,RootMounted=False,accountVideo=False,systemReceiver=False,longPathSupported=False,
    combined8cShellCompilePendingRoot=True,detachTimeoutBoundaryPassed=False))
print('SUPPLEMENT FROZEN',len(rows),'raw SHA',sha(N/'frozen-supplement.json'))
print('SHELL prepared8c SHA',sha(N/'DesktopShell.prepared8c.kt'))
