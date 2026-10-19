"""Whole v0.4.6 VideoCardAdaptiveTintPolicy and the WallpaperReadabilityPolicy it imports, emitted verbatim.

VideoCardAdaptiveTintPolicy.kt is a direct-mode v0.2.5 file: prepareUpstreamSources excludes the vendored copy and the
home-page producer emits the complete v0.4.6 file instead. It is a strict superset of the vendored declarations.
v0.4.6 adds resolveWallpaperGlassAlpha (the glass alpha behind resolveVideoCardAmbientDrawSpec), the
LocalHomeCardScrolling and LocalHomeCardTransitionRunning locals, WallpaperPalette.readability, and the
wallpaperYFraction and wallpaperGlassAlpha parameters of resolveVideoCardAdaptiveContentColors (both defaulted, so
every older caller compiles unchanged). WallpaperPalette.readability is typed by WallpaperReadabilityProfile, which
lives in the design-system WallpaperReadabilityPolicy.kt: a new file with no vendored copy, so no exclude.
No call sites are added.
"""
from pathlib import Path
import hashlib
import json
import os
import stat

COMMIT = 'd6bf8b1655ae910999860bf13e65c8fcea5b345b'
ARCHIVE = 'desktop/upstream-slices/v046-home-card-tint-policy'
MANIFEST_SHA256 = '4a3bfa65eee5a6ab3ddb80f9f5d0d9498a60f7f452803ffca1427ded70ec5bb4'
MAIN_FILES = (
    'VideoCardAdaptiveTintPolicy.kt',
    'WallpaperReadabilityPolicy.kt',
)
EXPECTED = set(MAIN_FILES)
PACKAGE = {
    'VideoCardAdaptiveTintPolicy.kt': 'com.android.purebilibili.feature.home.components.cards',
    'WallpaperReadabilityPolicy.kt': 'com.android.purebilibili.core.ui.wallpaper',
}
RELATIVE = {name: PACKAGE[name].replace('.', '/') + '/' + name for name in MAIN_FILES}
UPSTREAM_PATHS = {
    'VideoCardAdaptiveTintPolicy.kt':
        'app/src/main/java/com/android/purebilibili/feature/home/components/cards/VideoCardAdaptiveTintPolicy.kt',
    'WallpaperReadabilityPolicy.kt':
        'design-system/src/main/java/com/android/purebilibili/core/ui/wallpaper/WallpaperReadabilityPolicy.kt',
}
DECLARATIONS = {
    'VideoCardAdaptiveTintPolicy.kt': (
        'data class WallpaperPalette(',
        'val LocalWallpaperPalette',
        'val LocalHomeCardDynamicTintEnabled',
        'val LocalHomeCardFrostedGlassEnabled',
        'val LocalHomeScrollTickProvider',
        'val LocalHomeCardScrolling',
        'val LocalHomeCardTransitionRunning',
        'data class SamplingRegion(',
        'data class VideoCardAmbientDrawSpec(',
        'fun resolveCoverBottomSamplingRegion(',
        'fun shouldUseCoverTintForCard(',
        'fun interpolateWallpaperColor(',
        'fun resolveWallpaperGlassAlpha(',
        'fun resolveVideoCardAmbientDrawSpec(',
        'data class VideoCardAdaptiveContentColors(',
        'fun resolveVideoCardAdaptiveContentColors(',
    ),
    'WallpaperReadabilityPolicy.kt': (
        'data class WallpaperLuminanceBand(',
        'data class WallpaperReadabilityProfile(',
        'fun resolveWallpaperProtectionAlpha(',
        'fun resolveWallpaperBodyTintAlpha(',
    ),
}
# What v0.4.6 added to the vendored v0.2.5 VideoCardAdaptiveTintPolicy.kt: none of these is in the old copy.
ADDED_TO_TINT_POLICY = (
    'fun resolveWallpaperGlassAlpha(',
    'val LocalHomeCardScrolling',
    'val LocalHomeCardTransitionRunning',
    'val readability: WallpaperReadabilityProfile? = null',
    'wallpaperYFraction: Float = 0.5f',
    'wallpaperGlassAlpha: Float = 0f',
)


def sha(value):
    return hashlib.sha256(value.encode('utf8') if isinstance(value, str) else value).hexdigest()


def need(condition, message):
    if not condition:
        raise ValueError(message)


