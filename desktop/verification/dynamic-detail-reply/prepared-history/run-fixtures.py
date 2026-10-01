from pathlib import Path
import hashlib,json,subprocess,sys
sys.stdout.reconfigure(encoding='utf-8',errors='replace')
HERE=Path(__file__).resolve().parent
REPO=next(p for p in HERE.parents if (p/'.git').exists())
JAVA=REPO.parent/'toolchain/jdk/jdk-21.0.12.1+1/bin/java.exe'
attempt=sys.argv[1]
arguments=(HERE/('compiler-ui-'+attempt+'.args')).read_text(encoding='utf-8').splitlines()
cp=arguments[arguments.index('"-cp"')+1].strip('"').replace('/','\\')
for name,klass in [('session','com.bilipai.desktop.ui.ReplySessionFixtureKt'),
                   ('lifecycle','com.bilipai.desktop.ui.ReplyLifecycleFixtureKt'),
                   ('image','com.bilipai.desktop.ui.ReplyImageFixtureKt'),
                   ('ui','com.bilipai.desktop.ui.ReplyUiFixtureKt')]:
    if len(sys.argv)>2 and name not in sys.argv[2:]:continue
    resultPath=HERE/(name+'-result-'+attempt+'.json')
    command=[str(JAVA),'-Dfile.encoding=UTF-8','-Djava.awt.headless=true','-Xmx1g','-cp',str(HERE/('classes-ui-'+attempt))+';'+cp,klass,str(resultPath)]
    result=subprocess.run(command,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=60)
    (HERE/(name+'-run-'+attempt+'.log')).write_text(result.stdout+result.stderr,encoding='utf-8')
    print(name,result.returncode,result.stdout+result.stderr)
    if result.returncode != 0:raise SystemExit(result.returncode)
    assert json.loads(resultPath.read_text(encoding='utf-8'))['passed']
