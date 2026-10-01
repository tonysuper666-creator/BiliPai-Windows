from pathlib import Path
import hashlib,json,subprocess,sys
sys.stdout.reconfigure(encoding='utf-8',errors='replace')
HERE=Path(__file__).resolve().parent
REPO=next(p for p in HERE.parents if (p/'.git').exists())
attempt,main,result_name=sys.argv[1:]
data=json.loads((HERE/('compile-evidence-'+attempt+'.json')).read_text())
assert data['passed'] and data['rawPreparedOverlay'] is None
for row in data['dependencies']:
    assert hashlib.sha256(Path('\\\\?\\'+row['path']).read_bytes()).hexdigest()==row['sha256Bytes']
cp=[str(HERE/('classes-'+attempt))]+[row['path'] for row in data['dependencies']]
output=HERE/(result_name+'-'+attempt+'.json')
classlog=HERE/(result_name+'-'+attempt+'-classload.log')
java=REPO.parent/'toolchain/jdk/jdk-21.0.12.1+1/bin/java.exe'
result=subprocess.run([str(java),'-Dfile.encoding=UTF-8','-Xlog:class+load=info:file='+classlog.name+':time,tags',
    '-cp',';'.join(cp),main,str(output)],cwd=HERE,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=65)
(HERE/(result_name+'-'+attempt+'.log')).write_text(result.stdout+result.stderr,encoding='utf-8');print(result.stdout+result.stderr)
if result.returncode:sys.exit(result.returncode)
log=classlog.read_text(encoding='utf-8')
name='com.android.purebilibili.feature.dynamic.DesktopOriginalDynamicReplySession source:'
if name in log:
    source_line=next(line for line in log.splitlines() if name in line)
    assert '/post-admission-delta/classes-' in source_line,source_line
print(output.read_text())
