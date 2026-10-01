from pathlib import Path
import json,hashlib,subprocess,importlib.util,sys
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023';SNAP=MAIN/'desktop/.local/stable-product-snapshot-37'
OUT=HERE/'windows-home-prefs-actual37';assert not OUT.exists();OUT.mkdir()
sha=lambda b:hashlib.sha256(b).hexdigest()
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
cp=json.loads((SNAP/'ordered-runtime-cp.json').read_text(encoding='utf-8'))
for r in cp:assert sha(wide(r['path']).read_bytes())==r['sha256Bytes']
asset=REPO/'desktop/resources/common/native/windows-x64/bilipai-diagnostic-share.dll';dll=OUT/'bilipai-diagnostic-share.dll';dll.write_bytes(asset.read_bytes())
producer=json.loads((REPO/'desktop/build/generated/native-diagnostic-share/producer-receipt.json').read_text(encoding='utf-8'));assert producer['passed']and sha(dll.read_bytes())==producer['expectedDllSha256Bytes']
source=OUT/'HomeWindowsPreferencesActualFixture.kt';source.write_bytes((HERE/source.name).read_bytes())
spec=importlib.util.spec_from_file_location('cc',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');cc=importlib.util.module_from_spec(spec);spec.loader.exec_module(cc)
args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','actual_windows_home_prefs',
 '-Xfriend-paths='+str(SNAP/'main-kotlin.jar'),'-cp',';'.join(r['path']for r in cp),'-d',str(OUT/'classes'),str(source)]
(OUT/'compiler.args').write_text('\n'.join('"'+a.replace('\\','/')+'"'for a in args),encoding='utf-8')
r=subprocess.run([str(cc.JAVA),'-Dfile.encoding=UTF-8','-Xmx1g','-cp',';'.join(map(str,cc.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(OUT/'compiler.args')],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=120)
(OUT/'compiler.log').write_text(r.stdout+r.stderr,encoding='utf-8')
if r.returncode==0:
 r=subprocess.run([str(cc.JAVA),'-Dfile.encoding=UTF-8','-cp',';'.join([str(OUT/'classes')]+[row['path']for row in cp]),'com.bilipai.desktop.ui.HomeWindowsPreferencesActualFixtureKt',str(dll.absolute())],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=45)
 (OUT/'runtime.log').write_text(r.stdout+r.stderr,encoding='utf-8')
for row in cp:assert sha(wide(row['path']).read_bytes())==row['sha256Bytes']
(OUT/'result.json').write_text(json.dumps(dict(exitCode=r.returncode,actualFrozenProduct37=True,productionOverrides=0,runtimeEntries=len(cp),nativeFreshProducerReceipt=producer,actualRootMounted=False),indent=2)+'\n',encoding='utf-8')
print(r.stdout+r.stderr);sys.exit(r.returncode)
