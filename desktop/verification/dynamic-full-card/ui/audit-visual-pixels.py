"""Read known screenshot ROIs and report pixels; does not edit or generate images."""
from pathlib import Path
from collections import Counter
import argparse,hashlib,json
from PIL import Image
p=argparse.ArgumentParser();p.add_argument('--attempt',required=True);a=p.parse_args()
assert a.attempt.isalnum()
proof=Path(__file__).resolve().parent/'runs'/a.attempt/'proof'
def ext(path):
    value=str(Path(path).absolute())
    return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def luminance(rgb):
    values=[v/255 for v in rgb]
    linear=[v/12.92 if v<=.04045 else ((v+.055)/1.055)**2.4 for v in values]
    return .2126*linear[0]+.7152*linear[1]+.0722*linear[2]
def contrast(first,second):
    lights=sorted([luminance(first),luminance(second)])
    return (lights[1]+.05)/(lights[0]+.05)
def color(rgb):return '#'+''.join(f'{v:02X}' for v in rgb)
rows=[]
for cell,region,bounds in [
 ('material3-light','first action text/icon',(50,304,120,324)),
 ('miuix-light','selected All label',(100,64,139,83)),
 ('miuix-dark','Dynamic title',(20,16,59,39))]:
    file=proof/cell/'08-final-actual-all.png'
    with Image.open(ext(file)) as image:
        rgb=image.convert('RGB');pixels=Counter(rgb.getpixel((x,y)) for x in range(bounds[0],bounds[2]) for y in range(bounds[1],bounds[3]))
    common=pixels.most_common(6)
    row={'cell':cell,'region':region,'image':'08-final-actual-all.png','sha256Bytes':hashlib.sha256(ext(file).read_bytes()).hexdigest(),
         'boundsExclusive':list(bounds),'pixels':sum(pixels.values()),'uniqueColors':len(pixels),
         'commonColors':[{'rgb':color(rgb),'pixels':count} for rgb,count in common]}
    if len(common)>1:row['twoMostCommonColorContrast']=contrast(common[0][0],common[1][0])
    rows.append(row)
out=proof/'sampled-visual-pixels.json';assert not ext(out).exists(),'Do not rewrite a pixel audit'
ext(out).write_text(json.dumps({'attempt':a.attempt,'readOnlyImageInspection':True,'wholeImageVisualAcceptance':False,'samples':rows},indent=2)+'\n',encoding='utf-8',newline='\n')
print(json.dumps(rows,ensure_ascii=False))
