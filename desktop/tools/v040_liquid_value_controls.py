"""Complete fixed original liquid value controls and theme picker; narrow Windows bindings."""
from pathlib import Path
import hashlib
import importlib.util
import json
import xml.etree.ElementTree as ET

COMMIT = '415d01ec5bc3ac82521a9ac47bcc449b8e202460'
ARCHIVE = 'desktop/upstream-slices/v040-liquid-value-controls'
MANIFEST_SHA256 = 'eeae9081bc1a9bd2ab0fdf358f9561aac4d68578f106d13cf2a40fb5411a38c7'
PINS = [{'path': 'design-system/src/main/java/com/android/purebilibili/core/ui/AppThemeConfig.kt', 'filename': 'AppThemeConfig.kt', 'bytes': 4140, 'sha256Bytes': 'dcf5d881c212831ed0bfb55453a84ecd922b4784c96c526e2c56bbdfc44184c9', 'gitBlob': '642d54150318f263d6e1913140ff50c696662b4d'}, {'path': 'design-system/src/main/java/com/android/purebilibili/core/ui/MatchedLiquidIndicatorGeometry.kt', 'filename': 'MatchedLiquidIndicatorGeometry.kt', 'bytes': 2416, 'sha256Bytes': '888bc7225df42df639f26884a96ad2c264a437455034373664517a036796bdf8', 'gitBlob': 'ecfd8790e169c694ac4f08085de19a37daf8395c'}, {'path': 'design-system/src/main/java/com/android/purebilibili/core/ui/components/AppSwitch.kt', 'filename': 'AppSwitch.kt', 'bytes': 1574, 'sha256Bytes': 'f8762e68edbcfc4bb73557784ce67e710f2d9864fc7e883fae3581808f5dfcaf', 'gitBlob': '278bcedb5c1db0a11ce4f62dbf6bb608e552d2a4'}, {'path': 'design-system/src/main/java/com/android/purebilibili/core/ui/components/AppSlider.kt', 'filename': 'AppSlider.kt', 'bytes': 4201, 'sha256Bytes': '3cbab74395f1e369655ca4f2ea7ccb378aea1a1168e158f8c1562ac4851c5a57', 'gitBlob': 'cefbea7050d7b7dc090c4469cbe335e097bb7ecc'}, {'path': 'design-system/src/main/java/com/android/purebilibili/core/ui/components/AppColorPicker.kt', 'filename': 'AppColorPicker.kt', 'bytes': 3618, 'sha256Bytes': 'f64643940c299391056d4e0e4aa6a9cd99413ccecbe17b0c8505bfcd4ff23d82', 'gitBlob': '026fc86b1e66480287023e117b458c0774ca9628'}, {'path': 'design-system/src/main/java/com/android/purebilibili/core/ui/components/AppLiquidValueControlPolicy.kt', 'filename': 'AppLiquidValueControlPolicy.kt', 'bytes': 2980, 'sha256Bytes': '5684a3b80ce757e64bebb066a96d6ec8956383bb2fe1c463265e91fb7dd06f69', 'gitBlob': 'c2ad73170ee2d67accbdc1ae1160a059950f29fa'}, {'path': 'design-system/src/main/java/com/android/purebilibili/core/ui/components/AppLiquidValueControls.kt', 'filename': 'AppLiquidValueControls.kt', 'bytes': 26575, 'sha256Bytes': '2b58d74fc5a54f154d95317c2f38aa5d768728ae9f38400e5adddb8a46c4c85d', 'gitBlob': '082d84b0b9c366194773446738532e2222c6cf98'}, {'path': 'design-system/src/main/java/com/android/purebilibili/core/ui/components/AppChromeOverflow.kt', 'filename': 'AppChromeOverflow.kt', 'bytes': 4735, 'sha256Bytes': '22ae777cba84a6ea058776b71f1aa47c1477f01cd6f9f9bac1eea44c33bdd718', 'gitBlob': '510821f3be9fc81e49a91702427d35219509d259'}, {'path': 'design-system/src/main/java/com/android/purebilibili/core/ui/animation/LiquidIndicatorMotion.kt', 'filename': 'LiquidIndicatorMotion.kt', 'bytes': 1284, 'sha256Bytes': '8151b8196ed1d22313b5bfaaff52aae92ab669248cfb830df02626bae44bd0f0', 'gitBlob': 'c7c68a6e2139e7944d15a31044b7aabac8f11421'}, {'path': 'design-system/src/main/java/com/android/purebilibili/core/ui/animation/LiquidIndicatorVelocity.kt', 'filename': 'LiquidIndicatorVelocity.kt', 'bytes': 2511, 'sha256Bytes': 'a7f4e85a6018fbd7cdb7624da5f6ad20c4ad8e4715b091ae5e17710e367c208c', 'gitBlob': 'a02112c467ef9bea9642414824fd52420280e737'}, {'path': 'app/src/main/java/com/android/purebilibili/feature/settings/ThemeCustomColorPickerDialog.kt', 'filename': 'ThemeCustomColorPickerDialog.kt', 'bytes': 22686, 'sha256Bytes': 'bfa8ef7cd8d0c5de4912bff071c0342c26a91aea623cf50509171d8a01ef9b57', 'gitBlob': '60d9e66831953ece3ce0d66d7c28fdfe7bffba71'}, {'path': 'app/src/main/java/com/android/purebilibili/feature/settings/screen/AppearanceSettingsScreen.kt', 'filename': 'AppearanceSettingsScreen.kt', 'bytes': 139202, 'sha256Bytes': 'c79f46a302978558e236f34d14871bb0f4a7fbbad85acf366b3649aaecbcc599', 'gitBlob': '763f918ae3ba25642102cc82a04062357d16644e'}, {'path': 'design-system/src/main/res/values/liquid_value_colors.xml', 'filename': 'liquid_value_colors-light.xml', 'bytes': 325, 'sha256Bytes': '13c5ef2c665cb3cd0ce3f4b18edf83e274969b5f26de4c4aedb4d597dad2b578', 'gitBlob': 'b965f6c624bf339c9d414a98a96a7376ced73567'}, {'path': 'design-system/src/main/res/values-night/liquid_value_colors.xml', 'filename': 'liquid_value_colors-dark.xml', 'bytes': 325, 'sha256Bytes': '10855d97534e26ffe383f2abbafe99925cee539fff3f00f2d6cb5eac3d331299', 'gitBlob': '19b1d68aa17b0387c0f329b602112274fae69c1d'}, {'path': 'design-system/src/test/java/com/android/purebilibili/core/ui/animation/LiquidIndicatorMotionTest.kt', 'filename': 'LiquidIndicatorMotionTest.kt', 'bytes': 813, 'sha256Bytes': '6781e650a6825be1eaedc5befefcd938f467ceb275eba5447fd396b95eb02f31', 'gitBlob': '92af232dd63147e74397ae715c6b88b334b784f3'}, {'path': 'design-system/src/test/java/com/android/purebilibili/core/ui/animation/LiquidIndicatorVelocityTest.kt', 'filename': 'LiquidIndicatorVelocityTest.kt', 'bytes': 1932, 'sha256Bytes': 'bc1df30dbd18304aa63b7af7e91053d7168f44ab426be4ea4ea681e6a7def04b', 'gitBlob': '51b3dcf9c67d4f8ed2bdb0b61a55c024db60311e'}, {'path': 'design-system/src/test/java/com/android/purebilibili/core/ui/MatchedLiquidIndicatorGeometryTest.kt', 'filename': 'MatchedLiquidIndicatorGeometryTest.kt', 'bytes': 2027, 'sha256Bytes': '18eef10ababd3d0777aa1286e8c3c32685997a272949545395d08a42b389c1fb', 'gitBlob': 'd2b3af2464690b3423f4662ae07ab19650447812'}, {'path': 'app/src/test/java/com/android/purebilibili/feature/settings/AppearanceMd3ColorPickerPolicyTest.kt', 'filename': 'AppearanceMd3ColorPickerPolicyTest.kt', 'bytes': 1206, 'sha256Bytes': '82654025d0f988081834797d5ec51d3f77d20e0c2f50f24512ae6b30a2d9c611', 'gitBlob': 'f22c15df0dbe016cdb3d7fd45d1647913f336289'}, {'path': 'design-system/src/main/java/com/android/purebilibili/core/ui/components/AppActionColors.kt', 'filename': 'AppActionColors.kt', 'bytes': 2333, 'sha256Bytes': '5cf40e13bb9645a8171dc5e12f290996329f89172b246846ba5060d8dbdb9ef6', 'gitBlob': 'd7f0c338414450002b7af634fbfa1d2653f5ed49'}, {'path': 'design-system/src/main/java/com/android/purebilibili/core/ui/renderer/material3/AppMaterial3Switch.kt', 'filename': 'AppMaterial3Switch.kt', 'bytes': 1790, 'sha256Bytes': 'd087e16f3d37cee3c5f5f6ec11689a601d504bd2bc5393a687d8eb8c518bfd5c', 'gitBlob': 'a459609999254c7f2cddc1313f524a555f1e4b65'}, {'path': 'design-system/src/main/java/com/android/purebilibili/core/ui/renderer/miuix/AppMiuixSwitch.kt', 'filename': 'AppMiuixSwitch.kt', 'bytes': 798, 'sha256Bytes': 'c0627e3a9f289d826edcf1fda72472ce96a24746fc6b91d0d5631e4d014a4208', 'gitBlob': 'd9f00421360651c113139ab6a7f43d4f614191a6'}, {'path': 'design-system/src/main/java/com/android/purebilibili/core/ui/renderer/miuix/AppMiuixSlider.kt', 'filename': 'AppMiuixSlider.kt', 'bytes': 1368, 'sha256Bytes': 'bb3e2da67e1e5473ee84ce1e48b152423d3081801ad00cd73d25e96de0dc4688', 'gitBlob': '452762a6b08929e0ac1629113e12ef7e0c7ee13e'}, {'path': 'design-system/src/main/java/com/android/purebilibili/core/ui/effect/LiquidLens.kt', 'filename': 'LiquidLens.kt', 'bytes': 8898, 'sha256Bytes': 'f9a5863bf9e0cdd26dc05f8b0af08be07c291b425e327a682b0afb35b65df667', 'gitBlob': 'b21ec83491f4b4fa7bd865f09690427b877a488d'}]
DS = 'design-system/src/main/java/com/android/purebilibili/core/ui/'
PICKER = 'app/src/main/java/com/android/purebilibili/feature/settings/ThemeCustomColorPickerDialog.kt'
SCREEN = 'app/src/main/java/com/android/purebilibili/feature/settings/screen/AppearanceSettingsScreen.kt'
NEW_COMPONENT_DIRECT_PATHS = {DS+'components/AppSwitch.kt',
    DS+'renderer/material3/AppMaterial3Switch.kt', DS+'renderer/miuix/AppMiuixSwitch.kt', DS+'renderer/miuix/AppMiuixSlider.kt'}
