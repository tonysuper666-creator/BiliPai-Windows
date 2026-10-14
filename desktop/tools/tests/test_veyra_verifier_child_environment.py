"""Real Windows PowerShell children test the actual assembler invocation seam.

Verifier fixtures test process isolation/error forwarding only. They never
qualify a component, load its DLLs, start media, or invoke a GPU.
"""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

REPO=Path(__file__).resolve().parents[3]
HARNESS=r'''
$ErrorActionPreference='Stop'
$taskRoot=$env:BILIPAI_CHILD_TEST_ROOT
$taskFixture=$env:BILIPAI_CHILD_TEST_FIXTURE
$taskTokens=$null; $taskParseErrors=$null
$taskAst=[System.Management.Automation.Language.Parser]::ParseFile((Join-Path $taskRoot 'desktop/tools/native/veyra/assemble-veyra-private-component.ps1'),[ref]$taskTokens,[ref]$taskParseErrors)
if($taskParseErrors.Count -ne 0){throw 'Actual assembler parse failed.'}
foreach($taskName in @('Reject','Hash-Value','Equal-Hash','Invoke-Verifier')){
    $taskFunction=$taskAst.Find({param($a) $a -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $a.Name -eq $taskName},$true)
    if($null-eq$taskFunction){throw 'Actual invocation dependency missing.'}
    . ([scriptblock]::Create($taskFunction.Extent.Text))
}
$taskInvoke=$taskAst.Find({param($a) $a -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $a.Name -eq 'Invoke-Verifier'},$true)
# Negative control is a test-only reproduction, never production code.
$taskOld=$taskInvoke.Extent.Text.Replace('function Invoke-Verifier(','function Invoke-UnsanitizedVerifier(').Replace(",'PSModulePath'",'')
. ([scriptblock]::Create($taskOld))
$null='{}'|ConvertFrom-Json
$taskParentModulePath=$env:PSModulePath
$taskResult=@()
try{
    $env:PSModulePath=Join-Path $PSHOME 'Modules'
    $taskContaminated=$env:PSModulePath
    foreach($taskMode in @('clean-signature','old-signature','bounded-failure','unbounded-failure')){
        $result=[ordered]@{}
        $taskAccepted=$false; $taskProof=$null; $taskRejected=$false
        $env:BILIPAI_CHILD_TEST_MODE=$taskMode
        try{
            if($taskMode-eq'old-signature'){
                $taskProof=Invoke-UnsanitizedVerifier $taskFixture $taskFixture ('1'*64) (Split-Path -Parent $taskFixture)
            }else{
                $taskProof=Invoke-Verifier $taskFixture $taskFixture ('1'*64) (Split-Path -Parent $taskFixture)
            }
            $taskAccepted=$true
        }catch{
            if([string]$_.Exception.Message-cne'BV_CODE:FULL_VERIFIER_REJECTED'){throw}
            $taskRejected=$true
        }
        if($env:PSModulePath-cne$taskContaminated){throw 'Parent environment was changed.'}
        $taskResult += [pscustomobject]@{mode=$taskMode;accepted=$taskAccepted;rejected=$taskRejected;proof=$taskProof;diagnostics=$result;parentEnvironmentPreserved=$true}
    }
}finally{$env:PSModulePath=$taskParentModulePath}
$taskResult|ConvertTo-Json -Depth 7 -Compress
'''
FIXTURE=r'''
param([string]$ProfilePath,[string]$TrustedProfileSha256,[string]$RuntimeRoot)
$ErrorActionPreference='Stop'
if($env:BILIPAI_CHILD_TEST_MODE-eq'bounded-failure'){
    [Console]::Out.WriteLine('{"schema":1,"status":"REJECTED","engineStatus":"UNAVAILABLE","failureCode":"EXPECTED_INCOMPATIBILITY","diagnosticSourceLine":23}')
    exit 2
}
if($env:BILIPAI_CHILD_TEST_MODE-eq'unbounded-failure'){
    [Console]::Out.WriteLine('{"schema":1,"status":"REJECTED","engineStatus":"UNAVAILABLE","failureCode":"unsafe details C:/fixture/path","diagnosticSourceLine":10001}')
    exit 2
}
try{
    $taskSignature=Get-AuthenticodeSignature -LiteralPath (Join-Path $PSHOME 'powershell.exe')
    if([string]$taskSignature.Status-cne'Valid'){throw 'System signature fixture failed.'}
    $taskCommand=Get-Command Get-AuthenticodeSignature
    $taskProof=[ordered]@{schema=1;status='VERIFIED';engineStatus='AVAILABLE';checked=@{profileSha256=$TrustedProfileSha256;signatureStatus=[string]$taskSignature.Status;securityModuleIsOwnSystemModule=$taskCommand.Module.ModuleBase.StartsWith($PSHOME,[StringComparison]::OrdinalIgnoreCase)}}
    [Console]::Out.WriteLine(($taskProof|ConvertTo-Json -Depth 4 -Compress))
    exit 0
}catch{
    [Console]::Out.WriteLine('{"schema":1,"status":"REJECTED","engineStatus":"UNAVAILABLE","failureCode":"FIXTURE_MODULE_DISCOVERY_FAILED"}')
    exit 2
}
'''


@unittest.skipUnless(os.name=='nt' and shutil.which('pwsh') and shutil.which('powershell.exe'),
                     'The actual Windows PowerShell and PowerShell 7 processes are required')
class VeyraVerifierChildEnvironmentTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with tempfile.TemporaryDirectory() as tmp:
            fixture=Path(tmp)/'verifier-fixture.ps1'
            fixture.write_text(FIXTURE,encoding='utf-8-sig',newline='\r\n')
            env=dict(os.environ,BILIPAI_CHILD_TEST_ROOT=str(REPO),BILIPAI_CHILD_TEST_FIXTURE=str(fixture))
            for key in ('GITHUB_TOKEN','GH_TOKEN'):env.pop(key,None)
            child=subprocess.run(['pwsh','-NoLogo','-NoProfile','-NonInteractive','-Command',HARNESS],
                                 capture_output=True,env=env,timeout=60,check=True)
            cls.results={row['mode']:row for row in json.loads(child.stdout.decode('utf-8-sig'))}

    def test_real_child_discovers_its_system_signature_module_with_contaminated_parent(self):
        row=self.results['clean-signature']
        self.assertTrue(row['accepted'])
        self.assertEqual('Valid',row['proof']['checked']['signatureStatus'])
        self.assertTrue(row['proof']['checked']['securityModuleIsOwnSystemModule'])
        self.assertTrue(row['parentEnvironmentPreserved'])

    def test_negative_control_reproduces_the_previous_module_autoload_failure(self):
        row=self.results['old-signature']
        self.assertTrue(row['rejected'])
        self.assertFalse(row['accepted'])
        self.assertEqual('FIXTURE_MODULE_DISCOVERY_FAILED',row['diagnostics']['verifierFailureCode'])

    def test_child_failure_remains_rejected_and_only_bounded_codes_are_forwarded(self):
        safe=self.results['bounded-failure']
        self.assertTrue(safe['rejected'])
        self.assertEqual('EXPECTED_INCOMPATIBILITY',safe['diagnostics']['verifierFailureCode'])
        self.assertEqual(23,safe['diagnostics']['verifierDiagnosticSourceLine'])
        unsafe=self.results['unbounded-failure']
        self.assertTrue(unsafe['rejected'])
        self.assertNotIn('verifierFailureCode',unsafe['diagnostics'])
        self.assertNotIn('verifierDiagnosticSourceLine',unsafe['diagnostics'])


if __name__=='__main__':unittest.main()
