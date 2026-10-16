from pathlib import Path
import hashlib
import subprocess
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]


def git(root, *args):
    return subprocess.run(['git', '-C', str(root), *args], capture_output=True, check=True).stdout


class PackageCheckoutConsistencyTest(unittest.TestCase):
    def fixture(self, root):
        source = root / 'source'; source.mkdir()
        git(source, 'init')
        (source / '.gitattributes').write_bytes(b'* text=auto\n*.raw -text\n')
        (source / 'strings.xml').write_bytes(b'<resources>\n<string name="label">same bytes</string>\n</resources>\n')
        (source / 'upstream.raw').write_bytes(b'fixed upstream\r\nraw blob\r\n')
        git(source, '-c', 'core.autocrlf=false', 'add', '.')
        git(source, '-c', 'user.name=Fixture', '-c', 'user.email=fixture@invalid.example',
            '-c', 'commit.gpgsign=false', 'commit', '-m', 'Fixed fixture')
        return source

    def checkout(self, source, target, policy):
        git(source, 'clone', '--no-checkout', str(source), str(target))
        git(target, '-c', 'core.autocrlf=' + policy[0], '-c', 'core.eol=' + policy[1], 'read-tree', '-mu', 'HEAD')
        return target

    def policy(self, workflow):
        text = (REPO / '.github/workflows' / workflow).read_text(encoding='utf-8')
        before = text[:text.index('uses: actions/checkout@')]
        self.assertIn('git config --global core.autocrlf false', before)
        self.assertIn('git config --global core.eol lf', before)
        return ('false', 'lf')

    def test_actual_source_and_package_checkout_policy_preserves_identical_resource_bytes(self):
        source_policy = self.policy('windows-source-validation.yml')
        package_policy = self.policy('windows-package-candidate.yml')
        with tempfile.TemporaryDirectory(prefix='bilipai-checkout-text-') as directory:
            root = Path(directory); source = self.fixture(root)
            qualified = self.checkout(source, root / 'qualified', source_policy)
            packaged = self.checkout(source, root / 'packaged', package_policy)
            old = self.checkout(source, root / 'old-default', ('true', 'crlf'))
            expected = git(source, 'show', 'HEAD:strings.xml')
            self.assertEqual(expected, (qualified / 'strings.xml').read_bytes())
            self.assertEqual(expected, (packaged / 'strings.xml').read_bytes())
            self.assertNotEqual(hashlib.sha256(expected).hexdigest(), hashlib.sha256((old / 'strings.xml').read_bytes()).hexdigest())

    def test_checkout_policy_does_not_normalize_raw_archived_upstream_bytes(self):
        policy = self.policy('windows-package-candidate.yml')
        with tempfile.TemporaryDirectory(prefix='bilipai-checkout-raw-') as directory:
            root = Path(directory); source = self.fixture(root)
            packaged = self.checkout(source, root / 'packaged', policy)
            expected = git(source, 'show', 'HEAD:upstream.raw')
            self.assertIn(b'\r\n', expected)
            self.assertEqual(expected, (packaged / 'upstream.raw').read_bytes())


if __name__ == '__main__':
    unittest.main()
