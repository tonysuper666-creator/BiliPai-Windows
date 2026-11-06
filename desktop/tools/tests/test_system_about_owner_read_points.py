"""The About producer verifies the agreement owner at the read point it is given.

Gradle runs the live producer with --repo set to the archived legacy-root.commit
tree; the Python gate view keeps live desktop files. Each read point has exactly
one accepted owner identity, taken here from the real Git objects.
"""
import hashlib
import importlib.util
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
TOOLS = REPO / "desktop/tools"
sys.path.insert(0, str(TOOLS))
PROFILE = "desktop/tools/extract-upstream-profile-main.py"
CATALOG = "desktop/tools/v025-canonical-sources.json"


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def legacy_commit():
    lines = (REPO / "desktop/upstream-core/legacy-root.commit").read_text(encoding="utf-8").splitlines()
    commit, = [line.strip() for line in lines if line.strip() and not line.startswith("#")]
    return commit


class SystemAboutOwnerReadPointTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.about = load("owner_read_point_about", TOOLS / "extract-upstream-system-about.py")
        cls.temp = tempfile.TemporaryDirectory(prefix="bilipai-about-owner-")
        cls.root = Path(cls.temp.name)
        # Materialize the archived read point exactly as Gradle does (LF archive).
        archive = cls.root / "legacy.tar"
        subprocess.run(["git", "-c", "core.autocrlf=false", "-c", "core.eol=lf", "-C", str(REPO),
                        "archive", "--format=tar", "-o", str(archive), legacy_commit(), "--", PROFILE, CATALOG],
                       check=True)
        cls.archived = cls.root / "gradle" / ".legacy-root"
        with tarfile.open(archive) as tar:
            tar.extractall(cls.archived, filter="fully_trusted")
        cls.archived_owner = (cls.archived / PROFILE).read_bytes()
        cls.live_owner = subprocess.check_output(["git", "-C", str(REPO), "show", "HEAD:" + PROFILE])

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def read_point(self, name, kind, owner):
        root = self.root / name / ".legacy-root" if kind != "plain" else self.root / name
        (root / PROFILE).parent.mkdir(parents=True, exist_ok=True)
        (root / PROFILE).write_bytes(owner)
        if kind == "gate":
            (root / ".merged-root").mkdir(exist_ok=True)
        return root

    def lf_sha(self, raw):
        return hashlib.sha256(raw.replace(b"\r\n", b"\n")).hexdigest()

    def test_archived_read_point_is_the_gradle_snapshot(self):
        self.assertTrue(self.about.archived_read_point(self.archived))
        self.assertEqual(self.about.LEGACY_CATALOG_SHA,
                         hashlib.sha256((self.archived / CATALOG).read_bytes()).hexdigest())
        self.assertEqual(self.about.LEGACY_CATALOG_SHA, self.about.catalog_sha(self.archived))

    def test_archived_owner_is_accepted_with_lf_and_crlf(self):
        self.assertEqual(self.about.LEGACY_AGREEMENT_OWNER_PRODUCER_LF_SHA256, self.lf_sha(self.archived_owner))
        self.assertEqual(self.about.LEGACY_AGREEMENT_OWNER_PRODUCER_LF_SHA256,
                         self.about.verified_agreement_owner(self.archived))
        lf = self.archived_owner.replace(b"\r\n", b"\n")
        for index, data in enumerate([lf, lf.replace(b"\n", b"\r\n")]):
            root = self.read_point(f"archived-eol-{index}", "archived", data)
            self.assertEqual(self.about.LEGACY_AGREEMENT_OWNER_PRODUCER_LF_SHA256,
                             self.about.verified_agreement_owner(root))

    def test_live_owner_is_accepted_with_lf_and_crlf_at_live_read_points(self):
        self.assertEqual(self.about.AGREEMENT_OWNER_PRODUCER_LF_SHA256, self.lf_sha(self.live_owner))
        lf = self.live_owner.replace(b"\r\n", b"\n")
        for kind in ["gate", "plain"]:
            for index, data in enumerate([lf, lf.replace(b"\n", b"\r\n")]):
                root = self.read_point(f"live-{kind}-{index}", kind, data)
                self.assertFalse(self.about.archived_read_point(root))
                self.assertEqual(self.about.AGREEMENT_OWNER_PRODUCER_LF_SHA256,
                                 self.about.verified_agreement_owner(root))

    def test_owner_identities_are_not_interchangeable(self):
        self.assertNotEqual(self.about.AGREEMENT_OWNER_PRODUCER_LF_SHA256,
                            self.about.LEGACY_AGREEMENT_OWNER_PRODUCER_LF_SHA256)
        cases = [("archived-with-live", "archived", self.live_owner),
                 ("gate-with-archived", "gate", self.archived_owner),
                 ("plain-with-archived", "plain", self.archived_owner)]
        for name, kind, owner in cases:
            with self.subTest(name):
                with self.assertRaisesRegex(AssertionError, "Existing complete agreement owner changed"):
                    self.about.verified_agreement_owner(self.read_point(name, kind, owner))

    def test_tampered_owner_is_rejected_at_every_read_point(self):
        cases = [("archived", self.archived_owner), ("gate", self.live_owner), ("plain", self.live_owner)]
        for kind, owner in cases:
            for index, data in enumerate([owner + b" ", owner.replace(b"def ", b"def  ", 1)]):
                with self.subTest(kind=kind, tamper=index):
                    root = self.read_point(f"tamper-{kind}-{index}", kind, data)
                    with self.assertRaisesRegex(AssertionError, "Existing complete agreement owner changed"):
                        self.about.verified_agreement_owner(root)


if __name__ == "__main__":
    unittest.main()
