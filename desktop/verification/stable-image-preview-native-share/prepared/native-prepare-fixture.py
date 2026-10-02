from pathlib import Path
import ctypes,hashlib,json,tempfile
P=Path(__file__).resolve().parent; dll=P/'prepared/desktop/resources/common/native/windows-x64/bilipai-diagnostic-share.dll'; expected=json.loads((P/'native-verification.json').read_bytes())['newDLL']; assert hashlib.sha256(dll.read_bytes()).hexdigest()==expected
api=ctypes.WinDLL(str(dll)); prepare=api.BilipaiSharePrepareMedia;prepare.argtypes=[ctypes.c_wchar_p,ctypes.c_wchar_p,ctypes.c_wchar_p,ctypes.POINTER(ctypes.c_uint64)];prepare.restype=ctypes.c_long
retire=api.BilipaiShareRetire;retire.argtypes=[ctypes.c_uint64,ctypes.POINTER(ctypes.c_int),ctypes.POINTER(ctypes.c_int)];retire.restype=ctypes.c_long
rows=[];raw=bytes.fromhex('47494638396101000100800000000000ffffff21f90401000000002c00000000010001000002024401003b')
with tempfile.TemporaryDirectory(prefix='bp-image-share-native-') as owned:
 for label,root in [('short-owned-temp',Path(owned)),('long-main-lane',P/'native-prepare-work'),('actual-first-fixture-path',P/'compile/02/fixture-work/real-native-cache/shared_images')]:
  root.mkdir(parents=True,exist_ok=True); f=root/'BiliPai_share_1770000000000_aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee.gif';wide=Path(chr(92)*2+'?'+chr(92)+str(f));wide.write_bytes(raw);token=ctypes.c_uint64();hr=prepare(str(f),'BiliPai 图片分享','https://fixture.invalid/original.gif',ctypes.byref(token));row=dict(label=label,pathLength=len(str(f)),HRESULT=f'{hr&0xffffffff:08x}',token=token.value)
  if token.value:
   state=ctypes.c_int(-1);supplied=ctypes.c_int(1);closed=retire(token.value,ctypes.byref(state),ctypes.byref(supplied));row.update(retireHRESULT=f'{closed&0xffffffff:08x}',state=state.value,supplied=supplied.value);assert closed>=0 and supplied.value==0
  wide.unlink();rows.append(row)
(P/'native-prepare-fixture02.json').write_text(json.dumps(dict(rows=rows,expectedDLL=expected,ShareUI=False,externalReceiver=False),indent=2)+'\n',encoding='utf8');print(json.dumps(rows))
