"""Pinned complete native chrome; original shared material and existing Windows producers."""
from pathlib import Path
import hashlib,json
COMMIT = '415d01ec5bc3ac82521a9ac47bcc449b8e202460'
ARCHIVE = 'desktop/upstream-slices/v040-native-chrome'
MANIFEST_SHA256 = '961af50829fc48de84d21ad1ad1a8289661898870244e91bb5067506cc9a57fd'
PINS = [{'path': 'design-system/src/main/java/com/android/purebilibili/core/ui/AdaptiveChrome.kt', 'filename': 'AdaptiveChrome.kt', 'bytes': 24444, 'sha256Bytes': 'f25c89a48b07e16a48d83c75eccdb398ee9b664b1bbd8a849095c05ab18d1c3f', 'gitBlob': '07519808caad7d9d86225412474ad0c8c02dcaae'}, {'path': 'design-system/src/main/java/com/android/purebilibili/core/ui/wallpaper/GlobalWallpaperBackdrop.kt', 'filename': 'GlobalWallpaperBackdrop.kt', 'bytes': 2040, 'sha256Bytes': '9453976a3550fdc68ef9c3d468d48500fa046389c930c4e34db6252f1d261344', 'gitBlob': '03e4c071cbaadc7e430866a8a14b5fc231fe3271'}, {'path': 'design-system/src/main/java/com/android/purebilibili/core/ui/AdaptiveScaffoldPolicy.kt', 'filename': 'AdaptiveScaffoldPolicy.kt', 'bytes': 650, 'sha256Bytes': '520d1a4ec22d77c9a7460cd647a37e35603eb85980c21bf63ddb9cb433a5769f', 'gitBlob': 'dba16c09a6ac6e31e28576e6ea6760cc8439e93e'}, {'path': 'design-system/src/main/java/com/android/purebilibili/core/ui/TopReadabilityChromePolicy.kt', 'filename': 'TopReadabilityChromePolicy.kt', 'bytes': 1864, 'sha256Bytes': '21687f91a4d37f999584d0390f40c19a8e321a98700f7a5ddf727a80a9ce655d', 'gitBlob': 'a73d627ee10df5518dd77dbb927bf0e7cfde9dd4'}, {'path': 'app/src/main/java/com/android/purebilibili/core/ui/components/BiliPaiTopBarIconDecorator.kt', 'filename': 'BiliPaiTopBarIconDecorator.kt', 'bytes': 2532, 'sha256Bytes': 'dd38b5aa9a18f32849ba4ceac2d19e39ea3dcf6d25652d0bbd7b660b7b98e58f', 'gitBlob': '6f6c4fc3ef4c2a5f6d1ccec71de366c82f48549b'}, {'path': 'app/src/main/java/com/android/purebilibili/feature/home/components/BottomBarMatchedLiquidChrome.kt', 'filename': 'BottomBarMatchedLiquidChrome.kt', 'bytes': 25181, 'sha256Bytes': 'eb3df1fe7470aee80838c2b44da94298d622d6608f8b14a8304679cb452a1c07', 'gitBlob': '5d529789a1f464866faa16f7a384046b21676142'}, {'path': 'app/src/main/java/com/android/purebilibili/feature/home/components/FloatingDockChrome.kt', 'filename': 'FloatingDockChrome.kt', 'bytes': 20357, 'sha256Bytes': '26267c3c98904506bdb1ca6b16dfdbc21983e4ec8c91f9158e549cb6077d0903', 'gitBlob': '12c25df41a0cde6cde2d47c8b5d7bc6c36ebd903'}, {'path': 'app/src/main/java/com/android/purebilibili/feature/home/components/FloatingBottomBarGeometry.kt', 'filename': 'FloatingBottomBarGeometry.kt', 'bytes': 15156, 'sha256Bytes': 'aff3a0e24e17b710ddfdcac8746de18ea71330e691e18fbb5ef1fa70eb8c858a', 'gitBlob': '8913a67b7911d1ef63f5776afb0cd4c09819b961'}, {'path': 'app/src/main/java/com/android/purebilibili/feature/home/components/liquid/Lens.kt', 'filename': 'Lens.kt', 'bytes': 662, 'sha256Bytes': '222ac588fe6ed2b0a168024a4233f4a1de0877db71ef0cf566677c0680f5f99a', 'gitBlob': 'b88a318322d0fe44c9de828d339042310e27c8a3'}, {'path': 'app/src/test/java/com/android/purebilibili/feature/home/components/FloatingBottomBarGeometryTest.kt', 'filename': 'FloatingBottomBarGeometryTest.kt', 'bytes': 19590, 'sha256Bytes': 'bbe0ada6979c29756a0d1d83b36f439c59ff225b4d6b3b33088a9c2a9fd92935', 'gitBlob': '5ebddfce0e309e20a2e03db403fe88573764652d'}, {'path': 'design-system/src/test/java/com/android/purebilibili/core/ui/AdaptiveScaffoldPolicyTest.kt', 'filename': 'AdaptiveScaffoldPolicyTest.kt', 'bytes': 927, 'sha256Bytes': 'ef17ef785c3b13d0713fa0a86b8c695e07b2df0fbb2cc07506cd34cd3d63bde2', 'gitBlob': 'b777645595802821a7fe32032eafd58e8e2c5608'}, {'path': 'design-system/src/test/java/com/android/purebilibili/core/ui/TopReadabilityChromePolicyTest.kt', 'filename': 'TopReadabilityChromePolicyTest.kt', 'bytes': 2965, 'sha256Bytes': '7b33ba45fc0e17ec4bb8ddc0a0539fecaa5dc1976487a85d356e1c2c6eb8dfe9', 'gitBlob': '1473c524827819cf54c0e5be5b7210cce08471d6'}, {'path': 'design-system/src/test/java/com/android/purebilibili/core/ui/AppTopChromePolicyTest.kt', 'filename': 'AppTopChromePolicyTest.kt', 'bytes': 720, 'sha256Bytes': '652ab84a09042406d96ed88356db9988a28337e1fe1030ac8f1d40f181fbabc3', 'gitBlob': '9bc32321b808816ee1a29d13766f017a9395c480'}, {'path': 'app/src/main/java/com/android/purebilibili/feature/home/components/BottomBar.kt', 'filename': 'BottomBar.kt', 'bytes': 217593, 'sha256Bytes': '14a240860576ed36459ef0dc217dcd017d4b88f926ac7a1061f73782eea377be', 'gitBlob': '28fcdc488963a6bd2d243fd3ca51a77200451853'}]
DS = 'design-system/src/main/java/com/android/purebilibili/core/ui/'
APP = 'app/src/main/java/com/android/purebilibili/'
ADAPTIVE = DS+'AdaptiveChrome.kt'
SHARED_PATHS = {APP+'feature/home/components/'+p for p in [
    'FloatingBottomBarGeometry.kt','FloatingDockChrome.kt','BottomBarMatchedLiquidChrome.kt','BottomBar.kt','liquid/Lens.kt']}
