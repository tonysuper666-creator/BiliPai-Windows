from pathlib import Path
import hashlib,json,re,subprocess,zipfile
HERE=Path(__file__).resolve().parent
ROOT=next(p for p in HERE.parents if (p/'.git').exists())
PIN=ROOT/'desktop/.local/dynamic-editor-main-product-snapshot-01'
JAR=PIN/'main-kotlin.jar'
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
assert sha(JAR)=='47d866dc71544bf4dcc4ee39da7c6de3f0121fce90e7baca77c8e88e46ee45ed'
with zipfile.ZipFile(JAR) as z:
    owners=[n[:-6].replace('/','.') for n in z.namelist() if n.startswith('com/android/purebilibili/data/repository/') and n.endswith('Kt.class') and '$' not in n]
policy=(ROOT/'app/src/main/java/com/android/purebilibili/data/repository/DynamicDetailFallbackPolicy.kt').read_text(encoding='utf-8')
names=re.findall(r'^internal fun (\w+)\(',policy,re.M)
java=ROOT.parent/'toolchain/jdk/jdk-21.0.12.1+1/bin/javap.exe'
result=subprocess.run([str(java),'-classpath',str(JAR),'-p',*owners],capture_output=True,text=True,encoding='utf-8')
result.check_returncode()
matches=[line.strip() for line in result.stdout.splitlines() if 'public static' in line and any(re.search(r'\b'+re.escape(n)+r'\(',line) for n in names)]
assert not matches
(HERE/'actual-repository-top-methods.txt').write_text(result.stdout,encoding='utf-8',newline='\n')
record=dict(mainKotlinSha256Bytes=sha(JAR),repositoryKtOwners=len(owners),originalInternalPolicyFunctions=len(names),
    originalFunctionNames=names,existingPublicStaticNameMatches=matches,conflicts=0,
    strongerThanDescriptorCheckBecauseNamesHaveNoIntersection=True,
    javapSha256Bytes=sha(HERE/'actual-repository-top-methods.txt'),
    originalPolicyLfSha256=hashlib.sha256(policy.replace('\r\n','\n').encode()).hexdigest())
(HERE/'top-level-uniqueness.json').write_text(json.dumps(record,indent=2)+'\n',encoding='utf-8')
print(json.dumps(dict(owners=len(owners),declarations=len(names),conflicts=0)))

