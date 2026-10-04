from pathlib import Path
import hashlib
import importlib.util
import sys
import unittest

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
TOOLS = HERE.parent
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, TOOLS / filename)
    tool = importlib.util.module_from_spec(spec)
    sys.modules[name] = tool
    spec.loader.exec_module(tool)
    return tool


tool = load("bgm_discovery_extraction", "extract-upstream-bgm-detail.py")
identity = load("bgm_discovery_pinned_sources", "extract-upstream-dynamic-reply-protocol.py")
PATH = tool.BASE + "feature/video/ui/section/VideoInfoSection.kt"
OUTPUT = "com/android/purebilibili/feature/video/ui/section/DesktopOriginalBgmDiscovery.kt"
SOURCES = [PATH] + [tool.BASE + path + ".kt" for path in [
    "core/ui/LocalNavigationBackHandler", "feature/video/ui/components/VideoCardSkeleton",
    "feature/video/ui/components/SkeletonComponents", "feature/video/ui/components/RelatedVideoItem",
]]


class BgmDiscoveryExtractionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.original, cls.identities = identity.load_pinned_sources(REPO, SOURCES)

    def generate(self, originals=None):
        generated = {}
        proof = []
        tool.generate_bgm_discovery(originals or self.original, identity,
                                    lambda path, text: generated.__setitem__(path, text), proof)
        return generated, next(row for row in proof if row["source"] == PATH)

    def test_complete_original_selector_reconstructs_exact_fixed_source(self):
        generated, proof = self.generate()
        output = generated[OUTPUT]
        tail = output[output.index("@Composable\ninternal fun DesktopOriginalInlineBgmSection("):]
        for edit in reversed(proof["adaptations"]):
            index, before, after = edit["index"], edit["before"], edit["after"]
            self.assertEqual(tail[index:index + len(after)], after)
            tail = tail[:index] + before + tail[index + len(after):]
        source = self.original[PATH]
        start = source.rfind("@Composable", 0, source.index("private fun InlineBgmSection("))
        self.assertEqual(tail, source[start:])
        self.assertEqual(hashlib.sha256(tail.encode()).hexdigest(),
                         "cc16dbf897c65e1212e555a2b5b8b3890cd39fcd0e36da49e6d71d3815d3a03c")
        self.assertEqual(proof["originalTailSha256LF"], hashlib.sha256(tail.encode()).hexdigest())
        self.assertEqual(proof["generatedSha256"], hashlib.sha256(output.encode()).hexdigest())

    def test_all_original_state_publications_use_required_admission(self):
        generated, proof = self.generate()
        guarded = [row for row in proof["adaptations"] if "requests.commitBgmDiscoveryState {" in row["after"]]
        self.assertEqual(len(guarded), 4)
        self.assertEqual(proof["originalStateAssignments"], 4)
        for row in guarded:
            self.assertIn("itemStateByKey[", row["before"])
            self.assertEqual(row["after"].count("itemStateByKey["), row["before"].count("itemStateByKey["))
        self.assertNotIn("ViewGrpcRepository.getBgm", generated[OUTPUT])
        self.assertEqual(generated[OUTPUT].count("DesktopWindowsBgmModalSheet("), 1)
        self.assertIn("sheetState = sheetState", generated[OUTPUT])

    def test_an_unhandled_new_original_publication_fails_closed(self):
        changed = dict(self.original)
        changed[PATH] += "\nprivate fun extraPublication() {\n    itemStateByKey[itemKey] = BgmSheetItemState()\n}\n"
        with self.assertRaises(AssertionError):
            self.generate(changed)


if __name__ == "__main__":
    unittest.main()