def fixed(repo):
    root = Path(repo) / ARCHIVE
    need(root.is_dir() and not root.is_symlink(), 'Missing original home card tint policy inputs')
    need({p.name for p in root.iterdir()} == EXPECTED | {'manifest.json', 'LICENSE'},
         'Home card tint policy input leaves changed')
    manifest_raw = (root / 'manifest.json').read_bytes()
    need(b'\r' not in manifest_raw and sha(manifest_raw) == MANIFEST_SHA256, 'Home card tint policy manifest changed')
    manifest = json.loads(manifest_raw)
    need((manifest['repository'], manifest['tag'], manifest['commit'], manifest['hashNormalization']) ==
         ('jay3-yy/BiliPai', 'v0.4.6', COMMIT, 'raw'), 'Home card tint policy source identity changed')
    need(len(manifest['files']) == len(EXPECTED) and {row['filename'] for row in manifest['files']} == EXPECTED,
         'Home card tint policy source set changed')
    sources, raws = {}, {}
    for row in manifest['files']:
        file = root / row['filename']
        need(file.is_file() and not file.is_symlink() and Path(row['path']).name == file.name,
             'Linked or ambiguous home card tint policy source')
        need(row['path'] == UPSTREAM_PATHS[file.name] and row['path'].endswith(RELATIVE[file.name]),
             'Home card tint policy upstream path changed: ' + file.name)
        leaf = file.read_bytes()
        need(b'\r' not in leaf and len(leaf) == row['bytes'] and sha(leaf) == row['sha256'],
             'Home card tint policy bytes changed: ' + file.name)
        need(hashlib.sha1(b'blob ' + str(len(leaf)).encode() + b'\0' + leaf).hexdigest() == row['gitBlob'],
             'Home card tint policy Git blob changed: ' + file.name)
        text = leaf.decode('utf8')
        package_line = 'package ' + PACKAGE[file.name] + '\n'
        need(text.encode('utf8') == leaf and text.endswith('\n') and package_line in text[:400],
             'Home card tint policy text changed: ' + file.name)
        for token in DECLARATIONS[file.name]:
            need(text.count(token) == 1, 'Home card tint policy declaration changed: ' + token)
        sources[file.name] = text
        raws[file.name] = leaf
    for token in ADDED_TO_TINT_POLICY:
        need(sources['VideoCardAdaptiveTintPolicy.kt'].count(token) == 1,
             'Home card tint policy v0.4.6 member changed: ' + token)
    license_file = root / 'LICENSE'
    need(not license_file.is_symlink() and sha(license_file.read_bytes()) == manifest['license']['sha256'],
         'Home card tint policy license changed')
    return sources, raws, manifest


def validate_outputs(repo, *outputs):
    fixed(repo)
    root = (Path(repo) / ARCHIVE).resolve()
    for output in outputs:
        if output is None:
            continue
        target = Path(output).resolve()
        need(not (target.is_relative_to(root) or root.is_relative_to(target)),
             'Output overlaps original home card tint policy inputs')


def _target(root, name):
    base = Path(root).resolve()
    relative = RELATIVE[name]
    target = base / relative
    # A leaf symlink must stay unresolved; resolve() would follow it and write through it.
    if not target.is_symlink():
        target = target.resolve()
        need(target.is_relative_to(base), 'Home card tint policy target escapes the output root: ' + relative)
    return target, relative


def _require_regular_or_absent(target, relative):
    try:
        info = os.lstat(target)
    except FileNotFoundError:
        return
    need(stat.S_ISREG(info.st_mode), 'Home card tint policy target is not a regular file: ' + relative)


def emit(repo, output):
    validate_outputs(repo, output)
    _sources, raws, _manifest = fixed(repo)
    targets = []
    for name in MAIN_FILES:
        target, relative = _target(output, name)
        _require_regular_or_absent(target, relative)
        targets.append((target, relative, raws[name]))
    receipt = []
    for target, relative, leaf in targets:
        target.parent.mkdir(parents=True, exist_ok=True)
        _require_regular_or_absent(target, relative)
        target.write_bytes(leaf)
        written = target.read_bytes()
        need(written == leaf, 'Home card tint policy readback differs: ' + relative)
        receipt.append({'path': relative, 'sha256': sha(written)})
    return {'files': receipt}
