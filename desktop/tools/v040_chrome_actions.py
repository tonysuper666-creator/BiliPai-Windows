"""Complete fixed upstream action controls, using existing producers and Windows bindings."""
from pathlib import Path
import hashlib,json

COMMIT = '415d01ec5bc3ac82521a9ac47bcc449b8e202460'
ARCHIVE = 'desktop/upstream-slices/v040-chrome-actions'
MANIFEST_SHA256 = 'a78a770551ee2575467b55b47ac3b78133705289b2d4951c4b1181b9ca36f088'
PINS = [{'path': 'design-system/src/main/java/com/android/purebilibili/core/ui/components/AppPrimitiveComponents.kt', 'filename': 'AppPrimitiveComponents.kt', 'bytes': 56220, 'sha256Bytes': '2643c2b4a92b831827ebc4b9157ea2abbbc6677244d31d11cd68c3927d0ae6ba', 'gitBlob': '4434425a01575797d7638c534a5c65943e6d4602', 'testCases': None}, {'path': 'design-system/src/main/java/com/android/purebilibili/core/ui/components/AppIconButton.kt', 'filename': 'AppIconButton.kt', 'bytes': 4684, 'sha256Bytes': 'de08bf83ea026aefa5fd5a235fd74e0535db5311ac369fe4d53340f4335919ef', 'gitBlob': 'b5d74ad99839e6f3efc0d85a4993214ffbbd4dfb', 'testCases': None}, {'path': 'design-system/src/main/java/com/android/purebilibili/core/ui/components/AppPrimaryButton.kt', 'filename': 'AppPrimaryButton.kt', 'bytes': 5872, 'sha256Bytes': '22b86048cf19573a4651afefa468db675deaf9e92011651a5aed09036cdecf75', 'gitBlob': '17b089651103feb582f9ae8b9002313a9460628f', 'testCases': None}, {'path': 'design-system/src/main/java/com/android/purebilibili/core/ui/components/AppIcon.kt', 'filename': 'AppIcon.kt', 'bytes': 2182, 'sha256Bytes': '6e975a3a20ac6bc1cdf802d4a810755ed88716a0c1460d4650b5ac5531937827', 'gitBlob': 'b152283112a816d6cbf2597579d73f56aa5f8e53', 'testCases': None}, {'path': 'design-system/src/main/java/com/android/purebilibili/core/ui/components/AppText.kt', 'filename': 'AppText.kt', 'bytes': 11944, 'sha256Bytes': '147a05777f4ebab2e5c6e9186393eef76cf1a02b03b6cb10c13548d9399f17aa', 'gitBlob': '0bd53e1be2378229907a5509f2d61a9409780360', 'testCases': None}, {'path': 'design-system/src/main/java/com/android/purebilibili/core/ui/components/AppTopBarIconDecoration.kt', 'filename': 'AppTopBarIconDecoration.kt', 'bytes': 878, 'sha256Bytes': 'f232c8cd3ac5e0bb71c9e47ca425c9e6670ea4c95b801c7a2142d4783137e2b9', 'gitBlob': 'c86b4f39854f305833d851d3c0012a6922a9ed2f', 'testCases': None}, {'path': 'design-system/src/main/java/com/android/purebilibili/core/ui/components/AppElasticIndication.kt', 'filename': 'AppElasticIndication.kt', 'bytes': 1619, 'sha256Bytes': '29b225b0ad0969e37a86c83d02a3287bea9a4f694fe65e1bc380750968752759', 'gitBlob': '781ebfe3ceb0fd0c54d872d1ad7a5835faf24da2', 'testCases': None}, {'path': 'design-system/src/main/java/com/android/purebilibili/core/ui/components/AppElasticPress.kt', 'filename': 'AppElasticPress.kt', 'bytes': 12857, 'sha256Bytes': 'c2655b0ccc618defcb08c2c2d2b9eb2cc1c8dd843c7368fd97872a5b54d3a69c', 'gitBlob': 'd1aecdfc4a0df5a6dc61cf89bba0005d59539d9d', 'testCases': None}, {'path': 'design-system/src/main/java/com/android/purebilibili/core/ui/ButtonVisualPolicy.kt', 'filename': 'ButtonVisualPolicy.kt', 'bytes': 2154, 'sha256Bytes': 'fc70e9438211b4fc181d0bd99ab4f7da397658152f2d0950546ff6edaf68db37', 'gitBlob': '31ecee19053f0ce4ba09764b57da36a902638ec6', 'testCases': None}, {'path': 'design-system/src/main/java/com/android/purebilibili/core/ui/ButtonSlotColor.kt', 'filename': 'ButtonSlotColor.kt', 'bytes': 2665, 'sha256Bytes': 'c6a3251f7c5061272d194557f489cad4465384d29311fa07a8f13c963ccb37fc', 'gitBlob': 'a3033926b780a38a656792105625db5e155e6b9f', 'testCases': None}, {'path': 'design-system/src/main/java/com/android/purebilibili/core/ui/motion/AppTabIndicatorMotion.kt', 'filename': 'AppTabIndicatorMotion.kt', 'bytes': 616, 'sha256Bytes': 'dfbcb8131f3ef3abeed0fdf94ae15c003294881f78897b58371da1ed2e076a85', 'gitBlob': 'c05cd26c9f7d1d6a712578a93bdbddabb338bea8', 'testCases': None}, {'path': 'design-system/src/main/java/com/android/purebilibili/core/ui/renderer/miuix/AppMiuixActionPrimitives.kt', 'filename': 'AppMiuixActionPrimitives.kt', 'bytes': 13675, 'sha256Bytes': 'f0af7b6ef218d6c52e5a0d755c0d9c4bea6db037b199d1241225ac057432e705', 'gitBlob': 'c98d43d1d3a559d99704b177e8e986c3ccfedab8', 'testCases': None}, {'path': 'design-system/src/main/java/com/android/purebilibili/core/ui/renderer/miuix/AppMiuixIconButton.kt', 'filename': 'AppMiuixIconButton.kt', 'bytes': 5850, 'sha256Bytes': 'fdf8965a75cfcc2cc0de93f1bbdcc136ac000290127ec8fa76762d172934e13b', 'gitBlob': '03e69dd277ae813434406e0d2786cf182d4c8f5a', 'testCases': None}, {'path': 'design-system/src/main/java/com/android/purebilibili/core/ui/renderer/material3/AppMaterial3IconButton.kt', 'filename': 'AppMaterial3IconButton.kt', 'bytes': 4608, 'sha256Bytes': '364bd6d032ca826bc07ee98a496374b24a9be278aafb0d5c5a5c1c1a2396cd48', 'gitBlob': 'f59cf010e1f7b7d4a69dcab786e0b7e65ec3b821', 'testCases': None}, {'path': 'design-system/src/test/java/com/android/purebilibili/core/ui/ButtonSlotColorTest.kt', 'filename': 'ButtonSlotColorTest.kt', 'bytes': 1636, 'sha256Bytes': 'cf703a593ab5957e521a930ab04c142920443b68d2ae044f506fabd579e1de5e', 'gitBlob': '8de4f9fba374827a8413ba00aa8155b17ef63f26', 'testCases': 3}, {'path': 'design-system/src/test/java/com/android/purebilibili/core/ui/ButtonVisualPolicyTest.kt', 'filename': 'ButtonVisualPolicyTest.kt', 'bytes': 3255, 'sha256Bytes': 'fec626241d32605bac0d2a99d0e1099560f9318f3985b180132eee54966123d3', 'gitBlob': '7bdf57a982c1cac60309a876a9625d2f235f49b4', 'testCases': 5}, {'path': 'design-system/src/main/java/com/android/purebilibili/core/ui/components/AppPrimitiveRendererPolicy.kt', 'filename': 'AppPrimitiveRendererPolicy.kt', 'bytes': 2268, 'sha256Bytes': 'be1fd3db0007484ac9936a98c8dad4c6ff52c66af0e3af410f496475a0351ad2', 'gitBlob': 'c22cce4b64fbd2e447b97d560ed9ca851fb58691', 'testCases': None}, {'path': 'design-system/src/main/java/com/android/purebilibili/core/ui/AppChromeSizeTokens.kt', 'filename': 'AppChromeSizeTokens.kt', 'bytes': 4355, 'sha256Bytes': '89c7d9a7f651741d5322391c8b03c005310fc4a7c5589eadaefb88853e072657', 'gitBlob': 'c1357a229e2dbba858937a957e36d69df4bc682c', 'testCases': None}, {'path': 'design-system/src/test/java/com/android/purebilibili/core/ui/AppChromeSizeTokensTest.kt', 'filename': 'AppChromeSizeTokensTest.kt', 'bytes': 850, 'sha256Bytes': 'ddb629ca6feb528e202ae71230b1141ead3bb10d19782eab4c4f02b746a1702c', 'gitBlob': '145243541346f951542c5ec192ad2ab75c21af4c', 'testCases': 1}, {'path': 'design-system/src/test/java/com/android/purebilibili/core/ui/components/AppPrimitiveRendererPolicyTest.kt', 'filename': 'AppPrimitiveRendererPolicyTest.kt', 'bytes': 3406, 'sha256Bytes': '19cdbae75d96a5161c484e94dd712f4696d1c1c69a36a06aa33e849f237b8204', 'gitBlob': '529f25c546ec0402cb380369d095ae088d814bbb', 'testCases': 6}, {'path': 'design-system/src/main/java/com/android/purebilibili/core/ui/components/AppTextSearchHighlight.kt', 'filename': 'AppTextSearchHighlight.kt', 'bytes': 2163, 'sha256Bytes': 'dec631fc6fbeae00a0ed83b6f4d71afc0272cfc1740b6b1363c116c5acb2ab64', 'gitBlob': '8b8e24971d5ac80bd0a93c76f1e4ba2bd100213c'}, {'path': 'design-system/src/test/java/com/android/purebilibili/core/ui/components/AppTextSearchHighlightTest.kt', 'filename': 'AppTextSearchHighlightTest.kt', 'bytes': 767, 'sha256Bytes': '741cda90ca52843b90af7e8b385a6f2b9250a117b883d30a22950fd92ea71c25', 'gitBlob': 'eef823b41ff65237479003c47c5feab56cc51586'}]
DS = 'design-system/src/main/java/com/android/purebilibili/core/ui/'
COMPONENT_DIRECT_PATHS = {DS+p for p in [
    'components/AppPrimitiveComponents.kt','components/AppIconButton.kt','components/AppPrimaryButton.kt',
    'components/AppIcon.kt','ButtonVisualPolicy.kt','components/AppPrimitiveRendererPolicy.kt','AppChromeSizeTokens.kt',
    'renderer/miuix/AppMiuixActionPrimitives.kt','renderer/miuix/AppMiuixIconButton.kt','renderer/material3/AppMaterial3IconButton.kt']}
