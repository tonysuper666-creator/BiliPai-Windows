"""Actual PowerShell file/argument checks using non-executable format fixtures."""
from pathlib import Path
from tempfile import TemporaryDirectory
import hashlib
import json
import os
import shutil
import subprocess
import unittest

TOOLS = Path(__file__).resolve().parents[1]
HELPER = TOOLS / 'fixed-java-toolchain.ps1'


class FixedJavaToolchainTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.pwsh = shutil.which('pwsh') or os.environ.get('BILIPAI_TEST_PWSH')
        if not cls.pwsh or not Path(cls.pwsh).is_file(): raise RuntimeError('Actual PowerShell 7 is required')

    def setUp(self):
        self.temp = TemporaryDirectory(prefix='bp-jdk-files-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.home = self.root / 'fixed JDK space'
        (self.home / 'bin').mkdir(parents=True)
        (self.home / 'bin/java.exe').write_bytes(b'not executable; never launched')
        self.release = self.home / 'release'
        self.release.write_bytes(b'JAVA_VERSION="21.0.12.1"\r\n')
        self.app = self.root / 'candidate'
        (self.app / 'runtime').mkdir(parents=True)
        (self.app / 'runtime/release').write_bytes(b'JAVA_VERSION="21.0.12.1"\nMODULES="java.base"\n')
        self.archive = self.root / 'candidate.zip'
        self.archive.write_bytes(b'file-only ZIP identity fixture; never installed')
        self.output = self.root / 'evidence.json'
        self.wrapper = self.root / 'file-check.ps1'
        self.wrapper.write_text('''param([string]$Helper,[string]$Request)
$ErrorActionPreference = 'Stop'
. $Helper
$r = Get-Content -LiteralPath $Request -Raw | ConvertFrom-Json
if ($r.mode -eq 'syntax') {
    $tokens = $null; $errors = $null
    [void][Management.Automation.Language.Parser]::ParseFile($r.build,[ref]$tokens,[ref]$errors)
    if ($errors.Count) { throw ($errors | Out-String) }
    @{parsed=$true} | ConvertTo-Json
} else {
    $jdk = Get-BiliPaiFixedJavaToolchain -JavaHome $r.home
    if ($r.mode -eq 'get') { $jdk | ConvertTo-Json -Depth 5 }
    else {
        $sha = Write-BiliPaiCandidateToolchainEvidence -Toolchain $jdk -SourceSha $r.source `
            -WindowsVersion $r.version -Archive $r.archive -ApplicationRoot $r.app -Output $r.output
        @{sha256=$sha} | ConvertTo-Json
    }
}
''', encoding='utf-8')

    def call(self, mode='get'):
        request = self.root / 'request.json'
        request.write_text(json.dumps(dict(mode=mode, home=str(self.home), source='a' * 40,
            version='0.2.427.133', archive=str(self.archive), app=str(self.app), output=str(self.output),
            build=str(TOOLS / 'build.ps1'))), encoding='utf-8')
        return subprocess.run([self.pwsh, '-NoLogo', '-NoProfile', '-NonInteractive', '-File', str(self.wrapper),
            '-Helper', str(HELPER), '-Request', str(request)], capture_output=True,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)

    def success(self, mode='get'):
        result = self.call(mode)
        self.assertEqual(result.returncode, 0, result.stderr.decode('utf-8', 'replace'))
        return json.loads(result.stdout)

    def reject(self, text, mode='get'):
        result = self.call(mode)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(text, (result.stdout + result.stderr).decode('utf-8', 'replace'))

    def test_fixed_image_produces_exact_three_toolchain_arguments(self):
        value = self.success()
        self.assertEqual(value['javaVersion'], '21.0.12.1')
        self.assertEqual(value['releaseSha256'], hashlib.sha256(self.release.read_bytes()).hexdigest())
        # Windows runner TEMP can use RUNNER~1 while .NET emits runneradmin.
        # Verify the actual directory, then the exact three literal arguments.
        self.assertTrue(Path(value['javaHome']).samefile(self.home))
        self.assertEqual(value['gradleArguments'], ['-Porg.gradle.java.installations.auto-detect=false',
            '-Porg.gradle.java.installations.auto-download=false', '-Porg.gradle.java.installations.paths=' + value['javaHome']])

    def test_wrong_minor_version_is_rejected(self):
        self.release.write_bytes(b'JAVA_VERSION="21.0.11"\n')
        self.reject('fixed 21.0.12.1')

    def test_duplicate_version_identity_is_rejected(self):
        self.release.write_bytes(self.release.read_bytes() * 2)
        self.reject('fixed 21.0.12.1')

    def test_missing_java_executable_is_rejected_without_executing_it(self):
        (self.home / 'bin/java.exe').unlink()
        self.reject('complete fixed JDK is missing')

    def test_whitespace_path_remains_one_literal_toolchain_argument(self):
        value = self.success()
        self.assertEqual(len(value['gradleArguments']), 3)
        argument = value['gradleArguments'][-1].split('=', 1)[1]
        self.assertEqual(argument, value['javaHome'])
        self.assertTrue(Path(argument).samefile(self.home))
        self.assertIn(' ', Path(argument).name)

    def test_equivalent_parent_alias_retains_physical_jdk_and_literal_arguments(self):
        physical = self.home
        self.home = physical / '..' / physical.name
        value = self.success()
        self.assertTrue(Path(value['javaHome']).samefile(physical))
        self.assertNotIn('..', Path(value['javaHome']).parts)
        self.assertEqual(value['releaseSha256'], hashlib.sha256(self.release.read_bytes()).hexdigest())
        self.assertEqual(value['gradleArguments'], ['-Porg.gradle.java.installations.auto-detect=false',
            '-Porg.gradle.java.installations.auto-download=false', '-Porg.gradle.java.installations.paths=' + value['javaHome']])

    def test_evidence_binds_exact_source_zip_and_jdk_without_runtime_acceptance(self):
        value = self.success('evidence')
        raw = self.output.read_bytes()
        self.assertEqual(value['sha256'], hashlib.sha256(raw).hexdigest())
        evidence = json.loads(raw)
        self.assertEqual(evidence['sourceSha'], 'a' * 40)
        self.assertEqual(evidence['candidateZip']['sha256'], hashlib.sha256(self.archive.read_bytes()).hexdigest())
        self.assertEqual(evidence['javaReleaseSha256'], hashlib.sha256(self.release.read_bytes()).hexdigest())
        for key in ('toolchainAutoDetection', 'toolchainAutoDownload', 'completeRuntimeByteBindingVerified',
                    'applicationExecuted', 'hardwareExecuted', 'releaseQualified'): self.assertIs(evidence[key], False)

    def test_wrong_packaged_runtime_version_is_rejected(self):
        (self.app / 'runtime/release').write_bytes(b'JAVA_VERSION="21.0.11"\n')
        self.reject('Packaged runtime version differs', 'evidence')
        self.assertFalse(self.output.exists())

    def test_existing_evidence_cannot_be_overwritten(self):
        self.output.write_bytes(b'preserve prior evidence')
        result = self.call('evidence')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.output.read_bytes(), b'preserve prior evidence')

    def test_actual_build_script_parses_without_executing_it(self):
        self.assertTrue(self.success('syntax')['parsed'])


if __name__ == '__main__': unittest.main(verbosity=2)