NEW_COMPONENT_PATHS = [DS+'effect/LiquidLens.kt', DS+'components/AppActionColors.kt', DS+'MatchedLiquidIndicatorGeometry.kt', DS+'components/AppLiquidValueControls.kt',
    DS+'components/AppChromeOverflow.kt', DS+'animation/LiquidIndicatorMotion.kt', DS+'animation/LiquidIndicatorVelocity.kt']
SUPPORT_DECLARATIONS = ['ThemeRoleColorTarget','Md3ColorPickerSliderLayout',
    'resolveMd3ColorPickerSliderLayout','MD3_COLOR_PICKER_HAPTIC_MIN_INTERVAL_MS','shouldEmitMd3ColorPickerSelectionHaptic']


def sha(raw): return hashlib.sha256(raw).hexdigest()


def fixed_sources(repo):
    root=Path(repo)/ARCHIVE
    raw=(root/'manifest.json').read_bytes()
    if sha(raw)!=MANIFEST_SHA256: raise ValueError('Unreviewed liquid value source manifest')
    value=json.loads(raw)
    if value['fixedUpstreamCommit']!=COMMIT or value['files']!=PINS:
        raise ValueError('Unreviewed liquid value source identity')
    result={}
    for row in PINS:
        raw=(root/row['filename']).read_bytes()
        blob=hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest()
        if len(raw)!=row['bytes'] or sha(raw)!=row['sha256Bytes'] or blob!=row['gitBlob']:
            raise ValueError('Modified liquid value source: '+row['path'])
        result[row['path']]=raw
    return result


