from pathlib import Path
import ctypes,hashlib,json
from PIL import Image
HERE=Path(__file__).resolve().parent;BUILD=HERE/'video-share-native-build42';OUT=HERE/'native-media-prepare-actual42'
assert not OUT.exists();OUT.mkdir()
sha=lambda b:hashlib.sha256(b).hexdigest()
dll=BUILD/'bilipai-diagnostic-share.dll';assert sha(dll.read_bytes())=='89705e2e0b5b146c8fdbccda63dc68bea7e46bfcc7e4aaa50c4cb1efa5331f06'
api=ctypes.WinDLL(str(dll));prepare=api.BilipaiSharePrepareMedia
prepare.argtypes=[ctypes.c_wchar_p,ctypes.c_wchar_p,ctypes.c_wchar_p,ctypes.POINTER(ctypes.c_uint64)];prepare.restype=ctypes.c_long
state=api.BilipaiShareState;state.argtypes=[ctypes.c_uint64,ctypes.POINTER(ctypes.c_int)];state.restype=ctypes.c_long
retire=api.BilipaiShareRetire;retire.argtypes=[ctypes.c_uint64,ctypes.POINTER(ctypes.c_int),ctypes.POINTER(ctypes.c_int)];retire.restype=ctypes.c_long
probe=api.BilipaiShareProbe;probe.argtypes=[];probe.restype=ctypes.c_long
events=[];tokens=[]
def grant(path):
 token=ctypes.c_uint64(0);hr=prepare(str(path), 'BiliPai synthetic card','https://www.bilibili.com/video/BV-FIXTURE',ctypes.byref(token))
 if token.value:tokens.append(token.value)
 return hr,token.value
try:
 for name,kind in [('card.jpg','JPEG'),('card.png','PNG'),('card.webp','WEBP')]:
  p=OUT/name;Image.new('RGB',(64,32),(32,99,203)).save(p,format=kind);hr,token=grant(p)
  assert hr>=0 and token!=0,(name,hr,token);st=ctypes.c_int(-1);assert state(token,ctypes.byref(st))>=0 and st.value==0
  final=ctypes.c_int(-1);supplied=ctypes.c_int(1);assert retire(token,ctypes.byref(final),ctypes.byref(supplied))>=0 and final.value==0 and supplied.value==0
  tokens.remove(token);assert state(token,ctypes.byref(st))<0
  events.append(dict(file=name,prepare=True,preparedState=True,retiredWithoutGrant=True,tokenNoLongerValid=True))
 for name,content in [('empty.jpg',b''),('wrong.txt',b'synthetic only')]:
  p=OUT/name;p.write_bytes(content);hr,token=grant(p);assert hr<0 and token==0,(name,hr,token);events.append(dict(file=name,rejected=True))
 hr,token=grant(OUT/'missing.jpg');assert hr<0 and token==0;events.append(dict(case='missing',rejected=True))
 for i in range(8):
  hr,token=grant(OUT/'card.jpg');assert hr>=0 and token!=0
 hr,token=grant(OUT/'card.jpg');assert hr<0 and token==0;events.append(dict(case='boundedNativeRegistry8',rejected9th=True))
 available=probe()>=0
finally:
 for token in tokens:
  final=ctypes.c_int(-1);supplied=ctypes.c_int(1);assert retire(token,ctypes.byref(final),ctypes.byref(supplied))>=0 and supplied.value==0
report=dict(passed=True,groups=7,validFormats=3,nativeRegistryLimit=8,actualNativeExports=['BilipaiSharePrepareMedia','BilipaiShareState','BilipaiShareRetire','BilipaiShareProbe'],nativeProbeAvailable=available,dllSha256Bytes=sha(dll.read_bytes()),sourceSha256Bytes=json.loads((BUILD/'producer-input-graph.json').read_text(encoding='utf-8'))['source']['sha256Bytes'],events=events,HWND=False,ShareUI=False,externalReceiver=False,realAccountRead=False,scope='Fresh DLL preparation/state/bounded token registry and real retirement, synthetic local images only')
(OUT/'result.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8');print(json.dumps(dict(passed=True,groups=7,formats=3,nativeProbeAvailable=available,shareUiOpened=False)))
