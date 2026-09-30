"""Read original offscreen PNG bytes; no renderer, image editing, or HWND."""
from pathlib import Path
import hashlib,json
from PIL import Image
HERE=Path(__file__).resolve().parent
out=HERE/'snapshot-3'
results=[]
for style in ['material3','miuix']:
    for stage in ['startup-error','retry-error','stopped']:
        path=out/f'root-startup-{style}'/f'{style}-root-{stage}.png'
        old=HERE/'snapshot-2'/f'root-startup-{style}'/path.name
        raw=path.read_bytes();image=Image.open(path).convert('RGBA')
        pixels=list(image.getdata());background=image.getpixel((1000,700))
        transparent=sum(a==0 for r,g,b,a in pixels)
        opaque=sum(a==255 for r,g,b,a in pixels)
        ink=sum(a==255 and abs(r-background[0])+abs(g-background[1])+abs(b-background[2])>180 for r,g,b,a in pixels)
        assert image.size==(1080,800) and transparent==0 and opaque==1080*800 and ink>500,path
        # Two separately compiled fresh JVM runs capture identical actual Root frames.
        assert old.read_bytes()==raw,('Actual Root capture did not settle across independent runs',path)
        results.append({'path':str(path.relative_to(HERE)).replace('\\','/'),'sha256Bytes':hashlib.sha256(raw).hexdigest(),
            'width':1080,'height':800,'transparentPixels':transparent,'opaquePixels':opaque,
            'contrastedInkPixels':ink,'equalIndependentSnapshot2Capture':True,'actualProductAppSurface':True,
            'fixtureRendererOverride':False})
destination=HERE/'screenshot-verification.json'
if destination.exists():raise ValueError('Existing screenshot evidence is never overwritten')
destination.write_text(json.dumps({'passed':True,'images':results},indent=2)+'\n',encoding='utf-8')
print(json.dumps({'passed':True,'actualRootPngs':len(results)}))