def row_for(path):
    return next(dict(row) for row in PINS if row['path']==path)


def selected_component_source(repo, path, previous):
    if path in NEW_COMPONENT_DIRECT_PATHS | {DS+'AppThemeConfig.kt', DS+'components/AppSlider.kt'}:
        return fixed_sources(repo)[path].decode('utf-8')
    return previous


def retained_original_override(repo, path, raw, row, commit, manifest_sha):
    if path in {DS+'AppThemeConfig.kt', DS+'components/AppColorPicker.kt', DS+'components/AppLiquidValueControlPolicy.kt'}:
        return fixed_sources(repo)[path], row_for(path), COMMIT, MANIFEST_SHA256
    return raw, row, commit, manifest_sha


def adapt(path, raw):
    text=raw.decode('utf-8'); original=text; edits=[]
    def swap(before,after,count=1):
        nonlocal text
        if text.count(before)!=count: raise ValueError('Unexpected liquid value platform boundary: '+before)
        for _ in range(count):
            at=text.index(before)
            edits.append(dict(offset=at,before=before,after=after))
            text=text[:at]+after+text[at+len(before):]
    if path.endswith('/AppSlider.kt'):
        swap('import android.os.SystemClock','import com.bilipai.desktop.appearance.DesktopMonotonicClock as SystemClock')
    elif path.endswith('/AppLiquidValueControlPolicy.kt'):
        swap('import android.os.Build\n','')
        swap('Build.VERSION.SDK_INT >= 33','com.bilipai.desktop.ui.desktopDetailRenderEffectsSupported()')
    elif path.endswith('/AppLiquidValueControls.kt'):
        swap('import androidx.compose.ui.res.colorResource\n','')
        swap('import com.android.purebilibili.designsystem.R\n','')
        for name,count in [('liquid_switch_active',1),('liquid_slider_active',1),('liquid_value_inactive',2)]:
            before='colorResource(R.color.'+name+')'
            swap(before,'desktopLiquidValueColorResource("'+name+'")',count)
    elif path==PICKER:
        swap('import android.os.SystemClock','import com.bilipai.desktop.appearance.DesktopMonotonicClock as SystemClock')
        swap('import android.os.Build\n','')
        swap('import androidx.compose.ui.platform.LocalConfiguration','import com.bilipai.desktop.appearance.DesktopWindowConfiguration as LocalConfiguration')
        swap('Build.VERSION.SDK_INT >= 33','com.bilipai.desktop.ui.desktopDetailRenderEffectsSupported()')
        swap('android.graphics.Color.colorToHSV','desktopThemeColorToHSV',2)
        swap('                                    LocalLiquidControlThemeColor provides color,',
             '                                    com.bilipai.desktop.appearance.LocalDesktopDarkTheme provides previewDark,\n'+
             '                                    LocalLiquidControlThemeColor provides color,')
        extra='''
// Android HSV degrees are supplied by the existing JDK RGB/HSB implementation.
private fun desktopThemeColorToHSV(argb: Int, destination: FloatArray) {
    require(destination.size >= 3)
    java.awt.Color.RGBtoHSB((argb ushr 16) and 255, (argb ushr 8) and 255, argb and 255, destination)
    destination[0] *= 360f
}
'''
        edits.append(dict(offset=len(text),before='',after=extra));text+=extra
    inverse=text
    for edit in reversed(edits):
        at=edit['offset'];after=edit['after']
        if inverse[at:at+len(after)]!=after:raise ValueError('Liquid control inverse boundary mismatch')
        inverse=inverse[:at]+edit['before']+inverse[at+len(after):]
    if inverse!=original:raise ValueError('Liquid control original was not completely retained')
    return text,dict(sourcePath=path,fixedCommit=COMMIT,originalSHA256=sha(raw),
        adaptedSHA256=sha(text.encode()),fullOriginalInverseRecovered=True,edits=edits,
        mainCompiled=False,pixelsValidated=False)


