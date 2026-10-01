from pathlib import Path
import hashlib,json,zipfile,subprocess
ROOT=Path(__file__).resolve().parents[5]
MAIN=ROOT/'work/BiliPai'
LANE=Path(__file__).resolve().parent
def safe(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def sha(b):return hashlib.sha256(b).hexdigest()
cp=safe(MAIN/'desktop/.local/stable-product-snapshot-36/ordered-runtime-cp.json').read_bytes()
assert sha(cp)=='2fa3092ec985e32bf6b1b046266e6b84994adeeb023615549a9422eddb0aa493'
rows=json.loads(cp);assert len(rows)==97
classes={};pins=[]
for row in rows:
 p=Path(row['path']);data=safe(p).read_bytes();assert sha(data)==row['sha256Bytes'],p
 with zipfile.ZipFile(safe(p)) as z:
  for n in z.namelist():
   if n.endswith('.class'):classes.setdefault(n,[]).append(str(p))
 pins.append({'path':str(p),'sha256Bytes':sha(data)})
names=['LiveList','LiveRoomItem','LiveRoomCard','LiveChromePalette','LiveBiliPaiVisualPolicy','LiveHomeSelectableChip','LiveHomeCategoryIndicatorPolicy','LiveHomeAreaSelectionPolicy','LiveListTabColorPolicy','LiveFeedParsePolicy','LiveAreaRoomsPolicy','LiveAreaRoomsPage','LiveFeedHomeSnapshot','LiveCover','VideoSharedTransitionPolicy','AdaptivePullToRefreshBox','LiveModels','LiveFeedModels']
hits={n:{k:v for k,v in classes.items() if n in k and k.startswith('com/android/purebilibili/')} for n in names}
out={'actualEntries':97,'verifiedDependencyPins':pins,'classes':hits}
for name,hash in [('stable-home-request-ports-parity','4824a6984eead54af5ac2c045cb9a21d600488d6be4ed3cbea2ad5e6eabdf5f3'),('stable-home-viewmodel-parity','ac33081aa67a40a3f807e20426de7f7fc12d1ea6d3128f50e3a0f951e9587a37')]:
 base=MAIN/'desktop/.local'/name;assert sha(safe(base/'frozen-handoff.json').read_bytes())==hash
 folder=base/('classes-ports-05' if 'request' in name else 'classes-vm-06');jar=LANE/(name+'-declared-prospective.jar')
 entries=[]
 with zipfile.ZipFile(safe(jar),'w',zipfile.ZIP_DEFLATED) as z:
  for p in sorted(safe(folder).rglob('*')):
   if p.is_file():
    rel=p.relative_to(safe(folder)).as_posix();b=p.read_bytes();z.writestr(rel,b);entries.append({'path':rel,'sha256Bytes':sha(b)})
 out[name]={'frozenManifest':hash,'declaredProspectiveNotActual':True,'jar':str(jar),'jarSha256Bytes':sha(safe(jar).read_bytes()),'entries':entries}
safe(LANE/'input-audit.json').write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({'entries':97,'missingOriginalClasses':{k:len(v) for k,v in hits.items()},'prospectiveJars':{k:v['jarSha256Bytes'] for k,v in out.items() if isinstance(v,dict) and 'jarSha256Bytes' in v}},indent=2))