NEW_COMPONENT_PATHS = [DS+'wallpaper/GlobalWallpaperBackdrop.kt', APP+'core/ui/components/BiliPaiTopBarIconDecorator.kt']

def sha(raw):return hashlib.sha256(raw).hexdigest()

def fixed_sources(repo):
    root=Path(repo)/ARCHIVE;raw=(root/'manifest.json').read_bytes()
    if sha(raw)!=MANIFEST_SHA256:raise ValueError('Unreviewed native chrome manifest')
    m=json.loads(raw)
    if m['fixedUpstreamCommit']!=COMMIT or m['files']!=PINS:raise ValueError('Unknown native chrome identity')
    result={}
    for row in PINS:
        raw=(root/row['filename']).read_bytes()
        blob=hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest()
        if len(raw)!=row['bytes'] or sha(raw)!=row['sha256Bytes'] or blob!=row['gitBlob']:
            raise ValueError('Modified native chrome original: '+row['path'])
        result[row['path']]=raw
    return result

def identity_for(repo,path):
    if path not in SHARED_PATHS|{ADAPTIVE}:return None
    fixed_sources(repo)
    row=next(r for r in PINS if r['path']==path)
    return dict(pinnedCommit=COMMIT,originalPath=path,path=ARCHIVE+'/'+row['filename'],
        sha256Bytes=row['sha256Bytes'],gitBlobOid=row['gitBlob'],bytes=row['bytes'])

def selected_source(repo,path,previous):
    return fixed_sources(repo)[path].decode('utf-8') if path in SHARED_PATHS|{ADAPTIVE} else previous

def adapt_adaptive(repo):
    original=fixed_sources(repo)[ADAPTIVE].decode('utf-8');text=original;edits=[]
    for before,after in [
        ('import androidx.compose.ui.platform.LocalConfiguration','import com.android.purebilibili.core.util.LocalWindowSizeClass'),
        ('val LocalGlobalWallpaperBackdropVisible = compositionLocalOf { false }\n',''),
        ('val LocalImmersiveTopChromeActive = compositionLocalOf { false }\n',''),
        ('LocalConfiguration.current.screenWidthDp < 600','LocalWindowSizeClass.current.widthDp < 600.dp')]:
        if text.count(before)!=1:raise ValueError('Unknown adaptive chrome Windows boundary: '+before)
        at=text.index(before);edits.append(dict(offset=at,before=before,after=after));text=text[:at]+after+text[at+len(before):]
    inverse=text
    for edit in reversed(edits):
        at,after=edit['offset'],edit['after']
        if inverse[at:at+len(after)]!=after:raise ValueError('Native chrome inverse boundary mismatch')
        inverse=inverse[:at]+edit['before']+inverse[at+len(after):]
    if inverse!=original:raise ValueError('Complete original adaptive chrome was not retained')
    return text,dict(fixedCommit=COMMIT,sourcePath=ADAPTIVE,originalSHA256=sha(original.encode()),
        adaptedSHA256=sha(text.encode()),edits=edits,wholeOriginalInverseRecovered=True,mainCompiled=False,pixelsValidated=False)

def emit_components(repo,output,test_output=None):
    fixed=fixed_sources(repo);outputs=[]
    for path in NEW_COMPONENT_PATHS:
        target=Path(output)/path.split('/main/java/',1)[1]
        target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(fixed[path]);outputs.append(target)
    if test_output is not None:
        for path,raw in fixed.items():
            if '/test/java/' not in path:continue
            target=Path(test_output)/path.split('/test/java/',1)[1]
            target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(raw)
    return outputs
