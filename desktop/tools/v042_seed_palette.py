"""Original v042 seed, contrast and shared endpoint closure on the sole producer."""
from pathlib import Path
import hashlib
import json
import re

COMMIT = '5c34a82418b33bf88acea01784bb3c6b179d6491'
ARCHIVE = 'desktop/upstream-slices/v042-seed-palette'
MANIFEST_SHA256 = '99c0843df74232e08f93bec0210ffc373e7ed78cdc94455c32aeefc346737955'
THEME = 'com/android/purebilibili/core/theme/'
WHOLE = ['PaletteSpecPolicy.kt', 'SeedColorScheme.kt', 'SeedPaletteStyle.kt',
    'SeedPresetColors.kt', 'SeedTextContrastPolicy.kt', 'SeedToneResolver.kt',
    'SeedTones.kt', 'ThemePresetPalettePreview.kt', 'AppThemeEndpointPolicy.kt',
    'WallpaperPaletteOverridePolicy.kt', 'ThemeColorTransition.kt',
    'LiquidAccentColorScheme.kt', 'ThemePreferencePolicy.kt']
TESTS = ['PaletteSpecPolicyTest.kt', 'SeedColorSchemeTest.kt', 'SeedTextContrastPolicyTest.kt',
    'ThemePresetPalettePreviewTest.kt', 'AppThemeEndpointPolicyTest.kt',
    'WallpaperPaletteOverridePolicyTest.kt', 'LiquidAccentColorSchemeTest.kt', 'SeedPaletteSettingsPolicyTest.kt']
SELECTED = ['Theme.kt', 'ThemeRoleOverridePolicy.kt', 'WallpaperPaletteOverrides.kt',
    'PlaybackNotificationColorPolicy.kt', 'AppearanceThemeSegmentPolicy.kt', 'SettingsManager.kt', 'AppSegmentedControl.kt']
LICENSES = ['flex_seed_scheme.LICENSE', 'material_color_utilities.LICENSE']


def sha(raw):
    return hashlib.sha256(raw.encode() if isinstance(raw, str) else raw).hexdigest()


def fixed_sources(repo):
    root = Path(repo) / ARCHIVE
    raw = (root / 'manifest.json').read_bytes()
    if sha(raw) != MANIFEST_SHA256:
        raise ValueError('Unreviewed original seed palette manifest')
    manifest = json.loads(raw)
    expected = set(WHOLE + TESTS + SELECTED + LICENSES)
    if manifest['fixedUpstreamCommit'] != COMMIT or {r['filename'] for r in manifest['files']} != expected:
        raise ValueError('Unexpected original seed palette source set')
    result = {}
    for row in manifest['files']:
        if row['filename'] != Path(row['path']).name or row['filename'] in result:
            raise ValueError('Ambiguous original seed palette filename')
        raw = (root / row['filename']).read_bytes()
        oid = hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest()
        if len(raw) != row['bytes'] or sha(raw) != row['sha256Bytes'] or oid != row['gitBlob']:
            raise ValueError('Changed original seed palette input: ' + row['filename'])
        result[row['filename']] = raw
    return result


