from pathlib import Path
import hashlib,json,re,zipfile
P=Path(__file__).resolve().parent;MAIN=P.parents[2];REPO=MAIN.parent/'BiliPai-v023'
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
sources=list(wide(REPO/'desktop/build/generated').rglob('*.kt'))+list(wide(MAIN/'desktop/.local/stable-video-full-owner-parity/prepared').rglob('*.kt'))+list(wide(P/'prepared/generated').rglob('*.kt'))
owners={}
for p in sources:
 t=p.read_text(encoding='utf-8');package=re.search(r'^package ([\w.]+)',t,re.M);obj=re.search(r'\bobject (Desktop\w+)\s*\{',t)
 if not package or not obj:continue
 for n in re.findall(r'\bfun (\w+)\s*\(',t[obj.end():]):owners.setdefault(n,[]).append(dict(owner=package[1]+'.'+obj[1],path=str(p)))
names=set()
for p in wide(P/'prepared/generated').rglob('*.kt'):
 names.update(re.findall(r'SettingsManager\s*\.\s*(\w+)',p.read_text(encoding='utf-8')))
rows={n:sorted({x['owner']for x in owners.get(n,[])})for n in sorted(names)}
(P/'settings-owner-map.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps(rows,ensure_ascii=False,indent=2))
