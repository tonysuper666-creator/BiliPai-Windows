"""Exercise the installer against the exact source receipt from build 38574814418.

Only JSON parsing and source-graph admission run; no DLL is loaded or executed.
"""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import unittest

REPO = Path(__file__).resolve().parents[3]
FIXTURE = REPO / 'desktop/tools/tests/fixtures/mpv-rtx-present-v1-native-patch-receipt.json'
FIXTURE_SHA256 = 'b65f8eb0be10f461e50b255267ea6512179e627f3bf27d98a4ef324e4651a879'
SCRIPT = r'''
$ErrorActionPreference = 'Stop'
$taskRoot = $env:BILIPAI_RTX_PROTOCOL_TEST_ROOT
. (Join-Path $taskRoot 'desktop/tools/native/mpv-rtx-core-runtime-descriptor.ps1')
$taskOriginal = Get-Content -LiteralPath (Join-Path $taskRoot 'desktop/tools/tests/fixtures/mpv-rtx-present-v1-native-patch-receipt.json') -Raw
$taskMaterials = Join-Path $taskRoot 'desktop/third-party/libmpv/build/rtx-present-v1'
$taskManifest = Get-Content -LiteralPath (Join-Path $taskMaterials 'bilipai-rtx-presentation-source-manifest.json') -Raw | ConvertFrom-Json
$taskUpstream = Get-Content -LiteralPath (Join-Path $taskMaterials 'presentation-edits.json') -Raw | ConvertFrom-Json
$taskRegistration = Get-Content -LiteralPath (Join-Path $taskMaterials 'filter-registration-edits.json') -Raw | ConvertFrom-Json
$taskResults = @()
function Test-ReceiptCase([string]$Name, [scriptblock]$Mutation) {
    $taskReceipt = $taskOriginal | ConvertFrom-Json
    & $Mutation $taskReceipt
    $taskAccepted = $true
    try { Assert-PresentationSource $taskReceipt $taskManifest $taskUpstream $taskRegistration }
    catch { $taskAccepted = $false }
    $script:taskResults += [pscustomobject]@{name=$Name;accepted=$taskAccepted}
}
Test-ReceiptCase 'actual-json-int64' {param($r)}
Test-ReceiptCase 'boxed-int32' {param($r) $r.schema=[int]3; $r.tokenProtocol=[int]2}
Test-ReceiptCase 'boxed-int64' {param($r) $r.schema=[long]3; $r.tokenProtocol=[long]2}
Test-ReceiptCase 'string-schema' {param($r) $r.schema='3'}
Test-ReceiptCase 'string-token' {param($r) $r.tokenProtocol='2'}
Test-ReceiptCase 'boolean-token' {param($r) $r.tokenProtocol=$true}
Test-ReceiptCase 'floating-schema' {param($r) $r.schema=[double]3}
Test-ReceiptCase 'floating-token' {param($r) $r.tokenProtocol=[double]2}
Test-ReceiptCase 'fractional-token' {param($r) $r.tokenProtocol=[double]2.5}
Test-ReceiptCase 'null-token' {param($r) $r.tokenProtocol=$null}
Test-ReceiptCase 'wrong-schema' {param($r) $r.schema=[long]2}
Test-ReceiptCase 'wrong-token' {param($r) $r.tokenProtocol=[long]1}
Test-ReceiptCase 'string-runtime-flag' {param($r) $r.gpuExecuted='false'}
Test-ReceiptCase 'claimed-hardware-proof' {param($r) $r.displayProofRuntimeVerified=$true}
Test-ReceiptCase 'changed-source-graph' {param($r) $r.sourceGraph[0].afterSha256=('0'*64)}
Test-ReceiptCase 'changed-source-file' {param($r) $r.filterSourceFiles[0].sha256=('0'*64)}
Test-ReceiptCase 'wrong-presentation-property' {param($r) $r.presentationProperty='other-property'}
$taskResults | ConvertTo-Json -Compress
'''


@unittest.skipUnless(shutil.which('pwsh'), 'PowerShell 7 is required for the actual installer parser')
class MpvRtxInstallerProtocolTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if hashlib.sha256(FIXTURE.read_bytes()).hexdigest() != FIXTURE_SHA256:
            raise AssertionError('Actual native source receipt fixture changed')
        environment = dict(os.environ, BILIPAI_RTX_PROTOCOL_TEST_ROOT=str(REPO))
        environment.pop('GITHUB_TOKEN', None)
        environment.pop('GH_TOKEN', None)
        result = subprocess.run(['pwsh', '-NoProfile', '-NonInteractive', '-Command', SCRIPT],
                                capture_output=True, env=environment, timeout=30, check=True)
        cls.results = {row['name']: row['accepted'] for row in json.loads(result.stdout.decode('utf-8-sig'))}

    def test_actual_json_and_both_powershell_integer_types_are_accepted(self):
        for name in ('actual-json-int64', 'boxed-int32', 'boxed-int64'):
            with self.subTest(name=name): self.assertTrue(self.results[name])

    def test_coercible_non_integral_and_null_protocol_values_are_rejected(self):
        for name in ('string-schema', 'string-token', 'boolean-token', 'floating-schema',
                     'floating-token', 'fractional-token', 'null-token'):
            with self.subTest(name=name): self.assertFalse(self.results[name])

    def test_other_integer_versions_are_rejected(self):
        for name in ('wrong-schema', 'wrong-token'):
            with self.subTest(name=name): self.assertFalse(self.results[name])

    def test_execution_claims_remain_strict_boolean_and_unverified(self):
        for name in ('string-runtime-flag', 'claimed-hardware-proof'):
            with self.subTest(name=name): self.assertFalse(self.results[name])

    def test_source_graph_inventory_and_presentation_property_remain_bound(self):
        for name in ('changed-source-graph', 'changed-source-file', 'wrong-presentation-property'):
            with self.subTest(name=name): self.assertFalse(self.results[name])


if __name__ == '__main__':
    unittest.main()