def emit(repo, output, resources, tests, parser, select, extracted, headers, base_outputs):
    """All policies keep their original bodies; Windows supplies existing stores."""
    from v043_theme_chrome import seed_sources, seed_source_identity, NEW_SEED_TESTS, validate_outputs
    validate_outputs(repo, output, resources, tests)
    sources = seed_sources(repo, fixed_sources(repo))
    for key in ['seed_palette_style', 'theme_color_contrast']:
        if ('stringPreferencesKey("' + key + '")').encode() not in sources['SettingsManager.kt']:
            raise ValueError('Original seed preference key changed: ' + key)
    output = Path(output)
    writes = []
    test_proofs = []

    def save(relative, body, source, mode):
        target = output / relative
        previous = target.read_text(encoding='utf-8') if relative in base_outputs else None
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(body, encoding='utf-8', newline='\n')
        writes.append(dict(path=relative, source=source, mode=mode,
            before=previous, beforeSHA256=sha(previous) if previous is not None else None,
            afterSHA256=sha(body), originalSHA256=sha(sources[source]),
            **seed_source_identity(repo, source, COMMIT, MANIFEST_SHA256)))

    for filename in WHOLE:
        raw = sources[filename].decode('utf-8')
        package = re.search(r'(?m)^package (\S+)', raw).group(1)
        save(package.replace('.', '/') + '/' + filename, raw, filename, 'whole-original')

    original_path = 'app/src/main/java/com/android/purebilibili/core/theme/Theme.kt'
    selected_names = extracted[original_path] + ['resolveColorContrastPreference']
    body = select(parser, sources['Theme.kt'].decode('utf-8'), selected_names)
    head = headers['Theme.kt'] + 'import com.materialkolor.Contrast\n'
    save(THEME + 'Theme.kt', 'package com.android.purebilibili.core.theme\n\n' + head + '\n' + body,
        'Theme.kt', 'complete-selected-original-declarations')

    body = select(parser, sources['AppSegmentedControl.kt'].decode('utf-8'), ['AppSegmentOption'])
    save('com/android/purebilibili/core/ui/components/AppSegmentedControl.kt',
        'package com.android.purebilibili.core.ui.components\n\n' + body,
        'AppSegmentedControl.kt', 'complete-original-option-with-default-description')

    relative = THEME + 'ThemeRoleOverridePolicy.kt'
    previous = (output / relative).read_text(encoding='utf-8')
    helper = select(parser, sources['ThemeRoleOverridePolicy.kt'].decode('utf-8'), ['resolveLiquidControlAccentColor'])
    if 'fun resolveLiquidControlAccentColor(' in previous:
        old = select(parser, previous, ['resolveLiquidControlAccentColor']).rstrip()
        if previous.count(old) != 1:
            raise ValueError('Original liquid accent helper boundary changed')
        selected = previous.replace(old, helper.rstrip(), 1)
    else:
        selected = previous + '\n' + helper
    save(relative, selected, 'ThemeRoleOverridePolicy.kt', 'original-helper-on-existing-private-parser-owner')

    body = select(parser, sources['WallpaperPaletteOverrides.kt'].decode('utf-8'),
        ['WallpaperPaletteRole', 'WallpaperPaletteOverrides'])
    save('com/android/purebilibili/core/store/DesktopOriginalWallpaperPaletteModels.kt',
        'package com.android.purebilibili.core.store\n\n' + body,
        'WallpaperPaletteOverrides.kt', 'original-store-data-types-without-Android-DataStore')

    names = ['resolveSeedPaletteStyleOptions', 'resolveSeedPaletteStyleDescription',
        'shouldShowColorSpecControl', 'resolveColorContrastOptions',
        'resolveColorContrastDescription', 'resolveColorContrastSubtitle',
        'resolveColorSpecDescription', 'resolveColorSpecSubtitle',
        'resolveSelectedColorSpec', 'resolveSelectedColorSpecSubtitle']
    body = select(parser, sources['AppearanceThemeSegmentPolicy.kt'].decode('utf-8'), names)
    head = 'package com.android.purebilibili.feature.settings\n\nimport com.android.purebilibili.core.theme.*\nimport com.android.purebilibili.core.ui.components.AppSegmentOption\nimport com.materialkolor.Contrast\n\n'
    head += 'import com.materialkolor.PaletteStyle\nimport com.materialkolor.dynamiccolor.ColorSpec\n\n'
    save('com/android/purebilibili/feature/settings/DesktopOriginalSeedPaletteSettingsPolicy.kt',
        head + body, 'AppearanceThemeSegmentPolicy.kt', 'complete-selected-original-settings-policies')

    relative = 'com/android/purebilibili/feature/settings/AppearanceThemeSegmentPolicy.kt'
    current = (output / relative).read_text(encoding='utf-8')
    old = select(parser, current, ['resolveColorSpecOptions']).rstrip()
    new = select(parser, sources['AppearanceThemeSegmentPolicy.kt'].decode('utf-8'), ['resolveColorSpecOptions']).rstrip()
    if current.count(old) != 1:
        raise ValueError('Existing color specification options boundary changed')
    current = current.replace(old, new, 1)
    current = current.replace('import com.android.purebilibili.core.theme.AppUiStyle\n',
        'import com.android.purebilibili.core.theme.AppUiStyle\nimport com.android.purebilibili.core.theme.SeedPaletteStyle\nimport com.android.purebilibili.core.theme.material2025Style\nimport com.android.purebilibili.core.theme.supportsMaterialSpec2025\n', 1)
    save(relative, current, 'AppearanceThemeSegmentPolicy.kt', 'original-effective-spec-options-on-existing-owner')

    body = select(parser, sources['PlaybackNotificationColorPolicy.kt'].decode('utf-8'), ['resolvePlaybackNotificationAccentColor'])
    head = '''package com.android.purebilibili.feature.video.player

import androidx.compose.ui.graphics.Color
import com.bilipai.desktop.appearance.DesktopThemeSettings as AppThemeSettings
import com.android.purebilibili.core.theme.createSeedColorScheme
import com.android.purebilibili.core.theme.createBiliPaiStyleColorScheme
import com.android.purebilibili.core.theme.resolveLiquidControlAccentColor
import com.android.purebilibili.core.theme.resolveMd3ThemeSeedColor
import com.android.purebilibili.feature.settings.Md3ColorSource

'''
    save('com/android/purebilibili/feature/video/player/DesktopOriginalPlaybackNotificationAccent.kt',
        head + body, 'PlaybackNotificationColorPolicy.kt', 'original-accent-policy-on-existing-Windows-settings')

    if tests is not None:
        for filename in TESTS + list(NEW_SEED_TESTS):
            raw = sources[filename].decode('utf-8')
            selected = raw
            edits = []
            if filename == 'AppThemeEndpointPolicyTest.kt':
                before = 'import com.android.purebilibili.core.store.AppThemeSettings'
                after = 'import com.bilipai.desktop.appearance.DesktopThemeSettings as AppThemeSettings'
                if selected.count(before) != 1:
                    raise ValueError('Original endpoint settings test boundary changed')
                edits.append(dict(before=before, after=after, offset=selected.index(before)))
                selected = selected.replace(before, after, 1)
            if filename == 'SeedColorSchemeTest.kt':
                before = 'import kotlin.test.assertEquals'
                after = 'import com.bilipai.desktop.appearance.assertDesktopPaletteEquals as assertEquals'
                if selected.count(before) != 1:
                    raise ValueError('Original seed palette assertion import changed')
                edits.append(dict(before=before, after=after, offset=selected.index(before)))
                selected = selected.replace(before, after, 1)
            inverse = selected
            for edit in reversed(edits):
                at = edit['offset']
                if inverse[at:at + len(edit['after'])] != edit['after']:
                    raise ValueError('Original theme test inverse changed')
                inverse = inverse[:at] + edit['before'] + inverse[at + len(edit['after']):]
            if inverse != raw:
                raise ValueError('Whole original theme test not recovered')
            package = re.search(r'(?m)^package (\S+)', selected).group(1)
            target = Path(tests) / package.replace('.', '/') / filename
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(selected, encoding='utf-8', newline='\n')
            test_proofs.append(dict(filename=filename, originalSHA256=sha(raw), outputSHA256=sha(selected),
                edits=edits, wholeOriginalInverseRecovered=True, cases=raw.count('@Test'),
                **seed_source_identity(repo, filename, COMMIT, MANIFEST_SHA256)))

    for filename in LICENSES:
        target = Path(resources) / 'META-INF/licenses' / filename
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(sources[filename])
    proof = dict(upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256,
        writes=writes, wholeOriginalTestProofs=test_proofs,
        originalTestCaseCount=sum(t['cases'] for t in test_proofs),
        completeOriginalSeedPaletteClosureEmitted=True, WindowsDataStoreReused=True,
        AndroidContextOrDataStoreStubAdded=False, systemWallpaperExtractionImplemented=False,
        sourceQualified=False, mainCompiled=False, testsExecuted=False, applicationExecuted=False)
    (output / 'v042-seed-palette-proof.json').write_text(json.dumps(proof, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    return proof
