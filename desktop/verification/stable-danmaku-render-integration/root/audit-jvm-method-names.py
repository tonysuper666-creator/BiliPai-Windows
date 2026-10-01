from pathlib import Path
import json,struct,sys,zipfile,hashlib
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];phase=int(sys.argv[1]);jar=MAIN/f'desktop/.local/stable-product-snapshot-{phase}/main-kotlin.jar'
issues=[];count=0
def parse(data):
    pos=8
    def u1():
        nonlocal pos;x=data[pos];pos+=1;return x
    def u2():
        nonlocal pos;x=struct.unpack_from('>H',data,pos)[0];pos+=2;return x
    def u4():
        nonlocal pos;x=struct.unpack_from('>I',data,pos)[0];pos+=4;return x
    n=u2();cp=[None]*n;i=1
    while i<n:
        tag=u1()
        if tag==1:
            size=u2();cp[i]=(tag,data[pos:pos+size].decode('utf-8',errors='replace'));pos+=size
        elif tag in (3,4):pos+=4
        elif tag in (5,6):pos+=8;i+=1
        elif tag in (7,8,16,19,20):cp[i]=(tag,u2())
        elif tag in (9,10,11,12,17,18):cp[i]=(tag,u2(),u2())
        elif tag==15:cp[i]=(tag,u1(),u2())
        else:raise ValueError(tag)
        i+=1
    invalid=[]
    def bad(name):return any(c in name for c in '.;[/<>') and name not in ('<init>','<clinit>')
    for i,row in enumerate(cp):
        if row and row[0] in (10,11):
            owner=cp[cp[row[1]][1]][1];nat=cp[row[2]];name=cp[nat[1]][1];descriptor=cp[nat[2]][1]
            if bad(name):invalid.append(dict(kind='constant-pool-method-reference',index=i,owner=owner,name=name,descriptor=descriptor))
    pos+=6;interfaces=u2();pos+=2*interfaces
    def member(method):
        nonlocal pos
        access=u2();name=cp[u2()][1];descriptor=cp[u2()][1]
        if method and bad(name):invalid.append(dict(kind='method-declaration',name=name,descriptor=descriptor))
        for _ in range(u2()):u2();size=u4();pos+=size
    for _ in range(u2()):member(False)
    for _ in range(u2()):member(True)
    return invalid
with zipfile.ZipFile(jar) as archive:
    for name in archive.namelist():
        if not name.endswith('.class'):continue
        data=archive.read(name);count+=1;bad=parse(data)
        if bad:issues.append(dict(classFile=name,classSHA256=hashlib.sha256(data).hexdigest(),invalidMethodNames=bad))
report=dict(phase=phase,jarSHA256=hashlib.sha256(jar.read_bytes()).hexdigest(),classCount=count,issueCount=len(issues),issues=issues,
            staticClassfileCheckOnly=True,allClassesRuntimeLoaded=False)
(HERE/f'jvm-method-name-audit-{phase}.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
print(json.dumps(report))
