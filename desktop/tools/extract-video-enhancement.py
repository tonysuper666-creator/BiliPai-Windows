"""Original enhancement output policy, MIT notice and settings UI platform boundary."""
from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import json
import re

BASE = 'app/src/main/java/com/android/purebilibili/'


def generate(repo: Path, output: Path, selector, parser, write, substitute):
    files = []
    path = BASE + 'feature/anime4k/Anime4KOutputPolicy.kt'
    original = (_desktop_canonical_source(repo, path)).read_text(encoding='utf-8').replace('\r\n', '\n')
    body = substitute(original, '@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)\n\n', '')
    body = substitute(body, 'import android.app.ActivityManager\n', '')
    body = substitute(body, 'import android.content.Context\n', '')
    body = substitute(body, 'import androidx.media3.common.C', 'import com.bilipai.desktop.player.platform.DesktopMedia3ColorTransfers as C')
    availability = selector.function(body, 'isAnime4KGles3Available', parser)
    body = substitute(body, availability, '').rstrip() + '\n'
    files.append(write(output, path, original, body))
    codes = json.loads((repo / 'desktop/third-party/fsr-hdr-platform.json').read_text(encoding='utf-8'))
    assert codes['constants'] == {'COLOR_TRANSFER_ST2084': 6, 'COLOR_TRANSFER_HLG': 7}
    aliases = 'package com.bilipai.desktop.player.platform\n\ninternal object DesktopMedia3ColorTransfers {\n'
    aliases += ''.join(f'    const val {name} = {value}\n' for name, value in codes['constants'].items()) + '}\n'
    files.append(write(output, path, original, aliases, 'DesktopMedia3ColorTransfers.kt'))

    path = BASE + 'feature/anime4k/gl/Fsr1Shaders.kt'
    original = (_desktop_canonical_source(repo, path)).read_text(encoding='utf-8').replace('\r\n', '\n')
    notice = re.search(r' \* Copyright \(c\) 2021 Advanced Micro Devices.*? \* THE SOFTWARE\.', original, re.S)
    if notice is None:
        raise ValueError('Original AMD MIT notice changed')
    text = '\n'.join(line.removeprefix(' * ') for line in notice.group().splitlines())
    if '"""' in text or '$' in text:
        raise ValueError('Unsupported original AMD MIT notice')
    body = 'package com.bilipai.desktop.player\n\ninternal object DesktopFsrShaderLicense {\n'
    body += '    val TEXT = """\n/*\n' + text + '\n*/\n""".trimIndent()\n}\n'
    files.append(write(output, path, original, body, 'DesktopFsrShaderLicense.kt'))

    path = BASE + 'feature/plugin/Anime4KPlugin.kt'
    original = (_desktop_canonical_source(repo, path)).read_text(encoding='utf-8').replace('\r\n', '\n')
    selectable = substitute(original, 'override fun SettingsContent()', 'fun SettingsContent()')
    body = selector.function(selectable, 'SettingsContent', parser)
    body = substitute(body, 'fun SettingsContent()', 'fun DesktopVideoEnhancementSettingsContent(configuration: com.bilipai.desktop.plugins.DesktopVideoEnhancementConfiguration)')
    body = substitute(body, 'configState.collectAsStateWithLifecycle()', 'configuration.configState.collectAsState()')
    body = substitute(body, 'onValueChange = ::setFsrSharpness', 'onValueChange = { configuration.setFsrSharpness(it) }')
    body = substitute(body, 'setAlgorithm(algorithm)', 'configuration.setAlgorithm(algorithm)')
    body = substitute(body, 'setPreset(preset)', 'configuration.setPreset(preset)')
    body = substitute(body, 'setRememberAcrossVideos(', 'configuration.setRememberAcrossVideos(', 2)
    imports = re.findall(r'(?m)^import [^\n]+', original)
    imports = [line for line in imports if line.startswith(('import androidx.compose.foundation.layout.',
        'import androidx.compose.material3.MaterialTheme', 'import androidx.compose.runtime.',
        'import androidx.compose.ui.Modifier', 'import androidx.compose.ui.unit.dp',
        'import com.android.purebilibili.core.ui.', 'import com.android.purebilibili.feature.anime4k.'))]
    header = 'package com.bilipai.desktop.ui\n\n' + '\n'.join(imports)
    header += '\nimport androidx.compose.runtime.collectAsState\n\n@Composable\n'
    files.append(write(output, path, original, header + body, 'DesktopVideoEnhancementSettingsContent.kt'))
    path = BASE + 'feature/video/ui/components/Anime4KSettingsUi.kt'
    original = (_desktop_canonical_source(repo, path)).read_text(encoding='utf-8').replace('\r\n', '\n')
    body = substitute(original, '当前设备不支持 OpenGL ES 3.0', '当前原生渲染管线不可用')
    files.append(write(output, path, original, body))
    return files
