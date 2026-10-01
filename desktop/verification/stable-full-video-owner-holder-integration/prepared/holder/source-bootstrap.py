"""Read fixed original Holder closure; no product generation/installation here."""
from pathlib import Path
import hashlib, importlib.util, json, re, subprocess, sys
sys.dont_write_bytecode = True
P = Path(__file__).resolve().parent
MAIN = P.parents[2]
REPO = MAIN.parent/'BiliPai-v023'
COMMIT = '3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
PREFIX = 'app/src/main/java/com/android/purebilibili/feature/video/screen/'
def wide(p):
    s=str(Path(p).absolute()); prefix=chr(92)*2+'?'+chr(92)
    return Path(s if s.startswith(prefix) else prefix+s)
def sha(b): return hashlib.sha256(b).hexdigest()
def put(p, text):
    p=wide(p); p.parent.mkdir(parents=True, exist_ok=True)
    p.write_bytes(text.encode('utf-8') if isinstance(text,str) else text)
def save(p, value): put(p,json.dumps(value,ensure_ascii=False,indent=2)+'\n')

def main():
    paths=subprocess.check_output(['git','-C',str(REPO),'ls-tree','-r','--name-only',COMMIT,PREFIX],text=True).splitlines()
    rows=[]
    for path in paths:
        content=subprocess.check_output(['git','-C',str(REPO),'show',f'{COMMIT}:{path}']).replace(b'\r\n',b'\n')
        put(P/'original-stable'/path,content)
        text=content.decode('utf-8')
        rows.append(dict(path=path,sha256LF=sha(content),bytesLF=len(content),physicalLines=len(text.splitlines()),
            gitBlob=subprocess.check_output(['git','-C',str(REPO),'rev-parse',f'{COMMIT}:{path}'],text=True).strip(),
            declarations=[dict(line=i+1,text=line) for i,line in enumerate(text.splitlines())
                if re.match(r'^(?:(?:internal|private|public|suspend|inline|tailrec|data|sealed|abstract)\s+)*(?:fun|class|object|interface|enum class|val|const val)\b',line)],
            androidImports=[line for line in text.splitlines() if re.match(r'import (?:android\.|androidx\.(?:core\.view|media3|lifecycle\.viewmodel))',line)]))
    holder=next(r for r in rows if r['path'].endswith('/VideoDetailScreenStateHolder.kt'))
    assert holder['physicalLines']==5464
    raw=wide(P/'original-stable'/holder['path']).read_text(encoding='utf-8')
    spec=importlib.util.spec_from_file_location('holder_original_mask',REPO/'desktop/tools/extract-upstream-dynamic-reply-protocol.py')
    lexer=importlib.util.module_from_spec(spec); spec.loader.exec_module(lexer)
    masked=lexer.masked(raw)
    members={}
    for variable in ['viewModel','engagementViewModel','composerViewModel','supplementViewModel','commentViewModel','miniPlayerManager','playerState','sharedDanmakuManager','PlaylistManager']:
        members[variable]=sorted(set(re.findall(r'\b'+variable+r'(?:\??\.|::)([A-Za-z_]\w*)',masked)) - {'let'})
    save(P/'original-source-inventory.json',dict(sourceCommit=COMMIT,scope='Read-only same-package dependency inventory, not all selected production outputs.',rows=rows))
    save(P/'holder-required-members.json',dict(sourceCommit=COMMIT,holder=holder,members=members,
        uniqueFullVm='Parent owns original FQN com.android.purebilibili.feature.video.viewmodel.VideoPlaybackViewModel; explicit parameter, no viewModel() construction.',
        uniqueDomainOwners=['existing original VideoEngagementViewModel','complete original VideoComposerViewModel owned by this Holder closure','existing original VideoSupplementViewModel','existing original VideoCommentViewModel'],
        nativeState='Required same-entry bind of actual DesktopOriginalMpvVideoPlayerState over the same DesktopOriginalVideoNativeOwner/MpvPlayer; original load/readiness/resume parameters retained.',
        platformBlocks=[dict(start=1465,end=1580,topic='Window/system bar snapshot+same-owner restore; actual Windows client boundary'),
            dict(start=1832,end=1852,topic='System accelerometer rotation setting observer; Windows sensor capability explicit'),
            dict(start=1859,end=1939,topic='Disposition: viewport brightness/keepawake restore, PiP/service/orientation cleanup; delayed work captures original owner'),
            dict(start=2013,end=2022,topic='Required same-native PlayerState instead of constructing ExoPlayer'),
            dict(start=2276,end=2290,topic='Subtitle auto-muted policy uses actual existing root volume state'),
            dict(start=2658,end=2754,topic='Orientation sensor callback optional capability+lease, original target/settle math unchanged'),
            dict(start=3032,end=3074,topic='PiP transfer consumes same native source and page/epoch token'),
            dict(start=3160,end=3201,topic='Fullscreen intent+position preservation delegates actual Window placement owner')],
        status='Source audit prepared; no compile/runtime acceptance yet.'))
    print(json.dumps(dict(holderSha256LF=holder['sha256LF'],holderLines=holder['physicalLines'],inventorySources=len(rows),members={k:len(v) for k,v in members.items()})))

if __name__=='__main__':main()
