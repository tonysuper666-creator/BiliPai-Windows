"""Select original settings category root UI; reuse already-owned semantic vectors."""
from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import argparse
import hashlib
import importlib.util
import json
import re

BASE = 'app/src/main/java/com/android/purebilibili/feature/settings/'
DIRECT = [BASE+'SettingsVisualSpec.kt']
SELECTED = [BASE+'ui/SettingsSections.kt', BASE+'screen/SettingsScreen.kt']
SECTIONS = ['SettingsAdaptiveDivider', 'SettingsCardGroup', 'SettingsRootCategoryListSection', 'SettingsRootCategoryRow']
ARROW = 'ms_keyboard_arrow_right_24'
ARROW_PATH = 'app/src/main/res/drawable/'+ARROW+'.xml'

def helper(repo):
    spec = importlib.util.spec_from_file_location('category_helpers', repo/'desktop/tools/extract-upstream-plugins.py')
    host = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(host)
    return host

def read(repo, path):
    return (_desktop_canonical_source(repo, path)).read_text(encoding='utf-8').replace('\r\n', '\n')

def selected_body(repo):
    host = helper(repo)
    media = host.media_extractor(repo)
    parser = media.parser_for(repo)
    original = read(repo, SELECTED[0])
    source = '\n\n'.join('@Composable\n'+media.function(original, name, parser) for name in SECTIONS)
    # Shared Privacy/Home sections reuse the original divider body. Visibility only.
    source = host.substitute(source, 'private fun SettingsAdaptiveDivider()',
        'internal fun SettingsAdaptiveDivider()')
    # Existing SettingsSearch extractor owns these exact original XML paths.
    source = host.substitute(source, 'painterResource(id = it)',
        'rememberVectorPainter(DesktopSettingsVectors.vector(it))', count=2)
    source = host.substitute(source,
        'com.android.purebilibili.feature.settings.rememberMaterialSymbol(com.android.purebilibili.R.drawable.ms_keyboard_arrow_right_24)',
        'DesktopSettingsCategoryVectors.vector(DesktopSettingsCategorySymbols.ms_keyboard_arrow_right_24)')
    return source

def generate(repo, output, standalone=False):
    host = helper(repo)
    media = host.media_extractor(repo)
    parser = media.parser_for(repo)
    output.mkdir(parents=True, exist_ok=True)
    emitted = []
    for path in DIRECT:
        original = read(repo, path)
        host.prune_old_direct(output, path, original)
        if standalone:
            emitted.append(host.write(output, path, original, original))
    path = SELECTED[0]
    imports = '''package com.android.purebilibili.feature.settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.AppShapes
import com.android.purebilibili.core.ui.ContainerLevel
import com.android.purebilibili.core.ui.isMiuixNonGlassEnabled
import com.android.purebilibili.core.ui.components.AppIcon
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.core.ui.components.AppPreferenceDivider as SettingsDivider
import com.android.purebilibili.core.ui.components.AppPreferenceGroup as SettingsGroup
import com.android.purebilibili.core.ui.components.AppPreferenceGroupPresentation
import com.android.purebilibili.core.ui.components.rememberAdaptiveListVisualCapabilities
import com.android.purebilibili.core.ui.components.rememberAdaptivePreferenceIconContainerColor
import com.android.purebilibili.core.ui.components.rememberAdaptivePreferenceIconContentColor
import com.bilipai.desktop.settings.DesktopSettingsVectors
import com.bilipai.desktop.settings.DesktopSettingsCategorySymbols
import com.bilipai.desktop.settings.DesktopSettingsCategoryVectors
'''
    emitted.append(host.write(output, path, read(repo,path), imports+'\n'+selected_body(repo), 'SettingsRootCategoryList.kt'))
    path = SELECTED[1]
    imports = '''package com.android.purebilibili.feature.settings
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.AppSpacingTokens
import com.android.purebilibili.core.ui.components.AppText
'''
    emitted.append(host.write(output, path, read(repo,path), imports+'\n@Composable\n'+
        media.function(read(repo,path), 'SettingsCategoryHeader', parser), 'SettingsCategoryHeader.kt'))
    # Reuse the exact strict original-XML converter, selecting only the one additional
    # arrow asset. The existing 182 settings-search vectors are neither copied nor emitted.
    spec = importlib.util.spec_from_file_location('category_vector_converter',repo/'desktop/tools/extract-upstream-settings-search.py')
    converter = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(converter)
    if ARROW in converter.symbol_names(repo):
        raise ValueError('Category arrow is now owned by shared search vectors; use that owner instead')
    converter.symbol_names = lambda _: [ARROW]
    vector = converter.vectors(repo).replace('DesktopSettingsSymbols','DesktopSettingsCategorySymbols').replace('DesktopSettingsVectors','DesktopSettingsCategoryVectors')
    emitted.append(media.write(output, 'com/bilipai/desktop/settings/DesktopSettingsCategoryVectors.kt',
        ARROW_PATH,read(repo,ARROW_PATH),vector))
    return emitted

def inventory(repo):
    return [dict(path=path, mode='direct' if path in DIRECT else 'policy-extract',
        features=['settings-category-ui-parity'], sha256=hashlib.sha256(read(repo,path).encode()).hexdigest())
        for path in DIRECT+SELECTED]

def resource_inventory(repo):
    return [dict(path=ARROW_PATH,features=['settings-category-symbols'],sha256=hashlib.sha256(read(repo,ARROW_PATH).encode()).hexdigest())]

if __name__ == '__main__':
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument('--repo',type=Path,required=True)
    cli.add_argument('--output',type=Path)
    cli.add_argument('--standalone',action='store_true')
    cli.add_argument('--inventory',action='store_true')
    cli.add_argument('--resource-inventory',action='store_true')
    args=cli.parse_args()
    if args.inventory:
        print(json.dumps(inventory(args.repo.resolve()),indent=2))
    if args.resource_inventory:
        print(json.dumps(resource_inventory(args.repo.resolve()),indent=2))
    if args.output:
        print('Generated',len(generate(args.repo.resolve(),args.output.resolve(),args.standalone)))
