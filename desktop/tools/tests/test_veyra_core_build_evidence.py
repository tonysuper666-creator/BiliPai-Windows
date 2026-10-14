import importlib.util
import json
from pathlib import Path
import struct
import time
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location("veyra_actual_evidence", REPO / "desktop/tools/native/veyra-core-build-evidence.py")
evidence = importlib.util.module_from_spec(spec)
spec.loader.exec_module(evidence)
builder_spec = importlib.util.spec_from_file_location("veyra_evidence_fixture_builder", REPO / "desktop/tools/native/build-veyra-core-runtime.py")
builder = importlib.util.module_from_spec(builder_spec)
builder_spec.loader.exec_module(builder)


def object_fixture(symbols):
    raw = bytearray(64 + len(symbols) * 18)
    struct.pack_into("<HHIIIHH", raw, 0, 0x8664, 1, 0, 64, len(symbols), 0, 0)
    strings = bytearray(b"\0" * 4)
    for i, (name, section, value) in enumerate(symbols):
        at = 64 + i * 18
        if len(name) <= 8:
            raw[at:at+len(name)] = name.encode()
        else:
            struct.pack_into("<II", raw, at, 0, len(strings))
            strings += name.encode() + b"\0"
        struct.pack_into("<IhHBB", raw, at+8, value, section, 0, 2, 0)
    struct.pack_into("<I", strings, 0, len(strings))
    return raw + strings


