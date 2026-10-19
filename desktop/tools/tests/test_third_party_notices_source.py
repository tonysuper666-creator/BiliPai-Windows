"""Upstream notices for Windows-carried PiliPlus assets and liquid-lens sources."""
from pathlib import Path
import hashlib
import json
import subprocess
import unittest

REPO = Path(__file__).resolve().parents[3]
NOTICE_DIR = REPO / "desktop/src/main/resources/licenses/upstream-carried-assets"
COMMIT = "d6bf8b1655ae910999860bf13e65c8fcea5b345b"
FONT_ARCHIVE = "desktop/upstream-slices/v037-shared-tabs-progress-closure/piliplus_custom_icons.ttf"
ICON_ARCHIVE = "desktop/upstream-slices/v037-player-runtime-resources/ic_pili_tv_play.png"
FONT_COPY = 'from("upstream-slices/v037-shared-tabs-progress-closure/piliplus_custom_icons.ttf") { into("fonts") }'
ICON_COPY = 'from("upstream-slices/v037-player-runtime-resources/ic_pili_tv_play.png") { into("icons") }'


def commit_available():
    done = subprocess.run(
        ["git", "-C", str(REPO), "cat-file", "-e", COMMIT + "^{commit}"],
        capture_output=True)
    return done.returncode == 0


def git_blob_oid(data):
    return hashlib.sha1(b"blob %d\0" % len(data) + data).hexdigest()


def provenance():
    return json.loads((NOTICE_DIR / "provenance.json").read_bytes())


class ThirdPartyNoticesSourceTest(unittest.TestCase):
    def test_directory_listing_matches_provenance(self):
        document = provenance()
        names = {path.name for path in NOTICE_DIR.iterdir()}
        expected = {row["resource"] for row in document["files"]} | {"provenance.json", "NOTICE.txt"}
        self.assertEqual(expected, names)
        self.assertTrue(all(path.is_file() for path in NOTICE_DIR.iterdir()))

    def test_sha256_bytes_and_blob_match_provenance(self):
        document = provenance()
        self.assertEqual("jay3-yy/BiliPai", document["upstreamRepository"])
        self.assertEqual(COMMIT, document["commit"])
        self.assertGreaterEqual(len(document["files"]), 1)
        seen = set()
        for row in document["files"]:
            self.assertNotIn(row["resource"], seen)
            seen.add(row["resource"])
            data = (NOTICE_DIR / row["resource"]).read_bytes()
            self.assertEqual(row["bytes"], len(data))
            self.assertEqual(row["sha256"], hashlib.sha256(data).hexdigest())
            self.assertEqual(row["gitBlob"], git_blob_oid(data))
            self.assertEqual(row["upstreamPath"].count(".."), 0)

    @unittest.skipUnless(
        commit_available(),
        "upstream commit d6bf8b1655ae910999860bf13e65c8fcea5b345b is not available as a local git object")
    def test_resources_equal_upstream_git_blobs(self):
        for row in provenance()["files"]:
            spec = COMMIT + ":" + row["upstreamPath"]
            blob = subprocess.run(
                ["git", "-C", str(REPO), "cat-file", "blob", spec],
                check=True, capture_output=True).stdout
            self.assertEqual(blob, (NOTICE_DIR / row["resource"]).read_bytes())
            oid = subprocess.run(
                ["git", "-C", str(REPO), "rev-parse", spec],
                check=True, capture_output=True, text=True).stdout.strip()
            self.assertEqual(row["gitBlob"], oid)

    def test_notice_names_each_covered_asset_without_cr(self):
        document = provenance()
        notice = (NOTICE_DIR / "NOTICE.txt").read_text(encoding="utf-8")
        raw_notice = (NOTICE_DIR / "NOTICE.txt").read_bytes()
        raw_provenance = (NOTICE_DIR / "provenance.json").read_bytes()
        self.assertNotIn(b"\r", raw_notice)
        self.assertNotIn(b"\r", raw_provenance)
        self.assertIn(COMMIT, notice)
        self.assertIn("jay3-yy/BiliPai", notice)
        for row in document["covers"]:
            self.assertIn(row["name"], notice)
            for resource in row["notices"]:
                self.assertIn(resource, {item["resource"] for item in document["files"]})
                self.assertIn(resource, notice)

    def test_covered_windows_assets_and_copy_lines_exist(self):
        document = provenance()
        self.assertTrue((REPO / FONT_ARCHIVE).is_file())
        self.assertTrue((REPO / ICON_ARCHIVE).is_file())
        gradle = (REPO / "desktop/build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn(FONT_COPY, gradle)
        self.assertIn(ICON_COPY, gradle)
        names = {row["name"] for row in document["covers"]}
        self.assertIn("piliplus_custom_icons.ttf", names)
        self.assertIn("ic_pili_tv_play.png", names)
        for row in document["covers"]:
            self.assertTrue((REPO / row["archivePath"]).is_file(), row["archivePath"])
            prior = row.get("priorArchivePath")
            if prior:
                self.assertTrue((REPO / prior).is_file(), prior)


if __name__ == "__main__":
    unittest.main()
