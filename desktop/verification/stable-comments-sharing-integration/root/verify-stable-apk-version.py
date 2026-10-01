from pathlib import Path
import hashlib,json,re,struct,subprocess,zipfile
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023';COMMIT='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
apk=Path('C:/Users/TONYS/Downloads/Telegram Desktop/BiliPai-0.2.3.apk');digest=hashlib.sha256(apk.read_bytes()).hexdigest()
assert digest=='99f261463619552775a32730224f49f6675d043cfc0d38527ea510f5e4254a1c'
with zipfile.ZipFile(apk) as archive:data=archive.read('AndroidManifest.xml')
assert len(data)<1048576
def u16(p):return struct.unpack_from('<H',data,p)[0]
def u32(p):return struct.unpack_from('<I',data,p)[0]
def length8(p):
    first=data[p];return (((first&127)<<8)|data[p+1],p+2) if first&128 else (first,p+1)
def length16(p):
    first=u16(p);return (((first&32767)<<16)|u16(p+2),p+4) if first&32768 else (first,p+2)
strings=[];attributes={};position=u16(2)
while position<len(data):
    kind=u16(position);header=u16(position+2);size=u32(position+4);assert size>=header and size>0
    if kind==1:
        count=u32(position+8);flags=u32(position+16);start=position+u32(position+20)
        for index in range(count):
            pointer=start+u32(position+header+index*4)
            if flags&256:
                _,pointer=length8(pointer);length,pointer=length8(pointer);value=data[pointer:pointer+length].decode('utf-8')
            else:
                length,pointer=length16(pointer);value=data[pointer:pointer+length*2].decode('utf-16-le')
            strings.append(value)
    if kind==0x102 and strings[u32(position+20)]=='manifest':
        base=position+16+u16(position+24);stride=u16(position+26);count=u16(position+28)
        for index in range(count):
            pointer=base+index*stride;name=strings[u32(pointer+4)];raw=u32(pointer+8);value_type=data[pointer+15];value=u32(pointer+16)
            attributes[name]=strings[raw] if raw!=0xffffffff else strings[value] if value_type==3 else value
    position+=size
assert attributes.get('versionName')=='0.2.3' and isinstance(attributes.get('versionCode'),int)
source=subprocess.check_output(['git','show',COMMIT+':app/build.gradle.kts'],cwd=REPO).decode()
source_code=int(re.search(r'versionCode\s*=\s*(\d+)',source).group(1));source_name=re.search(r'versionName\s*=\s*"([^"]+)"',source).group(1)
report=dict(upstreamTag='v0.2.3',upstreamCommit=COMMIT,apkSha256Bytes=digest,apkVersionName=attributes['versionName'],apkVersionCode=attributes['versionCode'],
    sourceBuildVersionName=source_name,sourceBuildVersionCode=source_code,sourceBuildFileSha256LF=hashlib.sha256(source.replace('\r\n','\n').encode()).hexdigest(),
    sameVersionName=source_name==attributes['versionName'],sameVersionCode=source_code==attributes['versionCode'],apkExecuted=False)
(HERE/'stable-release-version-audit.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8');print(json.dumps(report))
