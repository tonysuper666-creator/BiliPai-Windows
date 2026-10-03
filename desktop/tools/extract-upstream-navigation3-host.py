from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import argparse, hashlib, importlib.util, json, os, re, textwrap

TARGET = '3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
ROOT = 'app/src/main/java/com/android/purebilibili/'
NAV = ROOT + 'navigation3/'
PREFIX = chr(92) * 2 + '?' + chr(92)

def safe(p):
    s = os.path.abspath(p)
    return Path(s if s.startswith(PREFIX) else PREFIX + s)

def digest(s): return hashlib.sha256(s.encode('utf-8')).hexdigest()

def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

def declarations(parser, source, names):
    tokens = parser.kotlin_tokens(source)
    depth = parens = brackets = 0
    starts = []
    for i, (token, start, _) in enumerate(tokens):
        if depth == 0 and parens == 0 and brackets == 0 and token in ('fun','val','var','class','object','interface'):
            name = tokens[i+1][0]
            if token == 'fun':
                j = i+1
                while tokens[j][0] != '(': j += 1
                name = tokens[j-1][0]
            line = source.rfind('\n', 0, start)+1
            while line > 0:
                previous = source.rfind('\n', 0, line-1)+1
                if source[previous:line].strip().startswith('@'): line = previous
                else: break
            starts.append((name,line))
        depth += (token == '{') - (token == '}')
        parens += (token == '(') - (token == ')')
        brackets += (token == '[') - (token == ']')
    selected = []
    for name in names:
        found = [i for i,(n,_) in enumerate(starts) if n == name]
        assert found, name
        for i in found:
            start = starts[i][1]
            end = starts[i+1][1] if i+1 < len(starts) else len(source)
            selected.append(source[start:end].rstrip())
    return '\n\n'.join(selected)+'\n'

DIRECT = [
    'BiliPaiCardMorphDestinationPolicy.kt', 'BiliPaiNavBackStackController.kt',
    'BiliPaiNavBackStackPolicy.kt', 'BiliPaiNavContentTransformPolicy.kt',
    'BiliPaiNavEntryContentPolicy.kt', 'BiliPaiNavEntryProvider.kt', 'BiliPaiNavMotionPolicy.kt',
    'predictiveback/AospNavTransition.kt', 'predictiveback/BiliPaiPredictiveBackAnimationStyle.kt',
    'predictiveback/BiliPaiPredictiveBackExitDirection.kt',
    'predictiveback/BiliPaiPredictiveBackExitDirectionPolicy.kt',
    'predictiveback/ClassicNavTransition.kt', 'predictiveback/MiuixPredictiveBackProgressTransition.kt',
    'predictiveback/MiuixVideoCardNavTransition.kt', 'predictiveback/NavTransitionEasing.kt',
    'predictiveback/NavTransitionGeometry.kt', 'predictiveback/NoPredictiveBackTransition.kt',
    'predictiveback/ScaleNavTransition.kt',
]

def function(raw, name):
    match = re.search(r'^    (?:suspend )?fun ' + re.escape(name) + r'\(', raw, re.M)
    if match is None: raise ValueError(name)
    start = match.start()
    next_decl = re.search(r'^    (?:(?:suspend )?fun |(?:private |internal )?(?:val |const val |fun ))', raw[match.end():], re.M)
    end = match.end() + next_decl.start() if next_decl else len(raw)
    chunk = raw[start:end]
    # All selected functions below have explicit source end tokens to exclude adjacent comments.
    if name in ('getClickToPlay', 'getFullScreenSwipeBackEnabled', 'getVideoTransitionRealtimeBlurEnabled', 'getRelatedVideoTransitionEnabled'):
        chunk = chunk[:chunk.index('\n\n')]
    elif name == 'getClickToPlaySync':
        chunk = chunk[:chunk.index('\n    }') + len('\n    }')]
    elif name == 'setClickToPlay':
        chunk = chunk[:chunk.index('\n    }') + len('\n    }')]
    elif name == 'setFullScreenSwipeBackEnabled':
        chunk = chunk[:chunk.index('\n    }') + len('\n    }')]
    elif name in ('setVideoTransitionRealtimeBlurEnabled', 'setRelatedVideoTransitionEnabled'):
        chunk = chunk[:chunk.index('\n    }') + len('\n    }')]
    return textwrap.dedent(chunk).rstrip()

