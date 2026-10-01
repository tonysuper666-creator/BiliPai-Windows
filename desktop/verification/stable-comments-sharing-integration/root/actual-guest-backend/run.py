from pathlib import Path
import hashlib, json, subprocess, os

HERE=Path(__file__).resolve().parent
MAIN=HERE.parents[2]
REPO=MAIN.parent/'BiliPai-v023'
SNAP=MAIN/'desktop/.local/stable-product-snapshot-16'
JAVA=MAIN.parent/'toolchain/jdk/jdk-21.0.12.1+1/bin/java.exe'
def sha(b):return hashlib.sha256(b).hexdigest()
def safe(p):
    s=str(Path(p).absolute())
    return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def read(p):return safe(p).read_bytes()
assert sha(read(SNAP/'manifest.json'))=='d5a8a3d5bfea1f5ad1911448d974344e5567e1069d205865a1417f29d2ab7c18'
raw=read(SNAP/'ordered-runtime-cp.json')
assert sha(raw)=='5a67a5cab59c2f1e3b9cd7fa04995bd03dfb61480bfc2c9b1654348d14f025eb'
cp=json.loads(raw)
for row in cp:assert sha(read(row['path']))==row['sha256Bytes'],row['path']
run=HERE/'run-01';assert not run.exists();run.mkdir()
profile=run/'isolated-localappdata';profile.mkdir()
args=['-Dfile.encoding=UTF-8','-Dsun.stdout.encoding=UTF-8','-Dsun.stderr.encoding=UTF-8',
    '-cp',';'.join(r['path'] for r in cp),'com.bilipai.desktop.MainKt','--backend-smoke']
argfile=run/'runtime.args';argfile.write_text('\n'.join('"'+a.replace('\\','/')+'"' for a in args),encoding='utf-8')
p=subprocess.run([str(JAVA),'@'+str(argfile)],cwd=REPO,env=dict(os.environ,LOCALAPPDATA=str(profile)),
    capture_output=True,text=True,encoding='utf-8',timeout=90)
log=p.stdout+p.stderr;(run/'runtime.log').write_text(log,encoding='utf-8')
report=dict(exitCode=p.returncode,passed=p.returncode==0 and 'BACKEND_SMOKE_OK' in log,
    snapshotManifestSha256Bytes='d5a8a3d5bfea1f5ad1911448d974344e5567e1069d205865a1417f29d2ab7c18',
    orderedCpSha256Bytes=sha(raw),actualProductEntries=len(cp),productClassOverrides=0,
    source='Actual product MainKt --backend-smoke entry',freshProfile=True,externalGuestHTTP=True,
    userAccountRead=False,accountMutation=False,nativePlaybackAccepted=False,rootCommentInteractionAccepted=False,
    stages=[line for line in log.splitlines() if line.startswith('BACKEND_SMOKE_')])
(run/'accepted-evidence.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
print(json.dumps(report));p.check_returncode();assert report['passed']