def resource_colors(repo):
    sources=fixed_sources(repo); result={}
    for night,qualifier in [(False,'values'),(True,'values-night')]:
        path='design-system/src/main/res/'+qualifier+'/liquid_value_colors.xml'
        rows={node.attrib['name']:node.text.strip() for node in ET.fromstring(sources[path]).findall('color')}
        if set(rows)!={'liquid_switch_active','liquid_slider_active','liquid_value_inactive'}:
            raise ValueError('Unknown liquid value resource set')
        if any(len(v)!=9 or not v.startswith('#') for v in rows.values()):raise ValueError('Unknown liquid value resource encoding')
        result[night]=rows
    return result


def resource_source(repo):
    colors=resource_colors(repo)
    result='''package com.android.purebilibili.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Exact fixed Android light/night resources, selected by the existing effective Windows theme.
@Composable
internal fun desktopLiquidValueColorResource(name: String): Color {
    val dark = com.bilipai.desktop.appearance.isDesktopInDarkTheme()
    return if (dark) when (name) {
'''
    for name,value in colors[True].items():result+='        "'+name+'" -> Color(0x'+value[1:]+')\n'
    result+='        else -> error("Unknown liquid value color: $name")\n    } else when (name) {\n'
    for name,value in colors[False].items():result+='        "'+name+'" -> Color(0x'+value[1:]+')\n'
    return result+'        else -> error("Unknown liquid value color: $name")\n    }\n}\n'


