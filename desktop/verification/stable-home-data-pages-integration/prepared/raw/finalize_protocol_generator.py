from pathlib import Path
import json,io,tokenize
HERE=Path(__file__).resolve().parent
source=(HERE/'prepare_protocols.py').read_text(encoding='utf-8')
pins=json.loads((HERE/'selected-declaration-identities.json').read_text(encoding='utf-8'))['sourcePins']
helpers=source[source.index('def safe(p):'):source.index("parser=load(REPO/")]
body=source[source.index("parser=load(REPO/"):]
body=body.replace("write(OUT/'com/android/purebilibili/core/refresh/WatchLaterRefreshBus.kt',bus)","if standalone:write(OUT/'com/android/purebilibili/core/refresh/WatchLaterRefreshBus.kt',bus)")
body=body.replace("write(HERE/'selected-declaration-identities.json',","write(OUT/'home-protocol-identities.json',")
last="print(json.dumps({'outputs':len(list(OUT.rglob('*.kt'))),'declarations':len(rows),'sourcePins':len(pins)}))"
assert body.count(last)==1
body=body.replace(last,"return {'productionOutputs':6,'standaloneOutputs':7,'declarations':len(rows),'sourcePins':pins,'directOnly':BASE+'core/refresh/WatchLaterRefreshBus.kt'}")
string_continuations=set()
for token in tokenize.generate_tokens(io.StringIO(body).readline):
 if token.type==tokenize.STRING and token.end[0]>token.start[0]:string_continuations.update(range(token.start[0]+1,token.end[0]+1))
indented=''.join(line if i in string_continuations else '    '+line if line.strip() else line for i,line in enumerate(body.splitlines(keepends=True),1))
text='''"""Selected complete stable Home protocols. No HTTP client/store/cache/DTO is generated.
Production deliberately skips DIRECT WatchLaterRefreshBus; --standalone includes it only for tests.
"""
from pathlib import Path
import hashlib,importlib.util,json,textwrap,re,argparse
BASE='app/src/main/java/com/android/purebilibili/'
PREFIX=chr(92)*2+'?'+chr(92)
SOURCE_PINS='''+repr(pins)+'\n\n'+helpers+'''
def generate(repo,output,standalone=False):
    REPO=Path(repo);OUT=Path(output)
    for path,expected in SOURCE_PINS.items():assert sha(read(REPO/path))==expected,path
'''+indented+'''
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--repo',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--standalone',action='store_true');a=p.parse_args()
    print(json.dumps(generate(a.repo,a.output,a.standalone),ensure_ascii=False))
'''
target=HERE/'prepared/tools/extract-upstream-home-protocols.py';target.parent.mkdir(parents=True,exist_ok=True);target.write_text(text,encoding='utf-8',newline='\n')
print('production generator prepared')
