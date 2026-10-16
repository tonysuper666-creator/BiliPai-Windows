"""Preserve the sole complete agreement owner when the original profile adds badges."""
from pathlib import Path
import hashlib
import importlib.util
import json
import shutil
import subprocess
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
TOOLS = REPO / "desktop/tools"
sys.path.insert(0, str(TOOLS))
BASELINE = "17705422ea734c49660df11bee3bd4d687bde8da"
PROFILE = "desktop/tools/extract-upstream-profile-main.py"
BASELINE_SHA = "17707c046d4e12f55c4bb8c17efe424247c884ff65148215eb4a0ea87610fedf"


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


class ProfileAgreementOwnerSourceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="bilipai-profile-agreement-")
        cls.root = Path(cls.temp.name)
        cls.about = load("actual_profile_agreement_about", TOOLS / "extract-upstream-system-about.py")
        old = subprocess.check_output(["git", "-C", str(REPO), "show", BASELINE + ":" + PROFILE])
        if hashlib.sha256(old).hexdigest() != BASELINE_SHA:
            raise AssertionError("The accepted baseline profile producer changed")
        prior = cls.root / "accepted_profile.py"
        prior.write_bytes(old)
        cls.before = cls.root / "before"
        cls.after = cls.root / "after"
        load("accepted_profile_agreement_owner", prior).generate(REPO, cls.before)
        load("current_profile_agreement_owner", REPO / PROFILE).generate(REPO, cls.after)
        proof_path = cls.root / "about-proof.json"
        cls.about.generate(REPO, cls.root / "about", cls.root / "resources", proof_path)
        cls.proof = json.loads(proof_path.read_bytes())

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def test_complete_original_agreement_output_remains_byte_identical(self):
        before = {p.relative_to(self.before).as_posix(): p.read_bytes() for p in self.before.rglob("*.kt")}
        after = {p.relative_to(self.after).as_posix(): p.read_bytes() for p in self.after.rglob("*.kt")}
        self.assertEqual(set(before), set(after))
        self.assertEqual(["com/android/purebilibili/feature/profile/ProfileScreen.kt"],
                         [p for p in before if before[p] != after[p]])
        agreement = "com/android/purebilibili/feature/agreement/DesktopOriginalAgreementReview.kt"
        self.assertEqual(before[agreement], after[agreement])
        self.assertEqual("598feaa9ea953ad7914031996dbea57f1d13f4f3d33bdc6eef6144a8461092d2",
                         hashlib.sha256(after[agreement]).hexdigest())

    def test_whole_profile_producer_pin_accepts_lf_and_crlf_checkout(self):
        raw = (REPO / PROFILE).read_bytes().replace(b"\r\n", b"\n")
        with tempfile.TemporaryDirectory(prefix="bilipai-agreement-eol-") as directory:
            root = Path(directory)
            path = root / PROFILE
            path.parent.mkdir(parents=True)
            for data in [raw, raw.replace(b"\n", b"\r\n")]:
                path.write_bytes(data)
                self.assertEqual(self.about.AGREEMENT_OWNER_PRODUCER_LF_SHA256,
                                 self.about.verified_agreement_owner(root))

    def test_changed_profile_producer_is_rejected(self):
        with tempfile.TemporaryDirectory(prefix="bilipai-agreement-tamper-") as directory:
            root = Path(directory)
            path = root / PROFILE
            path.parent.mkdir(parents=True)
            path.write_bytes((REPO / PROFILE).read_bytes() + b" ")
            with self.assertRaisesRegex(AssertionError, "Existing complete agreement owner changed"):
                self.about.verified_agreement_owner(root)

    def test_actual_about_proof_uses_verified_current_owner(self):
        matches = []

        def visit(value):
            if isinstance(value, dict):
                if value.get("producer") == PROFILE and "producerLfSha256" in value:
                    matches.append(value)
                for item in value.values():
                    visit(item)
            elif isinstance(value, list):
                for item in value:
                    visit(item)

        visit(self.proof)
        self.assertTrue(matches)
        for row in matches:
            self.assertEqual(self.about.verified_agreement_owner(REPO), row["producerLfSha256"])


if __name__ == "__main__":
    unittest.main()