COMPONENT_ADAPTED_PATHS = {DS+'components/AppText.kt'}
NEW_COMPONENT_PATHS = [DS+p for p in ['components/AppTextSearchHighlight.kt','ButtonSlotColor.kt','components/AppElasticIndication.kt','motion/AppTabIndicatorMotion.kt']]
RENDER_PATH = DS+'components/AppElasticPress.kt'
EXISTING_DECORATION_PATH = DS+'components/AppTopBarIconDecoration.kt'

def sha(raw):return hashlib.sha256(raw).hexdigest()

def fixed_sources(repo):
    root=Path(repo)/ARCHIVE
    raw=(root/'manifest.json').read_bytes()
    if sha(raw)!=MANIFEST_SHA256:raise ValueError('Unreviewed chrome action manifest')
    value=json.loads(raw)
    if value['fixedUpstreamCommit']!=COMMIT or value['files']!=PINS:raise ValueError('Unknown chrome action source identity')
    result={}
    for row in PINS:
        raw=(root/row['filename']).read_bytes()
        blob=hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest()
        if len(raw)!=row['bytes'] or sha(raw)!=row['sha256Bytes'] or blob!=row['gitBlob']:
            raise ValueError('Modified original chrome action source: '+row['path'])
        result[row['path']]=raw
    return result

def row_for(path):return next(dict(r) for r in PINS if r['path']==path)

def selected_component_source(repo,path,previous):
    return fixed_sources(repo)[path].decode('utf-8') if path in COMPONENT_DIRECT_PATHS|COMPONENT_ADAPTED_PATHS else previous

def retained_render_source(repo,path,previous,row,commit):
    if path==RENDER_PATH:return fixed_sources(repo)[path].decode('utf-8'),row_for(path),COMMIT
    return previous,row,commit

def emit_components(repo,output,test_output=None):
    sources=fixed_sources(repo);paths=[]
    for path in NEW_COMPONENT_PATHS:
        target=Path(output)/path.split('/main/java/',1)[1]
        target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(sources[path]);paths.append(target)
    if test_output is not None:
        for path,raw in sources.items():
            if '/test/java/' not in path:continue
            target=Path(test_output)/path.split('/test/java/',1)[1]
            target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(raw)
    return paths
