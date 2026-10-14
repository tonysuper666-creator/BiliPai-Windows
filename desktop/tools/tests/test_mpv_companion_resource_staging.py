"""Exercise packaging file copies only; fixture bytes are not executable DLLs."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

REPO=Path(__file__).resolve().parents[3]
SCRIPT=r'''
$ErrorActionPreference='Stop'
. (Join-Path $env:BILIPAI_COMPANION_REPO 'desktop/tools/native/stage-mpv-companion-resources.ps1')
$taskProvenance=Get-Content -LiteralPath $env:BILIPAI_COMPANION_PROVENANCE -Raw|ConvertFrom-Json
try{
    Copy-VerifiedMpvCompanionResources -NativeRoot $env:BILIPAI_COMPANION_NATIVE -ResourceRoot $env:BILIPAI_COMPANION_RESOURCES -Provenance $taskProvenance
    [Console]::Out.WriteLine('{"accepted":true}')
}catch{
    [Console]::Out.WriteLine('{"accepted":false}')
}
'''


@unittest.skipUnless(shutil.which('pwsh'),'PowerShell is required for the actual packaging copy seam')
class MpvCompanionResourceStagingTest(unittest.TestCase):
    def invoke(self,root,provenance):
        native=root/'native';resources=root/'resources'
        native.mkdir(exist_ok=True);resources.mkdir(exist_ok=True)
        manifest=root/'provenance.json';manifest.write_text(json.dumps(provenance),encoding='utf-8')
        env=dict(os.environ,BILIPAI_COMPANION_REPO=str(REPO),BILIPAI_COMPANION_NATIVE=str(native),
                 BILIPAI_COMPANION_RESOURCES=str(resources),BILIPAI_COMPANION_PROVENANCE=str(manifest))
        result=subprocess.run(['pwsh','-NoProfile','-NonInteractive','-Command',SCRIPT],env=env,capture_output=True,check=True,timeout=15)
        return json.loads(result.stdout.decode('utf-8-sig'))['accepted']

    def provenance(self,raw):
        return dict(variant='bilipai-veyra-rtx-present-v1',companionRuntime=dict(
            kind='SOURCE_BUILT_VULKAN_LOADER',path='vulkan-1.dll',architecture='windows-x64',
            bytes=len(raw),sha256=hashlib.sha256(raw).hexdigest()))

    def test_verified_companion_is_copied_byte_for_byte(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);(root/'native').mkdir()
            raw=b'non-executable companion fixture'
            (root/'native/vulkan-1.dll').write_bytes(raw)
            self.assertTrue(self.invoke(root,self.provenance(raw)))
            self.assertEqual(raw,(root/'resources/vulkan-1.dll').read_bytes())

    def test_missing_or_changed_companion_cannot_replace_existing_good_resource(self):
        for actual in (None,b'changed fixture bytes'):
            with self.subTest(actual=actual),tempfile.TemporaryDirectory() as tmp:
                root=Path(tmp);(root/'native').mkdir();(root/'resources').mkdir()
                (root/'resources/vulkan-1.dll').write_bytes(b'previous resource')
                if actual is not None:(root/'native/vulkan-1.dll').write_bytes(actual)
                self.assertFalse(self.invoke(root,self.provenance(b'expected fixture')))
                self.assertEqual(b'previous resource',(root/'resources/vulkan-1.dll').read_bytes())

    def test_presentation_cannot_omit_companion_metadata(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            self.assertFalse(self.invoke(root,dict(variant='bilipai-veyra-rtx-present-v1')))

    def test_unpatched_rebuild_removes_only_the_stale_known_companion(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);(root/'resources').mkdir()
            (root/'resources/vulkan-1.dll').write_bytes(b'stale')
            (root/'resources/unrelated.dll').write_bytes(b'keep')
            self.assertTrue(self.invoke(root,dict(variant='original-mpv')))
            self.assertFalse((root/'resources/vulkan-1.dll').exists())
            self.assertEqual(b'keep',(root/'resources/unrelated.dll').read_bytes())

    def test_metadata_cannot_select_a_foreign_or_escaping_path(self):
        for name in ('../outside.dll','unrelated.dll','C:/outside.dll'):
            with self.subTest(name=name),tempfile.TemporaryDirectory() as tmp:
                root=Path(tmp);provenance=self.provenance(b'fixture')
                provenance['companionRuntime']['path']=name
                self.assertFalse(self.invoke(root,provenance))
                self.assertFalse((root/'resources/vulkan-1.dll').exists())


if __name__=='__main__':unittest.main()
