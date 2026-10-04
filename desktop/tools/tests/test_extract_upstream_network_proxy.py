from pathlib import Path
import hashlib
import importlib.util
import tempfile
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
spec = importlib.util.spec_from_file_location('network_proxy', REPO / 'desktop/tools/extract-upstream-network-proxy.py')
tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tool)
host = tool.load(REPO / 'desktop/tools/extract-upstream-plugins.py', 'network_proxy_test_helpers')
media = host.media_extractor(REPO)
parser = media.parser_for(REPO)


class NetworkProxyExtractionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='bilipai-proxy-extraction-')
        cls.output = Path(cls.temp.name) / 'product'
        cls.files = {path.name: path.read_text(encoding='utf-8') for path in tool.generate(REPO, cls.output)}

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def test_original_store_has_only_context_import_adapter_and_policy_keeps_direct_owner(self):
        source = self.files['NetworkProxyStore.kt']
        self.assertEqual(source[source.index('package '):], tool.read(REPO, tool.STORE).replace(
            'import android.content.Context', 'import com.bilipai.desktop.plugins.DesktopPluginContext as Context'))
        self.assertNotIn('NetworkProxyPolicy.kt', self.files)
        inventory = {item['path']: item for item in tool.inventory(REPO)}
        self.assertEqual(inventory[tool.POLICY]['mode'], 'direct')
        self.assertEqual(inventory[tool.POLICY]['sha256'], hashlib.sha256(tool.read(REPO, tool.POLICY).encode()).hexdigest())

    def test_original_selector_and_direct_media_builder_keep_all_route_behavior(self):
        source = self.files['DesktopNetworkProxyPlatform.kt']
        original = tool.read(REPO, tool.API)
        selector = media.function(source, 'buildAppProxySelector', parser)
        selector = selector.replace(
            'buildAppProxySelector(systemSelector: () -> java.net.ProxySelector? = { java.net.ProxySelector.getDefault() }):',
            'buildAppProxySelector():').replace('systemSelector()?.select(uri).orEmpty()', 'getDefault()?.select(uri).orEmpty()')
        selector = selector.replace('recordDesktopProxyConnectionFailure(ioe)',
            'com.android.purebilibili.core.network.CoreDataLog.w(\n                "ApiClient",\n'
            '                "Proxy connect failed uri=$uri sa=$sa: ${ioe?.message}"\n            )')
        self.assertEqual(selector, media.function(original, 'buildAppProxySelector', parser))
        self.assertEqual(media.function(source, 'buildPlaybackOkHttpClient', parser),
                         media.function(original, 'buildPlaybackOkHttpClient', parser))

    def test_complete_original_address_dialog_and_two_controls_are_preserved(self):
        source = self.files['SettingsNetworkProxyFields.kt']
        original = tool.read(REPO, tool.SECTIONS)
        self.assertEqual(media.function(source, 'NetworkProxyEditDialog', parser),
                         media.function(original, 'NetworkProxyEditDialog', parser))
        section = media.function(original, 'DiagnosticsSection', parser)
        start = section.index('        SettingSwitchItem(\n            icon = com.android.purebilibili.feature.settings.rememberMaterialSymbol(com.android.purebilibili.R.drawable.ms_lan_24)')
        end = section.index('        SettingsAdaptiveDivider()', section.index('        SettingClickableItem(', start))
        reconstructed = source.replace('bindings.save(proxySettings.copy(enabled = enabled))',
            'NetworkProxyStore.save(context, proxySettings.copy(enabled = enabled))')
        for asset in tool.ASSETS:
            reconstructed = reconstructed.replace('DesktopProxySettingsVectors.vector(DesktopProxySettingsSymbols.' + asset + ')',
                'com.android.purebilibili.feature.settings.rememberMaterialSymbol(com.android.purebilibili.R.drawable.' + asset + ')')
        self.assertIn(section[start:end].rstrip(), reconstructed)
        for name in ['showEnhancedDiagnosticConsent', 'crashTrackingEnabled', 'analyticsEnabled',
                     'fun SettingsCardGroup', 'fun SettingsAdaptiveDivider']:
            self.assertNotIn(name, source)

    def test_product_outputs_are_stable_and_only_two_original_resource_identities_are_added(self):
        before = {path.name: path.read_bytes() for path in self.output.rglob('*.kt')}
        after = {path.name: path.read_bytes() for path in tool.generate(REPO, self.output)}
        self.assertEqual(before, after)
        self.assertEqual(set(after), {'NetworkProxyStore.kt', 'DesktopNetworkProxyPlatform.kt',
                                     'SettingsNetworkProxyFields.kt', 'DesktopProxySettingsVectors.kt'})
        resources = tool.resources(REPO)
        self.assertEqual(len(resources), 2)
        for item in resources:
            self.assertEqual(item['sha256'], hashlib.sha256(tool.read(REPO, item['path']).encode()).hexdigest())

    def test_upstream_changed_save_contract_fails_instead_of_emitting_unbound_controls(self):
        original_read = tool.read
        def changed_read(repo, path):
            source = original_read(repo, path)
            if path == tool.SECTIONS:
                return source.replace('NetworkProxyStore.save(context, proxySettings.copy(enabled = enabled))',
                                      'NewUpstreamProxyStore.save(context, proxySettings.copy(enabled = enabled))', 1)
            return source
        with patch.object(tool, 'read', side_effect=changed_read):
            with self.assertRaises(ValueError):
                tool.generate(REPO, Path(self.temp.name) / 'changed-contract')


if __name__ == '__main__':
    unittest.main()