class VeyraCoreBuildEvidenceTest(unittest.TestCase):
    def fixture_build(self, root):
        # Simulated compiler products test the collector contract only. They are
        # temporary unit fixtures, never retained as actual native build proof.
        closure, sdk, pins = builder.inputs(REPO)
        record = dict(nativeCoreLinked=True, actualConfigureExit=0, actualBuildExit=0,
                      sourceRepository=builder.REPOSITORY, sourceSha="1"*40,
                      sourceBefore=pins, sourceAfter=dict(pins),
                      sdkBefore={str(local):pin['sha256'] for _,local,pin in builder.sdk_entries(sdk)})
        record['sdkAfter'] = dict(record['sdkBefore'])
        record.update(exports=sorted(builder.EXPORTS), moduleSha256="2"*64, moduleBytes=2048)
        build = root / "build"; sdk_dir = root / "sdk"; system = root / "system"
        system.mkdir(); header = system / "actual-system-header.h"; header.write_bytes(b"fixture system include")
        sources = {target: [] for target in evidence.TARGETS}
        for row in closure['ownedSources']:
            path = REPO / 'desktop/native' / row['path']
            if path.suffix == '.cpp':
                target = evidence.TARGETS[1] if row['path']=='veyra-core/abi_layout_compile.cpp' else evidence.TARGETS[2] if row['path']=='veyra-dlss-sr/abi_layout_compile.cpp' else evidence.TARGETS[0]
                sources[target].append(path)
        for row in closure['fixedUniqueSources']:
            path = REPO / 'desktop/native' / row['sourceClosure'] / 'upstream' / row['path']
            if path.suffix == '.cpp': sources[evidence.TARGETS[0]].append(path)
        for target, paths in sources.items():
            objdir = build / 'CMakeFiles' / (target+'.dir'); depdir = build / 'dependencies' / target
            objdir.mkdir(parents=True); depdir.mkdir(parents=True)
            for source in paths:
                symbol = 'bp_ngx_checked_shutdown_shared' if source.name=='NgxCoreHost.cpp' else 'fixture_external'
                (objdir/(source.name+'.obj')).write_bytes(object_fixture([(symbol,0,0)]))
                (depdir/(source.name+'.json')).write_text(json.dumps({'Version':'1.2','Data':{'Source':str(source),'Includes':[str(header)]}}),encoding='utf-8')
        return build, sdk_dir, record, closure, sdk, time.time()-1, [system]

    def test_real_installer_source_identity_stays_exact(self):
        identity, manifest = evidence.source_identity(REPO)
        self.assertEqual(9, len(identity))
        self.assertEqual("bilipai-veyra-shared-core-v1-v2", manifest["variant"])
        self.assertEqual("BiliPai-Veyra-Core-Shared-1", manifest["actualNgxHostEngineVersion"])

    def test_coff_long_names_and_imported_checked_shutdown_reference_are_read(self):
        raw = object_fixture([("long_external_symbol", 1, 0), ("__imp_bp_ngx_checked_shutdown_shared", 0, 0)])
        rows = evidence.coff_symbols(raw)
        self.assertEqual("long_external_symbol", rows[0]["name"])
        self.assertEqual(["__imp_bp_ngx_checked_shutdown_shared"], evidence.checked_shutdown_redirect(rows))

    def test_defined_or_missing_redirect_cannot_stand_in_for_actual_host_reference(self):
        for symbols in [[("bp_ngx_checked_shutdown_shared", 1, 0)], [("other", 0, 0)],
                        [("bp_ngx_checked_shutdown_shared", 0, 0), ("NVSDK_NGX_D3D12_Shutdown1", 0, 0)]]:
            with self.subTest(symbols=symbols), self.assertRaisesRegex(ValueError, "redirect"):
                evidence.checked_shutdown_redirect(evidence.coff_symbols(object_fixture(symbols)))

    def test_wrong_architecture_and_truncated_coff_bounds_are_rejected(self):
        raw = object_fixture([("bp_ngx_checked_shutdown_shared", 0, 0)])
        bad = bytearray(raw); struct.pack_into("<H", bad, 0, 0x14c)
        for value in [raw[:59], raw[:-2], bad]:
            with self.assertRaises(ValueError): evidence.coff_symbols(value)

    def test_coff_string_and_auxiliary_rows_cannot_escape_the_object(self):
        raw = object_fixture([("bp_ngx_checked_shutdown_shared", 0, 0)])
        bad_offset = bytearray(raw); struct.pack_into("<I", bad_offset, 68, 0xffffffff)
        bad_aux = bytearray(raw); bad_aux[64+17] = 3
        for value in [bad_offset, bad_aux]:
            with self.assertRaises(ValueError): evidence.coff_symbols(value)

    def test_actual_msvc_report_requires_absolute_paths_and_known_schema(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = str(Path(tmp) / "actual.cpp"); header = str(Path(tmp) / "actual.h")
            doc = {"Version":"1.2", "Data":{"Source":source, "Includes":[header], "ImportedModules":[], "ImportedHeaderUnits":[]}}
            found, includes = evidence.dependency_document(json.dumps(doc).encode())
            self.assertEqual(Path(source), found); self.assertEqual([Path(header)], includes)
            doc["Version"] = "future"
            with self.assertRaises(ValueError): evidence.dependency_document(json.dumps(doc).encode())
            doc["Version"] = "1.2"; doc["Data"]["Source"] = "../untrusted.cpp"
            with self.assertRaises(ValueError): evidence.dependency_document(json.dumps(doc).encode())

    def test_unselected_modules_pch_and_malformed_include_lists_are_rejected(self):
        for extra in [{"ImportedModules":[{"Name":"foreign"}]}, {"PCH":"foreign.pch"}, {"Includes":"foreign"}]:
            doc = {"Version":"1.2", "Data":{"Source":str(REPO / "source.cpp"), "Includes":[], **extra}}
            with self.assertRaises(ValueError): evidence.dependency_document(json.dumps(doc).encode())

    def test_linked_dll_without_actual_exits_or_complete_input_observations_is_not_qualified(self):
        for record in [{"nativeCoreLinked":True}, {"nativeCoreLinked":True, "actualConfigureExit":0, "actualBuildExit":1},
                       {"nativeCoreLinked":True, "actualConfigureExit":0, "actualBuildExit":0, "sourceBefore":{"x":"old"}, "sourceAfter":{"x":"new"}}]:
            with self.assertRaises(ValueError): evidence.collect(REPO, Path("missing"), Path("sdk"), record, {}, {}, 0, [])

    def test_complete_collector_emits_the_existing_installer_contract_with_all_eleven_objects(self):
        with tempfile.TemporaryDirectory() as tmp:
            args = self.fixture_build(Path(tmp))
            receipt, proof = evidence.collect(REPO, *args)
            self.assertEqual(11, proof['actualObjectCount'])
            self.assertEqual(7, len(receipt['checks']))
            self.assertEqual('bilipai-veyra-shared-core-v1-v2', receipt['variant'])
            self.assertEqual(8, len(receipt['module']['exports']))
            self.assertFalse(receipt['hardwareExecuted']); self.assertFalse(receipt['releaseQualified'])

    def test_missing_abi_object_or_duplicate_source_report_stops_installer_receipt(self):
        for kind in ('missing-abi', 'duplicate-source'):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as tmp:
                args = self.fixture_build(Path(tmp)); build = args[0]
                if kind=='missing-abi':
                    next((build/'CMakeFiles'/('bilipai_veyra_abi_layout.dir')).glob('*.obj')).unlink()
                else:
                    reports = list((build/'dependencies'/'bilipai_veyra_core').glob('*.json'))
                    reports[0].write_bytes(reports[1].read_bytes())
                with self.assertRaises(ValueError): evidence.collect(REPO, *args)

    def test_stale_objects_cannot_supply_checks_for_a_new_dll_link(self):
        with tempfile.TemporaryDirectory() as tmp:
            args = list(self.fixture_build(Path(tmp))); args[5] = time.time()+100
            with self.assertRaisesRegex(ValueError, 'Stale'): evidence.collect(REPO, *args)

    def test_unlisted_include_cannot_be_treated_as_a_verified_generated_or_system_header(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); args = self.fixture_build(root)
            path = next((args[0]/'dependencies'/'bilipai_veyra_core').glob('*.json'))
            doc = json.loads(path.read_bytes()); foreign = root/'unlisted.h'; foreign.write_bytes(b'foreign')
            doc['Data']['Includes'].append(str(foreign)); path.write_text(json.dumps(doc),encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'outside the fixed'): evidence.collect(REPO, *args)

    def test_original_header_bytes_still_cannot_bypass_the_single_generated_include_root(self):
        with tempfile.TemporaryDirectory() as tmp:
            args = self.fixture_build(Path(tmp)); row = next(r for r in args[3]['fixedUniqueSources'] if r['path'].startswith('include/'))
            header = REPO/'desktop/native'/row['sourceClosure']/'upstream'/row['path']
            path = next((args[0]/'dependencies'/'bilipai_veyra_core').glob('*.json')); doc = json.loads(path.read_bytes())
            doc['Data']['Includes'].append(str(header)); path.write_text(json.dumps(doc),encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'single verified include'): evidence.collect(REPO, *args)

    def test_ngx_named_header_in_a_system_directory_cannot_shadow_the_pinned_sdk(self):
        with tempfile.TemporaryDirectory() as tmp:
            args = self.fixture_build(Path(tmp)); header = args[-1][0]/'nvsdk_ngx.h'; header.write_bytes(b'foreign SDK')
            path = next((args[0]/'dependencies'/'bilipai_veyra_core').glob('*.json')); doc = json.loads(path.read_bytes())
            doc['Data']['Includes'].append(str(header)); path.write_text(json.dumps(doc),encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'outside the fixed SDK'): evidence.collect(REPO, *args)


if __name__ == "__main__":
    unittest.main()
