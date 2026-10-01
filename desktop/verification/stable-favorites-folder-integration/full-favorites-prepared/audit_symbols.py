from pathlib import Path
import json,struct,zipfile
HERE=Path(__file__).resolve().parent
SNAP=HERE.parents[2]/'desktop/.local/stable-product-snapshot-15'
def methods(data):
 at=8;count=struct.unpack_from('>H',data,at)[0];at+=2;pool={};i=1
 while i<count:
  tag=data[at];at+=1
  if tag==1:
   size=struct.unpack_from('>H',data,at)[0];at+=2;pool[i]=data[at:at+size].decode('utf-8','replace');at+=size
  elif tag in (3,4,9,10,11,12,17,18):at+=4
  elif tag in (5,6):at+=8;i+=1
  elif tag in (7,8,16,19,20):at+=2
  elif tag==15:at+=3
  else:raise AssertionError(tag)
  i+=1
 at+=6;interfaces=struct.unpack_from('>H',data,at)[0];at+=2+2*interfaces
 def members():
  nonlocal at
  count=struct.unpack_from('>H',data,at)[0];at+=2;out=[]
  for _ in range(count):
   flags,name,desc,attrs=struct.unpack_from('>HHHH',data,at);at+=8
   out.append((flags,pool[name],pool[desc]))
   for _ in range(attrs):
    _,size=struct.unpack_from('>HI',data,at);at+=6+size
  return out
 members();return members()
def main():
 product={};static={}
 for p in SNAP.glob('*.jar'):
  with zipfile.ZipFile(p) as z:
   for n in z.namelist():
    if not n.endswith('.class'):continue
    product[n]=str(p)
    if '$' in n or not n.endswith('Kt.class'):continue
    pkg=n.rsplit('/',1)[0]
    for flags,name,desc in methods(z.read(n)):
     if flags&9==9 and name not in ('<init>','<clinit>'):
      static.setdefault((pkg,name,desc),[]).append(n)
 classes=HERE/'classes-install-final';overlap=[];function_overlap=[]
 for p in classes.rglob('*.class'):
  n=p.relative_to(classes).as_posix()
  if n in product:overlap.append(n)
  if '$' in n or not n.endswith('Kt.class'):continue
  pkg=n.rsplit('/',1)[0]
  for flags,name,desc in methods(p.read_bytes()):
   if flags&9==9 and (key:=(pkg,name,desc)) in static:
    function_overlap.append(dict(candidate=n,name=name,descriptor=desc,product=static[key]))
 report=dict(classOverlap=overlap,publicStaticOverlap=function_overlap,productSnapshot='c665e8cc40604866341c9ac5f6ef992ed84b52fe709a1f934337f754012cab87')
 (HERE/'symbol-audit.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
 print(json.dumps(report,indent=2))
if __name__=='__main__':main()
