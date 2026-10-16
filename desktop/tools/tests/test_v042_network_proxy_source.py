from pathlib import Path
import importlib.util
import json
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'desktop/tools'))
import v042_network_proxy as current


def load(name):
    spec = importlib.util.spec_from_file_location('current_proxy_' + name.replace('-', '_'),
        REPO / 'desktop/tools' / name)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def parameters(tokens, opening, declaration=False):
    level, braces, brackets = 1, 0, 0
    result = set()
    for i in range(opening + 1, len(tokens)):
        token = tokens[i][0]
        if (level, braces, brackets) == (1, 0, 0) and i + 1 < len(tokens):
            if tokens[i + 1][0] == (':' if declaration else '=') and (declaration or tokens[i - 1][0] in ('(', ',')):
                result.add(token)
        level += (token == '(') - (token == ')')
        braces += (token == '{') - (token == '}')
        brackets += (token == '[') - (token == ']')
        if level == 0:
            return result
    raise AssertionError('Unclosed actual proxy UI argument list')


class CurrentProxySourceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='bp-proxy-current-')
        cls.output = Path(cls.temp.name)
        cls.tool = load('extract-upstream-network-proxy.py')
        cls.tool.generate(REPO, cls.output / 'main')
        load('extract-upstream-preferences.py').generate(REPO, cls.output / 'preferences', True)
        current.emit_tests(REPO, cls.output / 'tests')
        cls.originals, cls.manifest = current.fixed_sources(REPO)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def leaf(self, name):
        paths = list((self.output / 'main').rglob(name))
        self.assertEqual(1, len(paths))
        return paths[0].read_text('utf8')

    def test_complete_current_store_policy_inverse_adapters_and_original_ten_cases_remain_exact(self):
        for logical in (current.STORE, current.POLICY):
            original = self.originals[current.PATHS[logical]].decode()
            actual = self.leaf(Path(logical).name).split('\n', 2)[2]
            if logical == current.STORE:
                original = original.replace('import android.content.Context',
                    'import com.bilipai.desktop.plugins.DesktopPluginContext as Context')
            else:
                self.assertEqual(1, actual.count('            .removePrefix("www.")\n'))
                actual = actual.replace('            .removePrefix("www.")\n', '')
            self.assertEqual(original.strip() + '\n', actual)
        target = self.output / 'tests' / current.TEST.split('/test/java/', 1)[1]
        self.assertEqual(self.originals[current.TEST], target.read_bytes())
        self.assertEqual(10, target.read_text('utf8').count('@Test'))

    def test_actual_shared_selector_reads_the_uri_host_and_retains_direct_playback(self):
        source = self.leaf('DesktopNetworkProxyPlatform.kt')
        self.assertIn('host = uri?.host,', source)
        self.assertIn('systemSelector()?.select(uri).orEmpty()', source)
        self.assertIn('.proxy(Proxy.NO_PROXY)', source)
        self.assertIn('recordDesktopProxyConnectionFailure(ioe)', source)
        self.assertNotIn('Proxy connect failed uri=', source)
        binding = (REPO / 'desktop/src/main/kotlin/com/bilipai/desktop/settings/DesktopNetworkProxyBindings.kt').read_text('utf8')
        self.assertIn('sharedApplicationClient.connectionPool.evictAll()', binding)
        self.assertLess(binding.index('NetworkProxyStore.save(context,value)'), binding.index('sharedApplicationClient.connectionPool.evictAll()'))
        self.assertIn('catch(cancelled:CancellationException){throw cancelled}', binding)

    def test_complete_domain_dialog_matches_the_real_generated_textfield_contract(self):
        ui = self.leaf('SettingsNetworkProxyFields.kt')
        preferences = load('extract-upstream-preferences.py')
        targets = list((self.output / 'preferences').rglob('AppPreferenceComponents.kt'))
        self.assertEqual(1, len(targets))
        source = targets[0].read_text('utf8')
        lexer = preferences.helper(REPO).parser_for(REPO)
        definitions = []
        tokens = lexer.kotlin_tokens(source)
        for i in range(len(tokens) - 2):
            if tokens[i][0] == 'fun' and tokens[i + 1][0] == 'AppTextField' and tokens[i + 2][0] == '(':
                definitions.append(parameters(tokens, i + 2, True))
        self.assertTrue(definitions)
        tokens = lexer.kotlin_tokens(ui)
        calls = [parameters(tokens, i + 1) for i in range(len(tokens) - 1)
            if tokens[i][0] == 'AppTextField' and tokens[i + 1][0] == '(']
        self.assertEqual(3, len(calls))
        for arguments in calls:
            self.assertTrue(any(arguments <= allowed for allowed in definitions), arguments)
        for term in ('ProxyRouteMode.SPLIT', 'parseProxyDomainRules(domainsText)', 'minLines = 3',
                'maxLines = 6', 'import androidx.compose.ui.draw.alpha', 'import com.android.purebilibili.core.ui.AppContentColors'):
            self.assertIn(term, ui)

    def test_retained_store_calls_resolve_to_the_existing_atomic_windows_preferences(self):
        store = self.leaf('NetworkProxyStore.kt')
        bridge = (REPO / 'desktop/src/main/kotlin/com/bilipai/desktop/plugins/DesktopPluginStore.kt').read_text('utf8')
        self.assertIn('.putStringSet(KEY_PROXIED_DOMAINS, normalized.proxiedDomains.toSet())', store)
        self.assertIn('prefs.getStringSet(KEY_PROXIED_DOMAINS, emptySet())', store)
        self.assertIn('fun getStringSet(key: String, fallback: Set<String>?)', bridge)
        self.assertIn('fun putStringSet(key: String, value: Set<String>?)', bridge)
        self.assertIn('fun apply() { store.update(name, values, clear) }', bridge)
        self.assertNotIn('Files.write', bridge[bridge.index('class DesktopPluginPreferences'):])

    def test_modified_raw_policy_is_rejected_before_emission(self):
        with tempfile.TemporaryDirectory(prefix='bp-proxy-tamper-') as folder:
            root = Path(folder)
            shutil.copytree(REPO / current.ARCHIVE, root / current.ARCHIVE)
            leaf = root / current.ARCHIVE / 'NetworkProxyPolicy.kt'
            leaf.write_bytes(leaf.read_bytes() + b'\n// changed\n')
            with self.assertRaisesRegex(ValueError, 'Changed current proxy source'):
                current.fixed_sources(root)

    def test_changed_rule_normalization_contract_fails_before_emitting_policy(self):
        original_read = self.tool.read

        def changed_read(repo, path):
            source = original_read(repo, path)
            if path == self.tool.POLICY:
                return source.replace('.removePrefix("*.")', '.removePrefix("wildcard:")')
            return source

        with patch.object(self.tool, 'read', side_effect=changed_read):
            with self.assertRaises(ValueError):
                self.tool.generate(REPO, self.output / 'changed-rule-contract')


if __name__ == '__main__':
    unittest.main()
