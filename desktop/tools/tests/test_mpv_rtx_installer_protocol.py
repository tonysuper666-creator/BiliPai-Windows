"""Exercise three actual source-admission guards with the native173 source fixture.

The complete source patch helper generated it from fixed archive bytes with Git
identity/status mocked. No native build, original Git checkout, DLL or GPU proof.
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
FIXTURE_SHA256 = 'd1c8357ca27184192dd8900400d80389d549bfc88166edb8a332df56fd93767a'
SCRIPT = r'''
$ErrorActionPreference = 'Stop'
$taskRoot = $env:BILIPAI_RTX_PROTOCOL_TEST_ROOT
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
    $script:taskResults += [pscustomobject]@{backend=$script:taskBackend;name=$Name;accepted=$taskAccepted}
}
foreach ($taskBackend in @('mpv-installer','component-verifier','component-assembler')) {
if ($taskBackend -eq 'mpv-installer') {
    . (Join-Path $taskRoot 'desktop/tools/native/mpv-rtx-core-runtime-descriptor.ps1')
} else {
    $taskTokens=$null; $taskParseErrors=$null
    $taskScriptLeaf=if ($taskBackend -eq 'component-verifier') {'verify-veyra-runtime.ps1'} else {'assemble-veyra-private-component.ps1'}
    $taskVerifierAst=[System.Management.Automation.Language.Parser]::ParseFile((Join-Path $taskRoot ('desktop/tools/native/veyra/'+$taskScriptLeaf)),[ref]$taskTokens,[ref]$taskParseErrors)
    if ($taskBackend -eq 'component-verifier') { $taskProfileVerifierAst=$taskVerifierAst }
    if ($taskParseErrors.Count -ne 0) { throw 'Actual component verifier parse failed.' }
    $taskFunction=$taskVerifierAst.Find({param($a) $a -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $a.Name -eq 'Assert-PresentationSource'},$true)
    if ($null -eq $taskFunction) { throw 'Actual component source guard missing.' }
    # Evaluate only the real pure function AST. The verifier's top-level file,
    # profile, runtime and component checks are not executed by this test.
    . ([scriptblock]::Create($taskFunction.Extent.Text))
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
Test-ReceiptCase 'retired-manifest' {param($r) $r.filterSourceManifestSha256='a1cda53ef043749e006c88a84abee129bccc7bfc649b011976cd421e75e5c607'}
Test-ReceiptCase 'retired-helper' {param($r) $r.sourcePatchHelperSha256='748199ed370c169b17337154a3f7d0fede10a1c9420b1a8b02b3844aa6e6bebf'}
}
$taskReject=$taskProfileVerifierAst.Find({param($a) $a -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $a.Name -eq 'Reject'},$true)
. ([scriptblock]::Create($taskReject.Extent.Text))
$taskProfileGuard=@($taskProfileVerifierAst.FindAll({param($a) $a -is [System.Management.Automation.Language.IfStatementAst] -and $a.Extent.Text.Contains("'PRESENTATION_PROFILE_INVALID'")},$true) | Sort-Object { $_.Extent.Text.Length })[0]
if ($null -eq $taskProfileGuard) { throw 'Actual component profile guard missing.' }
foreach ($taskProfileCase in @(
    @{name='profile-json-int64';value=('2' | ConvertFrom-Json);expected=$true},
    @{name='profile-int32';value=[int]2;expected=$true},
    @{name='profile-string';value='2';expected=$false},
    @{name='profile-double';value=[double]2;expected=$false},
    @{name='profile-fractional';value=[double]2.5;expected=$false},
    @{name='profile-boolean';value=$true;expected=$false},
    @{name='profile-null';value=$null;expected=$false},
    @{name='profile-other-version';value=[long]3;expected=$false}
)) {
    $profile=[pscustomobject]@{presentationProtocolVersion=$taskProfileCase.value;presentationProperty='bilipai-rtx-presentation';mpvNativeReceiptRelativePath='mpv/licenses/native-patch-receipt.json'}
    $taskAccepted=$true
    try { & ([scriptblock]::Create($taskProfileGuard.Extent.Text)) }
    catch { $taskAccepted=$false }
    if ($taskAccepted -ne $taskProfileCase.expected) { throw ('Actual profile integer guard failed: '+$taskProfileCase.name) }
    $taskResults += [pscustomobject]@{backend='component-profile';name=$taskProfileCase.name;accepted=$taskAccepted}
}
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
        cls.results = {(row['backend'],row['name']): row['accepted'] for row in json.loads(result.stdout.decode('utf-8-sig'))}

    def assert_receipt_case(self, name, expected):
        for backend in ('mpv-installer','component-verifier','component-assembler'):
            with self.subTest(backend=backend, name=name):
                self.assertEqual(expected, self.results[(backend,name)])

    def test_actual_json_and_both_powershell_integer_types_are_accepted(self):
        for name in ('actual-json-int64', 'boxed-int32', 'boxed-int64'):
            self.assert_receipt_case(name, True)

    def test_coercible_non_integral_and_null_protocol_values_are_rejected(self):
        for name in ('string-schema', 'string-token', 'boolean-token', 'floating-schema',
                     'floating-token', 'fractional-token', 'null-token'):
            self.assert_receipt_case(name, False)

    def test_other_integer_versions_are_rejected(self):
        for name in ('wrong-schema', 'wrong-token'):
            self.assert_receipt_case(name, False)

    def test_execution_claims_remain_strict_boolean_and_unverified(self):
        for name in ('string-runtime-flag', 'claimed-hardware-proof'):
            self.assert_receipt_case(name, False)

    def test_source_graph_inventory_and_presentation_property_remain_bound(self):
        for name in ('changed-source-graph', 'changed-source-file', 'wrong-presentation-property'):
            self.assert_receipt_case(name, False)

    def test_retired_presentation_source_and_helper_are_rejected(self):
        for name in ('retired-manifest', 'retired-helper'):
            self.assert_receipt_case(name, False)

    def test_actual_component_profile_guard_accepts_only_supported_json_integers(self):
        for name in ('profile-json-int64','profile-int32'):
            self.assertTrue(self.results[('component-profile',name)])
        for name in ('profile-string','profile-double','profile-fractional','profile-boolean','profile-null','profile-other-version'):
            self.assertFalse(self.results[('component-profile',name)])


if __name__ == '__main__':
    unittest.main()