def picker_support(repo):
    def load(name,path):
        spec=importlib.util.spec_from_file_location(name,path)
        module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module
    parser=load('liquid_value_original_parser',Path(repo)/'desktop/tools/sync-upstream.py')
    appearance=load('liquid_value_appearance_selector',Path(repo)/'desktop/tools/extract-appearance-platform.py')
    source=fixed_sources(repo)[SCREEN].decode('utf-8')
    body=appearance.declarations(parser,source,SUPPORT_DECLARATIONS)
    return '''package com.android.purebilibili.feature.settings

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.store.ThemeRoleOverrides

'''+body


def emit_components(repo,output):
    sources=fixed_sources(repo); result=[]
    for path in NEW_COMPONENT_PATHS:
        text,proof=adapt(path,sources[path])
        relative=path.split('/main/java/',1)[1];target=Path(output)/relative
        target.parent.mkdir(parents=True,exist_ok=True);target.write_text(text,encoding='utf-8',newline='\n')
        result.append(dict(path=relative,origin=path,fixedCommit=COMMIT,source=row_for(path),
            sourceManifestSHA256=MANIFEST_SHA256,sha256LF=sha(text.encode()),mode='full-original-Windows-resource-boundary',generated=True))
        target=Path(output)/'v040-liquid-value-platform-proofs'/(Path(path).name+'.json')
        target.parent.mkdir(parents=True,exist_ok=True);target.write_text(json.dumps(proof,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    target=Path(output)/'com/android/purebilibili/core/ui/components/DesktopLiquidValueColorResources.kt'
    target.parent.mkdir(parents=True,exist_ok=True);target.write_text(resource_source(repo),encoding='utf-8',newline='\n')
    return result


def emit_picker(repo,output,test_output=None):
    sources=fixed_sources(repo)
    text,proof=adapt(PICKER,sources[PICKER]);relative=PICKER.split('/main/java/',1)[1]
    target=Path(output)/relative;target.parent.mkdir(parents=True,exist_ok=True);target.write_text(text,encoding='utf-8',newline='\n')
    target=target.with_name('DesktopOriginalThemeColorPickerPolicy.kt')
    target.write_text(picker_support(repo),encoding='utf-8',newline='\n')
    target=Path(output)/'v040-liquid-value-platform-proofs/ThemeCustomColorPickerDialog.kt.json'
    target.parent.mkdir(parents=True,exist_ok=True);target.write_text(json.dumps(proof,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    if test_output is not None:
        for path,raw in sources.items():
            if '/test/java/' not in path:continue
            target=Path(test_output)/path.split('/test/java/',1)[1]
            target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(raw)
