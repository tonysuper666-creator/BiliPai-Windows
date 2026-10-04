from pathlib import Path
import importlib.util
import tempfile
import unittest
import xml.etree.ElementTree as ET
from unittest.mock import patch

HERE=Path(__file__).resolve().parent
REPO=HERE.parents[2]
SCRIPT=HERE/'extract-upstream-settings-privacy.py'
if not SCRIPT.exists():SCRIPT=REPO/'desktop/tools/extract-upstream-settings-privacy.py'
spec=importlib.util.spec_from_file_location('privacy',SCRIPT)
tool=importlib.util.module_from_spec(spec);spec.loader.exec_module(tool)
host=tool.load(REPO/'desktop/tools/extract-upstream-plugins.py','privacy_test_host')
media=host.media_extractor(REPO);parser=media.parser_for(REPO)

class OriginalPrivacyExtractionTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='privacy-extract-test-')
        self.output=Path(self.temp.name)
        self.files={p.name:p.read_text(encoding='utf-8') for p in tool.generate(REPO,self.output)}
    def tearDown(self):self.temp.cleanup()

    def test_hint_entire_original_body_and_boolean_schema_are_preserved(self):
        source=self.files['SearchHintSettingsStore.kt'].split('\n',2)[2]
        source=source.replace('import com.bilipai.desktop.plugins.DesktopPluginContext as Context','import android.content.Context')
        source=source.replace('import com.bilipai.desktop.plugins.booleanPreferencesKey','import androidx.datastore.preferences.core.booleanPreferencesKey')
        source=source.replace('import com.bilipai.desktop.settings.privacySettingsDataStore','import androidx.datastore.preferences.core.edit')
        source=source.replace('context.privacySettingsDataStore','context.settingsDataStore')
        self.assertEqual(source.strip(),tool.read(REPO,tool.HINT).strip())

    def test_original_section_body_only_has_reviewed_platform_bindings(self):
        original=media.function(tool.read(REPO,tool.SECTION),'PrivacySection',parser)
        source=media.function(self.files['SettingsPrivacySection.kt'],'PrivacySection',parser)
        source=source.replace('val bindings = LocalDesktopPrivacySectionBindings.current\n    val context = bindings.context','val context = LocalContext.current')
        source=source.replace('collectAsState(initial = true)','collectAsStateWithLifecycle(initialValue = true)')
        source=source.replace('bindings.setDefaultHintEnabled(enabled)','com.android.purebilibili.core.store.SearchHintSettingsStore.setEnabled(context, enabled)')
        source=source.replace('rememberVectorPainter(DesktopSettingsVectors.vector(it))','painterResource(id = it)')
        source=source.replace('DesktopPrivacySettingsVectors.vector(DesktopPrivacySettingsSymbols.ms_shield_24)',
            'com.android.purebilibili.feature.settings.rememberMaterialSymbol(com.android.purebilibili.R.drawable.ms_shield_24)')
        source=source.replace('subtitle = "Windows 身份验证尚未接入；当前不会验证或保护隐私内容",\n            enabled = false,',
            'subtitle = "进入收藏、历史等页面前使用指纹、人脸或锁屏密码确认身份",')
        self.assertEqual(source,original)
        self.assertNotIn('fun SettingsCardGroup',self.files['SettingsPrivacySection.kt'])
        self.assertNotIn('fun SettingsAdaptiveDivider',self.files['SettingsPrivacySection.kt'])

    def test_authentication_reader_and_submit_functions_use_original_algorithms(self):
        original=tool.read(REPO,tool.MANAGER)
        getter=media.function(self.files['DesktopPrivacyAuthenticationSettings.kt'],'getPrivacyContentAuthenticationEnabled',parser)
        self.assertEqual(getter.replace('context.privacySettingsDataStore','context.settingsDataStore'),
            media.function(original,'getPrivacyContentAuthenticationEnabled',parser))
        self.assertIn('booleanPreferencesKey("privacy_content_authentication_enabled")',self.files['DesktopPrivacyAuthenticationSettings.kt'])
        self.assertNotIn('privacy_mode_enabled',self.files['DesktopPrivacyAuthenticationSettings.kt'])
        # These complete original declarations have one actual SearchScreen owner;
        # the privacy producer must not recreate them in its retired policy leaf.
        search=tool.load(REPO/'desktop/tools/extract-upstream-search-pages.py','privacy_full_search_test')
        output=self.output/'complete-search'
        search.generate(REPO,output)
        screens=list(output.rglob('SearchScreen.kt'))
        self.assertEqual(1,len(screens))
        complete=screens[0].read_text(encoding='utf-8')
        for name in ['resolveSearchSubmitKeyword','resolveSearchDefaultPlaceholder']:
            self.assertEqual(media.function(complete,name,parser),
                media.function(tool.read(REPO,tool.SEARCH),name,parser))
            self.assertNotIn('fun '+name+'(',self.files['PrivacySearchHintPolicy.kt'])

    def test_shield_is_one_exact_original_xml_asset_with_same_paths(self):
        root=ET.fromstring(tool.read(REPO,tool.ASSET))
        vector=self.files['DesktopPrivacySettingsVectors.kt']
        self.assertEqual(len(root.findall('path')),vector.count('addPath(pathData='))
        for path in root.findall('path'):
            self.assertIn(path.attrib['{http://schemas.android.com/apk/res/android}pathData'],vector)
        self.assertEqual(len(tool.resources(REPO)),1)
        self.assertNotIn('DesktopSettingsCategoryVectors',vector)
        self.assertNotIn('DesktopSettingsSymbols {',vector)

    def test_changed_authentication_copy_fails_closed_before_silent_adaptation(self):
        real=tool.read
        def changed(repo,path):
            text=real(repo,path)
            return text.replace('进入收藏、历史等页面前使用指纹、人脸或锁屏密码确认身份','changed upstream copy') if path==tool.SECTION else text
        with patch.object(tool,'read',side_effect=changed),self.assertRaises(ValueError):
            tool.generate(REPO,self.output)

if __name__=='__main__':unittest.main()
