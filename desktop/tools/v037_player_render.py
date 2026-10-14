"""Complete fixed original player render/progress sources and actual Skia boundary."""
from pathlib import Path
import hashlib,json
COMMIT='8b47769d22c0d5bc88ae5248cee4aa04ff10d62c'
MANIFEST_SHA='b921ef87f1376bdf0900339f9ccb201a12bfc704a6786c821d0fbf91f73f8ae9'
PINS=[{'path': 'app/src/main/java/com/android/purebilibili/feature/video/ui/overlay/PlayerControlGlass.kt', 'filename': 'PlayerControlGlass.kt', 'bytes': 7732, 'sha256Bytes': '369653f30e4d7db00118a8f5dc9b2357ca359666d927c2135e0944b64ea9bfbf', 'gitBlob': 'ac8b2e578af3e7c824100d9a115af6739261df4c'}, {'path': 'app/src/main/java/com/android/purebilibili/feature/video/ui/overlay/VideoPlayerOverlayProgressState.kt', 'filename': 'VideoPlayerOverlayProgressState.kt', 'bytes': 2575, 'sha256Bytes': 'a46f7995e0b8e7b7a3535f8b23ca9a756f71d9acbf4cf6f8fe2b00702b858ce1', 'gitBlob': '1a1eb3d30634e9cd567806a48712a71d64934490'}, {'path': 'app/src/main/java/com/android/purebilibili/feature/video/ui/overlay/VideoPlayerProgressContent.kt', 'filename': 'VideoPlayerProgressContent.kt', 'bytes': 4585, 'sha256Bytes': '901ea2ae904e6119803f06ecc8a15eec6214dd2b82bf06adc8f19a13ffb78257', 'gitBlob': 'b7b88eab009a01140ccfd6eec23f1140935581c0'}, {'path': 'app/src/main/java/com/android/purebilibili/feature/video/ui/overlay/PlayerChromeLayout.kt', 'filename': 'PlayerChromeLayout.kt', 'bytes': 293, 'sha256Bytes': 'e43211fde64f48bc1c0b355db669034b9d38aee817d412f9adebe00027923ce8', 'gitBlob': 'f9d85fca61c4c03ccbceeca2c534ef5e4bd80f45'}, {'path': 'design-system/src/main/java/com/android/purebilibili/core/ui/components/AppElasticPress.kt', 'filename': 'AppElasticPress.kt', 'bytes': 12675, 'sha256Bytes': '533cb5bb042a794000ef3afe9d3aa12bf76d1fb879e191dd934237129d04137f', 'gitBlob': 'dd82a89a402b73f4ccb3ec16c7e7b16b7040dc48'}]
PRESS_EDITS=[{'before': 'import android.os.Build\n', 'after': '', 'reason': 'Windows shader capability comes from the real Skia constructor.'}, {'before': 'import android.graphics.RuntimeShader\n', 'after': 'import org.jetbrains.skia.RuntimeEffect\nimport org.jetbrains.skia.RuntimeShaderBuilder\nimport androidx.compose.ui.graphics.asComposeShader\n', 'reason': 'Use the actual bundled Skia effect and uniform builder.'}, {'before': 'import androidx.annotation.RequiresApi\n', 'after': '', 'reason': 'Remove only the Android API annotation import.'}, {'before': '    private val shader = if (Build.VERSION.SDK_INT >= 33) PressShader() else null', 'after': '    private val shader = runCatching { PressShader() }.getOrNull()', 'reason': 'Probe the actual shader implementation and retain the whole original fallback.'}, {'before': '        if (Build.VERSION.SDK_INT >= 33 && shader != null) {', 'after': '        if (shader != null) {', 'reason': 'Use the real successfully created Windows shader.'}, {'before': '@RequiresApi(33)\nprivate class PressShader {', 'after': 'private class PressShader {', 'reason': 'Preserve the original private shader owner.'}, {'before': '    private val shader = RuntimeShader("""', 'after': '    private val effect = RuntimeEffect.makeForShader("""', 'reason': 'Compile the same complete original SKSL program.'}, {'before': '    private val brush = ShaderBrush(shader)', 'after': '    private val shader = RuntimeShaderBuilder(effect)', 'reason': 'Keep one native effect and uniform builder for the original highlight lifetime.'}, {'before': '        shader.setColorUniform("color", android.graphics.Color.argb(0.15f * progress, 1f, 1f, 1f))', 'after': '        shader.uniform("color", 1f, 1f, 1f, 0.15f * progress)', 'reason': 'Pass the same original white color channels and floating alpha through the real API.'}, {'before': '        shader.setFloatUniform("radius", size.minDimension * 1.5f)', 'after': '        shader.uniform("radius", size.minDimension * 1.5f)', 'reason': 'Retain the original radius expression.'}, {'before': '        shader.setFloatUniform("position", position.x.coerceIn(0f, size.width), position.y.coerceIn(0f, size.height))', 'after': '        shader.uniform("position", position.x.coerceIn(0f, size.width), position.y.coerceIn(0f, size.height))', 'reason': 'Retain the original position and clipping expressions.'}, {'before': '        drawRect(brush, blendMode = BlendMode.Plus)', 'after': '        shader.makeShader().use { frameShader ->\n            drawRect(ShaderBrush(frameShader.asComposeShader()), blendMode = BlendMode.Plus)\n        }', 'reason': 'Wrap the real Skia shader through the bundled Compose1.12.1 asComposeShader API; retain original blend and per-draw release.'}]

