from pathlib import Path
import hashlib
import importlib.util
import json
import os
import shutil
import tempfile
import unittest

REPO=Path(__file__).resolve().parents[3]
def module(name):
    spec=importlib.util.spec_from_file_location(name,REPO/"desktop/tools"/(name+".py"))
    value=importlib.util.module_from_spec(spec);spec.loader.exec_module(value);return value
special=module("extract-upstream-special-danmaku")

class SpecialDanmakuExtractionTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix="special-contract-",dir=os.environ.get("BILIPAI_SPECIAL_TEST_OUTPUT_ROOT"))
        self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)
    def test_complete_originals_and_all_fifteen_original_tests_have_exact_inverse(self):
        main,tests=self.root/"main",self.root/"tests"
        self.assertEqual(6,len(special.generate(REPO,main,tests)))
        originals=special.checked_inputs(REPO)
        proof=json.loads((main/"source-proof.json").read_text(encoding="utf8"))
        self.assertEqual(15,proof["originalTests"])
        self.assertEqual(15,sum(text.count("@Test") for name,(_,text) in originals.items() if name.endswith("Test.kt")))
        for row in proof["files"]:
            value=((tests if row["testSource"] else main)/row["generatedPath"]).read_text(encoding="utf8")
            for change in reversed(row["changes"]):
                self.assertEqual(change.get("count",1),value.count(change["after"]))
                value=value.replace(change["after"],change["before"],change.get("count",1))
            self.assertEqual(originals[Path(row["source"]).name][1],value)
            self.assertTrue(row["wholeOriginalInverse"])
        self.assertFalse(proof["compiled"]);self.assertTrue(proof["originalBasCoreUnchanged"])
    def test_parallel_original_window_has_one_shared_bounded_context_and_no_core_rewrite(self):
        _,original=special.checked_inputs(REPO)["SpecialDanmakuWindow.kt"]
        adapted,changes=special.adapt("SpecialDanmakuWindow.kt",original)
        self.assertIn("pending < 4",adapted)
        self.assertIn("DesktopSpecialParseScope.withWindow",adapted)
        self.assertIn("DesktopSpecialParseScope::seed",adapted)
        self.assertIn("DesktopSpecialParseScope.beforeRead(entry.byteLength)",adapted)
        self.assertNotIn("MAX_SOURCE_CHARS",adapted)
    def test_tampered_fixed_raw_fails_before_output(self):
        repo=self.root/"repo";shutil.copytree(REPO/special.ARCHIVE,repo/special.ARCHIVE)
        path=repo/special.ARCHIVE/"SpecialDanmakuWindow.kt";path.write_bytes(path.read_bytes()+b"\n")
        with self.assertRaisesRegex(ValueError,"Pinned special original bytes changed"):
            special.generate(repo,self.root/"main",self.root/"tests")
        self.assertFalse((self.root/"main").exists())
    def test_modified_or_unknown_generated_source_is_never_overwritten(self):
        main,tests=self.root/"main",self.root/"tests";special.generate(REPO,main,tests)
        path=next(main.rglob("*.kt"));path.write_bytes(path.read_bytes()+b"// local modification\n")
        with self.assertRaisesRegex(ValueError,"changed special output"):
            special.generate(REPO,main,tests)
        self.assertTrue(path.read_bytes().endswith(b"// local modification\n"))
    def test_actual_api_generator_reuses_exact_fixed_streaming_pair_and_complete_canonical_inverse(self):
        api=module("extract-upstream-api");out=self.root/"api";api.generate(REPO,out)
        receipt=json.loads((out/"special-api-source-proof.json").read_text(encoding="utf8"))
        self.assertTrue(receipt["canonicalSelectedInverse"])
        generated=(out/"com/android/purebilibili/core/network/DesktopUpstreamApi.kt").read_text(encoding="utf8")
        exact=special.special_api_declarations(REPO)
        self.assertEqual(1,generated.count(exact));self.assertEqual(2,exact.count("@retrofit2.http.Streaming"))
        self.assertIn('@Headers("Accept-Encoding: identity")',exact)
    def test_existing_repository_contains_whole_original_stream_export_and_original_body_inverse(self):
        media=module("extract-upstream-media");out=self.root/"media";media.generate(REPO,out)
        generated=(out/"com/android/purebilibili/data/repository/DesktopDownloadDanmakuRepository.kt").read_text(encoding="utf8")
        receipt=json.loads((out/"special-download-source-proof.json").read_text(encoding="utf8"))
        binding=json.loads((out/"offline-task-binding-source-proof.json").read_text(encoding="utf8"))
        for change in reversed(binding["mappings"]):
            self.assertEqual(change["count"],generated.count(change["after"]))
            generated=generated.replace(change["after"],change["before"],change["count"])
        actual=media.function(generated,"downloadSpecialDanmaku",media.parser_for(REPO))
        for change in reversed(receipt["mappings"]):
            self.assertEqual(1,actual.count(change["after"]))
            actual=actual.replace(change["after"],change["before"],1)
        original=special.checked_inputs(REPO)["DanmakuRepository.kt"][1]
        self.assertEqual(media.function(original,"downloadSpecialDanmaku",media.parser_for(REPO)),actual)
        self.assertIn("ByteArray(8192)",actual);self.assertIn("destination.delete()",actual)

if __name__=="__main__":unittest.main()