def generate(repo, output, standalone=False):
    receipt = {'target': TARGET, 'sources': [], 'outputs': [], 'replacements': {}}
    def read(path):
        raw = safe(_desktop_canonical_source(repo, path)).read_text(encoding='utf-8').replace('\r\n', '\n')
        return raw
    def record(path, raw, mode):
        receipt['sources'].append({'path': path, 'sha256LF': digest(raw), 'mode': mode,
                                  'features': ['stable-navigation3-host']})
    def emit(rel, body):
        p = output / rel
        safe(p.parent).mkdir(parents=True, exist_ok=True)
        safe(p).write_text(body, encoding='utf-8', newline='\n')
        receipt['outputs'].append({'path': rel, 'sha256LF': digest(body)})
    parser = load('nav3_source_tokens', safe(repo / 'desktop/tools/sync-upstream.py'))
    def select(path, names, filename, imports, replacements=()):
        raw = read(path)
        record(path, raw, 'existing-feature-merge')
        original = declarations(parser, raw, names)
        for name in names:
            assert re.search(r'\b' + re.escape(name) + r'\b', original), (path, name)
        body = original
        for before, after in replacements:
            assert before in body, (path, before)
            body = body.replace(before, after)
        pkg = re.search(r'^package (.+)$', raw, re.M).group(1)
        receipt['replacements'][path] = [{'before': a, 'after': b} for a,b in replacements]
        receipt.setdefault('selectedDeclarations', []).append({
            'source': path, 'names': names, 'originalBody': original,
            'originalSha256LF': digest(original), 'generatedSha256LF': digest(body)})
        emit(pkg.replace('.', '/') + '/' + filename,
             '// Source: ' + path + '\n// Original LF SHA256: ' + digest(raw) + '\n'
             + 'package ' + pkg + '\n' + imports + '\n' + body + '\n')
    for rel in DIRECT:
        path = NAV + rel
        raw = read(path)
        record(path, raw, 'direct')
        if standalone:
            package = re.search(r'^package (.+)$', raw, re.M).group(1)
            emit(package.replace('.', '/') + '/' + Path(rel).name, raw)
    for path in [
        'design-system/src/main/java/com/android/purebilibili/core/ui/motion/NavigationSlideSpring.kt',
        'design-system/src/main/java/com/android/purebilibili/core/ui/motion/BottomBarLikeContentTransformPolicy.kt',
        'design-system/src/main/java/com/android/purebilibili/core/ui/motion/SettingsIosPushContentTransformPolicy.kt',
        ROOT + 'core/ui/transition/VideoCardTransitionNavBackdrop.kt',
    ]:
        raw = read(path)
        record(path, raw, 'direct')
        if standalone:
            package = re.search(r'^package (.+)$', raw, re.M).group(1)
            emit(package.replace('.', '/') + '/' + Path(path).name, raw)
    select(ROOT + 'navigation/AppTopLevelNavigationPolicy.kt',
           ['AppSystemBackAction', 'resolveAppSystemBackAction', 'shouldInterceptSystemBackForAppAction'],
           'DesktopOriginalNavigationSystemBack.kt',
           'import com.android.purebilibili.feature.home.components.BottomNavItem\n')
    select(ROOT + 'feature/settings/SettingsNavHierarchyPolicy.kt',
           ['resolveSettingsNavPopTransition', 'isSettingsNavPopTransition'],
           'DesktopOriginalNavigationSettingsPop.kt',
           'import com.android.purebilibili.navigation3.BiliPaiNavKey\n'
           'import com.android.purebilibili.navigation3.BiliPaiNavRouteTransition\n')
    select(ROOT + 'core/ui/transition/VideoCardTransitionHostDepthLayer.kt',
           ['VideoCardTransitionHostDepthLayer', 'shouldPaintHostOwnedDepthLayer',
            'resolveHostOwnedDepthProgress', 'shouldMarkDisplayListStaleOnHostOwnedSourceDispose',
            'shouldReleaseHostOwnedDepthLayer'],
           'DesktopOriginalNavigationHostDepth.kt',
           'import androidx.compose.foundation.layout.*\nimport androidx.compose.runtime.*\n'
           'import androidx.compose.ui.Modifier\nimport androidx.compose.ui.draw.drawWithContent\n'
           'import androidx.compose.ui.geometry.Rect\nimport androidx.compose.ui.graphics.Color\n'
           'import androidx.compose.ui.graphics.layer.drawLayer\nimport androidx.compose.ui.platform.LocalDensity\n'
           'import com.android.purebilibili.core.ui.adaptive.MotionTier\n'
           'import com.bilipai.desktop.ui.LocalDesktopNavigationHostEnvironment\n'
           'import com.bilipai.desktop.ui.desktopDetailRenderEffectsSupported\n',
           [('    val view = LocalView.current',
             '    val platform = LocalDesktopNavigationHostEnvironment.current\n    val platformDensity = LocalDensity.current'),
            ('deviceCornerRadiusPx = resolveDeviceDisplayCornerRadiusPx(view.rootWindowInsets)',
             'deviceCornerRadiusPx = platform.cornerRadiusQuery()?.let { with(platformDensity) { it.toPx() } } ?: 0f'),
            ('sdkInt: Int = Build.VERSION.SDK_INT', 'renderEffectSupported: Boolean = desktopDetailRenderEffectsSupported()'),
            ('if (sdkInt < Build.VERSION_CODES.S) return false', 'if (!renderEffectSupported) return false')])
    select(ROOT + 'core/ui/transition/PredictiveBackBackgroundPolicy.kt',
           ['PREDICTIVE_BACK_BACKGROUND_COMMIT_DURATION_MS', 'PREDICTIVE_BACK_BACKGROUND_CANCEL_DURATION_MS',
            'PredictiveBackBackgroundState', 'LocalPredictiveBackBackgroundState',
            'resolvePredictiveBackGestureBlurProgress', 'shouldApplyPredictiveBackGestureBlur',
            'shouldApplyPredictiveBackBlurToRoute', 'resolvePredictiveBackCommitBlurDurationMs',
            'PredictiveBackBlurFrameCache', 'predictiveBackBackgroundEffect'],
           'DesktopOriginalNavigationPredictiveBackground.kt',
           'import androidx.compose.runtime.compositionLocalOf\nimport androidx.compose.ui.Modifier\n'
           'import androidx.compose.ui.draw.drawWithContent\nimport androidx.compose.ui.graphics.BlurEffect\n'
           'import androidx.compose.ui.graphics.Color\nimport androidx.compose.ui.graphics.TileMode\n'
           'import androidx.compose.ui.graphics.graphicsLayer\n'
           'import com.android.purebilibili.core.ui.adaptive.MotionTier\n'
           'import com.android.purebilibili.navigation3.BiliPaiNavKey\n'
           'import com.android.purebilibili.navigation3.BiliPaiNavRouteTransition\n'
           'import com.bilipai.desktop.ui.desktopDetailRenderEffectsSupported\nimport kotlin.math.roundToInt\n',
           [('Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && frame.blurRadiusPx > 0.01f',
             'desktopDetailRenderEffectsSupported() && frame.blurRadiusPx > 0.01f'),
            ('RenderEffect\n                .createBlurEffect(\n                    frame.blurRadiusPx,\n                    frame.blurRadiusPx,\n                    Shader.TileMode.CLAMP,\n                )\n                .asComposeRenderEffect()',
             'BlurEffect(\n                    frame.blurRadiusPx,\n                    frame.blurRadiusPx,\n                    TileMode.Clamp,\n                )')])
    path = NAV + 'BiliPaiNavDisplayHost.kt'
    raw = read(path)
    record(path, raw, 'platform-extract')
    replacements = [
        ('import android.app.Application', 'import com.bilipai.desktop.ui.DesktopNavigationEntryViewModelPlatform as Application'),
        ('import androidx.compose.ui.platform.LocalContext', 'import com.bilipai.desktop.ui.LocalDesktopNavigationHostEnvironment'),
        ('    val application = LocalContext.current.applicationContext as Application',
         '    val application = LocalDesktopNavigationHostEnvironment.current.entryViewModels'),
        ('rememberDeviceCornerRadius(defaultRadius = 0.dp)',
         'LocalDesktopNavigationHostEnvironment.current.cornerRadiusQuery() ?: 0.dp'),
        ('com.android.purebilibili.core.store.SettingsManager\n            .getFullScreenSwipeBackEnabled(LocalContext.current)',
         'com.bilipai.desktop.ui.DesktopOriginalNavigationHostSettings\n            .getFullScreenSwipeBackEnabled(LocalDesktopNavigationHostEnvironment.current.context)'),
        ('com.android.purebilibili.core.store.SettingsManager\n        .getClickToPlay(LocalContext.current)',
         'com.bilipai.desktop.ui.DesktopOriginalNavigationHostSettings\n        .getClickToPlay(LocalDesktopNavigationHostEnvironment.current.context)'),
        ('com.android.purebilibili.core.store.SettingsManager.getClickToPlaySync(LocalContext.current)',
         'com.bilipai.desktop.ui.DesktopOriginalNavigationHostSettings.getClickToPlaySync(LocalDesktopNavigationHostEnvironment.current.context)'),
        ('com.android.purebilibili.core.store.SettingsManager\n        .getDynamicImagePreviewTextVisible(LocalContext.current)',
         'com.android.purebilibili.core.store.DesktopDynamicCardSettings\n        .getDynamicImagePreviewTextVisible(LocalDesktopNavigationHostEnvironment.current.context)'),
        ('    val patchedCreationExtras = MutableCreationExtras(defaultCreationExtras).apply {\n'
         '        set(ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY, application)\n    }',
         '    val patchedCreationExtras = application.patchCreationExtras(navEntryOwner, defaultCreationExtras)'),
        ('ViewModelProvider.AndroidViewModelFactory.getInstance(application)', 'application.defaultFactory(navEntryOwner)'),
    ]
    body = raw
    for before, after in replacements:
        assert body.count(before) == 1, before
        body = body.replace(before, after)
    receipt['replacements'][path] = [{'before': a, 'after': b} for a,b in replacements]
    emit('com/android/purebilibili/navigation3/BiliPaiNavDisplayHost.kt', body)

    path = NAV + 'predictiveback/BiliPaiMiuixNavTransition.kt'
    raw = read(path)
    record(path, raw, 'platform-extract')
    replacements = [
        ('import android.graphics.RenderEffect as AndroidRenderEffect\nimport android.graphics.Shader\nimport android.os.Build\n', ''),
        ('import androidx.compose.ui.graphics.asComposeRenderEffect',
         'import androidx.compose.ui.graphics.BlurEffect\nimport androidx.compose.ui.graphics.TileMode'),
        ('Build.VERSION.SDK_INT < Build.VERSION_CODES.S || radiusPx <= 0.5f', 'radiusPx <= 0.5f'),
        ('AndroidRenderEffect.createBlurEffect(\n                quantizedRadius,\n                quantizedRadius,\n                Shader.TileMode.CLAMP,\n            ).asComposeRenderEffect()',
         'BlurEffect(\n                quantizedRadius,\n                quantizedRadius,\n                TileMode.Clamp,\n            )'),
    ]
    body = raw
    for before, after in replacements:
        assert body.count(before) == 1, before
        body = body.replace(before, after)
    receipt['replacements'][path] = [{'before': a, 'after': b} for a,b in replacements]
    emit('com/android/purebilibili/navigation3/predictiveback/BiliPaiMiuixNavTransition.kt', body)

    settings_path = ROOT + 'core/store/SettingsManager.kt'
    nav_settings_path = ROOT + 'core/store/navigation/NavigationSettingsStore.kt'
    settings = read(settings_path)
    nav_settings = read(nav_settings_path)
    record(settings_path, settings, 'existing-feature-merge')
    record(nav_settings_path, nav_settings, 'policy-extract')
    selections = [(settings_path, 'getClickToPlay', settings),
                  (settings_path, 'setClickToPlay', settings),
                  (settings_path, 'getClickToPlaySync', settings),
                  (settings_path, 'getVideoTransitionRealtimeBlurEnabled', settings),
                  (settings_path, 'setVideoTransitionRealtimeBlurEnabled', settings),
                  (settings_path, 'getRelatedVideoTransitionEnabled', settings),
                  (settings_path, 'setRelatedVideoTransitionEnabled', settings),
                  (nav_settings_path, 'getFullScreenSwipeBackEnabled', nav_settings),
                  (nav_settings_path, 'setFullScreenSwipeBackEnabled', nav_settings)]
    selected = []
    receipt['selections'] = []
    for origin, name, source in selections:
        chunk = function(source, name)
        selected.append(chunk)
        receipt['selections'].append({'source': origin, 'name': name, 'sha256LF': digest(chunk), 'body': chunk})
    header = '// Original stable selected settings; single existing Root global settings authority.\n' \
             '// Target: ' + TARGET + '\npackage com.bilipai.desktop.ui\n' \
             'import com.bilipai.desktop.plugins.DesktopPluginContext as Context\n' \
             'import com.bilipai.desktop.plugins.booleanPreferencesKey\n' \
             'import com.bilipai.desktop.settings.dynamicSettingsDataStore as settingsDataStore\n' \
             'import kotlinx.coroutines.flow.Flow\nimport kotlinx.coroutines.flow.map\n\n' \
             'internal object DesktopOriginalNavigationHostSettings {\n' \
             '    private val KEY_CLICK_TO_PLAY = booleanPreferencesKey("click_to_play")\n' \
             '    private val KEY_VIDEO_TRANSITION_REALTIME_BLUR_ENABLED = booleanPreferencesKey("video_transition_realtime_blur_enabled")\n' \
             '    private val KEY_RELATED_VIDEO_TRANSITION_ENABLED = booleanPreferencesKey("related_video_transition_enabled")\n' \
             '    private val keyFullScreenSwipeBackEnabled = booleanPreferencesKey("full_screen_swipe_back_enabled")\n\n'
    emit('com/bilipai/desktop/ui/DesktopOriginalNavigationHostSettings.kt',
         header + '\n\n'.join(textwrap.indent(s, '    ') for s in selected) + '\n}\n')
    # Android physical display radius body is deliberately not emitted or faked.
    device_path = NAV + 'BiliPaiDeviceCornerRadius.kt'
    appearance_path = ROOT + 'navigation/AppNavigationAppearancePolicy.kt'
    receipt['references'] = [{'path': device_path, 'sha256LF': digest(read(device_path)),
        'reason': 'Android WindowInsets/RoundedCorner capability unavailable; required nullable Windows client query, original host fallback retained.'},
        {'path': appearance_path, 'sha256LF': digest(read(appearance_path)),
         'reason': 'Whole original policy already compiled by sole full Home producer; actual40 classes reused without override.'}]
    return receipt

if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--repo', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--receipt', type=Path)
    p.add_argument('--standalone', action='store_true')
    args = p.parse_args()
    receipt = generate(args.repo, args.output, args.standalone)
    if args.receipt: safe(args.receipt).write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8')