def fixed_sources():
    archive=Path(__file__).resolve().parent.parent/'upstream-slices/v037-player-render-closure'
    raw=(archive/'manifest.json').read_bytes()
    if hashlib.sha256(raw).hexdigest()!=MANIFEST_SHA:raise ValueError('Fixed player render manifest changed')
    manifest=json.loads(raw)
    if manifest['fixedUpstreamCommit']!=COMMIT or manifest['files']!=PINS:raise ValueError('Unknown player render closure')
    result={}
    for row in PINS:
        raw=(archive/row['filename']).read_bytes()
        if len(raw)!=row['bytes'] or hashlib.sha256(raw).hexdigest()!=row['sha256Bytes'] or hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest()!=row['gitBlob']:raise ValueError('Fixed original player render source changed')
        result[row['path']]=raw.decode('utf-8')
    return result

def generate_render_sources(output):
    from v029_comment_search import whole_proof
    sources=fixed_sources()
    outputs=[]
    for row in PINS:
        original=sources[row['path']]
        from v040_chrome_actions import retained_render_source
        original,effective_row,effective_commit=retained_render_source(Path(__file__).resolve().parents[2],row['path'],original,row,COMMIT)
        text=original
        if row['filename']=='VideoPlayerOverlayProgressState.kt':
            before='import androidx.media3.common.Player'
            if text.count(before)!=1:raise ValueError('Unknown original progress player import')
            text=text.replace(before,'import com.bilipai.desktop.ui.DesktopOriginalMpvOverlayControl as Player',1)
        elif row['filename']=='AppElasticPress.kt':
            for edit in PRESS_EDITS:
                if text.count(edit['before'])!=1:raise ValueError('Unknown press boundary: '+edit['reason'])
                text=text.replace(edit['before'],edit['after'],1)
        target=output/'com/android/purebilibili'/row['path'].split('com/android/purebilibili/')[1]
        target.parent.mkdir(parents=True,exist_ok=True)
        target.write_bytes(text.encode('utf-8'))
        proof=whole_proof(original,text)
        proof.update(upstreamCommit=effective_commit,sourcePath=row['path'],source=effective_row,wholeOriginalSelected=True,
                     sourceQualified=False,shaderCompiled=False,pixelsValidated=False)
        destination=output/'v037-render-source-proofs'/(row['filename']+'.json')
        destination.parent.mkdir(parents=True,exist_ok=True)
        destination.write_bytes((json.dumps(proof,ensure_ascii=False,indent=2)+'\n').encode('utf-8'))
        outputs.append(target)
    if len(outputs)!=5:raise ValueError('Incomplete player render closure')
    return outputs
