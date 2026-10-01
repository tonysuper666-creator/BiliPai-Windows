from pathlib import Path
import hashlib,json,subprocess
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2]
snap=MAIN/'desktop/.local/stable-product-snapshot-15'
cp=json.loads((snap/'ordered-runtime-cp.json').read_text())
java=MAIN.parent/'toolchain/jdk/jdk-21.0.12.1+1/bin/java.exe'
root=HERE/'task-store-final'
assert not root.exists(),'fixture fresh store required'
r=subprocess.run([str(java),'-Dfile.encoding=UTF-8','-cp',str(HERE/'classes-install-final')+';'+ ';'.join(x['path'] for x in cp),
 'com.bilipai.desktop.ui.FavoritesFixtureKt',str(root)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=45)
(HERE/'fixture-final.log').write_text(r.stdout+r.stderr,encoding='utf-8')
print('Fixture exit '+str(r.returncode));print(r.stdout.encode('ascii','backslashreplace').decode('ascii'));r.check_returncode()
assert '5 groups / ' in r.stdout and 'PASS' in r.stdout
(HERE/'fixture-final-evidence.json').write_text(json.dumps(dict(groupedCases=5,assertions=int(r.stdout.split('groups / ')[1].split(' assertions')[0]),
 actualMainIntegration=False,preparedOnly=True,sockets=False,HWND=False,productOverrides=False,
 productSnapshot='c665e8cc40604866341c9ac5f6ef992ed84b52fe709a1f934337f754012cab87',
 stdoutSha256=hashlib.sha256((r.stdout+r.stderr).encode()).hexdigest(),codeSources=[x for x in r.stdout.splitlines() if x.startswith('CodeSource ')]),indent=2),encoding='utf-8')
